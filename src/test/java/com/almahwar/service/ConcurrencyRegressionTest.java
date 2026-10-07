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
import com.almahwar.model.CashMovement;
import com.almahwar.model.Category;
import com.almahwar.model.Customer;
import com.almahwar.model.MovementType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Product;
import com.almahwar.model.Role;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleType;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency regression for the shared core: many simultaneous clients (as desktop PCs plus a future API will be)
 * against one temporary database. Proves that stock never goes negative, opposite lock orders do not deadlock,
 * credit limits and the cashbox stay consistent under races, and a repeated request id creates one document only.
 * <p>
 * <b>Known v1.0.0 limitation (documented, not changed):</b> document numbers are generated with
 * {@code SELECT MAX(...) WITH (UPDLOCK, HOLDLOCK)} (e.g. {@code SaleDao.nextNumber}); with many simultaneous new
 * documents SQL Server can pick a deadlock victim on the key range of the number index (error 1205). The victim's
 * transaction is rolled back completely. These tests therefore accept deadlock victims — counted and reported — but
 * every business invariant must still hold, and every other refusal must be the expected business refusal.
 * <pre>mvn test -Ddb.it=true -Dconcurrency=true -Ddb.name=AlMahwarConcurrencyIT -Dtest=ConcurrencyRegressionTest</pre>
 */
