package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CashTransactionDao.CashTransaction;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.Category;
import com.almahwar.model.Customer;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.Role;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleFilter;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.SaleType;
import com.almahwar.model.StockMovement;
import com.almahwar.model.Supplier;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POS / sales against a real SQL Server: the posting transaction and its effects on stock, historical cost,
 * customer ledger and cash; credit limits, permissions, duplicates, rollback and concurrent sales of the
 * last unit. Enable with {@code -Ddb.it=true}. Rows created here are removed.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SaleIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    private final SaleDao saleDao = new SaleDao();
    private final ProductDao productDao = new ProductDao();
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final AccountLedgerDao ledgerDao = new AccountLedgerDao();
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final StockLedger stockLedger = new StockLedger(movementDao);
    private final AccountLedger accountLedger = new AccountLedger(ledgerDao, customerDao, supplierDao);
    private final TestSecurity security = new TestSecurity();
    private final SaleService sales = service(cashDao);

    private int adminId;
    private int cashierId;
    private Customer customer;           // credit limit 1000 (shared by the credit / partial tests)
    private Customer cashOnly;           // credit limit 0
    private Customer inactiveCustomer;
    private Customer walkIn;
    private Supplier supplier;
    private Product tap;                 // cost 5.000, retail 10.000, wholesale 8.000, stock 50
    private Product pipe;                // metre: cost 1.250, retail 2.500, wholesale 2.000, stock 200
    private Product lastUnit;            // stock refilled per test
    private Product inactiveProduct;
    private final List<Product> products = new ArrayList<>();
    private final List<Customer> customers = new ArrayList<>();
    private Integer categoryId;
    private Integer pieceId;
    private Integer metreId;

    private final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }

        List<Integer> ints(String sql, Object... params) {
            return queryList(sql, rs -> rs.getInt(1), params);
        }
    }

    private final Sql sql = new Sql();

    private SaleService service(CashTransactionDao cash) {
        return new SaleServiceImpl(saleDao, customerDao, productDao, new UnitDao(), stockLedger, accountLedger, cash,
                new AuditLogDao(), security);
    }

    @BeforeAll
    void setUp() {
        adminId = newUser(Role.ADMIN);
        cashierId = newUser(Role.CASHIER);

        customer = newCustomer("CRD", "1000", true);
        cashOnly = newCustomer("NOC", "0", true);
        inactiveCustomer = newCustomer("OFF", "500", false);
        walkIn = customerDao.findCashCustomer().orElseThrow();

        supplier = new Supplier();
        supplier.setSupplierCode("SS-" + suffix);
        supplier.setName("مورد مبيعات " + suffix);
        supplier.setSupplierId(supplierDao.insert(supplier));

        Category c = new Category();
        c.setNameAr("قسم مبيعات " + suffix);
        categoryId = new CategoryDao().insert(c);
        Unit piece = new Unit();
        piece.setNameAr("حبة مبيعات " + suffix);
        pieceId = new UnitDao().insert(piece);
        Unit metre = new Unit();
        metre.setNameAr("متر مبيعات " + suffix);
        metre.setAllowsDecimal(true);
        metreId = new UnitDao().insert(metre);

        tap = newProduct("TAP", pieceId, "5.000", "10.000", "8.000", "50", true);
        pipe = newProduct("PIPE", metreId, "1.250", "2.500", "2.000", "200", true);
        lastUnit = newProduct("LAST", pieceId, "3.000", "6.000", "0", "1", true);
        inactiveProduct = newProduct("OFF", pieceId, "1.000", "2.000", "0", "10", false);
    }

    private int newUser(String role) {
        User u = new User();
        u.setUsername("sal_" + role.toLowerCase().substring(0, 3) + "_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("مستخدم مبيعات " + role);
        u.setRoleId(new RoleDao().findByCode(role).orElseThrow().getRoleId());
        return new UserDao().insert(u);
    }

    private Customer newCustomer(String tag, String creditLimit, boolean active) {
        Customer c = new Customer();
        c.setCustomerCode(tag + "-" + suffix);
        c.setName("عميل " + tag + " " + suffix);
        c.setPhone("9" + Math.abs((tag + suffix).hashCode() % 10_000_000));
        c.setCreditLimit(new BigDecimal(creditLimit));
        c.setActive(active);
        c.setCustomerId(customerDao.insert(c));
        customers.add(c);
        return c;
    }

    private Product newProduct(String tag, int unitId, String cost, String retail, String wholesale, String stock,
                               boolean active) {
        Product p = new Product();
        p.setProductCode("SA" + tag + "-" + suffix);
        p.setBarcode("629" + tag + suffix);
        p.setNameAr("صنف بيع " + tag + " " + suffix);
        p.setCategoryId(categoryId);
        p.setUnitId(unitId);
        p.setPurchasePrice(new BigDecimal(cost));
        p.setSalePrice(new BigDecimal(retail));
        p.setWholesalePrice(new BigDecimal(wholesale));
        p.setQuantity(BigDecimal.ZERO);
        p.setActive(active);
        productDao.insert(p);
        // opening stock through the ledger, so quantity = Σ movements
        TransactionManager.inTransaction(con -> stockLedger.post(con, p.getProductId(), MovementType.OPENING_BALANCE,
                new BigDecimal(stock), "PRODUCT", p.getProductId(), "رصيد افتتاحي", adminId));
        products.add(p);
        return productDao.findById(p.getProductId()).orElseThrow();
    }

    @BeforeEach
    void loginAsCashier() {
        security.as(Role.CASHIER, cashierId);
    }

    @AfterAll
    void cleanUp() {
        List<Integer> saleIds = new ArrayList<>();
        for (int user : new int[]{adminId, cashierId}) {
            saleDao.search(new SaleFilter(null, null, null, null, user, null, null, null), 10_000)
                    .forEach(s -> saleIds.add(s.getSaleId()));
        }
        security.admin(adminId);
        for (Integer id : saleIds) {
            cashDao.deleteBySourceForTests("SALE", id);
        }
        List<Integer> purchaseIds = new PurchaseDao().search(com.almahwar.model.PurchaseFilter.forSupplier(
                supplier.getSupplierId()), 1000).stream().map(Purchase::getPurchaseId).toList();
        for (Integer id : purchaseIds) {
            cashDao.deleteBySourceForTests("PURCHASE", id);
        }
        sql.exec("DELETE FROM dbo.Sales WHERE user_id IN (?, ?)", adminId, cashierId);
        sql.exec("DELETE FROM dbo.Purchases WHERE supplier_id = ?", supplier.getSupplierId());
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
        sql.exec("DELETE FROM dbo.Units WHERE unit_id IN (?, ?)", pieceId, metreId);
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id IN (?, ?)", adminId, cashierId);
        sql.exec("DELETE FROM dbo.Users WHERE user_id IN (?, ?)", adminId, cashierId);
    }

    // ---------- helpers ----------

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, d(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private SaleItem line(Product p, String qty, String price, String discount) {
        SaleItem i = new SaleItem();
        i.setProductId(p.getProductId());
        i.setProductName(p.getNameAr());
        i.setQuantity(d(qty));
        i.setUnitPrice(d(price));
        i.setDiscountAmount(d(discount));
        return i;
    }

    private Sale newSale(Customer c, SaleType type, String discount, SaleItem... items) {
        Sale s = new Sale();
        s.setCustomerId(c.getCustomerId());
        s.setSaleType(type);
        s.setDiscountAmount(d(discount));
        s.setItems(new ArrayList<>(List.of(items)));
        s.setRequestId(UUID.randomUUID());
        return s;
    }

    private Sale partial(Sale s, PaymentMethod method, String paid) {
        s.setPaymentMethod(method);
        s.setPaidAmount(d(paid));
        return s;
    }

    private BigDecimal qty(Product p) {
        return productDao.findById(p.getProductId()).orElseThrow().getQuantity();
    }

    private BigDecimal balance(Customer c) {
        return customerDao.findById(c.getCustomerId()).orElseThrow().getBalance();
    }

    private void setStock(Product p, String quantity) {
        BigDecimal delta = d(quantity).subtract(qty(p));
        if (delta.signum() != 0) {
            TransactionManager.inTransaction(con -> stockLedger.post(con, p.getProductId(),
                    delta.signum() > 0 ? MovementType.ADJUSTMENT_IN : MovementType.ADJUSTMENT_OUT, delta.abs(),
                    "PRODUCT", p.getProductId(), "تجهيز اختبار", adminId));
        }
    }

    private long countSalesByRequest(UUID request) {
        return sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE request_id = ?", request.toString());
    }

    /** The critical consistency checks after a posted sale: stock, movements, profit, customer ledger, cash. */
    private void assertConsistent(Sale s, java.util.Map<Integer, BigDecimal> stockBefore, Customer c,
                                  BigDecimal balanceBefore, BigDecimal cashBefore) {
        Sale stored = saleDao.findById(s.getSaleId()).orElseThrow();
        assertEquals(SaleStatus.POSTED, stored.getStatus());
        for (SaleItem item : stored.getItems()) {
            BigDecimal sold = stored.getItems().stream().filter(i -> i.getProductId().equals(item.getProductId()))
                    .map(SaleItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal now = productDao.findById(item.getProductId()).orElseThrow().getQuantity();
            assertEquals(0, stockBefore.get(item.getProductId()).subtract(sold).compareTo(now),
                    "product quantity = previous − sold");
            List<StockMovement> moves = movementDao.findByProduct(item.getProductId());
            StockMovement last = moves.get(moves.size() - 1);
            assertEquals(MovementType.SALE, last.getMovementType());
            assertEquals("SALE", last.getReferenceType());
            assertEquals(s.getSaleId(), last.getReferenceId());
            assertEquals(0, last.getQuantityAfter().compareTo(now), "movement balance_after = product quantity");
            assertEquals(0, last.getQuantityBefore().subtract(last.getQuantityAfter()).compareTo(
                    last.getQuantity().negate()), "quantity_after = quantity_before − sold");
            assertEquals(0, now.compareTo(movementDao.sumForProduct(item.getProductId())), "quantity = Σ movements");
            assertEquals(0, item.getUnitCost().compareTo(last.getUnitCost()), "movement cost = historical unit cost");
        }
        assertMoney(stored.getTotalAmount().subtract(stored.getPaidAmount()).toPlainString(),
                stored.getRemainingAmount(), "remaining = total − paid");
        assertMoney(stored.getTotalAmount().subtract(stored.itemsCostTotal()).toPlainString(),
                stored.getGrossProfit(), "gross profit = total − historical cost total");
        assertMoney(stored.itemsCostTotal().toPlainString(), stored.getCostTotal(), "stored cost total = Σ qty × unit cost");
        if (c != null) {
            assertMoney(accountLedger.rebuiltBalance(PartyType.CUSTOMER, c.getCustomerId()).toPlainString(), balance(c),
                    "customer balance = ledger");
            assertMoney(balanceBefore.add(stored.getRemainingAmount()).toPlainString(), balance(c),
                    "customer balance grows by the unpaid part");
        }
        assertMoney(cashBefore.add(stored.getPaidAmount()).toPlainString(), cashDao.balance(), "cash increases by paid");
    }

    private java.util.Map<Integer, BigDecimal> stock() {
        java.util.Map<Integer, BigDecimal> m = new java.util.HashMap<>();
        for (Product p : products) {
            m.put(p.getProductId(), qty(p));
        }
        return m;
    }

    // ---------- Retail cash sale to the walk-in customer ----------

    @Test
    void retailCashSaleToWalkInCustomer() {
        var before = stock();
        BigDecimal walkInBalance = balance(walkIn);
        BigDecimal cashBefore = cashDao.balance();

        Sale s = sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0",
                line(tap, "2", "10.000", "0"),
                line(pipe, "3.5", "2.500", "0")), PaymentType.CASH, false);

        assertTrue(s.getSaleNo().matches("SAL-\\d{6}"), s.getSaleNo());
        assertEquals(SaleStatus.POSTED, s.getStatus());
        assertMoney("28.750", s.getTotalAmount(), "2 × 10 + 3.5 × 2.5");
        assertMoney("28.750", s.getPaidAmount(), "paid in full");
        assertEquals(PaymentStatus.PAID, s.getPaymentStatus());
        assertNull(s.getCostTotal(), "a cashier does not see the cost");
        assertNull(s.getGrossProfit(), "a cashier does not see the profit");
        assertConsistent(s, before, null, null, cashBefore);

        // walk-in: no ledger entry and never a balance
        assertMoney(walkInBalance.toPlainString(), balance(walkIn), "walk-in balance unchanged");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE reference_type = 'SALE' AND reference_id = ?",
                s.getSaleId()));
        List<CashTransaction> cash = cashDao.findBySource("SALE", s.getSaleId());
        assertEquals(1, cash.size());
        assertEquals("IN", cash.get(0).type());
        assertEquals(PaymentMethod.CASH, cash.get(0).method());
        assertEquals("تحصيل فاتورة مبيعات " + s.getSaleNo(), cash.get(0).description());

        // historical cost: 2 × 5 + 3.5 × 1.25 = 14.375 → profit 14.375
        security.admin(adminId);
        Sale full = sales.findById(s.getSaleId()).orElseThrow();
        assertMoney("14.375", full.getCostTotal(), "cost total");
        assertMoney("14.375", full.getGrossProfit(), "gross profit");
        assertMoney("5.000", full.getItems().get(0).getUnitCost(), "unit cost captured");
    }

    @Test
    void wholesaleKnetSaleUsesWholesalePricesAndRecordsTheCustomerAccount() {
        security.admin(adminId);
        var before = stock();
        BigDecimal balanceBefore = balance(customer);
        BigDecimal cashBefore = cashDao.balance();

        Sale s = sales.saveAndPost(newSale(customer, SaleType.WHOLESALE, "0",
                line(tap, "3", "8.000", "0")), PaymentType.KNET, false);

        assertEquals(SaleType.WHOLESALE, s.getSaleType());
        assertMoney("24.000", s.getTotalAmount(), "3 × wholesale 8");
        assertEquals(PaymentMethod.KNET, s.getPaymentMethod());
        assertConsistent(s, before, customer, balanceBefore, cashBefore);
        assertEquals(PaymentMethod.KNET, cashDao.findBySource("SALE", s.getSaleId()).get(0).method());

        // the ledger shows the invoice and the payment: net zero for a fully paid sale
        List<LedgerEntry> entries = ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, customer.getCustomerId(), null, null)
                .stream().filter(e -> s.getSaleId().equals(e.getReferenceId())).toList();
        assertEquals(List.of(LedgerEntryType.SALE, LedgerEntryType.PAYMENT), entries.stream().map(LedgerEntry::getEntryType).toList());
        assertMoney("24.000", entries.get(0).getDebit(), "SALE debit");
        assertMoney("24.000", entries.get(1).getCredit(), "PAYMENT credit");
    }

    @Test
    void retailPriceOnAWholesaleSaleIsAnOverride() {
        // the cashier may not sell a wholesale invoice at retail prices (or any other price)
        ValidationException e = assertThrows(ValidationException.class, () -> sales.saveAndPost(
                newSale(customer, SaleType.WHOLESALE, "0", line(tap, "1", "10.000", "0")), PaymentType.CASH, false));
        assertTrue(e.getMessage().contains("8.000"), e.getMessage());
    }

    // ---------- Credit and partial ----------

    @Test
    void creditSaleGoesToTheCustomerAccountOnly() {
        var before = stock();
        BigDecimal balanceBefore = balance(customer);
        BigDecimal cashBefore = cashDao.balance();

        Sale s = sales.saveAndPost(newSale(customer, SaleType.RETAIL, "0",
                line(pipe, "4", "2.500", "0")), PaymentType.CREDIT, false);

        assertMoney("0", s.getPaidAmount(), "nothing paid");
        assertEquals(PaymentMethod.CREDIT, s.getPaymentMethod());
        assertEquals(PaymentStatus.UNPAID, s.getPaymentStatus());
        assertConsistent(s, before, customer, balanceBefore, cashBefore);
        assertTrue(cashDao.findBySource("SALE", s.getSaleId()).isEmpty(), "no cash for a credit sale");
    }

    @Test
    void partialSaleWithLineAndInvoiceDiscounts() {
        security.admin(adminId);
        var before = stock();
        BigDecimal balanceBefore = balance(customer);
        BigDecimal cashBefore = cashDao.balance();

        // tap: 10 × 10 − 2 = 98 ; pipe: 6 × 2.5 − 0.5 = 14.5 ; subtotal 112.5 ; invoice discount 12.5 → total 100
        Sale s = sales.saveAndPost(partial(newSale(customer, SaleType.RETAIL, "12.500",
                line(tap, "10", "10.000", "2.000"),
                line(pipe, "6", "2.500", "0.500")), PaymentMethod.KNET, "40"), PaymentType.PARTIAL, true);

        assertMoney("112.500", s.getSubtotal(), "subtotal");
        assertMoney("100.000", s.getTotalAmount(), "total");
        assertMoney("40.000", s.getPaidAmount(), "paid");
        assertMoney("60.000", s.getRemainingAmount(), "remaining");
        assertEquals(PaymentStatus.PARTIAL, s.getPaymentStatus());
        assertEquals(PaymentType.PARTIAL, s.getPaymentType());
        // cost 10 × 5 + 6 × 1.25 = 57.5 ; profit = 100 − 57.5 (both discounts reduce the profit)
        assertMoney("57.500", s.getCostTotal(), "cost");
        assertMoney("42.500", s.getGrossProfit(), "profit");
        assertConsistent(s, before, customer, balanceBefore, cashBefore);

        // ledger: SALE +100, PAYMENT −40 → balance +60
        List<LedgerEntry> entries = ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, customer.getCustomerId(), null, null)
                .stream().filter(e -> s.getSaleId().equals(e.getReferenceId())).toList();
        assertMoney("100.000", entries.get(0).getDebit(), "SALE");
        assertMoney("40.000", entries.get(1).getCredit(), "PAYMENT");
        assertMoney(balanceBefore.add(d("60")).toPlainString(), entries.get(1).getRunningBalance(), "running balance");
        CashTransaction cash = cashDao.findBySource("SALE", s.getSaleId()).get(0);
        assertMoney("40.000", cash.amount(), "cash in = paid");
        assertEquals(PaymentMethod.KNET, cash.method());

        // the statement lists SALE and PAYMENT with a correct running balance
        AccountStatement st = accountLedger.statement(PartyType.CUSTOMER, customer.getCustomerId(), customer.getCustomerCode(),
                customer.getName(), null, null, null);
        assertTrue(st.entries().stream().anyMatch(e -> e.getEntryType() == LedgerEntryType.SALE
                && s.getSaleNo().equals(e.getReferenceNo())));
        assertMoney(balance(customer).toPlainString(), st.closingBalance(), "statement closing = balance");
    }

    @Test
    void walkInCustomerMustPayInFull() {
        for (PaymentType t : new PaymentType[]{PaymentType.CREDIT, PaymentType.PARTIAL}) {
            Sale s = partial(newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1", "10.000", "0")), PaymentMethod.CASH, "4");
            ValidationException e = assertThrows(ValidationException.class, () -> sales.saveAndPost(s, t, false));
            assertTrue(e.getErrors().containsKey(SaleService.PAYMENT_TYPE), t + ": " + e.getErrors());
            assertEquals(0, countSalesByRequest(s.getRequestId()));
        }
    }

    // ---------- Validation against the database ----------

    @Test
    void inactiveCustomerAndInactiveProductAreRefused() {
        ValidationException c = assertThrows(ValidationException.class, () -> sales.saveAndPost(
                newSale(inactiveCustomer, SaleType.RETAIL, "0", line(tap, "1", "10.000", "0")), PaymentType.CREDIT, false));
        assertTrue(c.errorFor(SaleService.CUSTOMER).contains("غير نشط"));
        ValidationException p = assertThrows(ValidationException.class, () -> sales.saveAndPost(
                newSale(walkIn, SaleType.RETAIL, "0", line(inactiveProduct, "1", "2.000", "0")), PaymentType.CASH, false));
        assertTrue(p.errorFor(SaleService.ITEMS).contains("معطّل"));
    }

    @Test
    void insufficientStockIsRefusedWithAvailableAndRequested() {
        setStock(lastUnit, "2");
        Sale s = newSale(walkIn, SaleType.RETAIL, "0", line(lastUnit, "3", "6.000", "0"));
        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> sales.saveAndPost(s, PaymentType.CASH, false));
        assertTrue(e.getMessage().startsWith("الكمية المطلوبة غير متوفرة بالمخزون"));
        assertEquals(0, d("2").compareTo(e.getShortages().get(0).available()));
        assertEquals(0, d("3").compareTo(e.getShortages().get(0).requested()));
        assertMoney("2", qty(lastUnit), "stock unchanged");
        assertEquals(0, countSalesByRequest(s.getRequestId()), "nothing saved");
        // the same product on two lines counts together
        assertThrows(InsufficientStockException.class, () -> sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0",
                line(lastUnit, "1", "6.000", "0"), line(lastUnit, "2", "6.000", "0")), PaymentType.CASH, false));
    }

    @Test
    void wholeUnitsCannotBeSoldInFractions() {
        ValidationException e = assertThrows(ValidationException.class, () -> sales.saveAndPost(
                newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1.5", "10.000", "0")), PaymentType.CASH, false));
        assertTrue(e.errorFor(SaleService.ITEMS).contains("لا تقبل الكسور"));
    }

    // ---------- Credit limit ----------

    @Test
    void creditLimitIsEnforcedAndOnlyTheAdminCanOverride() {
        Customer limited = newCustomer("LIM", "100", true);
        // a first credit sale of 95 fits; the next 10 would make 105 > 100
        assertEquals(SaleStatus.POSTED, sales.saveAndPost(newSale(limited, SaleType.RETAIL, "0",
                line(pipe, "38", "2.500", "0")), PaymentType.CREDIT, false).getStatus());
        assertMoney("95", balance(limited), "within the limit");
        Sale s = newSale(limited, SaleType.RETAIL, "0", line(tap, "1", "10.000", "0"));
        security.admin(adminId);

        CreditLimitExceededException e = assertThrows(CreditLimitExceededException.class,
                () -> sales.saveAndPost(s, PaymentType.CREDIT, false));
        assertTrue(e.isOverridable(), "the admin may override");
        assertEquals(0, countSalesByRequest(s.getRequestId()), "refused sale saved nothing");

        assertTrue(e.getMessage().contains("95.000") && e.getMessage().contains("105.000"), "admin sees the figures: "
                + e.getMessage());
        // a partial payment that keeps the remaining within the limit is fine
        assertEquals(SaleStatus.POSTED, sales.saveAndPost(partial(newSale(limited, SaleType.RETAIL, "0",
                line(tap, "1", "10.000", "0")), PaymentMethod.CASH, "5"), PaymentType.PARTIAL, false).getStatus());
        assertMoney("100", balance(limited), "exactly at the limit");

        // the cashier: refused, not overridable, even when asking for the override
        security.as(Role.CASHIER, cashierId);
        Sale c = newSale(limited, SaleType.RETAIL, "0", line(tap, "1", "10.000", "0"));
        CreditLimitExceededException ce = assertThrows(CreditLimitExceededException.class,
                () -> sales.saveAndPost(c, PaymentType.CREDIT, true));
        assertFalse(ce.isOverridable());
        assertFalse(ce.getMessage().contains(MoneyText.of(balance(limited))), "a cashier does not see the balance");

        // the admin confirms: posted, and the override is in the audit log
        security.admin(adminId);
        Sale ok = sales.saveAndPost(s, PaymentType.CREDIT, true);
        assertEquals(SaleStatus.POSTED, ok.getStatus());
        assertMoney("110", balance(limited), "balance beyond the limit");
        assertMoney(accountLedger.rebuiltBalance(PartyType.CUSTOMER, limited.getCustomerId()).toPlainString(),
                balance(limited), "balance = ledger");
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'CREDIT_OVERRIDE' AND record_id = ? "
                + "AND new_values LIKE ?", String.valueOf(limited.getCustomerId()), "%" + ok.getSaleNo() + "%"));

        // a customer with no credit limit is cash only
        security.as(Role.CASHIER, cashierId);
        CreditLimitExceededException none = assertThrows(CreditLimitExceededException.class, () -> sales.saveAndPost(
                newSale(cashOnly, SaleType.RETAIL, "0", line(pipe, "1", "2.500", "0")), PaymentType.CREDIT, false));
        assertTrue(none.getMessage().contains("نقدي فقط"));
        // ... but may buy for cash
        assertEquals(SaleStatus.POSTED, sales.saveAndPost(newSale(cashOnly, SaleType.RETAIL, "0",
                line(pipe, "1", "2.500", "0")), PaymentType.CASH, false).getStatus());
    }

    /** Formats like the service messages (3 decimals). */
    private static final class MoneyText {
        static String of(BigDecimal v) {
            return com.almahwar.util.MoneyUtil.format(v);
        }
    }

    // ---------- Price override and discount permissions ----------

    @Test
    void priceOverrideNeedsPermissionAndIsAudited() {
        ValidationException e = assertThrows(ValidationException.class, () -> sales.saveAndPost(
                newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1", "9.000", "0")), PaymentType.CASH, false));
        assertTrue(e.errorFor(SaleService.ITEMS).contains("صلاحية تعديل سعر البيع"), e.getMessage());

        security.admin(adminId);
        Sale s = sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1", "9.000", "0")), PaymentType.CASH, false);
        assertMoney("9.000", s.getItems().get(0).getUnitPrice(), "admin price kept");
        int itemId = s.getItems().get(0).getSaleItemId();
        assertEquals(1, sql.count("""
                SELECT COUNT(*) FROM dbo.Audit_Log
                WHERE action = 'PRICE_OVERRIDE' AND table_name = 'Sale_Items' AND record_id = ? AND user_id = ?
                  AND old_values LIKE '%10.000%' AND new_values LIKE '%9.000%' AND description LIKE ?
                """, String.valueOf(itemId), adminId, "%" + s.getSaleNo() + "%"));
    }

    @Test
    void discountsNeedPermission() {
        security.with(cashierId, EnumSet.of(Permission.SALES_VIEW, Permission.SALES_CREATE, Permission.SALES_POST));
        ValidationException line = assertThrows(ValidationException.class, () -> sales.saveAndPost(
                newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1", "10.000", "1.000")), PaymentType.CASH, false));
        assertTrue(line.errorFor(SaleService.DISCOUNT).contains("صلاحية"));
        assertThrows(ValidationException.class, () -> sales.saveAndPost(
                newSale(walkIn, SaleType.RETAIL, "1.000", line(tap, "1", "10.000", "0")), PaymentType.CASH, false));
        // no discount: fine
        assertEquals(SaleStatus.POSTED, sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0",
                line(tap, "1", "10.000", "0")), PaymentType.CASH, false).getStatus());
        // the real cashier role may give discounts
        security.as(Role.CASHIER, cashierId);
        assertEquals(SaleStatus.POSTED, sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0.500",
                line(tap, "1", "10.000", "1.000")), PaymentType.CASH, false).getStatus());
    }

    // ---------- Drafts, duplicates, rollback ----------

    @Test
    void draftHasNoEffectUntilPostedAndCannotBePostedTwice() {
        var before = stock();
        BigDecimal balanceBefore = balance(customer);
        BigDecimal cashBefore = cashDao.balance();

        Sale draft = sales.saveDraft(partial(newSale(customer, SaleType.RETAIL, "0",
                line(pipe, "2", "2.500", "0")), PaymentMethod.CASH, "2"), PaymentType.PARTIAL);
        assertEquals(SaleStatus.DRAFT, draft.getStatus());
        assertEquals(0, before.get(pipe.getProductId()).compareTo(qty(pipe)), "draft: no stock change");
        assertMoney(balanceBefore.toPlainString(), balance(customer), "draft: no ledger");
        assertMoney(cashBefore.toPlainString(), cashDao.balance(), "draft: no cash");
        assertNull(draft.getGrossProfit());

        Sale posted = sales.post(draft.getSaleId(), false);
        assertEquals(SaleStatus.POSTED, posted.getStatus());
        assertConsistent(posted, before, customer, balanceBefore, cashBefore);

        ValidationException again = assertThrows(ValidationException.class, () -> sales.post(draft.getSaleId(), false));
        assertTrue(again.getMessage().contains("معتمدة"));
        assertConsistent(posted, before, customer, balanceBefore, cashBefore);   // nothing applied twice
        assertEquals(1, cashDao.findBySource("SALE", posted.getSaleId()).size(), "cash once");
        assertEquals(2, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE reference_type = 'SALE' AND reference_id = ?",
                posted.getSaleId()), "ledger once (SALE + PAYMENT)");
        assertThrows(ValidationException.class, () -> sales.cancelDraft(posted.getSaleId()), "a posted sale cannot be cancelled");
    }

    @Test
    void cancelledDraftIsKeptWithoutEffects() {
        Sale draft = sales.saveDraft(newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1", "10.000", "0")), PaymentType.CASH);
        sales.cancelDraft(draft.getSaleId());
        assertEquals(SaleStatus.CANCELLED, sales.findById(draft.getSaleId()).orElseThrow().getStatus());
        assertThrows(ValidationException.class, () -> sales.post(draft.getSaleId(), false));
    }

    @Test
    void doubleClickSavesOneSale() {
        var before = stock();
        BigDecimal cashBefore = cashDao.balance();
        Sale s = newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1", "10.000", "0"));
        Sale first = sales.saveAndPost(s, PaymentType.CASH, false);
        Sale copy = newSale(walkIn, SaleType.RETAIL, "0", line(tap, "1", "10.000", "0"));
        copy.setRequestId(s.getRequestId());     // the same request again (double click / retry)
        Sale second = sales.saveAndPost(copy, PaymentType.CASH, false);
        assertEquals(first.getSaleId(), second.getSaleId());
        assertEquals(1, countSalesByRequest(s.getRequestId()), "one invoice");
        assertMoney(before.get(tap.getProductId()).subtract(BigDecimal.ONE).toPlainString(), qty(tap), "stock once");
        assertMoney(cashBefore.add(d("10")).toPlainString(), cashDao.balance(), "cash once");
    }

    @Test
    void saleNumbersAreUniqueAndSequential() {
        Sale a = sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0", line(pipe, "1", "2.500", "0")), PaymentType.CASH, false);
        Sale b = sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0", line(pipe, "1", "2.500", "0")), PaymentType.CASH, false);
        assertEquals(Integer.parseInt(a.getSaleNo().substring(4)) + 1, Integer.parseInt(b.getSaleNo().substring(4)));
    }

    @Test
    void anyFailureRollsBackEverything() {
        // the cash box fails at the very end of the posting: nothing at all may remain
        CashTransactionDao broken = new CashTransactionDao() {
            @Override
            public long insert(Connection con, String type, BigDecimal amount, PaymentMethod method, String sourceType,
                               Integer sourceId, String description, int userId) {
                throw new IllegalStateException("simulated failure");
            }
        };
        SaleService failing = service(broken);
        var before = stock();
        BigDecimal balanceBefore = balance(customer);
        long ledgerRows = sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE customer_id = ?", customer.getCustomerId());
        long movementRows = sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id = ?", tap.getProductId());

        Sale s = partial(newSale(customer, SaleType.RETAIL, "0", line(tap, "2", "10.000", "0"),
                line(pipe, "1", "2.500", "0")), PaymentMethod.CASH, "5");
        assertThrows(IllegalStateException.class, () -> failing.saveAndPost(s, PaymentType.PARTIAL, false));

        assertNull(s.getSaleId(), "the id of the rolled back sale is forgotten");
        assertEquals(0, countSalesByRequest(s.getRequestId()), "no sale");
        assertEquals(0, before.get(tap.getProductId()).compareTo(qty(tap)), "stock unchanged");
        assertEquals(0, before.get(pipe.getProductId()).compareTo(qty(pipe)), "stock unchanged");
        assertEquals(movementRows, sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id = ?", tap.getProductId()));
        assertMoney(balanceBefore.toPlainString(), balance(customer), "balance unchanged");
        assertEquals(ledgerRows, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE customer_id = ?", customer.getCustomerId()));

        // the same request then succeeds normally
        Sale ok = sales.saveAndPost(s, PaymentType.PARTIAL, false);
        assertEquals(SaleStatus.POSTED, ok.getStatus());
    }

    // ---------- Historical cost (profit history) ----------

    @Test
    void oldSalesKeepTheirCostAndProfitWhenThePurchasePriceChanges() {
        security.admin(adminId);
        Product item = newProduct("HIST", pieceId, "5.000", "10.000", "0", "10", true);

        // sell 2 at 10 with cost 5 → profit 10
        Sale old = sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0", line(item, "2", "10.000", "0")),
                PaymentType.CASH, false);
        assertMoney("5.000", old.getItems().get(0).getUnitCost(), "cost at sale time");
        assertMoney("10.000", old.getGrossProfit(), "profit");

        // a new purchase at 7 changes the product's current cost (last purchase cost)
        PurchaseService purchases = new PurchaseServiceImpl(new PurchaseDao(), supplierDao, productDao, new UnitDao(),
                stockLedger, accountLedger, cashDao, new AuditLogDao(), CostingPolicy.LAST_PURCHASE_COST, security);
        Purchase p = new Purchase();
        p.setSupplierId(supplier.getSupplierId());
        PurchaseItem pi = new PurchaseItem();
        pi.setProductId(item.getProductId());
        pi.setQuantity(d("5"));
        pi.setUnitCost(d("7.000"));
        p.setItems(new ArrayList<>(List.of(pi)));
        p.setRequestId(UUID.randomUUID());
        purchases.saveAndPost(p, PaymentType.CREDIT);
        assertMoney("7.000", productDao.findById(item.getProductId()).orElseThrow().getPurchasePrice(), "new cost");
        assertMoney("13", qty(item), "purchase still adds stock: 10 − 2 + 5");

        // the old invoice is unchanged
        Sale again = sales.findById(old.getSaleId()).orElseThrow();
        assertMoney("5.000", again.getItems().get(0).getUnitCost(), "Sale_Items.unit_cost stays 5");
        assertMoney("10.000", again.getCostTotal(), "cost total stays 10");
        assertMoney("10.000", again.getGrossProfit(), "old profit unchanged");

        // a new sale uses the new cost
        Sale fresh = sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0", line(item, "2", "10.000", "0")),
                PaymentType.CASH, false);
        assertMoney("7.000", fresh.getItems().get(0).getUnitCost(), "new sale, new cost");
        assertMoney("6.000", fresh.getGrossProfit(), "20 − 14");
    }

    // ---------- Concurrency: two cashiers sell the last unit at the same moment ----------

    @Test
    void twoCashiersCannotBothSellTheLastUnit() throws Exception {
        setStock(lastUnit, "1");
        List<Outcome> outcomes = race(() -> sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0",
                line(lastUnit, "1", "6.000", "0")), PaymentType.CASH, false));
        assertOnlyOneSold(outcomes);
    }

    @Test
    void twoHeldDraftsCannotBothTakeTheLastUnit() throws Exception {
        // posting existing drafts skips the number lock, so this exercises the product row lock alone
        setStock(lastUnit, "1");
        Sale a = sales.saveDraft(newSale(walkIn, SaleType.RETAIL, "0", line(lastUnit, "1", "6.000", "0")), PaymentType.CASH);
        Sale b = sales.saveDraft(newSale(walkIn, SaleType.RETAIL, "0", line(lastUnit, "1", "6.000", "0")), PaymentType.CASH);
        List<Integer> ids = List.of(a.getSaleId(), b.getSaleId());
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        List<Outcome> outcomes = race(() -> sales.post(ids.get(next.getAndIncrement()), false));
        assertOnlyOneSold(outcomes);
        long posted = ids.stream().filter(id -> sales.findById(id).orElseThrow().getStatus() == SaleStatus.POSTED).count();
        assertEquals(1, posted, "one draft posted, the other is still a draft");
    }

    // ---------- Lock order: purchases and sales lock products by product_id, whatever the line order ----------

    private PurchaseService purchases() {
        return new PurchaseServiceImpl(new PurchaseDao(), supplierDao, productDao, new UnitDao(), stockLedger,
                accountLedger, cashDao, new AuditLogDao(), CostingPolicy.LAST_PURCHASE_COST, security);
    }

    private Purchase purchase(Product first, String q1, Product second, String q2) {
        Purchase p = new Purchase();
        p.setSupplierId(supplier.getSupplierId());
        List<PurchaseItem> items = new ArrayList<>();
        for (Object[] l : new Object[][]{{first, q1}, {second, q2}}) {
            PurchaseItem pi = new PurchaseItem();
            pi.setProductId(((Product) l[0]).getProductId());
            pi.setQuantity(d((String) l[1]));
            pi.setUnitCost(((Product) l[0]).getPurchasePrice());
            items.add(pi);
        }
        p.setItems(items);
        p.setRequestId(UUID.randomUUID());
        return p;
    }

    @Test
    void purchasesAndSalesPostLinesInProductIdOrder() {
        security.admin(adminId);
        Product low = tap.getProductId() < pipe.getProductId() ? tap : pipe;
        Product high = low == tap ? pipe : tap;
        // lines arrive "high, low" from the screen
        Purchase p = purchases().saveAndPost(purchase(high, "2", low, "2"), PaymentType.CREDIT);
        Sale s = sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0",
                line(high, "1", MoneyText.of(SaleType.RETAIL.priceOf(high)), "0"),
                line(low, "1", MoneyText.of(SaleType.RETAIL.priceOf(low)), "0")), PaymentType.CASH, false);
        for (String[] doc : new String[][]{{"PURCHASE", String.valueOf(p.getPurchaseId())}, {"SALE", String.valueOf(s.getSaleId())}}) {
            List<Integer> written = new ArrayList<>();
            sqlRows(doc[0], Integer.parseInt(doc[1])).forEach(written::add);
            assertEquals(List.of(low.getProductId(), high.getProductId()), written,
                    doc[0] + ": stock changed in product_id order, not in line order");
        }
    }

    /** Product ids of a document's movements in the order the stock was changed (movement id). */
    private List<Integer> sqlRows(String type, int id) {
        return sql.ints("SELECT product_id FROM dbo.Stock_Movements WHERE reference_type = ? AND reference_id = ? "
                + "ORDER BY movement_id", type, id);
    }

    @Test
    void concurrentPurchaseAndSaleWithReversedLinesNeverDeadlock() throws Exception {
        security.admin(adminId);
        Product a = newProduct("LKA", pieceId, "1.000", "2.000", "0", "100", true);
        Product b = newProduct("LKB", pieceId, "1.500", "3.000", "0", "100", true);
        PurchaseService purchases = purchases();
        int rounds = Integer.getInteger("lock.rounds", 20);
        for (int r = 0; r < rounds; r++) {
            // sale: a then b ; purchase: b then a (the opposite order)
            List<Throwable> errors = together(
                    () -> sales.saveAndPost(newSale(walkIn, SaleType.RETAIL, "0", line(a, "1", "2.000", "0"),
                            line(b, "1", "3.000", "0")), PaymentType.CASH, false),
                    () -> purchases.saveAndPost(purchase(b, "2", a, "2"), PaymentType.CREDIT));
            assertTrue(errors.isEmpty(), "round " + r + ": no deadlock / no failure: " + errors);
        }
        for (Product p : List.of(a, b)) {
            assertMoney(String.valueOf(100 + rounds * 2 - rounds), qty(p), p.getProductCode() + ": 100 + purchased − sold");
            assertEquals(0, qty(p).compareTo(movementDao.sumForProduct(p.getProductId())), "quantity = Σ movements");
        }
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Products WHERE quantity < 0"), "never negative");
    }

    @Test
    void aFailedSaleNextToAConcurrentPurchaseRollsBackCompletely() throws Exception {
        security.admin(adminId);
        Product a = newProduct("RBA", pieceId, "1.000", "2.000", "0", "10", true);
        Product b = newProduct("RBB", pieceId, "1.500", "3.000", "0", "10", true);
        SaleService failing = service(new CashTransactionDao() {
            @Override
            public long insert(Connection con, String type, BigDecimal amount, PaymentMethod method, String sourceType,
                               Integer sourceId, String description, int userId) {
                throw new IllegalStateException("simulated failure");
            }
        });
        Sale doomed = newSale(walkIn, SaleType.RETAIL, "0", line(a, "3", "2.000", "0"), line(b, "3", "3.000", "0"));
        PurchaseService purchases = purchases();
        List<Throwable> errors = together(() -> failing.saveAndPost(doomed, PaymentType.CASH, false),
                () -> purchases.saveAndPost(purchase(b, "5", a, "5"), PaymentType.CREDIT));
        assertEquals(1, errors.size(), "only the sale fails: " + errors);
        assertTrue(errors.get(0) instanceof IllegalStateException, String.valueOf(errors.get(0)));
        assertEquals(0, countSalesByRequest(doomed.getRequestId()), "no trace of the failed sale");
        for (Product p : List.of(a, b)) {
            assertMoney("15", qty(p), p.getProductCode() + ": 10 + 5 purchased, nothing sold");
            assertEquals(0, qty(p).compareTo(movementDao.sumForProduct(p.getProductId())), "quantity = Σ movements");
            assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id = ? AND movement_type = 'SALE'",
                    p.getProductId()), "no SALE movement left behind");
        }
    }

    /** Runs both at the same moment; returns the errors (empty when both succeeded). */
    private List<Throwable> together(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Callable<?> c : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        c.call();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();
            List<Throwable> errors = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                Throwable t = f.get(60, TimeUnit.SECONDS);
                if (t != null) {
                    errors.add(t);
                }
            }
            return errors;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Outcome(Sale sale, Throwable error) {
    }

    private List<Outcome> race(Callable<Sale> sell) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return new Outcome(sell.call(), null);
                    } catch (Throwable t) {
                        return new Outcome(null, t);
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertOnlyOneSold(List<Outcome> outcomes) {
        List<Outcome> sold = outcomes.stream().filter(o -> o.sale() != null).toList();
        List<Outcome> failed = outcomes.stream().filter(o -> o.error() != null).toList();
        assertEquals(1, sold.size(), "exactly one sale posted: " + outcomes);
        assertEquals(1, failed.size());
        assertTrue(failed.get(0).error() instanceof InsufficientStockException,
                "the other fails with a clear stock message: " + failed.get(0).error());
        assertTrue(failed.get(0).error().getMessage().contains("المتوفر 0"), failed.get(0).error().getMessage());
        assertMoney("0", qty(lastUnit), "final stock 0, never −1");
        assertEquals(1, sql.count("""
                SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id = ? AND movement_type = 'SALE'
                  AND reference_id = ?""", lastUnit.getProductId(), sold.get(0).sale().getSaleId()));
        assertEquals(0, qty(lastUnit).compareTo(movementDao.sumForProduct(lastUnit.getProductId())), "quantity = Σ movements");
    }

    // ---------- Permissions ----------

    @Test
    void accountantSeesCostAndProfitButCannotSell() {
        security.as(Role.ACCOUNTANT, adminId);
        assertThrows(AccessDeniedException.class, () -> sales.saveAndPost(
                newSale(walkIn, SaleType.RETAIL, "0", line(pipe, "1", "2.500", "0")), PaymentType.CASH, false));
        assertThrows(AccessDeniedException.class, () -> sales.searchProducts("صنف"));
        List<Sale> rows = sales.search(new SaleFilter(null, null, null, null, cashierId, null, null, SaleStatus.POSTED));
        if (!rows.isEmpty()) {
            assertNotNull(rows.get(0).getCostTotal(), "accountant sees cost");
            assertNotNull(rows.get(0).getGrossProfit(), "accountant sees profit");
        }
        security.as(Role.STOREKEEPER, adminId);
        assertThrows(AccessDeniedException.class, () -> sales.search(SaleFilter.all()));
    }

    @Test
    void posLookupsForTheCashier() {
        assertEquals(tap.getProductId(), sales.findByScan(tap.getBarcode()).orElseThrow().getProductId(), "barcode");
        assertEquals(tap.getProductId(), sales.findByScan(tap.getProductCode()).orElseThrow().getProductId(), "code");
        assertTrue(sales.findByScan("NO-SUCH-" + suffix).isEmpty(), "unknown barcode: nothing, no error");
        List<Product> found = sales.searchProducts("صنف بيع TAP " + suffix);
        assertEquals(1, found.size());
        assertNull(found.get(0).getPurchasePrice(), "cashier: no cost");
        assertTrue(sales.searchProducts("صنف بيع OFF " + suffix).isEmpty(), "inactive products are not offered");
        assertTrue(sales.searchCustomers(customer.getPhone()).stream().anyMatch(c -> c.getCustomerId().equals(customer.getCustomerId())));
        // (the digits of a code may also match other customers' phones, so check the customer itself)
        assertTrue(sales.searchCustomers(inactiveCustomer.getCustomerCode()).stream()
                .noneMatch(c -> c.getCustomerId().equals(inactiveCustomer.getCustomerId())), "inactive customers are not offered");
        assertEquals("CASH", sales.walkInCustomer().getCustomerCode());
        assertTrue(sales.suggestNumber().matches("SAL-\\d{6}"));
    }
}
