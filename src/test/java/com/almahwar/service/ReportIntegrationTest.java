package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DashboardDao;
import com.almahwar.dao.ExpenseDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.QuotationDao;
import com.almahwar.dao.ReportDao;
import com.almahwar.dao.ReturnDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.CashMovement;
import com.almahwar.model.Category;
import com.almahwar.model.Customer;
import com.almahwar.model.DashboardStats;
import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.ReportPeriod;
import com.almahwar.model.ReportTable;
import com.almahwar.model.ReportType;
import com.almahwar.model.Reports;
import com.almahwar.model.Reports.CashReportSummary;
import com.almahwar.model.Reports.ProductPerformance;
import com.almahwar.model.Reports.ProfitSummary;
import com.almahwar.model.Reports.PurchaseSummary;
import com.almahwar.model.Reports.QuotationSummary;
import com.almahwar.model.Reports.SalesSummary;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.model.ReturnReason;
import com.almahwar.model.Role;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleType;
import com.almahwar.model.Supplier;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reports against a real SQL Server. Documents are made through the real services, then moved to otherwise empty
 * historical periods (2002–2005), so each report's figures are known exactly: cross-period returns, historical
 * cost, day / month / year boundaries, cash opening balance, net best sellers, quotations, permissions and cost
 * visibility, XLSX export, dashboard consistency and read-only behaviour. Enable with {@code -Ddb.it=true}.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    private final ProductDao productDao = new ProductDao();
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final SaleDao saleDao = new SaleDao();
    private final PurchaseDao purchaseDao = new PurchaseDao();
    private final ReturnDao returnDao = new ReturnDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final AccountLedgerDao ledgerDao = new AccountLedgerDao();
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final StockLedger stockLedger = new StockLedger(movementDao);
    private final AccountLedger accountLedger = new AccountLedger(ledgerDao, customerDao, supplierDao);
    private final TestSecurity security = new TestSecurity();
    private final SaleService sales = new SaleServiceImpl(saleDao, customerDao, productDao, new UnitDao(), stockLedger,
            accountLedger, cashDao, new AuditLogDao(), security);
    private final PurchaseService purchases = new PurchaseServiceImpl(purchaseDao, supplierDao, productDao, new UnitDao(),
            stockLedger, accountLedger, cashDao, new AuditLogDao(), CostingPolicy.LAST_PURCHASE_COST, security);
    private final ReturnService returns = new ReturnServiceImpl(returnDao, saleDao, purchaseDao, customerDao,
            supplierDao, productDao, stockLedger, accountLedger, cashDao, new AuditLogDao(), security);
    private final ExpenseService expenses = new ExpenseServiceImpl(new ExpenseDao(), cashDao, new AuditLogDao(), security);
    private final CashboxService cashbox = new CashboxServiceImpl(cashDao, new AuditLogDao(), security);
    private final QuotationService quotations = new QuotationServiceImpl(new QuotationDao(), customerDao, productDao,
            new UnitDao(), saleDao, sales, new AuditLogDao(), security);
    private final ReportService reports = new ReportServiceImpl(new ReportDao(), accountLedger, customerDao, supplierDao,
            productDao, security, "شركة المحور");

    private int adminId;
    private int cashierId;
    private int storekeeperId;
    private int accountantId;
    private Integer categoryId;
    private Integer pieceId;
    private Supplier supplier;
    private final List<Customer> customers = new ArrayList<>();
    private final List<Product> products = new ArrayList<>();
    private final List<Integer> saleIds = new ArrayList<>();
    private final List<Integer> purchaseIds = new ArrayList<>();

    private final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }

        String text(String sql, Object... params) {
            return queryOne(sql, rs -> rs.getString(1), params).orElse(null);
        }
    }

    private final Sql sql = new Sql();

    @BeforeAll
    void setUp() {
        adminId = newUser(Role.ADMIN);
        cashierId = newUser(Role.CASHIER);
        storekeeperId = newUser(Role.STOREKEEPER);
        accountantId = newUser(Role.ACCOUNTANT);
        Category c = new Category();
        c.setNameAr("قسم تقارير " + suffix);
        categoryId = new CategoryDao().insert(c);
        Unit piece = new Unit();
        piece.setNameAr("حبة تقارير " + suffix);
        pieceId = new UnitDao().insert(piece);
        supplier = new Supplier();
        supplier.setSupplierCode("RPS-" + suffix);
        supplier.setName("مورد تقارير " + suffix);
        supplier.setSupplierId(supplierDao.insert(supplier));
    }

    private int newUser(String role) {
        User u = new User();
        u.setUsername("rep_" + role.toLowerCase().substring(0, 3) + "_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("مستخدم تقارير " + role);
        u.setRoleId(new RoleDao().findByCode(role).orElseThrow().getRoleId());
        return new UserDao().insert(u);
    }

    @BeforeEach
    void loginAsAdmin() {
        security.admin(adminId);
    }

    @AfterAll
    void cleanUp() {
        security.admin(adminId);
        Object[] users = {adminId, cashierId, storekeeperId, accountantId};
        for (Integer id : saleIds) {
            returnDao.deleteForTests(ReturnKind.SALE, id);
        }
        for (Integer id : purchaseIds) {
            returnDao.deleteForTests(ReturnKind.PURCHASE, id);
        }
        sql.exec("DELETE FROM dbo.Cash_Transactions WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("DELETE FROM dbo.Expenses WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("UPDATE dbo.Sales SET quotation_id = NULL WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("DELETE FROM dbo.Quotations WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("DELETE FROM dbo.Sales WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("DELETE FROM dbo.Purchases WHERE user_id IN (?, ?, ?, ?)", users);
        for (Product p : products) {
            movementDao.deleteByProduct(p.getProductId());
            sql.exec("DELETE FROM dbo.Products WHERE product_id = ?", p.getProductId());
        }
        for (Customer c : customers) {
            ledgerDao.deleteForParty(PartyType.CUSTOMER, c.getCustomerId());
            sql.exec("DELETE FROM dbo.Customers WHERE customer_id = ?", c.getCustomerId());
        }
        ledgerDao.deleteForParty(PartyType.SUPPLIER, supplier.getSupplierId());
        sql.exec("DELETE FROM dbo.Suppliers WHERE supplier_id = ?", supplier.getSupplierId());
        sql.exec("DELETE FROM dbo.Categories WHERE category_id = ?", categoryId);
        sql.exec("DELETE FROM dbo.Units WHERE unit_id = ?", pieceId);
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("DELETE FROM dbo.Users WHERE user_id IN (?, ?, ?, ?)", users);
    }

    // ======================= helpers =======================

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void money(String expected, BigDecimal actual, String what) {
        assertTrue(actual != null && d(expected).compareTo(actual) == 0, what + ": expected " + expected + " but was " + actual);
    }

    private Product product(String tag, String cost, String price, String stock, String minimum) {
        Product p = new Product();
        p.setProductCode("RP" + tag + "-" + suffix);
        p.setBarcode("626" + tag + suffix);
        p.setNameAr("صنف تقرير " + tag + " " + suffix);
        p.setCategoryId(categoryId);
        p.setUnitId(pieceId);
        p.setPurchasePrice(d(cost));
        p.setSalePrice(d(price));
        p.setWholesalePrice(d(price));
        p.setMinimumStock(d(minimum));
        p.setQuantity(BigDecimal.ZERO);
        productDao.insert(p);
        if (d(stock).signum() > 0) {
            TransactionManager.inTransaction(con -> stockLedger.post(con, p.getProductId(), MovementType.OPENING_BALANCE,
                    d(stock), "PRODUCT", p.getProductId(), "رصيد افتتاحي", adminId));
        }
        products.add(p);
        return productDao.findById(p.getProductId()).orElseThrow();
    }

    private Customer customer(String tag) {
        Customer c = new Customer();
        c.setCustomerCode("RPC" + tag + "-" + suffix);
        c.setName("عميل تقرير " + tag + " " + suffix);
        c.setPhone("5" + Math.abs((tag + suffix).hashCode() % 10_000_000));
        c.setCreditLimit(d("100000"));
        c.setCustomerId(customerDao.insert(c));
        customers.add(c);
        return c;
    }

    private Sale sell(Customer c, PaymentType type, String paid, Object... productQty) {
        Sale s = new Sale();
        s.setCustomerId(c.getCustomerId());
        s.setSaleType(SaleType.RETAIL);
        List<SaleItem> items = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            Product p = (Product) productQty[i];
            SaleItem item = new SaleItem();
            item.setProductId(p.getProductId());
            item.setQuantity(d((String) productQty[i + 1]));
            item.setUnitPrice(p.getSalePrice());
            items.add(item);
        }
        s.setItems(items);
        s.setDiscountAmount(BigDecimal.ZERO);
        if (type == PaymentType.PARTIAL) {
            s.setPaymentMethod(PaymentMethod.CASH);
            s.setPaidAmount(d(paid));
        }
        s.setRequestId(UUID.randomUUID());
        Sale saved = sales.saveAndPost(s, type, false);
        saleIds.add(saved.getSaleId());
        return saved;
    }

    private ReturnDocument returnSale(Sale s, int line, String qty) {
        ReturnDocument r = new ReturnDocument();
        r.setKind(ReturnKind.SALE);
        r.setOriginalId(s.getSaleId());
        r.setReason(ReturnReason.DEFECTIVE);
        r.setRefundMethod(PaymentMethod.CASH);
        ReturnLine l = new ReturnLine();
        l.setOriginalItemId(s.getItems().get(line).getSaleItemId());
        l.setQuantity(d(qty));
        r.setLines(new ArrayList<>(List.of(l)));
        r.setRequestId(UUID.randomUUID());
        return returns.create(r);
    }

    private Purchase buy(Product p, String qty, String cost) {
        Purchase pur = new Purchase();
        pur.setSupplierId(supplier.getSupplierId());
        PurchaseItem i = new PurchaseItem();
        i.setProductId(p.getProductId());
        i.setQuantity(d(qty));
        i.setUnitCost(d(cost));
        pur.setItems(new ArrayList<>(List.of(i)));
        pur.setRequestId(UUID.randomUUID());
        Purchase saved = purchases.saveAndPost(pur, PaymentType.CREDIT);
        purchaseIds.add(saved.getPurchaseId());
        return saved;
    }

    private ReturnDocument returnPurchase(Purchase p, String qty) {
        ReturnDocument r = new ReturnDocument();
        r.setKind(ReturnKind.PURCHASE);
        r.setOriginalId(p.getPurchaseId());
        r.setReason(ReturnReason.DAMAGED);
        r.setRefundMethod(PaymentMethod.CASH);
        ReturnLine l = new ReturnLine();
        l.setOriginalItemId(p.getItems().get(0).getPurchaseItemId());
        l.setQuantity(d(qty));
        r.setLines(new ArrayList<>(List.of(l)));
        r.setRequestId(UUID.randomUUID());
        return returns.create(r);
    }

    private void moveSale(Sale s, LocalDateTime when) {
        sql.exec("UPDATE dbo.Sales SET sale_date = ?, posted_at = ? WHERE sale_id = ?", when, when, s.getSaleId());
    }

    private void moveSaleReturn(ReturnDocument r, LocalDateTime when) {
        sql.exec("UPDATE dbo.Sale_Returns SET return_date = ? WHERE return_id = ?", when, r.getReturnId());
    }

    private static ReportFilter month(int year, int month) {
        LocalDate first = LocalDate.of(year, month, 1);
        return ReportFilter.between(first, first.withDayOfMonth(first.lengthOfMonth()));
    }

    private static ReportFilter day(LocalDate d) {
        return ReportFilter.between(d, d);
    }

    // ======================= sales, profit, cross-period =======================

    @Test
    void crossPeriodReturnAndHistoricalCost() {   // sections 6, 7, 32
        Customer c = customer("X");
        Product p = product("X", "2.000", "10.000", "20", "0");
        Sale sale = sell(c, PaymentType.CREDIT, null, p, "10");                      // 100 at cost 2
        moveSale(sale, LocalDateTime.of(2003, 9, 30, 23, 59, 59));
        sql.exec("UPDATE dbo.Products SET purchase_price = 3.000 WHERE product_id = ?", p.getProductId());   // cost now 3
        ReturnDocument ret = returnSale(sale, 0, "3");                               // 30 back
        moveSaleReturn(ret, LocalDateTime.of(2003, 10, 2, 0, 0, 0));

        ReportFilter sept = month(2003, 9).customerId(c.getCustomerId());
        SalesSummary s = reports.sales(sept).summary();
        money("100", s.grossSales(), "September gross");
        money("0", s.returns(), "September returns");
        money("100", s.netSales(), "September net");
        assertEquals(1, s.invoiceCount());
        Reports.SalesRow row = reports.sales(sept).rows().get(0);
        money("0", row.returned(), "returns after the period are not in September's row");

        SalesSummary o = reports.sales(month(2003, 10).customerId(c.getCustomerId())).summary();
        money("0", o.grossSales(), "October gross");
        money("30", o.returns(), "October returns");
        money("-30", o.netSales(), "October net");

        SalesSummary both = reports.sales(ReportFilter.between(LocalDate.of(2003, 9, 1), LocalDate.of(2003, 10, 31))
                .customerId(c.getCustomerId())).summary();
        money("70", both.netSales(), "September + October");

        // profit: historical cost 2 (not today's 3)
        ProfitSummary ps = reports.profit(month(2003, 9));
        money("100", ps.salesRevenue(), "revenue");
        money("20", ps.cogs(), "COGS = 10 × 2");
        money("80", ps.grossProfitBeforeReturns(), "GP before");
        money("80", ps.grossProfitAfterReturns(), "GP after (no return in September)");
        ProfitSummary po = reports.profit(month(2003, 10));
        money("30", po.returnRevenue(), "return revenue");
        money("6", po.returnedCogs(), "returned cost = 3 × 2, not 3 × 3");
        money("24", po.returnProfitReversal(), "reversal");
        money("-24", po.grossProfitAfterReturns(), "October GP after returns");
        money("-24", po.netProfit(), "no expenses");
        money("-30", po.netSales(), "net sales of October");

        // boundaries: 23:59:59 belongs to its day, 00:00:00 to the next
        money("100", reports.sales(day(LocalDate.of(2003, 9, 30)).customerId(c.getCustomerId())).summary().grossSales(), "30/9");
        money("0", reports.sales(day(LocalDate.of(2003, 10, 1)).customerId(c.getCustomerId())).summary().grossSales(), "1/10");
        money("0", reports.sales(day(LocalDate.of(2003, 10, 1)).customerId(c.getCustomerId())).summary().returns(), "1/10 returns");
        money("30", reports.sales(day(LocalDate.of(2003, 10, 2)).customerId(c.getCustomerId())).summary().returns(), "2/10");
    }

    @Test
    void yearBoundaryAndPaging() {   // section 31
        Customer c = customer("Y");
        Product p = product("Y", "1.000", "3.000", "50", "0");
        Sale a = sell(c, PaymentType.CREDIT, null, p, "1");    // 3
        Sale b = sell(c, PaymentType.CREDIT, null, p, "2");    // 6
        Sale e = sell(c, PaymentType.CREDIT, null, p, "3");    // 9
        moveSale(a, LocalDateTime.of(2004, 12, 31, 23, 59, 59));
        moveSale(b, LocalDateTime.of(2005, 1, 1, 0, 0, 0));
        moveSale(e, LocalDateTime.of(2005, 1, 31, 12, 0, 0));
        ReportFilter y2004 = ReportFilter.between(LocalDate.of(2004, 1, 1), LocalDate.of(2004, 12, 31)).customerId(c.getCustomerId());
        ReportFilter y2005 = ReportFilter.between(LocalDate.of(2005, 1, 1), LocalDate.of(2005, 12, 31)).customerId(c.getCustomerId());
        money("3", reports.sales(y2004).summary().grossSales(), "2004");
        money("15", reports.sales(y2005).summary().grossSales(), "2005");
        // average 15 / 2 = 7.500 ; three invoices → 18 / 3 = 6.000
        money("7.5", reports.sales(y2005).summary().averageInvoice(), "average");

        // paging: summary covers all pages; Σ page rows = summary
        ReportFilter all = ReportFilter.between(LocalDate.of(2004, 1, 1), LocalDate.of(2005, 12, 31))
                .customerId(c.getCustomerId()).pageSize(2);
        Reports.Result<SalesSummary, Reports.SalesRow> p0 = reports.sales(all);
        Reports.Result<SalesSummary, Reports.SalesRow> p1 = reports.sales(all.copy().page(1));
        assertEquals(3, p0.totalRows());
        assertEquals(2, p0.rows().size());
        assertEquals(1, p1.rows().size());
        assertEquals(2, p0.pageCount());
        BigDecimal sum = BigDecimal.ZERO;
        for (Reports.SalesRow r : p0.rows()) {
            sum = sum.add(r.grossTotal());
        }
        for (Reports.SalesRow r : p1.rows()) {
            sum = sum.add(r.grossTotal());
        }
        money(p0.summary().grossSales().toPlainString(), sum, "Σ detail = summary");
        money("18", sum, "all three invoices");
    }

    @Test
    void purchasesGrossReturnsNet() {   // sections 8, 32
        Product p = product("P", "4.000", "9.000", "0", "0");
        Purchase pur = buy(p, "10", "4.000");                // 40
        sql.exec("UPDATE dbo.Purchases SET purchase_date = ?, posted_at = ? WHERE purchase_id = ?",
                LocalDateTime.of(2002, 9, 15, 10, 0), LocalDateTime.of(2002, 9, 15, 10, 0), pur.getPurchaseId());
        ReturnDocument r = returnPurchase(pur, "2");         // 8
        sql.exec("UPDATE dbo.Purchase_Returns SET return_date = ? WHERE return_id = ?",
                LocalDateTime.of(2002, 10, 1, 0, 0), r.getReturnId());
        ReportFilter sept = month(2002, 9).supplierId(supplier.getSupplierId());
        PurchaseSummary s = reports.purchases(sept).summary();
        money("40", s.grossPurchases(), "September purchases");
        money("0", s.returns(), "September returns");
        money("40", s.netPurchases(), "September net");
        PurchaseSummary o = reports.purchases(month(2002, 10).supplierId(supplier.getSupplierId())).summary();
        money("0", o.grossPurchases(), "October purchases");
        money("8", o.returns(), "October returns");
        money("-8", o.netPurchases(), "October net");
        Reports.PurchaseRow row = reports.purchases(ReportFilter.between(LocalDate.of(2002, 9, 1), LocalDate.of(2002, 10, 31))
                .supplierId(supplier.getSupplierId())).rows().get(0);
        money("8", row.returned(), "returned by the end of October");
        money("32", row.netTotal(), "net purchase");
    }

    // ======================= expenses, cashbox =======================

    private Expense expense(ExpenseCategory category, String amount, LocalDateTime when) {
        Expense e = new Expense();
        e.setCategory(category);
        e.setDescription("مصروف تقرير " + suffix);
        e.setAmount(d(amount));
        e.setPaymentMethod(PaymentMethod.CASH);
        e.setRequestId(UUID.randomUUID());
        Expense saved = expenses.create(e);
        sql.exec("UPDATE dbo.Expenses SET expense_date = ? WHERE expense_id = ?", when, saved.getExpenseId());
        return saved;
    }

    @Test
    void expensesByCategory() {   // section 9
        expense(ExpenseCategory.RENT, "100", LocalDateTime.of(2002, 3, 1, 0, 0));
        expense(ExpenseCategory.OTHER, "25.500", LocalDateTime.of(2002, 3, 31, 23, 59, 59));
        expense(ExpenseCategory.RENT, "7", LocalDateTime.of(2002, 4, 1, 0, 0));
        Reports.Result<Reports.ExpenseSummary, Reports.ExpenseRow> r = reports.expenses(month(2002, 3).search(suffix));
        money("125.5", r.summary().total(), "March");
        assertEquals(2, r.summary().count());
        money("100", r.summary().byCategory().get(ExpenseCategory.RENT), "rent");
        money("25.5", r.summary().byCategory().get(ExpenseCategory.OTHER), "other");
        money("0", r.summary().byCategory().get(ExpenseCategory.SALARIES), "every category listed");
        BigDecimal sum = r.rows().stream().map(Reports.ExpenseRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        money("125.5", sum, "Σ rows = total");
        money("100", reports.expenses(month(2002, 3).search(suffix).expenseCategory(ExpenseCategory.RENT)).summary().total(),
                "category filter");
        // the expense of the period reduces the period's net profit
        money("-132.5", reports.profit(ReportFilter.between(LocalDate.of(2002, 3, 1), LocalDate.of(2002, 4, 30))).netProfit(),
                "net profit = − expenses when there are no sales");
    }

    private void moveCash(CashMovement m, LocalDateTime when) {
        sql.exec("UPDATE dbo.Cash_Transactions SET transaction_date = ? WHERE transaction_id = ?", when, m.getTransactionId());
    }

    private CashMovement manual(String amount) {
        CashMovement m = new CashMovement();
        m.setAmount(d(amount));
        m.setPaymentMethod(PaymentMethod.CASH);
        m.setDescription("حركة تقرير " + suffix);
        m.setRequestId(UUID.randomUUID());
        return m;
    }

    @Test
    void cashOpeningInOutClosing() {   // sections 10, 11
        moveCash(cashbox.deposit(manual("100")), LocalDateTime.of(2002, 5, 31, 23, 59, 59));
        moveCash(cashbox.deposit(manual("50")), LocalDateTime.of(2002, 6, 1, 0, 0, 0));
        moveCash(cashbox.withdraw(manual("20")), LocalDateTime.of(2002, 6, 30, 23, 59, 59));
        moveCash(cashbox.deposit(manual("7")), LocalDateTime.of(2002, 7, 1, 0, 0, 0));
        Reports.Result<CashReportSummary, Reports.CashRow> june = reports.cashbox(month(2002, 6).search(suffix));
        CashReportSummary s = june.summary();
        money("100", s.openingBalance(), "opening = everything before June (not 0)");
        money("50", s.totalIn(), "in");
        money("20", s.totalOut(), "out");
        money("30", s.netMovement(), "net");
        money("130", s.closingBalance(), "closing = opening + in − out");
        assertEquals(2, june.totalRows());
        CashReportSummary july = reports.cashbox(month(2002, 7).search(suffix)).summary();
        money("130", july.openingBalance(), "July opens with June's closing");
        money("137", july.closingBalance(), "July closing");
        CashReportSummary inOnly = reports.cashbox(month(2002, 6).search(suffix).cashIn(true)).summary();
        money("100", inOnly.openingBalance(), "deposits only");
        money("0", inOnly.totalOut(), "no out");
        money("150", inOnly.closingBalance(), "filtered closing");

        // the whole cashbox: closing of "up to today" = the cashbox balance
        LocalDate today = reports.today();
        CashReportSummary all = reports.cashbox(ReportFilter.between(today, today)).summary();
        money(cashDao.balance().toPlainString(), all.closingBalance(), "closing today = cashbox balance");
    }

    // ======================= inventory =======================

    @Test
    void inventoryLowStockMovementsAndCostVisibility() {   // sections 12–14, 24
        Product a = product("A", "1.250", "3.000", "5", "1");     // value 6.250
        Product b = product("B", "2.000", "4.000", "2", "5");     // value 4.000, short by 3
        ReportFilter cat = ReportFilter.none().categoryId(categoryId).search("RP");
        Reports.Result<Reports.InventoryValuation, Reports.InventoryRow> inv = reports.inventory(cat.copy().search("RPA-" + suffix));
        assertEquals(1, inv.summary().productCount());
        money("6.25", inv.summary().totalValue(), "5 × 1.250");
        money("1.25", inv.rows().get(0).purchaseCost(), "current cost");
        Reports.Result<Reports.InventoryValuation, Reports.InventoryRow> both = reports.inventory(
                ReportFilter.none().categoryId(categoryId).search(suffix));
        BigDecimal sum = both.rows().stream().map(Reports.InventoryRow::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        money(both.summary().totalValue().toPlainString(), sum, "Σ values = total");

        Reports.Result<Reports.CountSummary, Reports.LowStockRow> low = reports.lowStock(
                ReportFilter.none().categoryId(categoryId).search("RPB-" + suffix));
        assertEquals(1, low.summary().count());
        money("3", low.rows().get(0).shortage(), "shortage = 5 − 2");
        assertTrue(reports.lowStock(ReportFilter.none().search("RPA-" + suffix)).rows().isEmpty(), "A is above its minimum");

        LocalDate today = reports.today();
        Reports.Result<Reports.StockMovementSummary, Reports.StockMovementRow> moves = reports.stockMovements(
                ReportFilter.between(today, today).productId(a.getProductId()));
        assertEquals(1, moves.totalRows());
        Reports.StockMovementRow m = moves.rows().get(0);
        assertEquals(MovementType.OPENING_BALANCE, m.type());
        money("5", m.quantity(), "signed +5");
        money("0", m.before(), "before");
        money("5", m.after(), "after = before + quantity");

        // a user with inventory reports but no cost permission never receives cost or value
        security.with(storekeeperId, EnumSet.of(Permission.REPORTS_VIEW, Permission.REPORTS_INVENTORY,
                Permission.REPORTS_EXPORT));
        Reports.Result<Reports.InventoryValuation, Reports.InventoryRow> hidden = reports.inventory(cat.copy().search("RPA-" + suffix));
        assertNull(hidden.summary().totalValue(), "no total value");
        assertNull(hidden.rows().get(0).purchaseCost(), "no cost");
        assertNull(hidden.rows().get(0).value(), "no value");
        assertNull(reports.stockMovements(ReportFilter.between(today, today).productId(a.getProductId())).rows().get(0).unitCost());
        ReportTable t = reports.table(ReportType.INVENTORY, cat);
        assertTrue(t.columns().stream().noneMatch(c -> c.header().equals("القيمة") || c.header().equals("سعر الشراء")));
        assertTrue(t.summary().stream().noneMatch(i -> i.label().contains("قيمة")));
        List<String> headers = exportHeaders(ReportType.INVENTORY, cat);
        assertFalse(headers.contains("القيمة") || headers.contains("سعر الشراء"), "export without cost: " + headers);
    }

    @Test
    void bestSellersAreNetOfReturnsAndSlowMoving() {   // sections 15, 16
        Customer c = customer("BS");
        Product x = product("BX", "1.000", "2.000", "200", "0");   // sold 100, returned 90 → net 10
        Product y = product("BY", "1.000", "2.000", "200", "0");   // sold 20 → net 20
        Sale s1 = sell(c, PaymentType.CREDIT, null, x, "100", y, "20");
        moveSale(s1, LocalDateTime.of(2003, 3, 10, 9, 0));
        ReturnDocument r = returnSale(s1, 0, "90");
        moveSaleReturn(r, LocalDateTime.of(2003, 3, 20, 9, 0));
        List<ProductPerformance> top = reports.bestSellers(month(2003, 3).categoryId(categoryId));
        assertEquals(2, top.size(), top.toString());
        assertEquals(y.getProductCode(), top.get(0).productCode(), "20 net beats 100 − 90");
        ProductPerformance px = top.get(1);
        money("100", px.grossQuantity(), "gross");
        money("90", px.returnedQuantity(), "returned");
        money("10", px.netQuantity(), "net");
        money("20", px.netRevenue(), "200 − 180");
        money("10", px.grossProfit(), "20 − (100 − 90) × 1");

        security.as(Role.STOREKEEPER, storekeeperId);
        assertNull(reports.bestSellers(month(2003, 3).categoryId(categoryId)).get(0).grossProfit(), "no profit for the storekeeper");
        security.admin(adminId);

        // slow moving: Z never sold; X and Y were sold (in 2003, so they are slow for 30 days too)
        Product z = product("BZ", "1.000", "2.000", "4", "0");
        List<Reports.SlowMovingRow> slow = reports.slowMoving(ReportFilter.none().categoryId(categoryId).search(suffix).days(30)).rows();
        Reports.SlowMovingRow zr = slow.stream().filter(s -> s.productCode().equals(z.getProductCode())).findFirst().orElseThrow();
        assertNull(zr.lastSale(), "never sold");
        assertNull(zr.daysSinceLastSale());
        money("4", zr.value(), "4 × 1");
        Reports.SlowMovingRow xr = slow.stream().filter(s -> s.productCode().equals(x.getProductCode())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2003, 3, 10), xr.lastSale());
        // a product sold today is not slow
        Product w = product("BW", "1.000", "2.000", "5", "0");
        sell(c, PaymentType.CREDIT, null, w, "1");
        assertTrue(reports.slowMoving(ReportFilter.none().categoryId(categoryId).search(suffix).days(30)).rows().stream()
                .noneMatch(s -> s.productCode().equals(w.getProductCode())));
    }

    // ======================= customers & suppliers =======================

    @Test
    void debtsBalancesAndStatements() {   // sections 17–20
        Customer c = customer("D");
        Product p = product("D", "1.000", "10.000", "10", "0");
        Sale s = sell(c, PaymentType.PARTIAL, "4", p, "3");   // 30, paid 4 → owes 26
        Reports.Result<Reports.PartyBalanceSummary, Reports.PartyBalanceRow> debts = reports.customerDebts(
                ReportFilter.none().search(c.getCustomerCode()));
        assertEquals(1, debts.summary().count());
        money("26", debts.summary().total(), "owes 26");
        money("26", debts.rows().get(0).balance(), "row");
        assertTrue(debts.rows().get(0).lastDocument() != null, "last sale");
        // the ledger is the source: same as the rebuilt balance
        money(accountLedger.rebuiltBalance(PartyType.CUSTOMER, c.getCustomerId()).toPlainString(), debts.summary().total(), "ledger");

        LocalDate today = reports.today();
        AccountStatement st = reports.customerStatement(ReportFilter.between(today, today).customerId(c.getCustomerId()));
        money("0", st.openingBalance(), "new customer opens at 0");
        assertEquals(List.of(LedgerEntryType.SALE, LedgerEntryType.PAYMENT), st.entries().stream().map(e -> e.getEntryType()).toList());
        money("30", st.entries().get(0).getRunningBalance(), "after the sale");
        money("26", st.closingBalance(), "closing");
        AccountStatement later = reports.customerStatement(ReportFilter.between(today.plusDays(1), today.plusDays(2))
                .customerId(c.getCustomerId()));
        money("26", later.openingBalance(), "opening = everything before the period");
        assertTrue(later.entries().isEmpty());
        money("26", later.closingBalance(), "closing = opening without entries");
        ReportTable stTable = reports.table(ReportType.CUSTOMER_STATEMENT, ReportFilter.between(today, today).customerId(c.getCustomerId()));
        assertEquals("رصيد أول المدة", stTable.rows().get(0).get(1));
        assertThrows(ValidationException.class, () -> reports.customerStatement(ReportFilter.between(today, today)),
                "a customer is required");

        // supplier owed 40 by a credit purchase
        Product q = product("DS", "4.000", "9.000", "0", "0");
        buy(q, "10", "4.000");
        Reports.Result<Reports.PartyBalanceSummary, Reports.PartyBalanceRow> owed = reports.supplierBalances(
                ReportFilter.none().search(supplier.getSupplierCode()));
        money(accountLedger.rebuiltBalance(PartyType.SUPPLIER, supplier.getSupplierId()).toPlainString(),
                owed.summary().total(), "supplier balance from the ledger");
        AccountStatement sst = reports.supplierStatement(ReportFilter.between(today, today).supplierId(supplier.getSupplierId()));
        assertTrue(sst.entries().stream().anyMatch(e -> e.getEntryType() == LedgerEntryType.PURCHASE));
        money(owed.summary().total().toPlainString(), sst.closingBalance(), "statement closing = balance");
        assertTrue(s.getSaleId() != null);
    }

    // ======================= quotations, audit =======================

    @Test
    void quotationStatusesAndConversionRate() {   // section 21
        Customer c = customer("Q");
        Product p = product("Q", "1.000", "5.000", "10", "0");
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Quotation q = new Quotation();
            q.setCustomerId(c.getCustomerId());
            q.setPriceType(SaleType.RETAIL);
            q.setValidUntil(reports.today().plusDays(5));
            QuotationItem it = new QuotationItem();
            it.setProductId(p.getProductId());
            it.setQuantity(BigDecimal.ONE);
            it.setUnitPrice(d("5.000"));
            q.setItems(new ArrayList<>(List.of(it)));
            q.setRequestId(UUID.randomUUID());
            ids.add(quotations.save(q).getQuotationId());
        }
        String[] statuses = {"DRAFT", "SENT", "ACCEPTED", "REJECTED", "CONVERTED"};
        for (int i = 0; i < 5; i++) {
            sql.exec("UPDATE dbo.Quotations SET status = ?, quotation_date = ?, valid_until = ? WHERE quotation_id = ?",
                    statuses[i], LocalDateTime.of(2002, 11, 10 + i, 10, 0),
                    i == 2 ? LocalDate.of(2002, 11, 30) : LocalDate.of(2099, 1, 1), ids.get(i));
        }
        ReportFilter nov = month(2002, 11).customerId(c.getCustomerId());
        QuotationSummary s = reports.quotations(nov).summary();
        assertEquals(5, s.total());
        assertEquals(1, s.draft());
        assertEquals(1, s.sent());
        assertEquals(0, s.accepted(), "accepted but past its validity counts as expired");
        assertEquals(1, s.expired());
        assertEquals(1, s.rejected());
        assertEquals(1, s.converted());
        assertEquals(4, s.eligible(), "all but drafts");
        money("25", s.conversionRate(), "1 / 4");
        money("25", s.totalValue(), "5 × 5");
        assertEquals(1, reports.quotations(nov.copy().quotationStatus(QuotationStatus.EXPIRED)).totalRows());
        assertEquals("ACCEPTED", sql.text("SELECT status FROM dbo.Quotations WHERE quotation_id = ?", ids.get(2)),
                "the report did not change the stored status (read-only)");
        money("0", reports.sales(month(2002, 11).customerId(c.getCustomerId())).summary().grossSales(),
                "quotations are not sales");
        assertNull(reports.quotations(month(1990, 1)).summary().conversionRate(), "no rate without eligible quotations");
    }

    @Test
    void auditReport() {   // section 22
        LocalDate today = reports.today();
        long expected = sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id = ? AND created_at >= ?", adminId, today);
        Reports.Result<Reports.CountSummary, Reports.AuditRow> r = reports.userActivity(
                ReportFilter.between(today, today).userId(adminId).pageSize(1000));
        assertEquals(expected, r.summary().count());
        assertEquals(expected, r.rows().size());
        assertTrue(r.rows().stream().allMatch(a -> a.user().contains("تقارير")));
        assertTrue(reports.auditActions().size() > 0);
    }

    // ======================= permissions & export =======================

    @Test
    void permissionsByRoleIncludingExport() {   // sections 23, 24, 29
        LocalDate today = reports.today();
        ReportFilter t = ReportFilter.between(today, today);

        security.as(Role.CASHIER, cashierId);
        assertEquals(List.of(ReportType.SALES, ReportType.BEST_SELLERS), reports.availableReports());
        reports.sales(t);
        for (ReportType forbidden : List.of(ReportType.PROFIT, ReportType.PURCHASES, ReportType.EXPENSES, ReportType.CASHBOX,
                ReportType.INVENTORY, ReportType.CUSTOMER_DEBTS, ReportType.USER_ACTIVITY, ReportType.QUOTATIONS)) {
            assertThrows(AccessDeniedException.class, () -> reports.table(forbidden, t), forbidden.name());
        }
        assertThrows(AccessDeniedException.class, () -> reports.profit(t), "no profit through the service");
        assertThrows(AccessDeniedException.class, () -> reports.exportXlsx(ReportType.SALES, t, new ByteArrayOutputStream()),
                "no export permission");
        assertTrue(reports.bestSellers(t).stream().allMatch(p -> p.grossProfit() == null), "no profit in best sellers");
        assertTrue(reports.table(ReportType.BEST_SELLERS, t).columns().stream().noneMatch(c -> c.header().contains("ربح")));

        security.as(Role.STOREKEEPER, storekeeperId);
        reports.inventory(ReportFilter.none().search(suffix));
        assertThrows(AccessDeniedException.class, () -> reports.profit(t));
        assertThrows(AccessDeniedException.class, () -> reports.sales(t));
        assertThrows(AccessDeniedException.class, () -> reports.exportXlsx(ReportType.INVENTORY, ReportFilter.none(),
                new ByteArrayOutputStream()));

        security.as(Role.ACCOUNTANT, accountantId);
        reports.profit(t);
        reports.cashbox(t);
        assertThrows(AccessDeniedException.class, () -> reports.inventory(ReportFilter.none()), "stock reports are not financial");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reports.exportXlsx(ReportType.PROFIT, t, out);
        assertTrue(out.size() > 0);

        security.logout();
        assertThrows(RuntimeException.class, () -> reports.sales(t));
        assertTrue(reports.availableReports().isEmpty());
    }

    private List<String> exportHeaders(ReportType type, ReportFilter f) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reports.exportXlsx(type, f, out);
        List<String> texts = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            for (Row row : wb.getSheetAt(0)) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING) {
                        texts.add(cell.getStringCellValue());
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return texts;
    }

    @Test
    void xlsxExportIsARealWorkbook() {   // section 27
        Customer c = customer("XL");
        Product p = product("XL", "1.000", "12.345", "5", "0");
        Sale s = sell(c, PaymentType.CREDIT, null, p, "2");   // 24.690
        moveSale(s, LocalDateTime.of(2003, 6, 15, 10, 30));
        ReportFilter f = month(2003, 6).customerId(c.getCustomerId());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reports.exportXlsx(ReportType.SALES, f, out);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertTrue(sheet.isRightToLeft(), "Arabic sheet is right-to-left");
            assertEquals("شركة المحور", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("تقرير المبيعات", sheet.getRow(1).getCell(0).getStringCellValue());
            Map<String, Cell> summary = new LinkedHashMap<>();
            Row header = null;
            for (Row row : sheet) {
                Cell first = row.getCell(0);
                if (first != null && first.getCellType() == CellType.STRING && row.getCell(1) != null) {
                    summary.putIfAbsent(first.getStringCellValue(), row.getCell(1));
                }
                if (first != null && first.getCellType() == CellType.STRING && first.getStringCellValue().equals("رقم الفاتورة")) {
                    header = row;
                }
            }
            Cell gross = summary.get("إجمالي المبيعات");
            assertEquals(CellType.NUMERIC, gross.getCellType(), "amounts are numbers, not text");
            assertEquals(24.69, gross.getNumericCellValue(), 0.0001);
            assertEquals(XlsxReportWriter.MONEY_FORMAT, gross.getCellStyle().getDataFormatString(), "3 decimals");
            assertTrue(header != null, "table header");
            Row data = sheet.getRow(header.getRowNum() + 1);
            assertEquals(s.getSaleNo(), data.getCell(0).getStringCellValue());
            assertTrue(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(data.getCell(1)), "dates are dates");
            assertEquals(c.getName(), data.getCell(2).getStringCellValue(), "Arabic text kept");
            assertEquals(24.69, data.getCell(5).getNumericCellValue(), 0.0001);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ======================= dashboard, validation, empty, read-only =======================

    @Test
    void dashboardMatchesTheReports() {   // sections 5, 34, 41
        DashboardStats d = new DashboardDao().loadStats();
        LocalDate today = d.serverDate();
        SalesSummary todaySales = reports.sales(ReportFilter.between(today, today)).summary();
        money(todaySales.grossSales().toPlainString(), d.todaySales(), "today gross");
        money(todaySales.returns().toPlainString(), d.todayReturns(), "today returns");
        money(todaySales.netSales().toPlainString(), d.todayNetSales(), "today net");
        assertEquals(todaySales.invoiceCount(), d.todayInvoices(), "today invoices");
        ReportPeriod.DateRange m = reports.range(ReportPeriod.THIS_MONTH);
        ReportFilter month = ReportFilter.between(m.from(), m.to());
        SalesSummary monthSales = reports.sales(month).summary();
        money(monthSales.grossSales().toPlainString(), d.monthSales(), "month gross");
        money(monthSales.netSales().toPlainString(), d.monthNetSales(), "month net");
        ProfitSummary p = reports.profit(month);
        money(p.grossProfitAfterReturns().toPlainString(), d.monthGrossProfit(), "month gross profit");
        money(p.expenses().toPlainString(), d.monthExpenses(), "month expenses");
        money(p.netProfit().toPlainString(), d.monthNetProfit(), "month net profit");
        money(reports.customerDebts(ReportFilter.none()).summary().total().toPlainString(), d.receivables(), "receivables");
        money(reports.supplierBalances(ReportFilter.none()).summary().total().toPlainString(), d.payables(), "payables");
        assertEquals(reports.lowStock(ReportFilter.none()).summary().count(), d.lowStockProducts(), "low stock");

        // the dashboard service shows net sales with gross and returns alongside
        DashboardService.DashboardData data = new DashboardServiceImpl(new DashboardDao(), security)
                .load(DashboardService.TrendRange.LAST_7_DAYS);
        DashboardService.MetricValue card = data.metrics().stream()
                .filter(v -> v.metric() == DashboardService.Metric.TODAY_SALES).findFirst().orElseThrow();
        money(todaySales.netSales().toPlainString(), card.value(), "card = net");
        money(todaySales.grossSales().toPlainString(), card.gross(), "gross kept");
        money(todaySales.returns().toPlainString(), card.returns(), "returns shown");
    }

    @Test
    void invalidFiltersAndEmptyReports() {   // sections 3, 37
        LocalDate today = reports.today();
        assertThrows(ValidationException.class, () -> reports.sales(ReportFilter.between(today, today.minusDays(1))),
                "from after to");
        assertThrows(ValidationException.class, () -> reports.sales(ReportFilter.none()), "dates are required");
        assertThrows(ValidationException.class, () -> reports.sales(ReportFilter.between(today, today).pageSize(5000)));
        assertThrows(ValidationException.class, () -> reports.bestSellers(ReportFilter.between(today, today).topN(0)));
        ReportFilter empty = month(1990, 1);
        SalesSummary s = reports.sales(empty).summary();
        money("0", s.grossSales(), "empty");
        money("0", s.averageInvoice(), "no division by zero");
        assertTrue(reports.sales(empty).rows().isEmpty());
        money("0", reports.profit(empty).netProfit(), "empty profit");
        assertTrue(reports.table(ReportType.SALES, empty).rows().isEmpty());
        for (ReportType type : ReportType.values()) {
            if (type == ReportType.CUSTOMER_STATEMENT || type == ReportType.SUPPLIER_STATEMENT) {
                continue;
            }
            assertTrue(reports.table(type, empty.copy()) != null, type.name());
        }
    }

    /** Row counts and checksums of every business table. */
    private Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String table : List.of("Products", "Stock_Movements", "Sales", "Sale_Items", "Purchases", "Purchase_Items",
                "Sale_Returns", "Sale_Return_Items", "Purchase_Returns", "Purchase_Return_Items", "Account_Ledger",
                "Cash_Transactions", "Expenses", "Quotations", "Quotation_Items", "Customers", "Suppliers",
                "Customer_Payments", "Supplier_Payments", "Audit_Log")) {
            m.put(table, sql.text("SELECT CAST(COUNT(*) AS varchar(20)) + ':' + CAST(COALESCE(CHECKSUM_AGG(BINARY_CHECKSUM(*)), 0)"
                    + " AS varchar(20)) FROM dbo." + table));
        }
        return m;
    }

    @Test
    void reportsAreReadOnly() {   // section 35
        // an open quotation past its validity: a report must not expire it
        Customer c = customer("RO");
        Product p = product("RO", "1.000", "5.000", "10", "0");
        Quotation q = new Quotation();
        q.setCustomerId(c.getCustomerId());
        q.setPriceType(SaleType.RETAIL);
        q.setValidUntil(reports.today().plusDays(3));
        QuotationItem it = new QuotationItem();
        it.setProductId(p.getProductId());
        it.setQuantity(BigDecimal.ONE);
        it.setUnitPrice(d("5.000"));
        q.setItems(new ArrayList<>(List.of(it)));
        q.setRequestId(UUID.randomUUID());
        int qid = quotations.save(q).getQuotationId();
        sql.exec("UPDATE dbo.Quotations SET valid_until = DATEADD(DAY, -1, CAST(SYSDATETIME() AS date)) WHERE quotation_id = ?", qid);

        Map<String, String> before = snapshot();
        LocalDate today = reports.today();
        ReportFilter year = ReportFilter.between(today.withDayOfYear(1), today);
        for (ReportType type : ReportType.values()) {
            ReportFilter f = year.copy();
            if (type == ReportType.CUSTOMER_STATEMENT) {
                f.customerId(c.getCustomerId());
            }
            if (type == ReportType.SUPPLIER_STATEMENT) {
                f.supplierId(supplier.getSupplierId());
            }
            reports.table(type, f);
            reports.exportXlsx(type, f, new ByteArrayOutputStream());
        }
        reports.users();
        reports.customers();
        reports.auditActions();
        assertEquals(before, snapshot(), "no business table changed (stock, documents, ledger, cash, quotations, audit)");
        assertEquals("DRAFT", sql.text("SELECT status FROM dbo.Quotations WHERE quotation_id = ?", qid));
    }
}
