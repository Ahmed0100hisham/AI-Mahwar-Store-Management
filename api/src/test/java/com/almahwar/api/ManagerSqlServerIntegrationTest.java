package com.almahwar.api;

import com.almahwar.api.auth.AuthService;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Deterministic released-schema fixtures, real Phase 2 authentication and independent expected amounts. */
@EnabledIfSystemProperty(named="almahwar.it",matches="true")
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ManagerSqlServerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    private static final String PASSWORD="Phase3#Test2026";
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    private static final String PREFIX="/api/v1/manager/";
    private static final String RANGE="?from=2026-10-01&to=2026-10-07";
    private static int customer,supplier,a,b,c,off,admin;
    private final Map<String,LoginResponse> tokens=new HashMap<>();
    private Map<String,Object> before;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        TemporaryDatabase.create();TemporaryApiDatabase.create();TemporaryApiDatabase.properties(r);seed();
        r.add("almahwar.db.host",TemporaryDatabase::host);r.add("almahwar.db.port",TemporaryDatabase::port);
        r.add("almahwar.db.name",()->TemporaryDatabase.NAME);r.add("almahwar.db.user",TemporaryDatabase::user);
        r.add("almahwar.db.password",TemporaryDatabase::password);
        r.add("almahwar.db.trust-server-certificate",()->String.valueOf(TemporaryDatabase.trustServerCertificate()));
        r.add("almahwar.api.jwt.secret",()->ApiWebTestBase.TEST_JWT_SECRET);
        r.add("almahwar.api.health.ready-cache",()->"0s");
    }
    static int number(Object n) { return ((Number)n).intValue(); }
    static void seed() throws Exception {
        String hash=com.almahwar.util.PasswordHasher.hash(PASSWORD.toCharArray());
        for(String role:List.of("ADMIN","ACCOUNTANT","CASHIER","STOREKEEPER"))
            SqlServerIntegrationTest.user("p3_"+role,hash,role,true,false,null);
        admin=number(TemporaryDatabase.queryOne("SELECT user_id FROM dbo.Users WHERE username='p3_ADMIN'"));
        TemporaryDatabase.exec("INSERT dbo.Customers(customer_code,name,phone,area,balance) VALUES('P3-C1',N'عميل خاص 100%_[ O''Reilly','12345678',N'الكويت',8.875),('P3-C2',N'Inactive debt',NULL,NULL,20),('P3-C3',N'Credit customer',NULL,NULL,-2)");
        TemporaryDatabase.exec("UPDATE dbo.Customers SET is_active=0 WHERE customer_code='P3-C2'");
        customer=number(TemporaryDatabase.queryOne("SELECT customer_id FROM dbo.Customers WHERE customer_code='P3-C1'"));
        TemporaryDatabase.exec("INSERT dbo.Suppliers(supplier_code,name,phone,area,balance) VALUES('P3-S1',N'مورد خاص 100%_[ O''Reilly','12345678',N'الكويت',7),('P3-S2',N'Credit supplier',NULL,NULL,-1),('P3-S3',N'Inactive creditor',NULL,NULL,4)");
        TemporaryDatabase.exec("UPDATE dbo.Suppliers SET is_active=0 WHERE supplier_code='P3-S3'");
        supplier=number(TemporaryDatabase.queryOne("SELECT supplier_id FROM dbo.Suppliers WHERE supplier_code='P3-S1'"));
        Object category=TemporaryDatabase.queryOne("SELECT MIN(category_id) FROM dbo.Categories");Object unit=TemporaryDatabase.queryOne("SELECT MIN(unit_id) FROM dbo.Units");
        for(Object[] row:List.of(new Object[]{"P3-A","صنف خاص 100%_[ O'Reilly","2.125","3.000","9.999",true},
                new Object[]{"P3-B","B","0","0","5",true},new Object[]{"P3-C","Never sold","10","1","2",true},new Object[]{"P3-OFF","Inactive","0","9","100",false}))
            TemporaryDatabase.exec("INSERT dbo.Products(product_code,name_ar,name_en,category_id,unit_id,quantity,minimum_stock,purchase_price,sale_price,is_active) VALUES(?,?,N'English 123',?,?,?,?,?,4,?)",
                    row[0],row[1],category,unit,new BigDecimal((String)row[2]),new BigDecimal((String)row[3]),new BigDecimal((String)row[4]),row[5]);
        a=product("P3-A");b=product("P3-B");c=product("P3-C");off=product("P3-OFF");
        int old=sale("P3-OLD","2026-09-30T23:59:59",a,"1","3.125","1.250","0","POSTED");
        int first=sale("P3-1","2026-10-01T00:00:00",a,"4","3.125","1.250","2.500","POSTED");
        sale("P3-2","2026-10-07T23:59:59",b,"2.500","4.000","1.200","0","POSTED");
        sale("P3-DRAFT","2026-10-05T12:00:00",a,"1","100","20","0","DRAFT");
        sale("P3-CANCEL","2026-10-05T12:00:00",a,"1","100","20","0","CANCELLED");
        sale("P3-AFTER","2026-10-08T00:00:00",b,"1","999","1","0","POSTED");
        returned("P3-R1",first,"2026-10-03T12:00:00",a,"2.500");returned("P3-ROLD",old,"2026-10-07T12:00:00",a,"3.125");
        TemporaryDatabase.exec("INSERT dbo.Expenses(expense_no,expense_date,expense_type,category,amount,user_id) VALUES('P3-E1','2026-10-02',N'إيجار','RENT',3.125,?),('P3-E2','2026-10-07',N'أخرى','OTHER',1,?)",admin,admin);
        TemporaryDatabase.exec("INSERT dbo.Cash_Transactions(transaction_date,transaction_type,amount,payment_method,source_type,user_id) VALUES('2026-09-30','IN',50,'CASH','OPENING_BALANCE',?),('2026-10-01','IN',20,'CASH','SALE',?),('2026-10-06','OUT',4.125,'CASH','EXPENSE',?),('2026-10-07','OUT',2,'KNET','WITHDRAWAL',?)",admin,admin,admin,admin);
        ledger(true,customer,"2026-09-30","5","0","OPENING_BALANCE");ledger(true,customer,"2026-10-01","10","0","SALE");
        ledger(true,customer,"2026-10-03","0","2.500","SALE_RETURN");ledger(true,customer,"2026-10-07","0","3.625","PAYMENT");
        ledger(true,number(TemporaryDatabase.queryOne("SELECT customer_id FROM dbo.Customers WHERE customer_code='P3-C2'")),"2026-09-30","20","0","OPENING_BALANCE");
        ledger(true,number(TemporaryDatabase.queryOne("SELECT customer_id FROM dbo.Customers WHERE customer_code='P3-C3'")),"2026-09-30","0","2","OPENING_BALANCE");
        ledger(false,supplier,"2026-09-30","0","12","OPENING_BALANCE");ledger(false,supplier,"2026-10-01","0","5","PURCHASE");ledger(false,supplier,"2026-10-07","10","0","PAYMENT");
        ledger(false,number(TemporaryDatabase.queryOne("SELECT supplier_id FROM dbo.Suppliers WHERE supplier_code='P3-S2'")),"2026-09-30","1","0","OPENING_BALANCE");
        ledger(false,number(TemporaryDatabase.queryOne("SELECT supplier_id FROM dbo.Suppliers WHERE supplier_code='P3-S3'")),"2026-09-30","0","4","OPENING_BALANCE");
        TemporaryDatabase.exec("INSERT dbo.Stock_Movements(product_id,movement_date,movement_type,quantity,balance_after,unit_cost,user_id) VALUES(?,'2026-09-30','OPENING_BALANCE',6.250,6.250,1.250,?),(?,'2026-10-01','SALE',-4,2.250,1.250,?),(?,'2026-10-03','SALE_RETURN',1,3.250,1.250,?),(?,'2026-10-07T12:00:00','SALE_RETURN',1,4.250,1.250,?),(?,'2026-10-07T13:00:00','ADJUSTMENT_OUT',-2.125,2.125,1.250,?)",a,admin,a,admin,a,admin,a,admin,a,admin);
    }
    static int product(String code) throws Exception { return number(TemporaryDatabase.queryOne("SELECT product_id FROM dbo.Products WHERE product_code=?",code)); }
    static int sale(String no,String date,int product,String q,String price,String cost,String discount,String status) throws Exception {
        BigDecimal qty=new BigDecimal(q),unitPrice=new BigDecimal(price),unitCost=new BigDecimal(cost),disc=new BigDecimal(discount);
        var subtotal=qty.multiply(unitPrice);var total=subtotal.subtract(disc);
        TemporaryDatabase.exec("INSERT dbo.Sales(invoice_no,sale_date,customer_id,user_id,subtotal,discount_amount,total_amount,cost_total,status) VALUES(?,?,?,?,?,?,?,?,?)",
                no,java.time.LocalDateTime.parse(date),customer,admin,subtotal,disc,total,qty.multiply(unitCost),status);
        int id=number(TemporaryDatabase.queryOne("SELECT sale_id FROM dbo.Sales WHERE invoice_no=?",no));
        TemporaryDatabase.exec("INSERT dbo.Sale_Items(sale_id,product_id,quantity,unit_price,unit_cost) VALUES(?,?,?,?,?)",id,product,qty,unitPrice,unitCost);return id;
    }
    static void returned(String no,int sale,String date,int product,String revenue) throws Exception {
        TemporaryDatabase.exec("INSERT dbo.Sale_Returns(return_no,sale_id,customer_id,return_date,total_amount,cost_total,user_id) VALUES(?,?,?,?,?,1.250,?)",no,sale,customer,java.time.LocalDateTime.parse(date),new BigDecimal(revenue),admin);
        int returned=number(TemporaryDatabase.queryOne("SELECT return_id FROM dbo.Sale_Returns WHERE return_no=?",no));
        int item=number(TemporaryDatabase.queryOne("SELECT sale_item_id FROM dbo.Sale_Items WHERE sale_id=?",sale));
        TemporaryDatabase.exec("INSERT dbo.Sale_Return_Items(return_id,sale_item_id,product_id,quantity,unit_price,unit_cost) VALUES(?,?,?,1,?,1.250)",returned,item,product,new BigDecimal(revenue));
    }
    static void ledger(boolean customer,int id,String date,String debit,String credit,String type) throws Exception {
        TemporaryDatabase.exec("INSERT dbo.Account_Ledger(party_type,customer_id,supplier_id,entry_date,entry_type,debit,credit,user_id) VALUES(?,?,?,?,?,?,?,?)",
                customer?"CUSTOMER":"SUPPLIER",customer?id:null,customer?null:id,java.time.LocalDate.parse(date).atStartOfDay(),type,new BigDecimal(debit),new BigDecimal(credit),admin);
    }
    @BeforeEach void snapshot() throws Exception { before=fingerprint(); }
    @AfterEach void unchanged() throws Exception { assertThat(fingerprint()).isEqualTo(before); }
    static Map<String,Object> fingerprint() throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String table:List.of("Products","Customers","Suppliers","Sales","Sale_Items","Sale_Returns","Sale_Return_Items","Expenses","Cash_Transactions","Stock_Movements","Account_Ledger","Schema_Info"))
            result.put(table,TemporaryDatabase.queryOne("SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo."+table));
        return result;
    }
    String bearer(String role) { return "Bearer "+tokens.computeIfAbsent(role,r->auth.login("p3_"+r,PASSWORD.toCharArray(),"phase3-test")).accessToken(); }
    tools.jackson.databind.JsonNode getJson(String path,String role) throws Exception {
        String result=mvc.perform(get(PREFIX+path).header("Authorization",bearer(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return JSON.readTree(result);
    }
    @Test void salesReturnsHistoricalProfitAndDateEdgesAreExact() throws Exception {
        var r=getJson("sales/summary"+RANGE,"ADMIN");
        assertThat(r.path("grossSales").asText()).isEqualTo("20.000");assertThat(r.path("returns").asText()).isEqualTo("5.625");
        assertThat(r.path("netSales").asText()).isEqualTo("14.375");assertThat(r.path("invoiceCount").asInt()).isEqualTo(2);
        assertThat(r.path("averageInvoice").asText()).isEqualTo("10.000");assertThat(r.path("returnCount").asInt()).isEqualTo(2);
        assertThat(r.path("profit").path("historicalCost").asText()).isEqualTo("8.000");
        assertThat(r.path("profit").path("returnedHistoricalCost").asText()).isEqualTo("2.500");
        assertThat(r.path("profit").path("grossProfitAfterReturns").asText()).isEqualTo("8.875");
        assertThat(r.path("profit").path("netProfit").asText()).isEqualTo("4.750");
        assertThat(r.path("grossSales").isString()).isTrue();
    }
    @Test void returnOnlyAndEmptyRangesHaveCorrectSigns() throws Exception {
        var returned=getJson("sales/summary?from=2026-10-03&to=2026-10-03","ADMIN");
        assertThat(returned.path("netSales").asText()).isEqualTo("-2.500");assertThat(returned.path("averageInvoice").asText()).isEqualTo("0.000");
        var empty=getJson("sales/summary?from=2027-01-01&to=2027-01-01","ADMIN");
        assertThat(empty.path("netSales").asText()).isEqualTo("0.000");assertThat(empty.path("invoiceCount").asInt()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"daily","weekly","monthly"})
    void trendsAreOrderedAndReconcileWithIndependentExpectedNet(String grouping) throws Exception {
        var r=getJson("sales/trend"+RANGE+"&grouping="+grouping,"ADMIN");LocalDate previous=null;BigDecimal net=BigDecimal.ZERO;
        for(var bucket:r.path("buckets")) {
            var day=LocalDate.parse(bucket.path("bucket").asText());if(previous!=null) assertThat(day).isAfter(previous);previous=day;
            net=net.add(new BigDecimal(bucket.path("netSales").asText()));
        }
        assertThat(net).isEqualByComparingTo("14.375");
        if(grouping.equals("daily")) assertThat(r.path("buckets").size()).isEqualTo(7);
        if(grouping.equals("weekly")) assertThat(r.path("buckets").get(0).path("bucket").asText()).isEqualTo("2026-09-27");
    }
    @Test void topProductsUseNetQuantityAndInvoiceDiscountsWithoutCostLeak() throws Exception {
        var admin=getJson("sales/top-products"+RANGE+"&limit=2","ADMIN").path("items");
        assertThat(admin.get(0).path("code").asText()).isEqualTo("P3-B");assertThat(admin.get(0).path("netQuantity").asText()).isEqualTo("2.500");
        assertThat(admin.get(1).path("netRevenue").asText()).isEqualTo("4.375");assertThat(admin.get(1).path("grossProfit").asText()).isEqualTo("1.875");
        var cashier=getJson("sales/top-products"+RANGE+"&limit=1","CASHIER").path("items");
        assertThat(cashier.size()).isEqualTo(1);assertThat(cashier.get(0).has("grossProfit")).isFalse();
    }
    @Test void expenseAndCashBookDefinitionsIncludeOpeningAndNonCashMethods() throws Exception {
        var expenses=getJson("expenses/summary"+RANGE,"ACCOUNTANT");assertThat(expenses.path("total").asText()).isEqualTo("4.125");
        assertThat(expenses.path("count").asInt()).isEqualTo(2);assertThat(expenses.path("categories").size()).isEqualTo(9);
        var cash=getJson("cashbox/summary"+RANGE,"ACCOUNTANT");assertThat(cash.path("openingBalance").asText()).isEqualTo("50.000");
        assertThat(cash.path("totalIn").asText()).isEqualTo("20.000");assertThat(cash.path("totalOut").asText()).isEqualTo("6.125");
        assertThat(cash.path("closingBalance").asText()).isEqualTo("63.875");
    }
    @Test void activeLowStockOutOfStockAndValuationMatchReleasedRules() throws Exception {
        var inventory=getJson("inventory/summary","STOREKEEPER");assertThat(inventory.path("activeProducts").asInt()).isEqualTo(3);
        assertThat(inventory.path("lowStockProducts").asInt()).isEqualTo(2);assertThat(inventory.path("outOfStockProducts").asInt()).isEqualTo(1);
        assertThat(inventory.path("inventoryValue").asText()).isEqualTo("41.248");
        var low=getJson("inventory/low-stock?size=1&sort=quantity,desc","STOREKEEPER");
        assertThat(low.path("totalItems").asInt()).isEqualTo(2);assertThat(low.path("items").get(0).path("quantity").asText()).isEqualTo("2.125");
        assertThat(getJson("inventory/low-stock?page=1&size=1&sort=quantity,desc","STOREKEEPER").path("items").get(0).path("code").asText()).isEqualTo("P3-B");
    }
    @Test void productVisibilityAndStockMovementsPreservePrecision() throws Exception {
        var cashier=getJson("products/"+a,"CASHIER");assertThat(cashier.has("purchaseCost")).isFalse();
        assertThat(cashier.path("quantity").asText()).isEqualTo("2.125");assertThat(cashier.path("lowStock").asBoolean()).isTrue();
        mvc.perform(get(PREFIX+"products/"+off).header("Authorization",bearer("CASHIER"))).andExpect(status().isNotFound());
        assertThat(getJson("products/"+off,"STOREKEEPER").path("active").asBoolean()).isFalse();
        var moves=getJson("products/"+a+"/movements"+RANGE+"&size=2","STOREKEEPER");assertThat(moves.path("totalItems").asInt()).isEqualTo(4);
        assertThat(moves.path("items").get(0).path("quantity").asText()).isEqualTo("-2.125");
        assertThat(moves.path("items").get(0).path("before").asText()).isEqualTo("4.250");
        assertThat(moves.path("items").get(0).path("after").asText()).isEqualTo("2.125");
        assertThat(moves.toString()).doesNotContain("reference_id","notes","user_id");
    }
    @Test void slowMovingUsesNeverSoldDefinition() throws Exception {
        var r=getJson("sales/slow-products?days=3650","STOREKEEPER");assertThat(r.path("totalItems").asInt()).isEqualTo(1);
        assertThat(r.path("items").get(0).path("code").asText()).isEqualTo("P3-C");
    }
    @Test void slowStockPagesAreStableForTiedNamesAndMatchCoreEligibility() throws Exception {
        TemporaryDatabase.exec("INSERT dbo.Products(product_code,name_ar,category_id,unit_id,quantity,purchase_price,sale_price,is_active) SELECT 'P3-TIE',name_ar,category_id,unit_id,quantity,purchase_price,sale_price,is_active FROM dbo.Products WHERE product_id=?",c);
        try {
            assertThat(getJson("sales/slow-products?days=3650&size=1","ADMIN").path("items").get(0).path("code").asText()).isEqualTo("P3-C");
            assertThat(getJson("sales/slow-products?days=3650&size=1&page=1","ADMIN").path("items").get(0).path("code").asText()).isEqualTo("P3-TIE");
            var core=new com.almahwar.dao.ReportDao().slowMovingRows(com.almahwar.model.ReportFilter.none().days(3650).pageSize(100));
            var api=getJson("sales/slow-products?days=3650","ADMIN");
            assertThat(api.path("totalItems").asInt()).isEqualTo(core.size());
            assertThat(core.stream().map(com.almahwar.model.Reports.SlowMovingRow::productCode).toList()).containsExactlyInAnyOrder("P3-C","P3-TIE");
        } finally { TemporaryDatabase.exec("DELETE dbo.Products WHERE product_code='P3-TIE'"); }
    }
    @Test void customerAndSupplierBalancesAreHiddenFromProfileOnlyRoles() throws Exception {
        assertThat(getJson("customers/"+customer,"CASHIER").has("balance")).isFalse();
        assertThat(getJson("suppliers/"+supplier,"STOREKEEPER").has("balance")).isFalse();
        assertThat(getJson("customers/"+customer,"ACCOUNTANT").path("balance").asText()).isEqualTo("8.875");
        assertThat(getJson("suppliers/"+supplier,"ACCOUNTANT").path("balance").asText()).isEqualTo("7.000");
        assertThat(getJson("customers?size=1","CASHIER").path("page").path("items").get(0).has("balance")).isFalse();
        mvc.perform(get(PREFIX+"customers?sort=balance").header("Authorization",bearer("CASHIER"))).andExpect(status().isBadRequest());
        mvc.perform(get(PREFIX+"suppliers?sort=balance").header("Authorization",bearer("STOREKEEPER"))).andExpect(status().isBadRequest());
    }
    @Test void positiveLedgerReceivablesPayablesIncludeInactiveAndExcludeCredits() throws Exception {
        var customers=getJson("customers/receivables?size=1","ACCOUNTANT");assertThat(customers.path("page").path("totalItems").asInt()).isEqualTo(2);
        assertThat(customers.path("totalOutstanding").asText()).isEqualTo("28.875");
        assertThat(customers.path("page").path("items").get(0).path("active").asBoolean()).isFalse();
        var suppliers=getJson("suppliers/payables","ACCOUNTANT");assertThat(suppliers.path("page").path("totalItems").asInt()).isEqualTo(2);
        assertThat(suppliers.path("totalOutstanding").asText()).isEqualTo("11.000");
    }
    @Test void pagedStatementsRetainBroughtForwardAndFullHistoryRunningBalance() throws Exception {
        var customerAccount=getJson("customers/"+customer+"/account"+RANGE+"&page=1&size=1","ACCOUNTANT");
        assertThat(customerAccount.path("openingBalance").asText()).isEqualTo("5.000");
        assertThat(customerAccount.path("totalDebit").asText()).isEqualTo("10.000");assertThat(customerAccount.path("totalCredit").asText()).isEqualTo("6.125");
        assertThat(customerAccount.path("closingBalance").asText()).isEqualTo("8.875");
        assertThat(customerAccount.path("entries").path("items").get(0).path("runningBalance").asText()).isEqualTo("12.500");
        var supplierAccount=getJson("suppliers/"+supplier+"/account"+RANGE+"&page=1&size=1","ACCOUNTANT");
        assertThat(supplierAccount.path("openingBalance").asText()).isEqualTo("12.000");assertThat(supplierAccount.path("closingBalance").asText()).isEqualTo("7.000");
        assertThat(supplierAccount.path("entries").path("items").get(0).path("runningBalance").asText()).isEqualTo("7.000");
    }
    @ParameterizedTest @ValueSource(strings={"%","_","[","خاص","Reilly","100","O'Reilly","' OR 1=1--"})
    void literalSearchEscapesWildcardsAndBindsArabicQuotesAndSqlPayloads(String text) throws Exception {
        for(String path:List.of("inventory/low-stock","customers","suppliers")) {
            String body=mvc.perform(get(PREFIX+path).param("q",text).header("Authorization",bearer("ADMIN")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var r=JSON.readTree(body);if(!path.startsWith("inventory")) r=r.path("page");
            assertThat(r.path("totalItems").asInt()).isEqualTo(text.startsWith("' OR")?0:1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"customers/999999","suppliers/999999","products/999999","customers/999999/account","suppliers/999999/account","products/999999/movements"})
    void missingBusinessEntitiesAreSafe404(String path) throws Exception {
        mvc.perform(get(PREFIX+path).header("Authorization",bearer("ADMIN"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void injectionAndUnboundedQueriesAreRejectedOverAuthenticatedHttp() throws Exception {
        for(String path:List.of("customers","suppliers","inventory/low-stock")) {
            for(String sort:List.of("name; DROP TABLE dbo.Sales","name,desc--","balance UNION SELECT password_hash"))
                mvc.perform(get(PREFIX+path).param("sort",sort).header("Authorization",bearer("ADMIN"))).andExpect(status().isBadRequest());
            mvc.perform(get(PREFIX+path).param("q","x".repeat(101)).header("Authorization",bearer("ADMIN"))).andExpect(status().isBadRequest());
            mvc.perform(get(PREFIX+path).param("size","101").header("Authorization",bearer("ADMIN"))).andExpect(status().isBadRequest());
        }
    }
    @Test void dashboardSecurityUsesRealSessionsAndSafeMetricSubset() throws Exception {
        var cashier=getJson("dashboard","CASHIER");assertThat(cashier.toString()).contains("TODAY_SALES").doesNotContain("RECEIVABLES","NET_PROFIT");
        var response=tokens.get("CASHIER");
        TemporaryApiDatabase.exec("UPDATE dbo.api_sessions SET revoked_at=SYSUTCDATETIME() WHERE sid=?",response.sid().toString());
        mvc.perform(get(PREFIX+"dashboard").header("Authorization","Bearer "+response.accessToken())).andExpect(status().isUnauthorized());
    }
    @Test void representativeQueryPlansUseReleasedSchemaWithoutAddingIndexes() throws Exception {
        List<String> plans=new ArrayList<>();
        try(var con=TemporaryDatabase.connect(TemporaryDatabase.NAME);var st=con.createStatement()) {
            st.execute("SET SHOWPLAN_XML ON");
            for(String sql:List.of("SELECT COUNT_BIG(*) FROM dbo.Products WHERE is_active=1 AND quantity<=minimum_stock",
                    "SELECT SUM(total_amount) FROM dbo.Sales WHERE status='POSTED' AND sale_date>='2026-10-01' AND sale_date<'2026-10-08'",
                    "SELECT SUM(debit-credit) FROM dbo.Account_Ledger WHERE customer_id="+customer+" AND entry_date<'2026-10-01'"))
                try(var rs=st.executeQuery(sql)) { assertThat(rs.next()).isTrue();plans.add(rs.getString(1)); }
            st.execute("SET SHOWPLAN_XML OFF");
        }
        assertThat(plans).allSatisfy(xml->assertThat(xml).contains("RelOp","IndexScan"));
        Files.writeString(Path.of("target","phase3-query-plans.xml"),"<plans>"+String.join("\n",plans.stream().map(p->p.replaceFirst("<\\?xml[^>]*>","")).toList())+"</plans>");
    }
    @Test void realHttpServerServesAuthenticatedManagerReads() throws Exception {
        try(var context=new SpringApplicationBuilder(AlMahwarApiApplication.class).logStartupInfo(false)
                .run(TemporaryApiDatabase.withSessionArgs("--server.port=0","--almahwar.db.host="+TemporaryDatabase.host(),
                        "--almahwar.db.port="+TemporaryDatabase.port(),"--almahwar.db.name="+TemporaryDatabase.NAME,
                        "--almahwar.db.user="+TemporaryDatabase.user(),"--almahwar.db.password="+TemporaryDatabase.password(),
                        "--almahwar.db.trust-server-certificate="+TemporaryDatabase.trustServerCertificate(),"--almahwar.api.jwt.secret="+ApiWebTestBase.TEST_JWT_SECRET))) {
            var token=context.getBean(AuthService.class).login("p3_ADMIN",PASSWORD.toCharArray(),"real-http");
            int port=((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
            var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+PREFIX+"sales/summary"+RANGE))
                    .header("Authorization","Bearer "+token.accessToken()).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);assertThat(JSON.readTree(response.body()).path("netSales").asText()).isEqualTo("14.375");
            var docs=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/v3/api-docs"))
                    .GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(docs.statusCode()).isEqualTo(200);
            var specification=JSON.readTree(docs.body());int managerPaths=0;
            for(var path:specification.path("paths").properties()) {
                if(!path.getKey().startsWith(PREFIX)) continue;
                managerPaths++;assertThat(path.getValue().has("get")).isTrue();assertThat(path.getValue().has("post")).isFalse();
                assertThat(path.getValue().path("get").path("security").toString()).contains("bearer");
            }
            assertThat(managerPaths).isEqualTo(19);
            assertThat(specification.path("components").path("schemas").path("Sales").path("properties").path("grossSales").path("type").asText()).isEqualTo("string");
        }
    }
    @AfterAll static void cleanup() throws Exception {
        try { TemporaryApiDatabase.drop(); } finally { TemporaryDatabase.drop(); }
        try(var con=TemporaryDatabase.connect("master");var ps=con.prepareStatement("SELECT DB_ID(?),DB_ID(?)")) {
            ps.setString(1,TemporaryDatabase.NAME);ps.setString(2,TemporaryApiDatabase.NAME);
            try(var rs=ps.executeQuery()) { assertThat(rs.next()).isTrue();assertThat(rs.getObject(1)).isNull();assertThat(rs.getObject(2)).isNull(); }
        }
    }
}
