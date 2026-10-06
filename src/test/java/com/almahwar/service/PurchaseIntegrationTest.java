package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CashTransactionDao.CashTransaction;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DashboardDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.Category;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Product;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseFilter;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.PurchaseStatus;
import com.almahwar.model.Role;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Purchases against a real SQL Server: the posting transaction and its effects on stock, product cost,
 * supplier ledger and cash. Enable with {@code -Ddb.it=true}. Rows created here are removed.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    private final PurchaseDao purchaseDao = new PurchaseDao();
    private final ProductDao productDao = new ProductDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final AccountLedgerDao ledgerDao = new AccountLedgerDao();
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final StockLedger stockLedger = new StockLedger(movementDao);
    private final AccountLedger accountLedger = new AccountLedger(ledgerDao, new CustomerDao(), supplierDao);
    private final TestSecurity security = new TestSecurity();
    private final PurchaseService purchases = service(CostingPolicy.LAST_PURCHASE_COST);

    private int userId;
    private Supplier supplier;
    private Supplier inactiveSupplier;
    private final List<Product> products = new ArrayList<>();
    private final List<Integer> purchaseIds = new ArrayList<>();
    private Integer categoryId;
    private Integer unitId;

    private final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }
    }

    private final Sql sql = new Sql();

    private PurchaseService service(CostingPolicy costing) {
        return new PurchaseServiceImpl(purchaseDao, supplierDao, productDao, new UnitDao(), stockLedger, accountLedger,
                cashDao, new AuditLogDao(), costing, security);
    }

    @BeforeAll
    void setUp() {
        User u = new User();
        u.setUsername("pur_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("أمين مخزن مشتريات");
        u.setRoleId(new RoleDao().findByCode(Role.STOREKEEPER).orElseThrow().getRoleId());
        userId = new UserDao().insert(u);

        supplier = newSupplier("SUP", true);
        inactiveSupplier = newSupplier("OFF", false);

        Category c = new Category();
        c.setNameAr("قسم مشتريات " + suffix);
        categoryId = new CategoryDao().insert(c);
        Unit unit = new Unit();
        unit.setNameAr("حبة مشتريات " + suffix);
        unitId = new UnitDao().insert(unit);
        for (String[] p : new String[][]{{"A", "2.000"}, {"B", "5.500"}, {"C", "0.750"}}) {
            Product product = new Product();
            product.setProductCode("PU" + p[0] + "-" + suffix);
            product.setNameAr("صنف مشتريات " + p[0] + " " + suffix);
            product.setCategoryId(categoryId);
            product.setUnitId(unitId);
            product.setPurchasePrice(new BigDecimal(p[1]));
            product.setSalePrice(new BigDecimal("9"));
            product.setQuantity(BigDecimal.ZERO);
            productDao.insert(product);
            // opening stock of 10 through the ledger, so quantity = Σ movements
            TransactionManager.inTransaction(con -> stockLedger.post(con, product.getProductId(),
                    MovementType.OPENING_BALANCE, BigDecimal.TEN, "PRODUCT", product.getProductId(), "رصيد افتتاحي", userId));
            products.add(productDao.findById(product.getProductId()).orElseThrow());
        }
    }

    private Supplier newSupplier(String tag, boolean active) {
        Supplier s = new Supplier();
        s.setSupplierCode(tag + "-" + suffix);
        s.setName("مورد " + tag + " " + suffix);
        s.setActive(active);
        s.setSupplierId(supplierDao.insert(s));
        return s;
    }

    @BeforeEach
    void loginAsStorekeeper() {
        security.as(Role.STOREKEEPER, userId);
    }

    @AfterAll
    void cleanUp() {
        security.admin(userId);
        for (Integer id : purchaseIds) {
            cashDao.deleteBySourceForTests("PURCHASE", id);
        }
        sql.exec("DELETE FROM dbo.Purchases WHERE user_id = ?", userId);
        for (Product p : products) {
            movementDao.deleteByProduct(p.getProductId());
            sql.exec("DELETE FROM dbo.Products WHERE product_id = ?", p.getProductId());
        }
        for (Supplier s : List.of(supplier, inactiveSupplier)) {
            ledgerDao.deleteForParty(PartyType.SUPPLIER, s.getSupplierId());
            sql.exec("DELETE FROM dbo.Suppliers WHERE supplier_id = ?", s.getSupplierId());
        }
        sql.exec("DELETE FROM dbo.Categories WHERE category_id = ?", categoryId);
        sql.exec("DELETE FROM dbo.Units WHERE unit_id = ?", unitId);
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id = ?", userId);
        sql.exec("DELETE FROM dbo.Users WHERE user_id = ?", userId);
    }

    // ---------- helpers ----------

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private PurchaseItem line(int product, String qty, String cost, String discount) {
        PurchaseItem i = new PurchaseItem();
        i.setProductId(products.get(product).getProductId());
        i.setQuantity(d(qty));
        i.setUnitCost(d(cost));
        i.setDiscountAmount(d(discount));
        return i;
    }

    private Purchase newPurchase(String discount, PurchaseItem... items) {
        Purchase p = new Purchase();
        p.setSupplierId(supplier.getSupplierId());
        p.setDiscountAmount(d(discount));
        p.setItems(new ArrayList<>(List.of(items)));
        p.setRequestId(UUID.randomUUID());
        return p;
    }

    private Purchase track(Purchase p) {
        purchaseIds.add(p.getPurchaseId());
        return p;
    }

    private BigDecimal qty(int product) {
        return productDao.findById(products.get(product).getProductId()).orElseThrow().getQuantity();
    }

    private BigDecimal supplierBalance() {
        return supplierDao.findById(supplier.getSupplierId()).orElseThrow().getBalance();
    }

    private Map<Integer, BigDecimal> stock() {
        Map<Integer, BigDecimal> m = new HashMap<>();
        for (int i = 0; i < products.size(); i++) {
            m.put(i, qty(i));
        }
        return m;
    }

    /**
     * The critical consistency checks after a posted purchase: stock, movements, supplier ledger, cash.
     */
    private void assertConsistent(Purchase p, Map<Integer, BigDecimal> stockBefore, BigDecimal balanceBefore,
                                  BigDecimal cashBefore) {
        for (PurchaseItem item : p.getItems()) {
            int idx = indexOf(item.getProductId());
            BigDecimal received = p.getItems().stream().filter(i -> i.getProductId().equals(item.getProductId()))
                    .map(PurchaseItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, stockBefore.get(idx).add(received).compareTo(qty(idx)),
                    "product quantity = previous + purchased");
            List<StockMovement> moves = movementDao.findByProduct(item.getProductId());
            StockMovement last = moves.get(moves.size() - 1);
            assertEquals(0, last.getQuantityAfter().compareTo(qty(idx)), "last movement balance_after = product quantity");
            assertEquals(0, qty(idx).compareTo(movementDao.sumForProduct(item.getProductId())), "quantity = Σ movements");
        }
        assertEquals(0, supplierBalance().compareTo(accountLedger.rebuiltBalance(PartyType.SUPPLIER, supplier.getSupplierId())),
                "supplier balance = ledger");
        assertEquals(0, balanceBefore.add(p.getRemainingAmount()).compareTo(supplierBalance()),
                "supplier balance grows by the unpaid part");
        assertEquals(0, cashBefore.subtract(p.getPaidAmount()).compareTo(cashDao.balance()), "cash decreases by paid");
    }

    private int indexOf(int productId) {
        for (int i = 0; i < products.size(); i++) {
            if (products.get(i).getProductId() == productId) {
                return i;
            }
        }
        throw new IllegalArgumentException();
    }

    // ---------- Cash purchase with three items ----------

    @Test
    void cashPurchaseWithThreeItems() {
        Map<Integer, BigDecimal> before = stock();
        BigDecimal balanceBefore = supplierBalance();
        BigDecimal cashBefore = cashDao.balance();

        Purchase p = track(purchases.saveAndPost(newPurchase("1.500",
                line(0, "12", "2.250", "0"),       // 27.000
                line(1, "4", "6.125", "0.500"),    // 24.000
                line(2, "100", "0.700", "2")),     // 68.000
                PaymentType.CASH));

        assertEquals(PurchaseStatus.POSTED, p.getStatus());
        assertTrue(p.getPurchaseNo().matches("PUR-\\d{6}"), p.getPurchaseNo());
        assertEquals(d("119.000"), p.getSubtotal());
        assertEquals(d("117.500"), p.getTotalAmount());
        assertEquals(d("117.500"), p.getPaidAmount());
        assertEquals(d("0.000"), p.getRemainingAmount());
        assertEquals(PaymentStatus.PAID, p.getPaymentStatus());
        assertEquals(PaymentMethod.CASH, p.getPaymentMethod());
        assertNotNull(p.getPostedAt());
        assertEquals(userId, p.getPostedBy());
        assertConsistent(p, before, balanceBefore, cashBefore);

        // stock movements: PURCHASE, referencing the purchase, with the line's net unit cost
        StockMovement m = last(products.get(1));
        assertEquals(MovementType.PURCHASE, m.getMovementType());
        assertEquals("PURCHASE", m.getReferenceType());
        assertEquals(p.getPurchaseId(), m.getReferenceId());
        assertEquals(d("4.000"), m.getQuantity());
        assertEquals(0, m.getQuantityBefore().add(m.getQuantity()).compareTo(m.getQuantityAfter()));
        assertEquals(d("6.000"), m.getUnitCost(), "(4 × 6.125 − 0.500) / 4");
        assertEquals(userId, m.getUserId());

        // supplier ledger: PURCHASE credit total + PAYMENT debit paid
        List<LedgerEntry> entries = ledgerDao.findWithRunningBalance(PartyType.SUPPLIER, supplier.getSupplierId(), null, null)
                .stream().filter(e -> p.getPurchaseId().equals(e.getReferenceId())).toList();
        assertEquals(2, entries.size());
        assertEquals(LedgerEntryType.PURCHASE, entries.get(0).getEntryType());
        assertEquals(d("117.500"), entries.get(0).getCredit());
        assertEquals(LedgerEntryType.PAYMENT, entries.get(1).getEntryType());
        assertEquals(d("117.500"), entries.get(1).getDebit());
        assertEquals(p.getPurchaseNo(), entries.get(0).getReferenceNo());

        // cash: one OUT for the paid amount, method CASH
        List<CashTransaction> cash = cashDao.findBySource("PURCHASE", p.getPurchaseId());
        assertEquals(1, cash.size());
        assertEquals("OUT", cash.get(0).type());
        assertEquals(d("117.500"), cash.get(0).amount());
        assertEquals(PaymentMethod.CASH, cash.get(0).method());
        assertTrue(cash.get(0).description().contains(p.getPurchaseNo()));

        // audit
        for (String action : List.of("CREATE_PURCHASE", "POST_PURCHASE")) {
            assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = ? AND record_id = ?",
                    action, String.valueOf(p.getPurchaseId())), action);
        }
    }

    private StockMovement last(Product product) {
        List<StockMovement> moves = movementDao.findByProduct(product.getProductId());
        return moves.get(moves.size() - 1);
    }

    // ---------- Credit and partial ----------

    @Test
    void creditPurchaseGoesToTheSupplierAccountOnly() {
        Map<Integer, BigDecimal> before = stock();
        BigDecimal balanceBefore = supplierBalance();
        BigDecimal cashBefore = cashDao.balance();
        Purchase p = track(purchases.saveAndPost(newPurchase("0", line(0, "5", "2.100", "0")), PaymentType.CREDIT));
        assertEquals(d("10.500"), p.getTotalAmount());
        assertEquals(d("0.000"), p.getPaidAmount());
        assertEquals(PaymentMethod.CREDIT, p.getPaymentMethod());
        assertEquals(PaymentStatus.UNPAID, p.getPaymentStatus());
        assertTrue(cashDao.findBySource("PURCHASE", p.getPurchaseId()).isEmpty(), "credit: no cash movement");
        assertConsistent(p, before, balanceBefore, cashBefore);
        assertEquals(0, balanceBefore.add(d("10.500")).compareTo(supplierBalance()));
    }

    @Test
    void partialPurchasePaysPartNowWithKnet() {
        Map<Integer, BigDecimal> before = stock();
        BigDecimal balanceBefore = supplierBalance();
        BigDecimal cashBefore = cashDao.balance();
        Purchase draft = newPurchase("0", line(1, "10", "5.000", "0"), line(2, "20", "1", "0"));
        draft.setPaymentMethod(PaymentMethod.KNET);
        draft.setPaidAmount(d("30"));
        Purchase p = track(purchases.saveAndPost(draft, PaymentType.PARTIAL));
        assertEquals(d("70.000"), p.getTotalAmount());
        assertEquals(d("30.000"), p.getPaidAmount());
        assertEquals(d("40.000"), p.getRemainingAmount());
        assertEquals(PaymentStatus.PARTIAL, p.getPaymentStatus());
        assertEquals(PaymentType.PARTIAL, p.getPaymentType());
        List<CashTransaction> cash = cashDao.findBySource("PURCHASE", p.getPurchaseId());
        assertEquals(1, cash.size());
        assertEquals(PaymentMethod.KNET, cash.get(0).method());
        assertEquals(d("30.000"), cash.get(0).amount());
        assertConsistent(p, before, balanceBefore, cashBefore);
    }

    @Test
    void partialPaymentAboveTotalIsRefused() {
        Purchase draft = newPurchase("0", line(0, "1", "10", "0"));
        draft.setPaymentMethod(PaymentMethod.CASH);
        draft.setPaidAmount(d("10.001"));
        ValidationException e = assertThrows(ValidationException.class,
                () -> purchases.saveAndPost(draft, PaymentType.PARTIAL));
        assertTrue(e.errorFor(PurchaseService.PAID).contains("يتجاوز"));
    }

    // ---------- Costing ----------

    @Test
    void lastPurchaseCostUpdatesTheProductButNotOldInvoices() {
        Purchase first = track(purchases.saveAndPost(newPurchase("0", line(2, "10", "0.800", "0")), PaymentType.CASH));
        assertEquals(d("0.800"), productDao.findById(products.get(2).getProductId()).orElseThrow().getPurchasePrice());
        Purchase second = track(purchases.saveAndPost(newPurchase("0", line(2, "10", "0.950", "1")), PaymentType.CASH));
        assertEquals(d("0.850"), productDao.findById(products.get(2).getProductId()).orElseThrow().getPurchasePrice(),
                "(10 × 0.950 − 1) / 10");
        Purchase reloaded = purchases.findById(first.getPurchaseId()).orElseThrow();
        assertEquals(d("0.800"), reloaded.getItems().get(0).getUnitCost(), "historical cost kept on the old invoice");
        assertEquals(d("0.950"), purchases.findById(second.getPurchaseId()).orElseThrow().getItems().get(0).getUnitCost());
    }

    // ---------- Drafts, double posting, cancellation ----------

    @Test
    void draftHasNoEffectUntilPostedAndCannotBePostedTwice() {
        Map<Integer, BigDecimal> before = stock();
        BigDecimal balanceBefore = supplierBalance();
        BigDecimal cashBefore = cashDao.balance();

        Purchase draft = track(purchases.saveDraft(newPurchase("0", line(0, "3", "2", "0")), PaymentType.CREDIT));
        assertEquals(PurchaseStatus.DRAFT, draft.getStatus());
        assertEquals(before.get(0), qty(0), "a draft does not move stock");
        assertEquals(0, balanceBefore.compareTo(supplierBalance()), "a draft does not touch the supplier");
        assertEquals(0, cashBefore.compareTo(cashDao.balance()));

        // edit the draft: different quantity and a second line
        draft.setItems(new ArrayList<>(List.of(line(0, "4", "2", "0"), line(1, "1", "5", "0"))));
        Purchase edited = purchases.saveDraft(draft, PaymentType.CREDIT);
        assertEquals(2, edited.getItems().size());
        assertEquals(d("13.000"), edited.getTotalAmount());

        Purchase posted = purchases.post(edited.getPurchaseId());
        assertEquals(PurchaseStatus.POSTED, posted.getStatus());
        assertConsistent(posted, before, balanceBefore, cashBefore);

        ValidationException twice = assertThrows(ValidationException.class, () -> purchases.post(edited.getPurchaseId()));
        assertTrue(twice.getMessage().contains("مسبقًا"));
        assertEquals(0, before.get(0).add(d("4")).compareTo(qty(0)), "posting twice did not add stock twice");
        assertThrows(ValidationException.class, () -> purchases.saveDraft(posted, PaymentType.CREDIT),
                "a posted purchase cannot be edited");
        assertThrows(ValidationException.class, () -> purchases.cancelDraft(posted.getPurchaseId()));
    }

    @Test
    void cancelledDraftIsKeptWithoutEffects() {
        BigDecimal q = qty(1);
        Purchase draft = track(purchases.saveDraft(newPurchase("0", line(1, "2", "5", "0")), PaymentType.CASH));
        purchases.cancelDraft(draft.getPurchaseId());
        Purchase cancelled = purchases.findById(draft.getPurchaseId()).orElseThrow();
        assertEquals(PurchaseStatus.CANCELLED, cancelled.getStatus());
        assertEquals(q, qty(1));
        assertThrows(ValidationException.class, () -> purchases.post(draft.getPurchaseId()));
        assertTrue(purchases.search(new PurchaseFilter(null, null, null, supplier.getSupplierId(), null,
                PurchaseStatus.CANCELLED)).stream().anyMatch(x -> x.getPurchaseId().equals(draft.getPurchaseId())));
    }

    // ---------- Duplicates ----------

    @Test
    void sameRequestTwiceSavesOnce() {
        BigDecimal q = qty(0);
        Purchase request = newPurchase("0", line(0, "2", "2", "0"));
        UUID id = request.getRequestId();
        Purchase first = track(purchases.saveAndPost(request, PaymentType.CASH));
        Purchase retry = newPurchase("0", line(0, "2", "2", "0"));
        retry.setRequestId(id);   // double click / Enter twice / UI retry
        Purchase second = purchases.saveAndPost(retry, PaymentType.CASH);
        assertEquals(first.getPurchaseId(), second.getPurchaseId());
        assertEquals(0, q.add(d("2")).compareTo(qty(0)), "stock added once");
        assertEquals(1, cashDao.findBySource("PURCHASE", first.getPurchaseId()).size());
    }

    @Test
    void supplierInvoiceNumberCannotBeEnteredTwice() {
        Purchase a = newPurchase("0", line(0, "1", "1", "0"));
        a.setSupplierInvoiceNo("INV-" + suffix);
        track(purchases.saveAndPost(a, PaymentType.CASH));
        Purchase b = newPurchase("0", line(0, "1", "1", "0"));
        b.setSupplierInvoiceNo("INV-" + suffix);
        ValidationException e = assertThrows(ValidationException.class, () -> purchases.saveAndPost(b, PaymentType.CASH));
        assertTrue(e.errorFor(PurchaseService.SUPPLIER_INVOICE_NO).contains("مسجلة مسبقًا"));
    }

    @Test
    void purchaseNumbersAreUniqueAndSequential() {
        Purchase a = track(purchases.saveDraft(newPurchase("0", line(0, "1", "1", "0")), PaymentType.CASH));
        Purchase b = track(purchases.saveDraft(newPurchase("0", line(0, "1", "1", "0")), PaymentType.CASH));
        int na = Integer.parseInt(a.getPurchaseNo().substring(4));
        int nb = Integer.parseInt(b.getPurchaseNo().substring(4));
        assertEquals(na + 1, nb);
        assertFalse(a.getPurchaseNo().equals(b.getPurchaseNo()));
    }

    // ---------- Validation against the database ----------

    @Test
    void inactiveSupplierIsRefused() {
        Purchase p = newPurchase("0", line(0, "1", "1", "0"));
        p.setSupplierId(inactiveSupplier.getSupplierId());
        ValidationException e = assertThrows(ValidationException.class, () -> purchases.saveAndPost(p, PaymentType.CASH));
        assertTrue(e.errorFor(PurchaseService.SUPPLIER).contains("غير نشط"));
    }

    @Test
    void invalidQuantityAndCostAreRefused() {
        BigDecimal q = qty(0);
        assertThrows(ValidationException.class,
                () -> purchases.saveAndPost(newPurchase("0", line(0, "0", "1", "0")), PaymentType.CASH));
        assertThrows(ValidationException.class,
                () -> purchases.saveAndPost(newPurchase("0", line(0, "1.5", "1", "0")), PaymentType.CASH),
                "pieces cannot be fractional");
        assertThrows(ValidationException.class,
                () -> purchases.saveAndPost(newPurchase("0", line(0, "1", "-1", "0")), PaymentType.CASH));
        assertThrows(ValidationException.class,
                () -> purchases.saveAndPost(newPurchase("0"), PaymentType.CASH), "no items");
        assertEquals(q, qty(0));
    }

    // ---------- Rollback ----------

    @Test
    void anyFailureRollsBackEverything() {
        Map<Integer, BigDecimal> before = stock();
        BigDecimal balanceBefore = supplierBalance();
        BigDecimal cashBefore = cashDao.balance();
        long purchasesBefore = sql.count("SELECT COUNT(*) FROM dbo.Purchases WHERE user_id = ?", userId);
        int failingProduct = products.get(2).getProductId();
        PurchaseService failing = service((cost, stockBefore, qty, received) -> {
            if (received.compareTo(d("0.777")) == 0) {
                throw new IllegalStateException("simulated failure on the third line");
            }
            return received;
        });

        Purchase p = newPurchase("0", line(0, "5", "2", "0"), line(1, "5", "5", "0"), line(2, "5", "0.777", "0"));
        assertThrows(IllegalStateException.class, () -> failing.saveAndPost(p, PaymentType.CASH));

        assertEquals(purchasesBefore, sql.count("SELECT COUNT(*) FROM dbo.Purchases WHERE user_id = ?", userId),
                "no purchase row");
        assertEquals(before, stock(), "no stock change");
        assertEquals(0, balanceBefore.compareTo(supplierBalance()), "no supplier change");
        assertEquals(0, cashBefore.compareTo(cashDao.balance()), "no cash change");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id = ? AND unit_cost = 0.777",
                failingProduct));
        assertTrue(p.getPurchaseId() == null && p.getPurchaseNo() == null, "a retry starts clean");

        // the same request then succeeds with a working service
        Purchase ok = track(purchases.saveAndPost(p, PaymentType.CASH));
        assertEquals(PurchaseStatus.POSTED, ok.getStatus());
        assertConsistent(ok, before, balanceBefore, cashBefore);
    }

    // ---------- Supplier statement, dashboard, permissions ----------

    @Test
    void supplierStatementAndDashboardPayables() {
        BigDecimal payablesBefore = new DashboardDao().loadStats().payables();
        BigDecimal balanceBefore = supplierBalance();
        Purchase p = track(purchases.saveAndPost(newPurchase("0", line(1, "2", "7.250", "0")), PaymentType.CREDIT));

        security.admin(userId);
        AccountStatement st = new SupplierServiceImpl(supplierDao, accountLedger, new AuditLogDao(), security)
                .statement(supplier.getSupplierId(), null, null, p.getPurchaseNo());
        assertEquals(1, st.entries().size());
        assertEquals(LedgerEntryType.PURCHASE, st.entries().get(0).getEntryType());
        assertEquals(d("14.500"), st.entries().get(0).getCredit());
        assertEquals(0, balanceBefore.add(d("14.500")).compareTo(st.entries().get(0).getRunningBalance()));

        BigDecimal payablesAfter = new DashboardDao().loadStats().payables();
        BigDecimal expected = balanceBefore.signum() >= 0 ? payablesBefore.add(d("14.500"))
                : payablesBefore.add(balanceBefore.add(d("14.500")).max(BigDecimal.ZERO));
        assertEquals(0, expected.compareTo(payablesAfter), "dashboard payables follow the ledger");

        assertTrue(purchases.search(PurchaseFilter.forSupplier(supplier.getSupplierId())).stream()
                .anyMatch(x -> x.getPurchaseId().equals(p.getPurchaseId())), "supplier purchases tab");
    }

    @Test
    void accountantSeesButCannotCreateOrPost() {
        Purchase draft = track(purchases.saveDraft(newPurchase("0", line(0, "1", "2", "0")), PaymentType.CASH));
        security.as(Role.ACCOUNTANT, userId);
        Purchase seen = purchases.findById(draft.getPurchaseId()).orElseThrow();
        assertEquals(d("2.000"), seen.getTotalAmount());
        assertThrows(AccessDeniedException.class, () -> purchases.post(draft.getPurchaseId()));
        assertThrows(AccessDeniedException.class,
                () -> purchases.saveDraft(newPurchase("0", line(0, "1", "2", "0")), PaymentType.CASH));
        security.as(Role.CASHIER, userId);
        assertThrows(AccessDeniedException.class, () -> purchases.findById(draft.getPurchaseId()));
    }
}
