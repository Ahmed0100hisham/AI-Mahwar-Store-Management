package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.QuotationDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Category;
import com.almahwar.model.Customer;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationConversion;
import com.almahwar.model.QuotationFilter;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.Role;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.SaleType;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Quotations against a real SQL Server: the workflow, the guarantee that a quotation moves nothing (stock, accounts,
 * cash, sales), conversion to a sale through the sales service, expiry, permissions, idempotency and concurrency
 * (numbering, double conversion). Enable with {@code -Ddb.it=true}. Rows created here are removed.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotationIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    private final ProductDao productDao = new ProductDao();
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final SaleDao saleDao = new SaleDao();
    private final QuotationDao quotationDao = new QuotationDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final AccountLedgerDao ledgerDao = new AccountLedgerDao();
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final StockLedger stockLedger = new StockLedger(movementDao);
    private final AccountLedger accountLedger = new AccountLedger(ledgerDao, customerDao, supplierDao);
    private final TestSecurity security = new TestSecurity();
    private final SaleService sales = new SaleServiceImpl(saleDao, customerDao, productDao, new UnitDao(), stockLedger,
            accountLedger, cashDao, new AuditLogDao(), security, quotationDao);
    private final QuotationService quotations = new QuotationServiceImpl(quotationDao, customerDao, productDao,
            new UnitDao(), saleDao, sales, new AuditLogDao(), security);

    private int adminId;
    private int cashierId;
    private int accountantId;
    private int storekeeperId;
    private Customer customer;
    private Customer inactiveCustomer;
    private Customer walkIn;
    private Product tap;          // piece: retail 5.000, wholesale 4.000 — stock reset per test
    private Product pipe;         // metre (fractions): retail 2.500, wholesale 2.000
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

        BigDecimal dec(String sql, Object... params) {
            return queryOne(sql, rs -> rs.getBigDecimal(1), params).orElse(BigDecimal.ZERO);
        }
    }

    private final Sql sql = new Sql();

    @BeforeAll
    void setUp() {
        adminId = newUser(Role.ADMIN);
        cashierId = newUser(Role.CASHIER);
        accountantId = newUser(Role.ACCOUNTANT);
        storekeeperId = newUser(Role.STOREKEEPER);
        customer = newCustomer("QC", true);
        inactiveCustomer = newCustomer("QOFF", false);
        walkIn = customerDao.findCashCustomer().orElseThrow();

        Category c = new Category();
        c.setNameAr("قسم عروض " + suffix);
        categoryId = new CategoryDao().insert(c);
        Unit piece = new Unit();
        piece.setNameAr("حبة عروض " + suffix);
        pieceId = new UnitDao().insert(piece);
        Unit metre = new Unit();
        metre.setNameAr("متر عروض " + suffix);
        metre.setAllowsDecimal(true);
        metreId = new UnitDao().insert(metre);

        tap = newProduct("TAP", pieceId, "5.000", "4.000", true);
        pipe = newProduct("PIPE", metreId, "2.500", "2.000", true);
        inactiveProduct = newProduct("OFF", pieceId, "1.000", "1.000", false);
    }

    private int newUser(String role) {
        User u = new User();
        u.setUsername("quo_" + role.toLowerCase().substring(0, 3) + "_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("مستخدم عروض " + role);
        u.setRoleId(new RoleDao().findByCode(role).orElseThrow().getRoleId());
        return new UserDao().insert(u);
    }

    private Customer newCustomer(String tag, boolean active) {
        Customer c = new Customer();
        c.setCustomerCode(tag + "-" + suffix);
        c.setName("عميل عروض " + tag + " " + suffix);
        c.setPhone("6" + Math.abs((tag + suffix).hashCode() % 10_000_000));
        c.setCreditLimit(new BigDecimal("1000"));
        c.setActive(active);
        c.setCustomerId(customerDao.insert(c));
        customers.add(c);
        return c;
    }

    private Product newProduct(String tag, int unitId, String retail, String wholesale, boolean active) {
        Product p = new Product();
        p.setProductCode("QU" + tag + "-" + suffix);
        p.setBarcode("628" + tag + suffix);
        p.setNameAr("صنف عرض " + tag + " " + suffix);
        p.setCategoryId(categoryId);
        p.setUnitId(unitId);
        p.setPurchasePrice(new BigDecimal("1.000"));
        p.setSalePrice(new BigDecimal(retail));
        p.setWholesalePrice(new BigDecimal(wholesale));
        p.setQuantity(BigDecimal.ZERO);
        p.setActive(active);
        productDao.insert(p);
        products.add(p);
        return productDao.findById(p.getProductId()).orElseThrow();
    }

    @BeforeEach
    void reset() {
        security.admin(adminId);
        setStock(tap, "10");
        setStock(pipe, "100");
        setPrice(tap, "5.000");
        security.as(Role.CASHIER, cashierId);
    }

    @AfterAll
    void cleanUp() {
        security.admin(adminId);
        Object[] users = {adminId, cashierId, accountantId, storekeeperId};
        for (Integer id : sql.ints("SELECT sale_id FROM dbo.Sales WHERE user_id IN (?, ?, ?, ?)", users)) {
            cashDao.deleteBySourceForTests("SALE", id);
        }
        // only this run's quotations (the walk-in customer's other quotations are left alone)
        sql.exec("UPDATE dbo.Sales SET quotation_id = NULL WHERE quotation_id IN "
                + "(SELECT quotation_id FROM dbo.Quotations WHERE user_id IN (?, ?, ?, ?))", users);
        sql.exec("DELETE FROM dbo.Quotations WHERE user_id IN (?, ?, ?, ?)", users);
        for (Customer c : customers) {
            quotationDao.deleteForTests(c.getCustomerId());
        }
        sql.exec("DELETE FROM dbo.Sales WHERE user_id IN (?, ?, ?, ?)", adminId, cashierId, accountantId, storekeeperId);
        for (Product p : products) {
            movementDao.deleteByProduct(p.getProductId());
            sql.exec("DELETE FROM dbo.Products WHERE product_id = ?", p.getProductId());
        }
        for (Customer c : customers) {
            ledgerDao.deleteForParty(PartyType.CUSTOMER, c.getCustomerId());
            sql.exec("DELETE FROM dbo.Customers WHERE customer_id = ?", c.getCustomerId());
        }
        sql.exec("DELETE FROM dbo.Categories WHERE category_id = ?", categoryId);
        sql.exec("DELETE FROM dbo.Units WHERE unit_id IN (?, ?)", pieceId, metreId);
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id IN (?, ?, ?, ?)", adminId, cashierId, accountantId, storekeeperId);
        sql.exec("DELETE FROM dbo.Users WHERE user_id IN (?, ?, ?, ?)", adminId, cashierId, accountantId, storekeeperId);
    }

    // ---------- helpers ----------

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
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

    private void setPrice(Product p, String retail) {
        sql.exec("UPDATE dbo.Products SET sale_price = ? WHERE product_id = ?", d(retail), p.getProductId());
    }

    private QuotationItem line(Product p, String qty, String price, String discount) {
        QuotationItem i = new QuotationItem();
        i.setProductId(p.getProductId());
        i.setProductName(p.getNameAr());
        i.setQuantity(d(qty));
        i.setUnitPrice(d(price));
        i.setDiscountAmount(d(discount));
        return i;
    }

    private Quotation draft(Customer c, String discount, QuotationItem... items) {
        Quotation q = new Quotation();
        q.setCustomerId(c.getCustomerId());
        q.setPriceType(SaleType.RETAIL);
        q.setValidUntil(LocalDate.now().plusDays(7));
        q.setDiscountAmount(d(discount));
        q.setItems(new ArrayList<>(List.of(items)));
        q.setRequestId(UUID.randomUUID());
        return q;
    }

    /** A quotation for 3 taps at 5.000, sent and accepted. */
    private Quotation accepted(String qty) {
        Quotation q = quotations.save(draft(customer, "0", line(tap, qty, "5.000", "0")));
        quotations.send(q.getQuotationId());
        return quotations.accept(q.getQuotationId(), "موافقة هاتفية");
    }

    private void expireInDatabase(int quotationId) {
        sql.exec("UPDATE dbo.Quotations SET valid_until = DATEADD(day, -1, CAST(SYSDATETIME() AS date)) WHERE quotation_id = ?",
                quotationId);
    }

    private long audits(String action, int quotationId) {
        return sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = ? AND table_name = 'Quotations' AND record_id = ?",
                action, String.valueOf(quotationId));
    }

    // ---------- creation, numbering, idempotency ----------

    @Test
    void createsADraftWithANumberAndTotals() {
        Quotation q = quotations.save(draft(customer, "1.000", line(tap, "3", "5.000", "0.500"),
                line(pipe, "2.5", "2.500", "0")));
        assertNotNull(q.getQuotationId());
        assertTrue(q.getQuotationNo().matches("QUO-\\d{6}"), q.getQuotationNo());
        assertEquals(QuotationStatus.DRAFT, q.getStatus());
        // 3 × 5 − 0.5 = 14.500 ; 2.5 × 2.5 = 6.250 ; subtotal 20.750 ; total 19.750
        assertEquals(0, d("20.750").compareTo(q.getSubtotal()));
        assertEquals(0, d("19.750").compareTo(q.getTotalAmount()));
        assertEquals(2, q.getItems().size());
        assertEquals(cashierId, q.getUserId());
        assertEquals(1, audits(AuditLogDao.QUOTATION_CREATED, q.getQuotationId()));
        assertEquals(1, audits(AuditLogDao.QUOTATION_DISCOUNT, q.getQuotationId()), "discounts are audited");
        assertTrue(quotations.search(QuotationFilter.forCustomer(customer.getCustomerId())).stream()
                .anyMatch(x -> x.getQuotationId().equals(q.getQuotationId())));
        assertTrue(quotations.search(new QuotationFilter(q.getQuotationNo(), null, null, null, null, null)).stream()
                .anyMatch(x -> x.getQuotationId().equals(q.getQuotationId())), "search by number");
    }

    @Test
    void sameRequestIsSavedOnce() {
        Quotation q = draft(customer, "0", line(tap, "1", "5.000", "0"));
        UUID request = q.getRequestId();
        Quotation first = quotations.save(q);
        Quotation again = draft(customer, "0", line(tap, "1", "5.000", "0"));
        again.setRequestId(request);
        Quotation second = quotations.save(again);
        assertEquals(first.getQuotationId(), second.getQuotationId());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Quotations WHERE request_id = ?", request.toString()));
    }

    @Test
    void concurrentNumberingGivesDistinctNumbers() throws Exception {   // (A)
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0"))).getQuotationNo();
            }));
        }
        start.countDown();
        Set<String> numbers = new HashSet<>();
        for (Future<String> f : results) {
            numbers.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(n, numbers.size(), "every concurrent quotation got its own number: " + numbers);
        assertEquals(0, sql.count("SELECT COUNT(*) FROM (SELECT quotation_no FROM dbo.Quotations GROUP BY quotation_no HAVING COUNT(*) > 1) d"));
    }

    @Test
    void concurrentRetriesOfOneRequestSaveOnce() throws Exception {
        UUID request = UUID.randomUUID();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                Quotation q = draft(customer, "0", line(tap, "1", "5.000", "0"));
                q.setRequestId(request);
                return quotations.save(q).getQuotationId();
            }));
        }
        start.countDown();
        Set<Integer> ids = new HashSet<>();
        for (Future<Integer> f : results) {
            ids.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, ids.size());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Quotations WHERE request_id = ?", request.toString()));
    }

    // ---------- the critical guarantee: a quotation moves nothing ----------

    @Test
    void draftSendAcceptHaveNoFinancialOrStockEffect() {
        security.admin(adminId);
        BigDecimal tapBefore = qty(tap);
        BigDecimal pipeBefore = qty(pipe);
        BigDecimal balanceBefore = balance(customer);
        BigDecimal cashBefore = cashDao.balance();
        long salesBefore = sql.count("SELECT COUNT(*) FROM dbo.Sales");
        BigDecimal profitBefore = sql.dec("SELECT SUM(gross_profit) FROM dbo.Sales WHERE status = 'POSTED'");
        long movesBefore = sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id IN (?, ?)",
                tap.getProductId(), pipe.getProductId());
        long ledgerBefore = sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE party_type = 'CUSTOMER' AND customer_id = ?",
                customer.getCustomerId());
        long cashRowsBefore = sql.count("SELECT COUNT(*) FROM dbo.Cash_Transactions");

        security.as(Role.CASHIER, cashierId);
        Quotation q = quotations.save(draft(customer, "0.500", line(tap, "4", "5.000", "0"), line(pipe, "3.5", "2.500", "0")));
        quotations.send(q.getQuotationId());
        Quotation a = quotations.accept(q.getQuotationId(), null);
        assertEquals(QuotationStatus.ACCEPTED, a.getStatus());

        assertEquals(0, tapBefore.compareTo(qty(tap)), "product quantity unchanged");
        assertEquals(0, pipeBefore.compareTo(qty(pipe)));
        assertEquals(0, balanceBefore.compareTo(balance(customer)), "customer balance unchanged");
        assertEquals(0, cashBefore.compareTo(cashDao.balance()), "cashbox unchanged");
        assertEquals(salesBefore, sql.count("SELECT COUNT(*) FROM dbo.Sales"), "no sale");
        assertEquals(0, profitBefore.compareTo(sql.dec("SELECT SUM(gross_profit) FROM dbo.Sales WHERE status = 'POSTED'")),
                "gross profit unchanged");
        assertEquals(movesBefore, sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id IN (?, ?)",
                tap.getProductId(), pipe.getProductId()), "no stock movement");
        assertEquals(ledgerBefore, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE party_type = 'CUSTOMER' AND customer_id = ?",
                customer.getCustomerId()), "no ledger entry");
        assertEquals(cashRowsBefore, sql.count("SELECT COUNT(*) FROM dbo.Cash_Transactions"), "no cash movement");
    }

    // ---------- validation ----------

    @Test
    void validationAgainstTheDatabase() {
        ValidationException inactive = assertThrows(ValidationException.class,
                () -> quotations.save(draft(inactiveCustomer, "0", line(tap, "1", "5.000", "0"))));
        assertTrue(inactive.getErrors().containsKey(QuotationService.CUSTOMER));

        ValidationException product = assertThrows(ValidationException.class,
                () -> quotations.save(draft(customer, "0", line(inactiveProduct, "1", "1.000", "0"))));
        assertTrue(product.getErrors().get(QuotationService.ITEMS).contains("معطّل"));

        ValidationException fraction = assertThrows(ValidationException.class,
                () -> quotations.save(draft(customer, "0", line(tap, "1.5", "5.000", "0"))));
        assertTrue(fraction.getErrors().get(QuotationService.ITEMS).contains("لا تقبل الكسور"));
        Quotation metres = quotations.save(draft(customer, "0", line(pipe, "1.75", "2.500", "0")));
        assertEquals(0, d("1.75").compareTo(metres.getItems().get(0).getQuantity()), "fractions where the unit allows");

        Quotation past = draft(customer, "0", line(tap, "1", "5.000", "0"));
        past.setValidUntil(LocalDate.now().minusDays(1));
        assertTrue(assertThrows(ValidationException.class, () -> quotations.save(past))
                .getErrors().containsKey(QuotationService.VALID_UNTIL));
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Quotations WHERE request_id = ?", past.getRequestId().toString()),
                "nothing saved");
    }

    @Test
    void wholesalePricesAndWalkInProspects() {
        Quotation w = draft(walkIn, "0", line(tap, "2", "4.000", "0"));
        w.setPriceType(SaleType.WHOLESALE);
        w.setProspectName("شركة الريان " + suffix);
        w.setProspectPhone("55501234");
        Quotation saved = quotations.save(w);
        assertEquals(SaleType.WHOLESALE, saved.getPriceType());
        assertTrue(saved.isWalkIn());
        assertEquals("شركة الريان " + suffix, saved.getDisplayName());
        assertTrue(quotations.search(new QuotationFilter("الريان " + suffix, null, null, null, null, null)).stream()
                .anyMatch(x -> x.getQuotationId().equals(saved.getQuotationId())), "search by prospect name");

        Quotation registered = draft(customer, "0", line(tap, "1", "5.000", "0"));
        registered.setProspectName("يُتجاهل");
        assertNull(quotations.save(registered).getProspectName(), "a registered customer has no prospect name");
    }

    @Test
    void priceOverrideAndDiscountNeedPermissions() {
        // cashier: no QUOTATIONS_PRICE_OVERRIDE
        ValidationException price = assertThrows(ValidationException.class,
                () -> quotations.save(draft(customer, "0", line(tap, "1", "4.500", "0"))));
        assertTrue(price.getErrors().get(QuotationService.ITEMS).contains("صلاحية"));

        security.with(cashierId, EnumSet.of(Permission.QUOTATIONS_VIEW, Permission.QUOTATIONS_CREATE));
        assertThrows(ValidationException.class,
                () -> quotations.save(draft(customer, "0.100", line(tap, "1", "5.000", "0"))), "quotation discount");
        assertThrows(ValidationException.class,
                () -> quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0.100"))), "line discount");

        security.admin(adminId);
        Quotation q = quotations.save(draft(customer, "0", line(tap, "2", "4.500", "0")));
        assertEquals(0, d("4.500").compareTo(q.getItems().get(0).getUnitPrice()));
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = ? AND record_id = ?",
                AuditLogDao.QUOTATION_PRICE_OVERRIDE, String.valueOf(q.getQuotationId())), "price override audited");
    }

    @Test
    void permissionsByRole() {
        security.as(Role.STOREKEEPER, storekeeperId);
        assertThrows(RuntimeException.class, () -> quotations.search(QuotationFilter.all()));

        security.as(Role.CASHIER, cashierId);
        Quotation q = quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0")));
        quotations.send(q.getQuotationId());

        security.as(Role.ACCOUNTANT, accountantId);
        assertFalse(quotations.search(QuotationFilter.all()).isEmpty(), "the accountant reads quotations");
        assertThrows(RuntimeException.class, () -> quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0"))));
        assertEquals(QuotationStatus.ACCEPTED, quotations.accept(q.getQuotationId(), "اعتمدها المحاسب").getStatus());
        assertThrows(RuntimeException.class, () -> quotations.convert(q.getQuotationId(), UUID.randomUUID()),
                "the accountant does not sell");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE quotation_id = ?", q.getQuotationId()));
    }

    // ---------- workflow ----------

    @Test
    void workflowEditSendReopenRejectDelete() {
        Quotation q = quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0")));
        // edit the draft
        q.getItems().get(0).setQuantity(d("2"));
        Quotation edited = quotations.save(q);
        assertEquals(0, d("10.000").compareTo(edited.getTotalAmount()));
        assertEquals(1, audits(AuditLogDao.QUOTATION_UPDATED, q.getQuotationId()));

        Quotation sent = quotations.send(q.getQuotationId());
        assertEquals(QuotationStatus.SENT, sent.getStatus());
        assertNotNull(sent.getSentAt());
        sent.getItems().get(0).setQuantity(d("3"));
        assertThrows(ValidationException.class, () -> quotations.save(sent), "a sent quotation is not edited");
        assertThrows(ValidationException.class, () -> quotations.deleteDraft(q.getQuotationId()), "nor deleted");

        Quotation reopened = quotations.reopen(q.getQuotationId());
        assertEquals(QuotationStatus.DRAFT, reopened.getStatus());
        assertNull(reopened.getSentAt());
        reopened.getItems().get(0).setQuantity(d("3"));
        assertEquals(0, d("15.000").compareTo(quotations.save(reopened).getTotalAmount()), "editable again");

        quotations.send(q.getQuotationId());
        Quotation rejected = quotations.reject(q.getQuotationId(), "السعر مرتفع");
        assertEquals(QuotationStatus.REJECTED, rejected.getStatus());
        assertEquals("السعر مرتفع", rejected.getStatusNote());
        assertEquals(cashierId, rejected.getDecidedBy());
        assertThrows(ValidationException.class, () -> quotations.accept(q.getQuotationId(), null), "rejected is final");
        assertThrows(ValidationException.class, () -> quotations.reopen(q.getQuotationId()));
        for (String action : List.of(AuditLogDao.QUOTATION_SENT, AuditLogDao.QUOTATION_REOPENED, AuditLogDao.QUOTATION_REJECTED)) {
            assertTrue(audits(action, q.getQuotationId()) >= 1, action);
        }

        Quotation other = quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0")));
        assertThrows(ValidationException.class, () -> quotations.reject(other.getQuotationId(), null),
                "a draft is not rejected (it is deleted)");
        quotations.deleteDraft(other.getQuotationId());
        assertTrue(quotationDao.findById(other.getQuotationId()).isEmpty());
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Quotation_Items WHERE quotation_id = ?", other.getQuotationId()));
    }

    @Test
    void expiredQuotationsAreBlocked() {
        Quotation sent = quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0")));
        quotations.send(sent.getQuotationId());
        expireInDatabase(sent.getQuotationId());
        Quotation read = quotations.findById(sent.getQuotationId()).orElseThrow();
        assertEquals(QuotationStatus.EXPIRED, read.getStatus(), "expired on read, without a scheduler");
        ValidationException e = assertThrows(ValidationException.class, () -> quotations.accept(sent.getQuotationId(), null));
        assertTrue(e.getMessage().contains("انتهت صلاحية"));

        Quotation acc = accepted("1");
        expireInDatabase(acc.getQuotationId());
        ValidationException c = assertThrows(ValidationException.class,
                () -> quotations.convert(acc.getQuotationId(), UUID.randomUUID()));
        assertTrue(c.getMessage().contains("انتهت صلاحية"));
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE quotation_id = ?", acc.getQuotationId()));
    }

    // ---------- conversion ----------

    @Test
    void conversionKeepsTheAgreedPriceAndCompletesOnPosting() {
        // stock 10, price 5, quantity 3; the price then changes to 6
        Quotation q = accepted("3");
        setPrice(tap, "6.000");
        BigDecimal cashBefore = cashDao.balance();

        QuotationConversion conv = quotations.convert(q.getQuotationId(), UUID.randomUUID());
        assertFalse(conv.alreadyMade());
        assertTrue(conv.warnings().stream().anyMatch(w -> w.contains("6.000") && w.contains("5.000")),
                "the price change is reported: " + conv.warnings());
        Sale draft = saleDao.findById(conv.saleId()).orElseThrow();
        assertEquals(SaleStatus.DRAFT, draft.getStatus());
        assertEquals(q.getQuotationId(), draft.getQuotationId());
        assertEquals(q.getQuotationNo(), draft.getQuotationNo());
        assertEquals(0, d("5.000").compareTo(draft.getItems().get(0).getUnitPrice()), "no silent price change");
        assertEquals(0, d("10").compareTo(qty(tap)), "a sale draft moves nothing");
        Quotation stillAccepted = quotations.findById(q.getQuotationId()).orElseThrow();
        assertEquals(QuotationStatus.ACCEPTED, stillAccepted.getStatus(), "converted only when the sale is posted");
        assertEquals(conv.saleId(), stillAccepted.getConvertedSaleId());
        assertEquals(1, audits(AuditLogDao.QUOTATION_CONVERSION_STARTED, q.getQuotationId()));

        // the cashier (no SALES_PRICE_OVERRIDE) completes it at the agreed price
        Sale posted = sales.post(conv.saleId(), false);
        assertEquals(SaleStatus.POSTED, posted.getStatus());
        assertEquals(0, d("7").compareTo(qty(tap)), "stock 10 − 3 = 7");
        assertEquals(0, d("15.000").compareTo(posted.getTotalAmount()));
        assertEquals(0, cashBefore.add(d("15.000")).compareTo(cashDao.balance()), "cash sale through the normal posting");
        Quotation converted = quotations.findById(q.getQuotationId()).orElseThrow();
        assertEquals(QuotationStatus.CONVERTED, converted.getStatus());
        assertEquals(posted.getSaleNo(), converted.getConvertedSaleNo(), "traceability both ways");
        assertEquals(1, audits(AuditLogDao.QUOTATION_CONVERTED, q.getQuotationId()));
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = ? AND description LIKE ?",
                AuditLogDao.PRICE_OVERRIDE, "%" + posted.getSaleNo() + "%" + q.getQuotationNo() + "%"),
                "the agreed price is still audited, with the quotation number");

        // converting again never makes a second sale
        QuotationConversion again = quotations.convert(q.getQuotationId(), UUID.randomUUID());
        assertTrue(again.alreadyMade());
        assertEquals(conv.saleId(), again.saleId());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE quotation_id = ?", q.getQuotationId()));
    }

    @Test
    void conversionNeedsAnAcceptedQuotation() {
        Quotation q = quotations.save(draft(customer, "0", line(tap, "1", "5.000", "0")));
        assertThrows(ValidationException.class, () -> quotations.convert(q.getQuotationId(), UUID.randomUUID()));
        quotations.send(q.getQuotationId());
        assertThrows(ValidationException.class, () -> quotations.convert(q.getQuotationId(), UUID.randomUUID()));
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE quotation_id = ?", q.getQuotationId()));
    }

    @Test
    void acceptedThenExpiredCannotBePosted() {   // (C)
        Quotation q = accepted("2");
        QuotationConversion conv = quotations.convert(q.getQuotationId(), UUID.randomUUID());
        expireInDatabase(q.getQuotationId());
        BigDecimal cashBefore = cashDao.balance();

        ValidationException e = assertThrows(ValidationException.class, () -> sales.post(conv.saleId(), false));
        assertTrue(e.getMessage().contains("انتهت صلاحيته"), e.getMessage());
        assertEquals(SaleStatus.DRAFT, saleDao.findById(conv.saleId()).orElseThrow().getStatus(), "rolled back");
        assertEquals(0, d("10").compareTo(qty(tap)), "stock untouched");
        assertEquals(0, cashBefore.compareTo(cashDao.balance()), "cash untouched");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE reference_type = 'SALE' AND reference_id = ?",
                conv.saleId()));
    }

    @Test
    void stockShortageDraftWarnsButPostingFails() {   // (D)
        Quotation q = accepted("5");
        setStock(tap, "2");   // stock changed between the quotation and the sale

        QuotationConversion conv = quotations.convert(q.getQuotationId(), UUID.randomUUID());
        assertTrue(conv.warnings().stream().anyMatch(w -> w.contains("المتوفر")), conv.warnings().toString());
        assertEquals(SaleStatus.DRAFT, saleDao.findById(conv.saleId()).orElseThrow().getStatus(), "the draft is created");

        assertThrows(InsufficientStockException.class, () -> sales.post(conv.saleId(), false));
        assertEquals(0, d("2").compareTo(qty(tap)), "stock unchanged");
        assertEquals(QuotationStatus.ACCEPTED, quotations.findById(q.getQuotationId()).orElseThrow().getStatus());

        // the cashier lowers the quantity in the POS (same draft), then completes the sale
        Sale s = saleDao.findById(conv.saleId()).orElseThrow();
        s.getItems().get(0).setQuantity(d("2"));
        s.setNotes(null);
        Sale posted = sales.saveAndPost(s, PaymentType.CASH, false);
        assertEquals(SaleStatus.POSTED, posted.getStatus());
        assertEquals(q.getQuotationId(), posted.getQuotationId(), "the link survives a re-save from the POS");
        assertEquals("من عرض السعر " + q.getQuotationNo(), posted.getNotes(), "and so does the note");
        assertEquals(0, qty(tap).signum());
        assertEquals(QuotationStatus.CONVERTED, quotations.findById(q.getQuotationId()).orElseThrow().getStatus());
    }

    @Test
    void cancelledSaleDraftAllowsANewConversion() {
        Quotation q = accepted("1");
        QuotationConversion first = quotations.convert(q.getQuotationId(), UUID.randomUUID());
        sales.cancelDraft(first.saleId());
        QuotationConversion second = quotations.convert(q.getQuotationId(), UUID.randomUUID());
        assertFalse(second.alreadyMade());
        assertFalse(first.saleId() == second.saleId());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE quotation_id = ? AND status <> 'CANCELLED'",
                q.getQuotationId()));
    }

    @Test
    void concurrentConversionMakesOneSale() throws Exception {   // (B)
        Quotation q = accepted("1");
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Integer> task = () -> {
                start.await();
                return quotations.convert(q.getQuotationId(), UUID.randomUUID()).saleId();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        Set<Integer> saleIds = new HashSet<>();
        for (Future<Integer> f : results) {
            saleIds.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, saleIds.size(), "every click got the same sale: " + saleIds);
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE quotation_id = ? AND status <> 'CANCELLED'",
                q.getQuotationId()));

        // and two concurrent postings of that sale convert the quotation once
        int saleId = saleIds.iterator().next();
        ExecutorService posters = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> posts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            posts.add(posters.submit(() -> {
                go.await();
                try {
                    sales.post(saleId, false);
                    return true;
                } catch (ValidationException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : posts) {
            ok += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        posters.shutdown();
        assertEquals(1, ok, "posted once");
        assertEquals(0, d("9").compareTo(qty(tap)));
        assertEquals(1, audits(AuditLogDao.QUOTATION_CONVERTED, q.getQuotationId()));
    }

    @Test
    void theDatabaseRefusesASecondLiveSale() {
        Quotation q = accepted("1");
        QuotationConversion conv = quotations.convert(q.getQuotationId(), UUID.randomUUID());
        security.admin(adminId);
        Sale second = new Sale();
        second.setCustomerId(customer.getCustomerId());
        second.setSaleType(SaleType.RETAIL);
        second.setDiscountAmount(BigDecimal.ZERO);
        SaleItem i = new SaleItem();
        i.setProductId(tap.getProductId());
        i.setQuantity(BigDecimal.ONE);
        i.setUnitPrice(d("5.000"));
        i.setDiscountAmount(BigDecimal.ZERO);
        second.setItems(new ArrayList<>(List.of(i)));
        second.setRequestId(UUID.randomUUID());
        second.setQuotationId(q.getQuotationId());
        assertThrows(RuntimeException.class, () -> sales.saveDraft(second, PaymentType.CASH), "UX_Sales_quotation");
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE quotation_id = ? AND status <> 'CANCELLED'",
                q.getQuotationId()));
        assertEquals(conv.saleId(), quotationDao.liveSaleId(q.getQuotationId()).orElseThrow());
    }
}