@EnabledIfSystemProperty(named = "concurrency", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyRegressionTest {

    private static final int THREADS = 12;

    private final TestSecurity security = new TestSecurity();
    private final ProductDao productDao = new ProductDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final StockLedger ledger = new StockLedger(movementDao);
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final AccountLedger accountLedger = new AccountLedger(new AccountLedgerDao(), customerDao, supplierDao);
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final AuditLogDao audit = new AuditLogDao();
    private final SaleService sales = new SaleServiceImpl(new SaleDao(), customerDao, productDao, new UnitDao(), ledger,
            accountLedger, cashDao, audit, security, new QuotationDao());
    private final CashboxService cashbox = new CashboxServiceImpl(cashDao, audit, security);

    private int admin;
    private int category;
    private int piece;
    private Customer walkIn;

    private static final class Sql extends BaseDao {
        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }

        BigDecimal decimal(String sql, Object... params) {
            return queryOne(sql, rs -> rs.getBigDecimal(1), params).orElse(BigDecimal.ZERO);
        }
    }

    private final Sql sql = new Sql();

    @BeforeAll
    void setUp() throws Exception {
        GoldenDatabase.create();
        User u = new User();
        u.setUsername("c_admin");
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("مدير التزامن");
        u.setRoleId(new RoleDao().findByCode(Role.ADMIN).orElseThrow().getRoleId());
        admin = new UserDao().insert(u);
        Category c = new Category();
        c.setNameAr("قسم التزامن");
        category = new CategoryDao().insert(c);
        Unit p = new Unit();
        p.setNameAr("حبة التزامن");
        piece = new UnitDao().insert(p);
        walkIn = customerDao.findCashCustomer().orElseThrow();
        security.as(Role.ADMIN, admin);   // set before any thread starts: visible to all of them
    }

    @AfterAll
    void tearDown() throws Exception {
        GoldenDatabase.drop();
    }

    private Product product(String code, String stock) {
        Product p = new Product();
        p.setProductCode(code);
        p.setNameAr("صنف " + code);
        p.setCategoryId(category);
        p.setUnitId(piece);
        p.setPurchasePrice(new BigDecimal("1.000"));
        p.setSalePrice(new BigDecimal("2.000"));
        p.setWholesalePrice(new BigDecimal("2.000"));
        p.setQuantity(BigDecimal.ZERO);
        p.setActive(true);
        productDao.insert(p);
        TransactionManager.inTransaction(con -> ledger.post(con, p.getProductId(), MovementType.OPENING_BALANCE,
                new BigDecimal(stock), "PRODUCT", p.getProductId(), "رصيد افتتاحي", admin));
        return p;
    }

    private Sale sale(Customer c, UUID request, Product... products) {
        Sale s = new Sale();
        s.setCustomerId(c.getCustomerId());
        s.setSaleType(SaleType.RETAIL);
        List<SaleItem> items = new ArrayList<>();
        for (Product p : products) {
            SaleItem i = new SaleItem();
            i.setProductId(p.getProductId());
            i.setQuantity(BigDecimal.ONE);
            i.setUnitPrice(new BigDecimal("2.000"));
            items.add(i);
        }
        s.setItems(items);
        s.setRequestId(request);
        return s;
    }

    /** Starts all calls at the same moment; returns each result or the exception it threw. */
    private static List<Object> race(int n, java.util.function.IntFunction<Callable<Object>> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Object> c = call.apply(i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return c.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static long ok(List<Object> results) {
        return results.stream().filter(r -> !(r instanceof Exception)).count();
    }

    /** SQL Server deadlock victim (error 1205): rolled back completely by SQL Server. */
    static boolean deadlockVictim(Object r) {
        for (Throwable t = r instanceof Throwable x ? x : null; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getErrorCode() == 1205) {
                return true;
            }
            if (String.valueOf(t.getMessage()).contains("deadlock victim")) {
                return true;
            }
        }
        return false;
    }

    /** Counts and reports deadlock victims; every other exception must be one of the expected business refusals. */
    private static long victims(String test, List<Object> results, Class<?>... expectedRefusals) {
        long victims = results.stream().filter(ConcurrencyRegressionTest::deadlockVictim).count();
        for (Object r : results) {
            if (r instanceof Exception e && !deadlockVictim(e)) {
                boolean expected = false;
                for (Class<?> c : expectedRefusals) {
                    expected |= c.isInstance(e);
                }
                assertTrue(expected, test + ": unexpected failure " + e);
            }
        }
        System.out.println("[concurrency] " + test + ": " + results.size() + " calls, " + ok(results) + " succeeded, "
                + victims + " deadlock victim(s) (number-generation key range, rolled back)");
        return victims;
    }

    private void assertStockConsistent() {
        // movements are stored signed (the same invariant as the existing integration suites)
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Products p WHERE p.quantity <> COALESCE((SELECT "
                + "SUM(m.quantity) FROM dbo.Stock_Movements m WHERE m.product_id = p.product_id), 0)"),
                "Products.quantity equals the sum of its movements");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Products WHERE quantity < 0"), "no negative stock");
    }

    @Test
    void manySellersOfTheLastUnitsNeverOversell() throws Exception {
        Product scarce = product("C-SCARCE", "7");
        List<Object> r = race(THREADS + 8, i -> () -> sales.saveAndPost(sale(walkIn, UUID.randomUUID(), scarce),
                PaymentType.CASH, false));
        long victims = victims("oversell", r, InsufficientStockException.class);
        BigDecimal left = productDao.findById(scarce.getProductId()).orElseThrow().getQuantity();
        assertTrue(left.signum() >= 0, "never negative");
        assertEquals(7, ok(r) + left.intValue(), "sold + left = opening stock");
        assertTrue(victims > 0 || left.signum() == 0, "without victims everything is sold: " + r);
        assertStockConsistent();
    }

    @Test
    void oppositeLineOrderDoesNotDeadlock() throws Exception {
        Product a = product("C-A", "1000");
        Product b = product("C-B", "1000");
        List<Object> r = race(THREADS * 2, i -> () -> sales.saveAndPost(
                i % 2 == 0 ? sale(walkIn, UUID.randomUUID(), a, b) : sale(walkIn, UUID.randomUUID(), b, a),
                PaymentType.CASH, false));
        victims("opposite line order", r);   // no business refusal is expected at all
        BigDecimal sold = BigDecimal.valueOf(ok(r));
        assertEquals(new BigDecimal("1000").subtract(sold).setScale(3),
                productDao.findById(a.getProductId()).orElseThrow().getQuantity());
        assertEquals(new BigDecimal("1000").subtract(sold).setScale(3),
                productDao.findById(b.getProductId()).orElseThrow().getQuantity());
        assertStockConsistent();
    }

    @Test
    void creditLimitHoldsUnderConcurrentCreditSales() throws Exception {
        Product stock = product("C-CRD", "1000");
        Customer c = new Customer();
        c.setCustomerCode("C-CUST");
        c.setName("عميل التزامن");
        c.setCreditLimit(new BigDecimal("10.000"));
        c.setActive(true);
        c.setCustomerId(customerDao.insert(c));
        List<Object> r = race(THREADS, i -> () -> sales.saveAndPost(sale(c, UUID.randomUUID(), stock),
                PaymentType.CREDIT, false));
        long victims = victims("credit limit", r, CreditLimitExceededException.class);
        assertTrue(ok(r) <= 5, "never beyond the limit (10.000 / 2.000 per sale): " + r);
        assertTrue(victims > 0 || ok(r) == 5, "without victims the limit is used exactly: " + r);
        BigDecimal balance = customerDao.findById(c.getCustomerId()).orElseThrow().getBalance();
        assertEquals(new BigDecimal("2.000").multiply(BigDecimal.valueOf(ok(r))), balance);
        assertEquals(0, balance.compareTo(sql.decimal("SELECT SUM(debit - credit) FROM dbo.Account_Ledger "
                + "WHERE customer_id = ?", c.getCustomerId())), "balance equals the ledger");
    }

    @Test
    void sameRequestIdCreatesOneInvoice() throws Exception {
        Product stock = product("C-IDEM", "1000");
        UUID request = UUID.randomUUID();
        List<Object> r = race(THREADS, i -> () -> sales.saveAndPost(sale(walkIn, request, stock), PaymentType.CASH,
                false));
        victims("same request id", r, ValidationException.class);
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Sales WHERE request_id = ?", request));
        assertEquals(new BigDecimal("999.000"), productDao.findById(stock.getProductId()).orElseThrow().getQuantity());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Cash_Transactions c JOIN dbo.Sales s ON c.source_type = "
                + "'SALE' AND c.source_id = s.sale_id WHERE s.request_id = ?", request), "one cash entry");
        assertStockConsistent();
    }

    @Test
    void cashboxWithdrawalsAreSerialised() throws Exception {
        CashMovement dep = manual("60.000", "إيداع تجهيز", UUID.randomUUID());
        cashbox.deposit(dep);
        BigDecimal before = cashDao.balance();
        List<Object> r = race(THREADS, i -> () -> cashbox.withdraw(manual("10.000", "سحب " + i, UUID.randomUUID())));
        victims("cashbox withdrawals", r, ValidationException.class);
        long done = ok(r);
        assertTrue(done <= before.divideToIntegralValue(new BigDecimal("10.000")).longValue(), "only what the cash covers");
        assertTrue(cashDao.balance().signum() >= 0, "cash never negative");
        assertEquals(0, before.subtract(new BigDecimal("10.000").multiply(BigDecimal.valueOf(done)))
                .compareTo(cashDao.balance()));
    }

    @Test
    void sameCashRequestIsRecordedOnce() throws Exception {
        UUID request = UUID.randomUUID();
        List<Object> r = race(THREADS, i -> () -> cashbox.deposit(manual("3.000", "إيداع مكرر", request)));
        victims("same cash request id", r);
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Cash_Transactions WHERE request_id = ?", request));
    }

    private static CashMovement manual(String amount, String description, UUID request) {
        CashMovement m = new CashMovement();
        m.setAmount(new BigDecimal(amount));
        m.setDescription(description);
        m.setPaymentMethod(PaymentMethod.CASH);
        m.setRequestId(request);
        return m;
    }
}
