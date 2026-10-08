package com.almahwar.api;

import com.almahwar.api.auth.AuthService;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import java.net.*;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Released schema only, independent expected values, real sessions and read-only business fingerprints. */
@EnabledIfSystemProperty(named="almahwar.it",matches="true")
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("dev")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ManagerDocumentSqlServerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    private static final String PASSWORD="Phase3#Test2026";
    private static final String PREFIX="/api/v1/manager/";
    private static final String RANGE="?from=2026-11-01&to=2026-11-01";
    private static int invoice,full,quotation;
    private final Map<String,LoginResponse> logins=new HashMap<>();
    private Map<String,Object> before;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        TemporaryDatabase.create();TemporaryApiDatabase.create();TemporaryApiDatabase.properties(r);
        ManagerSqlServerIntegrationTest.seed();seed();
        r.add("almahwar.db.host",TemporaryDatabase::host);r.add("almahwar.db.port",TemporaryDatabase::port);
        r.add("almahwar.db.name",()->TemporaryDatabase.NAME);r.add("almahwar.db.user",TemporaryDatabase::user);r.add("almahwar.db.password",TemporaryDatabase::password);
        r.add("almahwar.db.trust-server-certificate",()->String.valueOf(TemporaryDatabase.trustServerCertificate()));
        r.add("almahwar.api.jwt.secret",()->ApiWebTestBase.TEST_JWT_SECRET);r.add("almahwar.api.health.ready-cache",()->"0s");
    }
    static int id(String sql,Object... args) throws Exception { return ((Number)TemporaryDatabase.queryOne(sql,args)).intValue(); }
    static void seed() throws Exception {
        int customer=id("SELECT customer_id FROM dbo.Customers WHERE customer_code='P3-C1'");
        int admin=id("SELECT user_id FROM dbo.Users WHERE username='p3_ADMIN'");
        int product=id("SELECT product_id FROM dbo.Products WHERE product_code='P3-A'");
        TemporaryDatabase.exec("INSERT dbo.Sales(invoice_no,sale_date,customer_id,user_id,subtotal,discount_amount,tax_amount,total_amount,paid_amount,cost_total,status,payment_method) VALUES(N'P5-فاتورة%_[','2026-11-01T00:00:00',?,?,14,2,0.125,12.125,5,5,'POSTED','MIXED')",customer,admin);
        invoice=id("SELECT sale_id FROM dbo.Sales WHERE invoice_no=N'P5-فاتورة%_['");
        TemporaryDatabase.exec("INSERT dbo.Sale_Items(sale_id,product_id,quantity,unit_price,unit_cost,discount_amount) VALUES(?,?,2.500,4,1.200,1),(?,?,1,5,2,0)",invoice,product,invoice,product);
        int line=id("SELECT MIN(sale_item_id) FROM dbo.Sale_Items WHERE sale_id=?",invoice);
        returned("P5-R1",invoice,line,product,customer,admin,"2026-11-02T00:00:00","0.500","3.117","1.559","0.600","0");
        returned("P5-R2",invoice,line,product,customer,admin,"2026-11-03T23:59:59","1.000","3.117","3.117","1.200","1.117");
        full=ManagerSqlServerIntegrationTest.sale("P5-FULL","2026-11-01T23:59:59",product,"1","3","1","0","POSTED");
        int fullLine=id("SELECT sale_item_id FROM dbo.Sale_Items WHERE sale_id=?",full);
        returned("P5-RFULL",full,fullLine,product,customer,admin,"2026-11-02T12:00:00","1","3","3","1","3");
        ManagerSqlServerIntegrationTest.sale("P5-NEXT","2026-11-02T00:00:00",product,"1","7","1","0","POSTED");
        for(String status:List.of("DRAFT","SENT","ACCEPTED","REJECTED","EXPIRED","CONVERTED")) {
            TemporaryDatabase.exec("INSERT dbo.Quotations(quotation_no,quotation_date,valid_until,customer_id,customer_name,customer_phone,subtotal,discount_amount,total_amount,status,user_id) VALUES(?,'2026-11-01', '2020-01-01',?,N'عرض Arabic 100%_[ O''Reilly','12345678',9,1,8,?,?)","P5-Q-"+status,customer,status,admin);
            int qid=id("SELECT quotation_id FROM dbo.Quotations WHERE quotation_no=?","P5-Q-"+status);
            TemporaryDatabase.exec("INSERT dbo.Quotation_Items(quotation_id,product_id,quantity,unit_price,discount_amount) VALUES(?,?,2.500,4,1)",qid,product);
        }
        quotation=id("SELECT quotation_id FROM dbo.Quotations WHERE quotation_no='P5-Q-ACCEPTED'");
        int draft=ManagerSqlServerIntegrationTest.sale("P5-CONVERSION-DRAFT","2026-11-04T12:00:00",product,"1","3","1","0","DRAFT");
        TemporaryDatabase.exec("UPDATE dbo.Sales SET quotation_id=? WHERE sale_id=?",quotation,draft);
        TemporaryDatabase.exec("UPDATE dbo.Quotations SET converted_sale_id=? WHERE quotation_id=?",draft,quotation);
        int converted=id("SELECT quotation_id FROM dbo.Quotations WHERE quotation_no='P5-Q-CONVERTED'");
        TemporaryDatabase.exec("UPDATE dbo.Quotations SET converted_sale_id=? WHERE quotation_id=?",full,converted);
        TemporaryDatabase.exec("UPDATE dbo.Sales SET quotation_id=? WHERE sale_id=?",converted,full);
        int cancelled=ManagerSqlServerIntegrationTest.sale("P5-CANCELLED-DRAFT","2026-11-04T12:00:00",product,"1","3","1","0","CANCELLED");
        TemporaryDatabase.exec("INSERT dbo.Quotations(quotation_no,quotation_date,customer_id,status,converted_sale_id,user_id) VALUES('P5-CANCELLED-LINK','2026-11-04',?,'ACCEPTED',?,?)",customer,cancelled,admin);
        TemporaryDatabase.exec("INSERT dbo.Quotations(quotation_no,quotation_date,valid_until,customer_name,subtotal,total_amount,status,user_id) VALUES('P5-Q-TODAY','2026-11-01',CAST(SYSDATETIME() AS date),N'Prospect only',0,0,'DRAFT',?),('P5-Q-NONE','2026-11-01',NULL,N'No expiry',0,0,'DRAFT',?)",admin,admin);
        SqlServerIntegrationTest.user("p5_must",com.almahwar.util.PasswordHasher.hash(PASSWORD.toCharArray()),"CASHIER",true,true,null);
    }
    static void returned(String no,int sale,int item,int product,int customer,int admin,String date,String qty,String price,String total,String cost,String refund) throws Exception {
        TemporaryDatabase.exec("INSERT dbo.Sale_Returns(return_no,sale_id,customer_id,return_date,total_amount,cost_total,refund_amount,user_id) VALUES(?,?,?,?,?,?,?,?)",no,sale,customer,LocalDateTime.parse(date),new java.math.BigDecimal(total),new java.math.BigDecimal(cost),new java.math.BigDecimal(refund),admin);
        int returned=id("SELECT return_id FROM dbo.Sale_Returns WHERE return_no=?",no);
        TemporaryDatabase.exec("INSERT dbo.Sale_Return_Items(return_id,sale_item_id,product_id,quantity,unit_price,unit_cost) VALUES(?,?,?,?,?,?)",returned,item,product,new java.math.BigDecimal(qty),new java.math.BigDecimal(price),new java.math.BigDecimal(cost).divide(new java.math.BigDecimal(qty)));
    }
    @BeforeEach void snapshot() throws Exception { before=fingerprint(); }
    static Map<String,Object> fingerprint() throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String table:List.of("Products","Customers","Suppliers","Sales","Sale_Items","Sale_Returns","Sale_Return_Items","Quotations","Quotation_Items","Expenses","Cash_Transactions","Stock_Movements","Account_Ledger","Schema_Info"))
            result.put(table,TemporaryDatabase.queryOne("SELECT CHECKSUM_AGG(BINARY_CHECKSUM(*)) FROM dbo."+table));
        return result;
    }
    @AfterEach void noBusinessMutation() throws Exception { assertThat(fingerprint()).isEqualTo(before); }
    String bearer(String role) { return "Bearer "+logins.computeIfAbsent(role,r->auth.login("p3_"+r,PASSWORD.toCharArray(),"phase5-sql")).accessToken(); }
    tools.jackson.databind.JsonNode getJson(String path,String role) throws Exception {
        var response=mvc.perform(get(PREFIX+path).header("Authorization",bearer(role))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        return JSON.readTree(response.getContentAsString());
    }
    @Test void invoiceTaxDiscountFractionalLinesPaymentAndHistoricalCostAreStoredExactly() throws Exception {
        var body=getJson("invoices/"+invoice,"ADMIN");var h=body.path("invoice");
        assertThat(h.path("subtotal").asText()).isEqualTo("14.000");assertThat(h.path("discount").asText()).isEqualTo("2.000");
        assertThat(h.path("tax").asText()).isEqualTo("0.125");assertThat(h.path("total").asText()).isEqualTo("12.125");
        assertThat(h.path("paid").asText()).isEqualTo("5.000");assertThat(h.path("remaining").asText()).isEqualTo("7.125");
        assertThat(h.path("paymentStatus").asText()).isEqualTo("PARTIAL");assertThat(h.path("paymentMethod").asText()).isEqualTo("MIXED");
        assertThat(h.path("historicalCost").asText()).isEqualTo("5.000");assertThat(h.path("grossProfit").asText()).isEqualTo("7.125");
        var line=body.path("items").path("items").get(0);
        assertThat(line.path("quantity").asText()).isEqualTo("2.500");assertThat(line.path("discount").asText()).isEqualTo("1.000");
        assertThat(line.path("total").asText()).isEqualTo("9.000");assertThat(line.path("historicalUnitCost").asText()).isEqualTo("1.200");
        assertThat(h.path("customer").path("name").asText()).contains("عميل");
    }
    @Test void multipleLaterReturnsKeepValueRefundAndOriginalPaymentDistinct() throws Exception {
        var body=getJson("invoices/"+invoice,"ADMIN");var h=body.path("invoice");
        assertThat(h.path("returnedAmount").asText()).isEqualTo("4.676");assertThat(h.path("refundedAmount").asText()).isEqualTo("1.117");
        assertThat(h.path("netAmount").asText()).isEqualTo("7.449");assertThat(h.path("remaining").asText()).isEqualTo("7.125");
        assertThat(body.path("returns").path("totalItems").asInt()).isEqualTo(2);
        assertThat(body.path("items").path("items").get(0).path("returnedQuantity").asText()).isEqualTo("1.500");
        var listed=getJson("invoices"+RANGE+"&q=P5-فاتورة","ADMIN").path("items").get(0);
        assertThat(listed.path("returnedAmount").asText()).isEqualTo("0.000");assertThat(listed.path("netAmount").asText()).isEqualTo("12.125");
        assertThat(listed.path("returnCutoffDate").asText()).isEqualTo("2026-11-01");
        var partial=getJson("invoices?from=2026-11-01&to=2026-11-02&q=P5-فاتورة","ADMIN").path("items").get(0);
        assertThat(partial.path("returnedAmount").asText()).isEqualTo("1.559");assertThat(partial.path("refundedAmount").asText()).isEqualTo("0.000");assertThat(partial.path("netAmount").asText()).isEqualTo("10.566");
    }
    @Test void fullReturnAndNoReturnInvoiceAreTruthful() throws Exception {
        var h=getJson("invoices/"+full,"ADMIN").path("invoice");assertThat(h.path("netAmount").asText()).isEqualTo("0.000");
        int next=id("SELECT sale_id FROM dbo.Sales WHERE invoice_no='P5-NEXT'");
        h=getJson("invoices/"+next,"ADMIN").path("invoice");assertThat(h.path("returnedAmount").asText()).isEqualTo("0.000");assertThat(h.path("netAmount").asText()).isEqualTo("7.000");
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","CANCELLED"}) void unpostedProfitIsOmittedExactlyLikeReleasedSaleDao(String status) throws Exception {
        int sale=id("SELECT sale_id FROM dbo.Sales WHERE invoice_no=?",status.equals("DRAFT")?"P5-CONVERSION-DRAFT":"P5-CANCELLED-DRAFT");
        var detail=getJson("invoices/"+sale,"ADMIN").path("invoice");assertThat(detail.has("grossProfit")).isFalse();assertThat(detail.has("historicalCost")).isTrue();
        var listed=getJson("invoices?from=2026-11-04&to=2026-11-04&status="+status,"ADMIN").path("items").get(0);assertThat(listed.has("grossProfit")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"ADMIN","ACCOUNTANT","CASHIER"}) void fieldPermissionsOnListAndDetail(String role) throws Exception {
        boolean allowed=!role.equals("CASHIER");
        var detail=getJson("invoices/"+invoice,role);var listed=getJson("invoices"+RANGE,role).path("items").get(0);
        assertThat(detail.path("invoice").has("historicalCost")).isEqualTo(allowed);assertThat(detail.path("invoice").has("grossProfit")).isEqualTo(allowed);
        assertThat(detail.path("items").path("items").get(0).has("historicalUnitCost")).isEqualTo(allowed);
        assertThat(listed.has("historicalCost")).isEqualTo(allowed);assertThat(listed.has("grossProfit")).isEqualTo(allowed);
        if(!allowed) assertThat(detail.toString()).doesNotContain("cost_total","unit_cost","grossProfit","historicalCost","historicalUnitCost");
    }
    @Test void independentDetailPagesAndDeterministicListPages() throws Exception {
        var body=getJson("invoices/"+invoice+"?page=1&size=1&returnsPage=1&returnsSize=1","ADMIN");
        assertThat(body.path("items").path("totalItems").asInt()).isEqualTo(2);assertThat(body.path("items").path("items").size()).isEqualTo(1);
        assertThat(body.path("returns").path("page").asInt()).isEqualTo(1);assertThat(body.path("returns").path("items").get(0).path("number").asText()).isEqualTo("P5-R2");
        var first=getJson("invoices"+RANGE+"&size=1&sort=total,desc","ADMIN");var second=getJson("invoices"+RANGE+"&page=1&size=1&sort=total,desc","ADMIN");
        assertThat(first.path("totalItems").asInt()).isEqualTo(2);assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("items").get(0).path("id").asInt()).isEqualTo(invoice);assertThat(second.path("items").get(0).path("id").asInt()).isEqualTo(full);
    }
    @Test void invoiceFiltersAndMidnightRangeAreInclusiveOnlyForTheirDay() throws Exception {
        int customer=id("SELECT customer_id FROM dbo.Customers WHERE customer_code='P3-C1'");
        var list=getJson("invoices"+RANGE+"&customerId="+customer+"&status=POSTED&paymentMethod=MIXED","ADMIN");assertThat(list.path("totalItems").asInt()).isEqualTo(1);
        assertThat(getJson("invoices"+RANGE+"&status=DRAFT","ADMIN").path("totalItems").asInt()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"%","_","[","'","--",";","عرض","Arabic","P5-Q","100%_[ O'Reilly"})
    void quotationSearchIsLiteralParameterizedAndUnicodeSafe(String search) throws Exception {
        var result=mvc.perform(get(PREFIX+"quotations").param("from","2026-11-01").param("to","2026-11-01").param("q",search).header("Authorization",bearer("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        int expected=Set.of("--",";").contains(search)?0:search.equals("P5-Q")?8:6;
        assertThat(JSON.readTree(result.getContentAsString()).path("totalItems").asInt()).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","SENT","ACCEPTED","REJECTED","EXPIRED","CONVERTED"})
    void allSixStoredStatusesRemainUnchangedDuringReads(String status) throws Exception {
        var body=getJson("quotations"+RANGE+"&q=P5-Q-"+status+"&status="+status,"ADMIN");assertThat(body.path("totalItems").asInt()).isEqualTo(1);
        var h=body.path("items").get(0);assertThat(h.path("status").asText()).isEqualTo(status);assertThat(h.path("pastValidity").asBoolean()).isTrue();
        int qid=h.path("id").asInt();var detail=getJson("quotations/"+qid,"ADMIN");assertThat(detail.path("items").path("items").get(0).path("quantity").asText()).isEqualTo("2.500");
        assertThat(detail.path("quotation").path("total").asText()).isEqualTo("8.000");assertThat(detail.toString()).doesNotContain("tax","notes","requestId","password");
    }
    @Test void validityBoundaryNullableCustomerAndStoredConversionAreNotInvented() throws Exception {
        var q=getJson("quotations/"+quotation,"ADMIN").path("quotation");assertThat(q.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(q.path("linkedSale").path("number").asText()).isEqualTo("P5-CONVERSION-DRAFT");assertThat(q.path("linkedSale").path("status").asText()).isEqualTo("DRAFT");
        var today=getJson("quotations"+RANGE+"&q=P5-Q-TODAY","ADMIN").path("items").get(0);
        assertThat(today.path("pastValidity").asBoolean()).isFalse();assertThat(today.path("prospectName").asText()).isEqualTo("Prospect only");assertThat(today.path("customer").path("id").isNull()).isTrue();
        assertThat(getJson("quotations"+RANGE+"&pastValidity=true","ADMIN").path("totalItems").asInt()).isEqualTo(6);
        assertThat(getJson("quotations"+RANGE+"&pastValidity=false","ADMIN").path("totalItems").asInt()).isEqualTo(2);
    }
    @Test void cancelledLinkedDraftAndConvertedPostedSaleKeepTheirActualStatuses() throws Exception {
        int qid=id("SELECT quotation_id FROM dbo.Quotations WHERE quotation_no='P5-CANCELLED-LINK'");
        var q=getJson("quotations/"+qid,"ADMIN").path("quotation");
        assertThat(q.path("status").asText()).isEqualTo("ACCEPTED");assertThat(q.path("linkedSale").path("status").asText()).isEqualTo("CANCELLED");
        qid=id("SELECT quotation_id FROM dbo.Quotations WHERE quotation_no='P5-Q-CONVERTED'");q=getJson("quotations/"+qid,"ADMIN").path("quotation");
        assertThat(q.path("status").asText()).isEqualTo("CONVERTED");assertThat(q.path("linkedSale").path("status").asText()).isEqualTo("POSTED");
    }
    @Test void quotationPagingSortingCustomerFilterAndEmptyItemPageStayBounded() throws Exception {
        var first=getJson("quotations"+RANGE+"&size=1&sort=validUntil,asc","ADMIN");
        var second=getJson("quotations"+RANGE+"&page=1&size=1&sort=validUntil,asc","ADMIN");
        assertThat(first.path("totalItems").asInt()).isEqualTo(8);assertThat(first.path("totalPages").asInt()).isEqualTo(8);
        assertThat(first.path("items").get(0).path("id").asInt()).isNotEqualTo(second.path("items").get(0).path("id").asInt());
        int customer=id("SELECT customer_id FROM dbo.Customers WHERE customer_code='P3-C1'");
        assertThat(getJson("quotations"+RANGE+"&customerId="+customer,"ADMIN").path("totalItems").asInt()).isEqualTo(6);
        var detail=getJson("quotations/"+quotation+"?page=1&size=1","ADMIN");assertThat(detail.path("items").path("items").size()).isZero();assertThat(detail.path("items").path("totalItems").asInt()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"%","_","[","'","--",";","عميل","P5-فاتورة","' OR 1=1--"})
    void invoiceSearchIsLiteralAndSafe(String search) throws Exception {
        var response=mvc.perform(get(PREFIX+"invoices").param("from","2026-11-01").param("to","2026-11-01").param("q",search).header("Authorization",bearer("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        int expected=Set.of("--",";","' OR 1=1--").contains(search)?0:search.equals("P5-فاتورة")?1:2;
        assertThat(JSON.readTree(response.getContentAsString()).path("totalItems").asInt()).isEqualTo(expected);
    }
    static Stream<org.junit.jupiter.params.provider.Arguments> matrix() { return Stream.of("ADMIN","ACCOUNTANT","CASHIER","STOREKEEPER").flatMap(r->Stream.of("invoices","invoices/"+invoice,"quotations","quotations/"+quotation,"daily-summary").map(p->org.junit.jupiter.params.provider.Arguments.of(r,p))); }
    @ParameterizedTest @MethodSource("matrix") void sqlReleasedRoleMatrix(String role,String path) throws Exception {
        mvc.perform(get(PREFIX+path).header("Authorization",bearer(role))).andExpect(status().is(role.equals("STOREKEEPER")&&!path.equals("daily-summary")?403:200));
    }
    @Test void dailyComposesReleasedAccountingByEachActivityDate() throws Exception {
        var summary=getJson("daily-summary?date=2026-10-07","ADMIN");
        assertThat(summary.path("sales").path("grossSales").asText()).isEqualTo("10.000");assertThat(summary.path("sales").path("returns").asText()).isEqualTo("3.125");
        assertThat(summary.path("sales").path("netSales").asText()).isEqualTo("6.875");assertThat(summary.path("sales").path("invoiceCount").asInt()).isEqualTo(1);
        assertThat(summary.path("sales").path("profit").path("historicalCost").asText()).isEqualTo("3.000");assertThat(summary.path("sales").path("profit").path("netProfit").asText()).isEqualTo("4.125");
        assertThat(summary.path("expenses").path("total").asText()).isEqualTo("1.000");assertThat(summary.path("cashbox").path("netMovement").asText()).isEqualTo("-2.000");
        assertThat(summary.path("inventorySnapshot").path("businessDate").asText()).isNotEqualTo("2026-10-07");
    }
    @Test void returnOnlyDayAndEmptyFutureDayAllowLegitimateNegativeAndZeroValues() throws Exception {
        var day=getJson("daily-summary?date=2026-10-03","ADMIN");assertThat(day.path("sales").path("netSales").asText()).isEqualTo("-2.500");
        assertThat(day.path("sales").path("invoiceCount").asInt()).isZero();assertThat(day.path("sales").path("profit").path("netProfit").asText()).isEqualTo("-1.250");
        var future=getJson("daily-summary?date=2099-01-01","ADMIN");assertThat(future.path("sales").path("grossSales").asText()).isEqualTo("0.000");assertThat(future.path("sales").path("averageInvoice").asText()).isEqualTo("0.000");
        var midnight=getJson("daily-summary?date=2026-11-01","ADMIN");assertThat(midnight.path("sales").path("grossSales").asText()).isEqualTo("15.125");assertThat(midnight.path("sales").path("invoiceCount").asInt()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","STOREKEEPER","ACCOUNTANT"}) void dailySectionRedactionMatchesCore(String role) throws Exception {
        var body=getJson("daily-summary?date=2026-10-07",role);
        assertThat(body.has("sales")).isEqualTo(!role.equals("STOREKEEPER"));assertThat(body.has("cashbox")).isEqualTo(role.equals("ACCOUNTANT"));
        assertThat(body.has("expenses")).isEqualTo(role.equals("ACCOUNTANT"));assertThat(body.has("inventorySnapshot")).isEqualTo(role.equals("STOREKEEPER"));
        if(role.equals("CASHIER")) assertThat(body.path("sales").has("profit")).isFalse();
    }
    @Test void missingDocumentsValidationAndNoWrites() throws Exception {
        for(String path:List.of("invoices/2147483647","quotations/2147483647")) mvc.perform(get(PREFIX+path).header("Authorization",bearer("ADMIN"))).andExpect(status().isNotFound());
        for(String path:List.of("invoices","quotations")) {
            mvc.perform(get(PREFIX+path).param("sort","total; DROP TABLE Sales").header("Authorization",bearer("ADMIN"))).andExpect(status().isBadRequest());
            mvc.perform(post(PREFIX+path).header("Authorization",bearer("ADMIN"))).andExpect(status().isMethodNotAllowed());
        }
        mvc.perform(get(PREFIX+"invoices").param("from","2026-11-02").param("to","2026-11-01").header("Authorization",bearer("ADMIN"))).andExpect(status().isBadRequest());
        assertThat(getJson("daily-summary","ADMIN").path("date").asText()).isEqualTo(TemporaryDatabase.queryOne("SELECT CONVERT(varchar(10),SYSDATETIME(),23)"));
    }
    @Test void representativeDocumentQueryPlansUseFrozenIndexesWithoutDdl() throws Exception {
        List<String> plans=new ArrayList<>();
        try(var con=TemporaryDatabase.connect(TemporaryDatabase.NAME);var st=con.createStatement()) {
            st.execute("SET SHOWPLAN_XML ON");
            for(String sql:List.of("SELECT s.sale_id,s.total_amount,COALESCE(r.returned,0) FROM dbo.Sales s OUTER APPLY (SELECT SUM(sr.total_amount) AS returned FROM dbo.Sale_Returns sr WHERE sr.sale_id=s.sale_id AND sr.return_date<'2026-11-02') r WHERE s.sale_date>='2026-11-01' AND s.sale_date<'2026-11-02' ORDER BY s.sale_date,s.sale_id OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
                    "SELECT q.quotation_id,q.status,q.total_amount FROM dbo.Quotations q WHERE q.quotation_date>='2026-11-01' AND q.quotation_date<'2026-11-02' ORDER BY q.quotation_date,q.quotation_id OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY"))
                try(var rs=st.executeQuery(sql)) {assertThat(rs.next()).isTrue();plans.add(rs.getString(1));}
            st.execute("SET SHOWPLAN_XML OFF");
        }
        assertThat(plans).allSatisfy(xml->assertThat(xml).contains("RelOp","IndexScan"));
        Files.writeString(Path.of("target/phase5-query-plans.xml"),"<plans>"+String.join("\n",plans.stream().map(p->p.replaceFirst("<\\?xml[^>]*>","")).toList())+"</plans>");
    }
    @Test void packagedJarRealHttpAndCompleteOpenApiInventory() throws Exception {
        Path jar=Path.of("target/almahwar-api-0.1.0-SNAPSHOT.jar");assertThat(Files.isRegularFile(jar)).isTrue();
        try(var zip=new java.util.zip.ZipFile(jar.toFile());var files=Files.walk(Path.of("target/classes/com/almahwar/api/manager"))) {
            for(Path file:files.filter(p->p.getFileName().toString().startsWith("ManagerDocument")&&p.toString().endsWith(".class")).toList()) {
                String entry="BOOT-INF/classes/"+Path.of("target/classes").relativize(file).toString().replace('\\','/');
                assertThat(zip.getInputStream(zip.getEntry(entry)).readAllBytes()).as("Packaged class matches compiled source: "+file.getFileName()).isEqualTo(Files.readAllBytes(file));
            }
        }
        int port;try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",jar.toAbsolutePath().toString());
        var env=builder.environment();env.put("SPRING_PROFILES_ACTIVE","dev");env.put("ALMAHWAR_API_PORT",String.valueOf(port));
        env.put("ALMAHWAR_DB_HOST",TemporaryDatabase.host());env.put("ALMAHWAR_DB_PORT",TemporaryDatabase.port());env.put("ALMAHWAR_DB_NAME",TemporaryDatabase.NAME);
        env.put("ALMAHWAR_DB_USER",TemporaryDatabase.user());env.put("ALMAHWAR_DB_PASSWORD",TemporaryDatabase.password());env.put("ALMAHWAR_DB_TRUST_SERVER_CERTIFICATE",String.valueOf(TemporaryDatabase.trustServerCertificate()));
        env.put("ALMAHWAR_API_DB_HOST",TemporaryDatabase.host());env.put("ALMAHWAR_API_DB_PORT",TemporaryDatabase.port());env.put("ALMAHWAR_API_DB_NAME",TemporaryApiDatabase.NAME);
        env.put("ALMAHWAR_API_DB_USER",TemporaryDatabase.user());env.put("ALMAHWAR_API_DB_PASSWORD",TemporaryDatabase.password());env.put("ALMAHWAR_API_DB_TRUST_SERVER_CERTIFICATE",String.valueOf(TemporaryDatabase.trustServerCertificate()));
        env.put("ALMAHWAR_API_JWT_SECRET",ApiWebTestBase.TEST_JWT_SECRET);
        builder.redirectErrorStream(true).redirectOutput(Path.of("target/phase5-packaged-http.log").toFile());
        Process process=builder.start();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();String base="http://localhost:"+port;
        try {
            boolean ready=false;long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while(process.isAlive() && System.nanoTime()<deadline) {
                try { ready=send(client,base+"/api/v1/health/ready",null).statusCode()==200; } catch(java.io.IOException e) { }
                if(ready) break;Thread.sleep(250);
            }
            assertThat(ready).as("Packaged API ready; diagnostics in ignored target log").isTrue();
            assertThat(JSON.readTree(send(client,base+"/api/v1/health",null).body()).path("status").asText()).isEqualTo("UP");
            Map<String,String> tokens=new HashMap<>();
            for(String role:List.of("ADMIN","ACCOUNTANT","CASHIER","STOREKEEPER")) tokens.put(role,httpLogin(client,base,"p3_"+role));
            List<String> paths=List.of("invoices"+RANGE,"invoices/"+invoice,"quotations"+RANGE,"quotations/"+quotation,"daily-summary?date=2026-10-07");
            for(String role:tokens.keySet()) for(String path:paths) {
                var response=send(client,base+PREFIX+path,tokens.get(role));assertThat(response.statusCode()).isEqualTo(role.equals("STOREKEEPER")&&!path.startsWith("daily-summary")?403:200);
                if(response.statusCode()==200) assertThat(response.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
            }
            var cashier=JSON.readTree(send(client,base+PREFIX+"invoices/"+invoice,tokens.get("CASHIER")).body());assertThat(cashier.path("invoice").has("historicalCost")).isFalse();assertThat(cashier.path("invoice").has("grossProfit")).isFalse();
            assertThat(JSON.readTree(send(client,base+PREFIX+"invoices"+RANGE+"&size=1&sort=total,desc&q=P5",tokens.get("ADMIN")).body()).path("items").size()).isEqualTo(1);
            assertThat(send(client,base+PREFIX+"invoices/2147483647",tokens.get("ADMIN")).statusCode()).isEqualTo(404);
            assertThat(send(client,base+PREFIX+"quotations?size=101",tokens.get("ADMIN")).statusCode()).isEqualTo(400);
            for(String path:paths) { assertThat(send(client,base+PREFIX+path,null).statusCode()).isEqualTo(401);assertThat(send(client,base+PREFIX+path,"invalid-test-token").statusCode()).isEqualTo(401); }
            String restricted=httpLogin(client,base,"p5_must");for(String path:paths) assertThat(send(client,base+PREFIX+path,restricted).statusCode()).isEqualTo(403);
            TemporaryDatabase.exec("UPDATE dbo.Users SET is_active=0 WHERE username='p3_CASHIER'");
            try { for(String path:paths) assertThat(send(client,base+PREFIX+path,tokens.get("CASHIER")).statusCode()).isEqualTo(401); }
            finally { TemporaryDatabase.exec("UPDATE dbo.Users SET is_active=1 WHERE username='p3_CASHIER'"); }
            var specification=JSON.readTree(send(client,base+"/v3/api-docs",null).body());int total=0,manager=0,gets=0,managerGets=0,phase5=0;
            List<Map<String,String>> inventory=new ArrayList<>();
            for(var path:specification.path("paths").properties()) for(var operation:path.getValue().properties()) {
                String method=operation.getKey();if(!Set.of("get","post","put","delete","patch").contains(method)) continue;
                total++;if(method.equals("get")) gets++;boolean isManager=path.getKey().startsWith(PREFIX);
                if(isManager) { manager++;if(method.equals("get")) managerGets++;
                    if(!path.getKey().contains("/admin/")) assertThat(method).isEqualTo("get"); }
                if(path.getKey().matches("/api/v1/manager/(invoices(/\\{id\\})?|quotations(/\\{id\\})?|daily-summary)")) {phase5++;assertThat(method).isEqualTo("get");}
                inventory.add(Map.of("method",method.toUpperCase(Locale.ROOT),"path",path.getKey()));
            }
            assertThat(total).isEqualTo(45);assertThat(manager).isEqualTo(34);assertThat(managerGets).isEqualTo(29);assertThat(phase5).isEqualTo(5);
            Files.writeString(Path.of("target/phase5-route-inventory.json"),JSON.writeValueAsString(Map.of("totalApiOperations",total,"apiGet",gets,"apiMutations",total-gets,"managerOperations",manager,"managerGet",managerGets,"managerMutations",manager-managerGets,"phase5Get",phase5,"routes",inventory)));
            Files.writeString(Path.of("target/phase5-openapi.json"),specification.toString());
        } finally { process.destroy();if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();process.waitFor();} }
    }
    private static HttpResponse<String> send(HttpClient client,String url,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));if(token!=null) request.header("Authorization","Bearer "+token);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String httpLogin(HttpClient client,String base,String user) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username",user,"password",PASSWORD,"deviceLabel","phase5-packaged")))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body()).path("accessToken").asText();
    }
    @AfterAll static void cleanup() throws Exception {
        try { TemporaryApiDatabase.drop(); } finally { TemporaryDatabase.drop(); }
        try(var con=TemporaryDatabase.connect("master");var ps=con.prepareStatement("SELECT DB_ID(?),DB_ID(?)")) {
            ps.setString(1,TemporaryDatabase.NAME);ps.setString(2,TemporaryApiDatabase.NAME);
            try(var rs=ps.executeQuery()) { assertThat(rs.next()).isTrue();assertThat(rs.getObject(1)).isNull();assertThat(rs.getObject(2)).isNull(); }
        }
    }
}
