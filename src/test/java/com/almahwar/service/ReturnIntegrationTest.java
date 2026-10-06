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
import com.almahwar.dao.ReturnDao;
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
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Product;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnFilter;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.model.ReturnReason;
import com.almahwar.model.Role;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sales and purchase returns against a real SQL Server: stock, historical cost, customer / supplier ledger, refunds,
 * remaining quantities, duplicates, concurrency, rollback and permissions. Enable with {@code -Ddb.it=true}.
 * Rows created here are removed.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    private final ReturnDao returnDao = new ReturnDao();
    private final SaleDao saleDao = new SaleDao();
    private final PurchaseDao purchaseDao = new PurchaseDao();
    private final ProductDao productDao = new ProductDao();
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final AccountLedgerDao ledgerDao = new AccountLedgerDao();
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final StockLedger stockLedger = new StockLedger(movementDao);
    private final AccountLedger accountLedger = new AccountLedger(ledgerDao, customerDao, supplierDao);
    private final TestSecurity security = new TestSecurity();
    private final ReturnService returns = returns(stockLedger, accountLedger, cashDao);
    private final SaleService sales = new SaleServiceImpl(saleDao, customerDao, productDao, new UnitDao(), stockLedger,
            accountLedger, cashDao, new AuditLogDao(), security);
    private final PurchaseService purchases = new PurchaseServiceImpl(purchaseDao, supplierDao, productDao, new UnitDao(),
            stockLedger, accountLedger, cashDao, new AuditLogDao(), CostingPolicy.LAST_PURCHASE_COST, security);

    private int userId;
    private Integer categoryId;
    private Integer pieceId;
    private Customer walkIn;
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

        BigDecimal decimal(String sql, Object... params) {
            return queryOne(sql, rs -> rs.getBigDecimal(1), params).orElse(BigDecimal.ZERO);
        }
    }

    private final Sql sql = new Sql();

    private ReturnService returns(StockLedger stock, AccountLedger ledger, CashTransactionDao cash) {
        return new ReturnServiceImpl(returnDao, saleDao, purchaseDao, customerDao, supplierDao, productDao, stock, ledger,
                cash, new AuditLogDao(), security);
    }

    @BeforeAll
    void setUp() {
        User u = new User();
        u.setUsername("ret_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("مستخدم مرتجعات");
        u.setRoleId(new RoleDao().findByCode(Role.ADMIN).orElseThrow().getRoleId());
        userId = new UserDao().insert(u);
        Category c = new Category();
        c.setNameAr("قسم مرتجعات " + suffix);
        categoryId = new CategoryDao().insert(c);
        Unit piece = new Unit();
        piece.setNameAr("حبة مرتجعات " + suffix);
        pieceId = new UnitDao().insert(piece);
        walkIn = customerDao.findCashCustomer().orElseThrow();
        supplier = new Supplier();
        supplier.setSupplierCode("RS-" + suffix);
        supplier.setName("مورد مرتجعات " + suffix);
        supplier.setSupplierId(supplierDao.insert(supplier));
    }

    @BeforeEach
    void loginAsAdmin() {
        security.admin(userId);
    }

    @AfterAll
    void cleanUp() {
        security.admin(userId);
        for (Integer id : saleIds) {
            returnDao.search(ReturnKind.SALE, ReturnFilter.forOriginal(id), 1000)
                    .forEach(r -> cashDao.deleteBySourceForTests("SALE_RETURN", r.getReturnId()));
            returnDao.deleteForTests(ReturnKind.SALE, id);
            cashDao.deleteBySourceForTests("SALE", id);
        }
        for (Integer id : purchaseIds) {
            returnDao.search(ReturnKind.PURCHASE, ReturnFilter.forOriginal(id), 1000)
                    .forEach(r -> cashDao.deleteBySourceForTests("PURCHASE_RETURN", r.getReturnId()));
            returnDao.deleteForTests(ReturnKind.PURCHASE, id);
            cashDao.deleteBySourceForTests("PURCHASE", id);
        }
        sql.exec("DELETE FROM dbo.Sales WHERE user_id = ?", userId);
        sql.exec("DELETE FROM dbo.Purchases WHERE user_id = ?", userId);
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
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id = ?", userId);
        sql.exec("DELETE FROM dbo.Users WHERE user_id = ?", userId);
    }

    // ---------- helpers ----------

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, d(expected).compareTo(actual), message + ": expected " + expected + " but was " + actual);
    }

    private Product product(String tag, String cost, String price, String stock) {
        Product p = new Product();
        p.setProductCode("RT" + tag + "-" + suffix);
        p.setNameAr("صنف مرتجع " + tag + " " + suffix);
        p.setCategoryId(categoryId);
        p.setUnitId(pieceId);
        p.setPurchasePrice(d(cost));
        p.setSalePrice(d(price));
        p.setQuantity(BigDecimal.ZERO);
        productDao.insert(p);
        if (d(stock).signum() > 0) {
            TransactionManager.inTransaction(con -> stockLedger.post(con, p.getProductId(), MovementType.OPENING_BALANCE,
                    d(stock), "PRODUCT", p.getProductId(), "رصيد افتتاحي", userId));
        }
        products.add(p);
        return productDao.findById(p.getProductId()).orElseThrow();
    }

    private Customer customer(String tag) {
        Customer c = new Customer();
        c.setCustomerCode("RC" + tag + "-" + suffix);
        c.setName("عميل مرتجع " + tag + " " + suffix);
        c.setCreditLimit(d("10000"));
        c.setCustomerId(customerDao.insert(c));
        customers.add(c);
        return c;
    }

    /** A posted sale of {@code qty} × product at its retail price. */
    private Sale sell(Customer c, Product p, String qty, PaymentType type, String paid, String invoiceDiscount) {
        Sale s = new Sale();
        s.setCustomerId(c.getCustomerId());
        s.setSaleType(SaleType.RETAIL);
        SaleItem i = new SaleItem();
        i.setProductId(p.getProductId());
        i.setQuantity(d(qty));
        i.setUnitPrice(p.getSalePrice());
        s.setItems(new ArrayList<>(List.of(i)));
        s.setDiscountAmount(d(invoiceDiscount));
        if (type == PaymentType.PARTIAL) {
            s.setPaymentMethod(PaymentMethod.CASH);
            s.setPaidAmount(d(paid));
        }
        s.setRequestId(UUID.randomUUID());
        Sale saved = sales.saveAndPost(s, type, false);
        saleIds.add(saved.getSaleId());
        return saved;
    }

    private Purchase buy(Product p, String qty, String cost, PaymentType type) {
        Purchase pur = new Purchase();
        pur.setSupplierId(supplier.getSupplierId());
        PurchaseItem i = new PurchaseItem();
        i.setProductId(p.getProductId());
        i.setQuantity(d(qty));
        i.setUnitCost(d(cost));
        pur.setItems(new ArrayList<>(List.of(i)));
        pur.setRequestId(UUID.randomUUID());
        Purchase saved = purchases.saveAndPost(pur, type);
        purchaseIds.add(saved.getPurchaseId());
        return saved;
    }

    private ReturnDocument request(ReturnKind kind, int originalId, int originalItemId, String qty) {
        ReturnDocument r = new ReturnDocument();
        r.setKind(kind);
        r.setOriginalId(originalId);
        r.setReason(ReturnReason.DEFECTIVE);
        r.setRefundMethod(PaymentMethod.CASH);
        ReturnLine l = new ReturnLine();
        l.setOriginalItemId(originalItemId);
        l.setQuantity(d(qty));
        r.setLines(new ArrayList<>(List.of(l)));
        r.setRequestId(UUID.randomUUID());
        return r;
    }

    private ReturnDocument saleReturn(Sale s, String qty) {
        return request(ReturnKind.SALE, s.getSaleId(), s.getItems().get(0).getSaleItemId(), qty);
    }

    private ReturnDocument purchaseReturn(Purchase p, String qty) {
        return request(ReturnKind.PURCHASE, p.getPurchaseId(), p.getItems().get(0).getPurchaseItemId(), qty);
    }

    private BigDecimal qty(Product p) {
        return productDao.findById(p.getProductId()).orElseThrow().getQuantity();
    }

    private BigDecimal balance(Customer c) {
        return customerDao.findById(c.getCustomerId()).orElseThrow().getBalance();
    }

    private BigDecimal supplierBalance() {
        return supplierDao.findById(supplier.getSupplierId()).orElseThrow().getBalance();
    }

    /** Every global invariant of returns, ledgers, stock and cash. */
    private void assertConsistent() {
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Products p WHERE p.quantity <> COALESCE((SELECT SUM(m.quantity) "
                + "FROM dbo.Stock_Movements m WHERE m.product_id = p.product_id), 0)"), "product quantity = Σ movements");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Products WHERE quantity < 0"), "no negative stock");
        assertTrue(accountLedger.balanceMismatches(PartyType.CUSTOMER).isEmpty(), "customer balance = ledger");
        assertTrue(accountLedger.balanceMismatches(PartyType.SUPPLIER).isEmpty(), "supplier balance = ledger");
        assertMoney(sql.decimal("SELECT COALESCE(SUM(CASE WHEN transaction_type = 'IN' THEN amount ELSE -amount END), 0) "
                + "FROM dbo.Cash_Transactions").toPlainString(), cashDao.balance(), "cash = Σ IN − Σ OUT");
        assertEquals(0, sql.count("""
                SELECT COUNT(*) FROM dbo.Sale_Items si
                WHERE (SELECT COALESCE(SUM(ri.quantity), 0) FROM dbo.Sale_Return_Items ri WHERE ri.sale_item_id = si.sale_item_id)
                      > si.quantity"""), "returned sale qty ≤ sold");
        assertEquals(0, sql.count("""
                SELECT COUNT(*) FROM dbo.Purchase_Items pi
                WHERE (SELECT COALESCE(SUM(ri.quantity), 0) FROM dbo.Purchase_Return_Items ri WHERE ri.purchase_item_id = pi.purchase_item_id)
                      > pi.quantity"""), "returned purchase qty ≤ purchased");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Sale_Returns r WHERE NOT EXISTS (SELECT 1 FROM dbo.Sale_Return_Items i WHERE i.return_id = r.return_id)"));
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Purchase_Returns r WHERE NOT EXISTS (SELECT 1 FROM dbo.Purchase_Return_Items i WHERE i.return_id = r.return_id)"));
        assertEquals(0, sql.count("""
                SELECT COUNT(*) FROM dbo.Sale_Return_Items ri JOIN dbo.Sale_Returns r ON r.return_id = ri.return_id
                JOIN dbo.Sale_Items si ON si.sale_item_id = ri.sale_item_id
                WHERE si.sale_id <> r.sale_id OR si.product_id <> ri.product_id"""), "each sale return line belongs to its sale");
        assertEquals(0, sql.count("""
                SELECT COUNT(*) FROM dbo.Purchase_Return_Items ri JOIN dbo.Purchase_Returns r ON r.return_id = ri.return_id
                JOIN dbo.Purchase_Items pi ON pi.purchase_item_id = ri.purchase_item_id
                WHERE pi.purchase_id <> r.purchase_id OR pi.product_id <> ri.product_id"""), "each purchase return line belongs to its purchase");
    }

    // ======================= Sales returns =======================

    @Test
    void partialMultipleAndFullSalesReturnsOfACreditSale() {
        Product a = product("A", "2.000", "5.000", "100");
        Customer c = customer("CR");
        Sale s = sell(c, a, "10", PaymentType.CREDIT, null, "0");
        assertMoney("50", balance(c), "owes the sale");
        BigDecimal stock = qty(a);
        BigDecimal cash = cashDao.balance();

        ReturnDocument r1 = returns.create(saleReturn(s, "3"));
        assertTrue(r1.getReturnNo().matches("SRN-\\d{6}"), r1.getReturnNo());
        assertMoney("15", r1.getTotalAmount(), "3 × 5");
        assertMoney("0", r1.getRefundAmount(), "credit sale: no money back");
        assertEquals(PaymentMethod.CREDIT, r1.getRefundMethod());
        assertMoney("6", r1.getCostTotal(), "historical cost 3 × 2");
        assertMoney("35", r1.getBalanceAfter(), "debt 50 − 15");
        assertMoney(stock.add(d("3")).toPlainString(), qty(a), "stock back +3");
        StockMovement m = movementDao.findByProduct(a.getProductId()).stream()
                .filter(x -> x.getMovementType() == MovementType.SALE_RETURN).findFirst().orElseThrow();
        assertEquals("SALE_RETURN", m.getReferenceType());
        assertEquals(r1.getReturnId(), m.getReferenceId());
        assertMoney("3", m.getQuantity(), "movement +3");
        assertMoney(m.getQuantityBefore().add(d("3")).toPlainString(), m.getQuantityAfter(), "after = before + 3");
        assertMoney("2", m.getUnitCost(), "at the sale's cost");
        assertMoney(cash.toPlainString(), cashDao.balance(), "no cash");

        ReturnDocument r2 = returns.create(saleReturn(s, "4"));
        assertMoney("15", r2.getBalanceAfter(), "35 − 20");
        ReturnLine left = returns.returnableLines(ReturnKind.SALE, s.getSaleId()).get(0);
        assertMoney("7", left.getReturnedQuantity(), "returned 3 + 4");
        assertMoney("3", left.getRemainingQuantity(), "3 left");
        ValidationException over = assertThrows(ValidationException.class, () -> returns.create(saleReturn(s, "4")));
        assertTrue(over.getMessage().contains("أكبر من المتبقي"), over.getMessage());

        ReturnDocument r3 = returns.create(saleReturn(s, "3"));
        assertMoney("0", r3.getBalanceAfter(), "fully returned: owes nothing");
        assertThrows(ValidationException.class, () -> returns.create(saleReturn(s, "1")), "nothing left");
        List<String> stmt = ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, c.getCustomerId(), null, null).stream()
                .map(e -> e.getEntryType() + "=" + e.getRunningBalance().setScale(3)).toList();
        assertEquals(List.of("SALE=50.000", "SALE_RETURN=35.000", "SALE_RETURN=15.000", "SALE_RETURN=0.000"), stmt);
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'SALE_RETURN_CREATED' AND record_id = ?",
                String.valueOf(r1.getReturnId())));
        assertConsistent();
    }

    @Test
    void walkInReturnIsPaidBackInCashWithoutLedger() {
        Product a = product("W", "1.000", "4.000", "20");
        Sale s = sell(walkIn, a, "2", PaymentType.CASH, null, "0");
        BigDecimal cash = cashDao.balance();
        long ledger = sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger");
        ReturnDocument r = returns.create(saleReturn(s, "1"));
        assertMoney("4", r.getRefundAmount(), "the whole value back");
        assertEquals(PaymentMethod.CASH, r.getRefundMethod());
        assertNull(r.getBalanceAfter(), "walk-in has no account");
        assertMoney(cash.subtract(d("4")).toPlainString(), cashDao.balance(), "cash −4");
        CashTransaction out = cashDao.findBySource("SALE_RETURN", r.getReturnId()).get(0);
        assertEquals("OUT", out.type());
        assertEquals(ledger, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger"), "no ledger entry");
        assertMoney("0", balance(walkIn), "walk-in balance stays 0");
        assertConsistent();
    }

    @Test
    void partlyPaidSaleReturnRefundsOnlyWhatWasPaid() {
        Product a = product("P", "2.000", "5.000", "50");
        Customer c = customer("PP");
        Sale s = sell(c, a, "10", PaymentType.PARTIAL, "20", "0");
        assertMoney("30", balance(c), "50 − 20 paid");
        BigDecimal cash = cashDao.balance();
        ReturnDocument r = returns.create(saleReturn(s, "10"));
        assertMoney("50", r.getTotalAmount(), "full return");
        assertMoney("20", r.getRefundAmount(), "only the 20 paid comes back");
        assertMoney("30", r.getAccountAmount(), "the 30 still owed is cancelled");
        assertMoney("0", balance(c), "no unintended credit");
        assertMoney(cash.subtract(d("20")).toPlainString(), cashDao.balance(), "cash −20");
        List<LedgerEntry> entries = ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, c.getCustomerId(), null, null);
        assertEquals(List.of(LedgerEntryType.SALE, LedgerEntryType.PAYMENT, LedgerEntryType.SALE_RETURN, LedgerEntryType.PAYMENT),
                entries.stream().map(LedgerEntry::getEntryType).toList());
        assertMoney("50", entries.get(2).getCredit(), "SALE_RETURN credit 50");
        assertMoney("20", entries.get(3).getDebit(), "refund 20 as a debit");
        assertConsistent();
    }

    @Test
    void invoiceDiscountIsSharedSoTheRefundNeverExceedsTheTotal() {
        Product a = product("D", "2.000", "5.000", "20");
        Sale s = sell(walkIn, a, "3", PaymentType.CASH, null, "1.500");   // 15 − 1.5 = 13.5
        ReturnLine line = returns.returnableLines(ReturnKind.SALE, s.getSaleId()).get(0);
        assertMoney("4.5", line.getUnitPrice(), "net unit value");
        ReturnDocument r = returns.create(saleReturn(s, "3"));
        assertMoney("13.5", r.getTotalAmount(), "= what was paid");
        assertMoney("13.5", r.getRefundAmount(), "walk-in: all back");
        assertConsistent();
    }

    @Test
    void costReversalUsesTheSaleSnapshotAndProfitIsCorrected() {
        Product a = product("C", "2.000", "5.000", "10");
        Customer c = customer("CO");
        Sale s = sell(c, a, "4", PaymentType.CREDIT, null, "0");
        buy(a, "5", "3.000", PaymentType.CREDIT);   // the current cost becomes 3
        assertMoney("3", productDao.findById(a.getProductId()).orElseThrow().getPurchasePrice(), "current cost 3");
        BigDecimal profitBefore = new DashboardDao().loadStats().monthGrossProfit();
        ReturnDocument r = returns.create(saleReturn(s, "2"));
        assertMoney("4", r.getCostTotal(), "2 × the sale's cost 2.000, not 3.000");
        assertMoney("2", r.getLines().get(0).getUnitCost(), "line cost snapshot");
        StockMovement m = movementDao.findByProduct(a.getProductId()).stream()
                .filter(x -> x.getMovementType() == MovementType.SALE_RETURN).findFirst().orElseThrow();
        assertMoney("2", m.getUnitCost(), "stock comes back at 2.000");
        BigDecimal profitAfter = new DashboardDao().loadStats().monthGrossProfit();
        assertMoney("6", profitBefore.subtract(profitAfter), "profit −(10 value − 4 cost)");
        assertConsistent();
    }

    // ======================= Purchase returns =======================

    @Test
    void partialMultipleAndOverPurchaseReturns() {
        Product a = product("PA", "2.000", "5.000", "0");
        BigDecimal supplierBefore = supplierBalance();
        Purchase p = buy(a, "10", "2.000", PaymentType.CREDIT);
        assertMoney(supplierBefore.add(d("20")).toPlainString(), supplierBalance(), "we owe 20 more");
        ReturnDocument r1 = returns.create(purchaseReturn(p, "3"));
        assertTrue(r1.getReturnNo().matches("PRN-\\d{6}"), r1.getReturnNo());
        assertMoney("6", r1.getTotalAmount(), "3 × 2");
        assertMoney("0", r1.getRefundAmount(), "we owed the supplier: no money back");
        assertMoney("7", qty(a), "stock 10 − 3");
        assertMoney(supplierBefore.add(d("14")).toPlainString(), supplierBalance(), "owe 6 less");
        StockMovement m = movementDao.findByProduct(a.getProductId()).stream()
                .filter(x -> x.getMovementType() == MovementType.PURCHASE_RETURN).findFirst().orElseThrow();
        assertEquals("PURCHASE_RETURN", m.getReferenceType());
        assertMoney("-3", m.getQuantity(), "movement −3");
        returns.create(purchaseReturn(p, "4"));
        assertThrows(ValidationException.class, () -> returns.create(purchaseReturn(p, "4")), "only 3 left");
        returns.create(purchaseReturn(p, "3"));
        assertMoney("0", qty(a), "all returned");
        assertMoney(supplierBefore.toPlainString(), supplierBalance(), "back to the balance before the purchase");
        List<LedgerEntryType> types = ledgerDao.findWithRunningBalance(PartyType.SUPPLIER, supplier.getSupplierId(), null, null)
                .stream().map(LedgerEntry::getEntryType).toList();
        assertTrue(types.contains(LedgerEntryType.PURCHASE_RETURN));
        assertConsistent();
    }

    @Test
    void purchaseReturnCannotTakeMoreThanTheCurrentStock() {
        Product a = product("ST", "2.000", "5.000", "0");
        Purchase p = buy(a, "10", "2.000", PaymentType.CREDIT);
        sell(walkIn, a, "8", PaymentType.CASH, null, "0");
        assertMoney("2", qty(a), "10 − 8");
        ValidationException e = assertThrows(ValidationException.class, () -> returns.create(purchaseReturn(p, "5")));
        assertTrue(e.getMessage().contains("المخزون الحالي 2") && e.getMessage().contains("5"), e.getMessage());
        assertMoney("2", qty(a), "unchanged");
        assertNotNull(returns.create(purchaseReturn(p, "2")), "what is in stock can be returned");
        assertMoney("0", qty(a), "never negative");
        assertConsistent();
    }

    @Test
    void paidPurchaseReturnBringsMoneyBack() {
        Product a = product("PC", "2.000", "5.000", "0");
        // pay everything we owe this supplier first, so the return cannot just reduce a debt
        Purchase p = buy(a, "10", "2.500", PaymentType.CASH);
        BigDecimal owed = supplierBalance();
        BigDecimal cash = cashDao.balance();
        ReturnDocument r = returns.create(purchaseReturn(p, "4"));
        BigDecimal value = d("10");   // 4 × 2.5
        BigDecimal expectedRefund = value.subtract(owed.max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
        assertMoney(expectedRefund.toPlainString(), r.getRefundAmount(), "money back = value beyond what we owe");
        assertMoney(cash.add(expectedRefund).toPlainString(), cashDao.balance(), "cash IN");
        if (expectedRefund.signum() > 0) {
            assertEquals("IN", cashDao.findBySource("PURCHASE_RETURN", r.getReturnId()).get(0).type());
        }
        assertTrue(supplierBalance().signum() >= 0, "no unintended negative supplier balance");
        assertConsistent();
    }

    @Test
    void purchaseReturnRefundWhenNothingIsOwed() {
        // a dedicated supplier with no other debt: the paid purchase return must come back as money
        Supplier own = new Supplier();
        own.setSupplierCode("RS2-" + suffix);
        own.setName("مورد مدفوع " + suffix);
        own.setSupplierId(supplierDao.insert(own));
        Product a = product("PZ", "2.000", "5.000", "0");
        Purchase pur = new Purchase();
        pur.setSupplierId(own.getSupplierId());
        PurchaseItem i = new PurchaseItem();
        i.setProductId(a.getProductId());
        i.setQuantity(d("6"));
        i.setUnitCost(d("1.500"));
        pur.setItems(new ArrayList<>(List.of(i)));
        pur.setRequestId(UUID.randomUUID());
        Purchase p = purchases.saveAndPost(pur, PaymentType.KNET);
        purchaseIds.add(p.getPurchaseId());
        BigDecimal cash = cashDao.balance();
        ReturnDocument req = purchaseReturn(p, "2");
        req.setRefundMethod(PaymentMethod.KNET);
        ReturnDocument r = returns.create(req);
        assertMoney("3", r.getRefundAmount(), "2 × 1.5 received back");
        assertEquals(PaymentMethod.KNET, r.getRefundMethod());
        assertMoney(cash.add(d("3")).toPlainString(), cashDao.balance(), "cash IN 3");
        assertMoney("0", supplierDao.findById(own.getSupplierId()).orElseThrow().getBalance(), "balance stays 0");
        assertConsistent();
        ledgerDao.deleteForParty(PartyType.SUPPLIER, own.getSupplierId());
        cashDao.deleteBySourceForTests("PURCHASE_RETURN", r.getReturnId());
        returnDao.deleteForTests(ReturnKind.PURCHASE, p.getPurchaseId());
        cashDao.deleteBySourceForTests("PURCHASE", p.getPurchaseId());
        sql.exec("DELETE FROM dbo.Purchases WHERE purchase_id = ?", p.getPurchaseId());
        purchaseIds.remove(p.getPurchaseId());
        movementDao.deleteByProduct(a.getProductId());
        sql.exec("UPDATE dbo.Products SET quantity = 0 WHERE product_id = ?", a.getProductId());
        sql.exec("DELETE FROM dbo.Suppliers WHERE supplier_id = ?", own.getSupplierId());
    }

    // ======================= Idempotency and concurrency =======================

    @Test
    void doubleSubmitCreatesOneReturn() {
        Product a = product("DS", "2.000", "5.000", "20");
        Sale s = sell(walkIn, a, "5", PaymentType.CASH, null, "0");
        ReturnDocument req = saleReturn(s, "2");
        ReturnDocument first = returns.create(req);
        ReturnDocument copy = saleReturn(s, "2");
        copy.setRequestId(req.getRequestId());
        assertEquals(first.getReturnId(), returns.create(copy).getReturnId());
        assertEquals(1, returns.search(ReturnKind.SALE, ReturnFilter.forOriginal(s.getSaleId())).size(), "one return");
        assertEquals(1, cashDao.findBySource("SALE_RETURN", first.getReturnId()).size(), "refund once");
        assertMoney("17", qty(a), "20 − 5 + 2, once");

        Purchase p = buy(a, "4", "2.000", PaymentType.CREDIT);
        ReturnDocument preq = purchaseReturn(p, "1");
        ReturnDocument pfirst = returns.create(preq);
        ReturnDocument pcopy = purchaseReturn(p, "1");
        pcopy.setRequestId(preq.getRequestId());
        assertEquals(pfirst.getReturnId(), returns.create(pcopy).getReturnId());
        assertEquals(1, returns.search(ReturnKind.PURCHASE, ReturnFilter.forOriginal(p.getPurchaseId())).size());
    }

    @Test
    void concurrentSalesReturnsCannotExceedTheRemaining() throws Exception {
        Product a = product("CS", "2.000", "5.000", "30");
        Customer c = customer("CC");
        Sale s = sell(c, a, "10", PaymentType.CREDIT, null, "0");
        returns.create(saleReturn(s, "5"));   // 5 left
        List<Object> results = race(() -> returns.create(saleReturn(s, "4")), () -> returns.create(saleReturn(s, "4")));
        assertEquals(1, results.stream().filter(r -> r instanceof ReturnDocument).count(), "exactly one: " + results);
        assertTrue(results.stream().anyMatch(r -> r instanceof ValidationException), "the other is refused");
        assertMoney("9", returns.returnableLines(ReturnKind.SALE, s.getSaleId()).get(0).getReturnedQuantity(), "5 + 4, never 13");
        // the same request twice at once: one return
        UUID request = UUID.randomUUID();
        List<Object> same = race(() -> {
            ReturnDocument r = saleReturn(s, "1");
            r.setRequestId(request);
            return returns.create(r);
        }, () -> {
            ReturnDocument r = saleReturn(s, "1");
            r.setRequestId(request);
            return returns.create(r);
        });
        assertTrue(same.stream().allMatch(r -> r instanceof ReturnDocument), String.valueOf(same));
        assertEquals(((ReturnDocument) same.get(0)).getReturnId(), ((ReturnDocument) same.get(1)).getReturnId());
        assertMoney("10", returns.returnableLines(ReturnKind.SALE, s.getSaleId()).get(0).getReturnedQuantity(), "all 10");
        assertConsistent();
    }

    @Test
    void concurrentPurchaseReturnsCannotExceedTheRemaining() throws Exception {
        Product a = product("CP", "2.000", "5.000", "0");
        Purchase p = buy(a, "10", "2.000", PaymentType.CREDIT);
        returns.create(purchaseReturn(p, "5"));
        List<Object> results = race(() -> returns.create(purchaseReturn(p, "4")), () -> returns.create(purchaseReturn(p, "4")));
        assertEquals(1, results.stream().filter(r -> r instanceof ReturnDocument).count(), "exactly one: " + results);
        assertMoney("1", qty(a), "10 − 5 − 4");
        assertConsistent();
    }

    @Test
    void saleAndPurchaseReturnsOfTheSameProductsTogetherDoNotDeadlock() throws Exception {
        Product a = product("LA", "1.000", "3.000", "100");
        Product b = product("LB", "1.000", "3.000", "100");
        for (int round = 0; round < 10; round++) {
            Sale s = new Sale();
            Customer c = customer("LK" + round);
            s.setCustomerId(c.getCustomerId());
            s.setSaleType(SaleType.RETAIL);
            List<SaleItem> items = new ArrayList<>();
            for (Product x : List.of(b, a)) {   // lines in "high, low" order
                SaleItem i = new SaleItem();
                i.setProductId(x.getProductId());
                i.setQuantity(d("2"));
                i.setUnitPrice(d("3.000"));
                items.add(i);
            }
            s.setItems(items);
            s.setRequestId(UUID.randomUUID());
            Sale sale = sales.saveAndPost(s, PaymentType.CREDIT, false);
            saleIds.add(sale.getSaleId());
            Purchase pur = new Purchase();
            pur.setSupplierId(supplier.getSupplierId());
            List<PurchaseItem> pitems = new ArrayList<>();
            for (Product x : List.of(a, b)) {
                PurchaseItem i = new PurchaseItem();
                i.setProductId(x.getProductId());
                i.setQuantity(d("2"));
                i.setUnitCost(d("1.000"));
                pitems.add(i);
            }
            pur.setItems(pitems);
            pur.setRequestId(UUID.randomUUID());
            Purchase purchase = purchases.saveAndPost(pur, PaymentType.CREDIT);
            purchaseIds.add(purchase.getPurchaseId());

            ReturnDocument sr = new ReturnDocument();
            sr.setKind(ReturnKind.SALE);
            sr.setOriginalId(sale.getSaleId());
            sr.setReason(ReturnReason.WRONG_ORDER);
            sr.setRefundMethod(PaymentMethod.CASH);
            sr.setRequestId(UUID.randomUUID());
            for (SaleItem i : sale.getItems()) {
                ReturnLine l = new ReturnLine();
                l.setOriginalItemId(i.getSaleItemId());
                l.setQuantity(d("1"));
                sr.getLines().add(l);
            }
            ReturnDocument pr = new ReturnDocument();
            pr.setKind(ReturnKind.PURCHASE);
            pr.setOriginalId(purchase.getPurchaseId());
            pr.setReason(ReturnReason.DAMAGED);
            pr.setRefundMethod(PaymentMethod.CASH);
            pr.setRequestId(UUID.randomUUID());
            for (PurchaseItem i : purchase.getItems()) {
                ReturnLine l = new ReturnLine();
                l.setOriginalItemId(i.getPurchaseItemId());
                l.setQuantity(d("1"));
                pr.getLines().add(l);
            }
            List<Object> results = race(() -> returns.create(sr), () -> returns.create(pr));
            int r = round;
            assertTrue(results.stream().allMatch(x -> x instanceof ReturnDocument), "round " + r + ": " + results);
        }
        assertConsistent();
    }

    // ======================= Rollback =======================

    @Test
    void anyFailureRollsBackTheWholeReturn() {
        Product a = product("RB", "2.000", "5.000", "20");
        Customer c = customer("RB");
        Sale s = sell(c, a, "10", PaymentType.PARTIAL, "45", "0");   // owes 5 → a return of 2 (10) refunds 5
        BigDecimal stock = qty(a);
        BigDecimal balance = balance(c);
        BigDecimal cash = cashDao.balance();
        long movements = sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id = ?", a.getProductId());

        StockLedger brokenStock = new StockLedger(movementDao) {
            @Override
            public StockMovement post(Connection con, int productId, MovementType type, BigDecimal quantity, BigDecimal unitCost,
                                      String referenceType, Integer referenceId, String reason, int userId) {
                throw new IllegalStateException("simulated stock failure");
            }
        };
        AccountLedger brokenLedger = new AccountLedger(ledgerDao, customerDao, supplierDao) {
            @Override
            public LedgerEntry post(Connection con, PartyType party, int partyId, LedgerEntryType type, BigDecimal amount,
                                    String referenceType, Integer referenceId, String referenceNo, String description,
                                    int userId, java.time.LocalDateTime entryDate) {
                throw new IllegalStateException("simulated ledger failure");
            }
        };
        CashTransactionDao brokenCash = new CashTransactionDao() {
            @Override
            public long insert(Connection con, CashMovement m) {
                throw new IllegalStateException("simulated cash failure");
            }
        };
        for (ReturnService broken : List.of(returns(brokenStock, accountLedger, cashDao),
                returns(stockLedger, brokenLedger, cashDao), returns(stockLedger, accountLedger, brokenCash))) {
            ReturnDocument req = saleReturn(s, "2");
            assertThrows(IllegalStateException.class, () -> broken.create(req));
            assertNull(req.getReturnId(), "the id of the rolled back return is forgotten");
            assertTrue(returns.search(ReturnKind.SALE, ReturnFilter.forOriginal(s.getSaleId())).isEmpty(), "no return header");
            assertMoney(stock.toPlainString(), qty(a), "stock unchanged");
            assertEquals(movements, sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements WHERE product_id = ?", a.getProductId()));
            assertMoney(balance.toPlainString(), balance(c), "balance unchanged");
            assertMoney(cash.toPlainString(), cashDao.balance(), "cash unchanged");
        }
        ReturnDocument ok = returns.create(saleReturn(s, "2"));
        assertMoney("5", ok.getRefundAmount(), "then it works: 5 off the debt, 5 back");
        assertConsistent();
    }

    // ======================= Permissions and input =======================

    @Test
    void permissionsAreCheckedByTheService() {
        Product a = product("PM", "2.000", "5.000", "20");
        Sale s = sell(walkIn, a, "3", PaymentType.CASH, null, "0");
        Purchase p = buy(a, "3", "2.000", PaymentType.CREDIT);
        security.as(Role.CASHIER, userId);
        assertNotNull(returns.create(saleReturn(s, "1")), "a cashier takes sales returns");
        assertNull(returns.findById(ReturnKind.SALE, returns.search(ReturnKind.SALE, ReturnFilter.forOriginal(s.getSaleId()))
                .get(0).getReturnId()).orElseThrow().getCostTotal(), "but does not see the cost");
        assertThrows(AccessDeniedException.class, () -> returns.create(purchaseReturn(p, "1")));
        security.as(Role.STOREKEEPER, userId);
        assertThrows(AccessDeniedException.class, () -> returns.create(purchaseReturn(p, "1")), "not on the storekeeper alone");
        assertThrows(AccessDeniedException.class, () -> returns.search(ReturnKind.SALE, ReturnFilter.all()));
        security.as(Role.ACCOUNTANT, userId);
        assertNotNull(returns.create(purchaseReturn(p, "1")));
        assertNotNull(returns.create(saleReturn(s, "1")));
    }

    @Test
    void invalidQuantitiesAreRefused() {
        Product a = product("IQ", "2.000", "5.000", "20");
        Sale s = sell(walkIn, a, "3", PaymentType.CASH, null, "0");
        for (String bad : new String[]{"0", "-1", "1.5", "1.0001"}) {
            ValidationException e = assertThrows(ValidationException.class, () -> returns.create(saleReturn(s, bad)), bad);
            assertTrue(e.getErrors().containsKey(ReturnService.LINES), bad + ": " + e.getErrors());
        }
        ReturnDocument noReason = saleReturn(s, "1");
        noReason.setReason(null);
        assertTrue(assertThrows(ValidationException.class, () -> returns.create(noReason)).getErrors()
                .containsKey(ReturnService.REASON), "reason is required");
        assertTrue(returns.search(ReturnKind.SALE, ReturnFilter.forOriginal(s.getSaleId())).isEmpty());
    }

    // ---------- concurrency helper ----------

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> c : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return c.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
