package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.ExpenseDao;
import com.almahwar.dao.PaymentDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.QuotationDao;
import com.almahwar.dao.ReturnDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Category;
import com.almahwar.model.Customer;
import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Product;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationConversion;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.model.ReturnReason;
import com.almahwar.model.Role;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleType;
import com.almahwar.model.StockAdjustment;
import com.almahwar.model.Supplier;
import com.almahwar.model.Unit;
import com.almahwar.model.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The golden scenarios: the critical business workflows of Desktop 1.0.0, run through the real services wired exactly
 * as {@code config.AppContext} wires them (same constructors, {@code CostingPolicy.LAST_PURCHASE_COST}, one shared
 * {@code AuditLogDao}), on deterministic data with fixed codes and fixed request ids. After every scenario the whole
 * database is dumped; the concatenated report is the golden record.
 * <p>
 * The scenarios are part of the behaviour contract: changing them invalidates the recorded baseline.
 */
final class GoldenScenarios {

    @FunctionalInterface
    interface Step {
        String run() throws Exception;
    }

    private final TestSecurity security = new TestSecurity();
    private final GoldenDatabase db = new GoldenDatabase();

    // ---- wiring copied from config.AppContext ----
    private final ProductDao productDao = new ProductDao();
    private final UnitDao unitDao = new UnitDao();
    private final AuditLogDao auditLogDao = new AuditLogDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final StockLedger ledger = new StockLedger(movementDao);
    private final InventoryService inventory = new InventoryServiceImpl(productDao, unitDao, movementDao, ledger,
            auditLogDao, security);
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final AccountLedger accountLedger = new AccountLedger(new AccountLedgerDao(), customerDao, supplierDao);
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final PurchaseService purchases = new PurchaseServiceImpl(new PurchaseDao(), supplierDao, productDao, unitDao,
            ledger, accountLedger, cashDao, auditLogDao, CostingPolicy.LAST_PURCHASE_COST, security);
    private final QuotationDao quotationDao = new QuotationDao();
    private final SaleDao saleDao = new SaleDao();
    private final SaleService sales = new SaleServiceImpl(saleDao, customerDao, productDao, unitDao, ledger,
            accountLedger, cashDao, auditLogDao, security, quotationDao);
    private final QuotationService quotations = new QuotationServiceImpl(quotationDao, customerDao, productDao, unitDao,
            saleDao, sales, auditLogDao, security);
    private final PaymentService payments = new PaymentServiceImpl(new PaymentDao(), customerDao, supplierDao,
            accountLedger, cashDao, auditLogDao, security);
    private final ExpenseService expenses = new ExpenseServiceImpl(new ExpenseDao(), cashDao, auditLogDao, security);
    private final ReturnService returns = new ReturnServiceImpl(new ReturnDao(), new SaleDao(), new PurchaseDao(),
            customerDao, supplierDao, productDao, ledger, accountLedger, cashDao, auditLogDao, security);

    // ---- deterministic data ----
    private int admin;
    private int cashier;
    private int storekeeper;
    private int accountant;
    private Customer credit;      // credit limit 100.000
    private Customer noCredit;    // credit limit 0
    private Customer partial;     // credit limit 500.000
    private Customer walkIn;
    private Supplier supplier;
    private Product tap;          // piece: cost 5.000, retail 10.000, wholesale 8.000, stock 50
    private Product pipe;         // metre: cost 1.250, retail 2.500, wholesale 2.000, stock 200
    private Product valve;        // piece: cost 3.000, retail 7.000, wholesale 6.000, stock 5

    private final StringBuilder report = new StringBuilder();

    /** Runs everything and returns the golden report. */
    String runAll() throws Exception {
        report.append("# Golden behaviour report — scenarios: ").append(SCENARIO_COUNT).append('\n');
        scenario("S00 seed deterministic data", this::seed);
        scenario("S01 cash sale, two products, line + invoice discount (cashier)", this::cashSale);
        scenario("S02 repeated request_id of S01 is idempotent: same invoice, nothing new written", this::duplicateSale);
        scenario("S03 credit sale within the limit (cashier)", this::creditSale);
        scenario("S04 credit limit exceeded: refused for cashier, override by admin", this::creditOverride);
        scenario("S05 price override: refused for cashier, allowed and audited for admin", this::priceOverride);
        scenario("S06 partial payment sale: part cash, rest on the customer account", this::partialSale);
        scenario("S07 purchases: cash and credit, multiple items, costing (storekeeper)", this::purchasesScenario);
        scenario("S08 repeated purchase request_id is idempotent: same purchase, nothing new written", this::duplicatePurchase);
        scenario("S09 sale returns: credit customer partial, walk-in cash", this::saleReturns);
        scenario("S10 purchase return", this::purchaseReturn);
        scenario("S11 customer collection and supplier payment (accountant)", this::partyPayments);
        scenario("S12 expense (accountant)", this::expense);
        scenario("S13 inventory adjustments in / out and refused negative stock (storekeeper)", this::adjustments);
        scenario("S14 quotation save, send, accept, convert, convert again, post", this::quotationConversion);
        scenario("S15 failures roll back completely (stock, injected cash / ledger faults)", this::rollbacks);
        scenario("S16 permission refusals change nothing", this::permissions);
        return report.toString();
    }

    static final int SCENARIO_COUNT = 17;

    private void scenario(String name, Step step) throws Exception {
        String outcome;
        try {
            outcome = step.run();
        } catch (Exception e) {
            outcome = "UNEXPECTED " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        report.append("\n=== ").append(name).append('\n').append("outcome: ").append(outcome).append('\n')
                .append(db.dump());
    }

    /** Runs a call that must fail and describes the failure; marks whether the database stayed identical. */
    private String refused(Step call) throws Exception {
        String before = db.dump();
        String result;
        try {
            result = "NOT REFUSED: " + call.run();
        } catch (RuntimeException e) {
            result = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return result + " [db unchanged: " + before.equals(db.dump()) + "]";
    }

    /** Runs a call that must succeed without writing anything (idempotent replay); marks whether the db stayed identical. */
    private String unchanged(Step call) throws Exception {
        String before = db.dump();
        String result = call.run();
        return "returned " + result + " [db unchanged: " + before.equals(db.dump()) + "]";
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format(java.util.Locale.ROOT, "00000000-0000-0000-0000-%012d", n));
    }

    // ======================= seed =======================

    private String seed() {
        admin = user("g_admin", Role.ADMIN);
        cashier = user("g_cashier", Role.CASHIER);
        storekeeper = user("g_store", Role.STOREKEEPER);
        accountant = user("g_account", Role.ACCOUNTANT);

        credit = customer("GC-CRD", "عميل آجل", "100.000");
        noCredit = customer("GC-NOC", "عميل نقدي", "0");
        partial = customer("GC-PRT", "عميل دفعات", "500.000");
        walkIn = customerDao.findCashCustomer().orElseThrow();

        supplier = new Supplier();
        supplier.setSupplierCode("GS-01");
        supplier.setName("مورد ذهبي");
        supplier.setSupplierId(supplierDao.insert(supplier));

        Category c = new Category();
        c.setNameAr("قسم ذهبي");
        int category = new CategoryDao().insert(c);
        Unit piece = new Unit();
        piece.setNameAr("حبة ذهبية");
        int pieceId = unitDao.insert(piece);
        Unit metre = new Unit();
        metre.setNameAr("متر ذهبي");
        metre.setAllowsDecimal(true);
        int metreId = unitDao.insert(metre);

        tap = product("G-TAP", "خلاط", category, pieceId, "5.000", "10.000", "8.000", "50");
        pipe = product("G-PIPE", "ماسورة", category, metreId, "1.250", "2.500", "2.000", "200");
        valve = product("G-VALVE", "محبس", category, pieceId, "3.000", "7.000", "6.000", "5");
        return "users " + admin + "," + cashier + "," + storekeeper + "," + accountant;
    }

    private int user(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("مستخدم " + username);
        u.setRoleId(new RoleDao().findByCode(role).orElseThrow().getRoleId());
        return new UserDao().insert(u);
    }

    private Customer customer(String code, String name, String limit) {
        Customer c = new Customer();
        c.setCustomerCode(code);
        c.setName(name);
        c.setPhone("9" + code.hashCode() % 1000 + "0000");
        c.setCreditLimit(d(limit));
        c.setActive(true);
        c.setCustomerId(customerDao.insert(c));
        return c;
    }

    private Product product(String code, String name, int category, int unit, String cost, String retail,
                            String wholesale, String stock) {
        Product p = new Product();
        p.setProductCode(code);
        p.setBarcode("629" + code.substring(2));
        p.setNameAr(name);
        p.setCategoryId(category);
        p.setUnitId(unit);
        p.setPurchasePrice(d(cost));
        p.setSalePrice(d(retail));
        p.setWholesalePrice(d(wholesale));
        p.setQuantity(BigDecimal.ZERO);
        p.setActive(true);
        productDao.insert(p);
        TransactionManager.inTransaction(con -> ledger.post(con, p.getProductId(), MovementType.OPENING_BALANCE,
                d(stock), "PRODUCT", p.getProductId(), "رصيد افتتاحي", admin));
        return productDao.findById(p.getProductId()).orElseThrow();
    }

    // ======================= sales =======================

    private SaleItem line(Product p, String qty, String price, String discount) {
        SaleItem i = new SaleItem();
        i.setProductId(p.getProductId());
        i.setQuantity(d(qty));
        i.setUnitPrice(d(price));
        i.setDiscountAmount(d(discount));
        return i;
    }

    private Sale sale(Customer c, SaleType type, String invoiceDiscount, int request, SaleItem... items) {
        Sale s = new Sale();
        s.setCustomerId(c.getCustomerId());
        s.setSaleType(type);
        s.setDiscountAmount(d(invoiceDiscount));
        s.setItems(new ArrayList<>(List.of(items)));
        s.setRequestId(id(request));
        return s;
    }

    private static String describe(Sale s) {
        return "sale " + s.getSaleNo() + " " + s.getStatus() + " total=" + s.getTotalAmount() + " paid="
                + s.getPaidAmount() + " cost=" + s.getCostTotal() + " profit=" + s.getGrossProfit();
    }

    private String cashSale() {
        security.as(Role.CASHIER, cashier);
        Sale s = sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "1.000", 101,
                line(tap, "3", "10.000", "2.000"), line(pipe, "4.5", "2.500", "0")), PaymentType.CASH, false);
        return describe(s);
    }

    private String duplicateSale() throws Exception {   // v1.0.0: idempotent replay (no exception)
        security.as(Role.CASHIER, cashier);
        return unchanged(() -> describe(sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "1.000", 101,
                line(tap, "3", "10.000", "2.000"), line(pipe, "4.5", "2.500", "0")), PaymentType.CASH, false)));
    }

    private String creditSale() {
        security.as(Role.CASHIER, cashier);
        Sale s = sales.saveAndPost(sale(credit, SaleType.WHOLESALE, "0", 103, line(tap, "5", "8.000", "0")),
                PaymentType.CREDIT, false);
        return describe(s);
    }

    private String creditOverride() throws Exception {
        security.as(Role.CASHIER, cashier);
        String cashierTry = refused(() -> describe(sales.saveAndPost(sale(credit, SaleType.RETAIL, "0", 104,
                line(tap, "8", "10.000", "0")), PaymentType.CREDIT, false)));
        security.as(Role.ADMIN, admin);
        Sale s = sales.saveAndPost(sale(credit, SaleType.RETAIL, "0", 105, line(tap, "8", "10.000", "0")),
                PaymentType.CREDIT, true);
        return "cashier: " + cashierTry + " | admin: " + describe(s);
    }

    private String priceOverride() throws Exception {
        security.as(Role.CASHIER, cashier);
        String cashierTry = refused(() -> describe(sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "0", 106,
                line(valve, "1", "6.500", "0")), PaymentType.CASH, false)));
        security.as(Role.ADMIN, admin);
        Sale s = sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "0", 107, line(valve, "1", "6.500", "0")),
                PaymentType.CASH, false);
        return "cashier: " + cashierTry + " | admin: " + describe(s);
    }

    private String partialSale() {
        security.as(Role.CASHIER, cashier);
        Sale p = sale(partial, SaleType.RETAIL, "0", 109, line(pipe, "8", "2.500", "0"));
        p.setPaymentMethod(PaymentMethod.CASH);
        p.setPaidAmount(d("5.000"));
        return describe(sales.saveAndPost(p, PaymentType.PARTIAL, false));
    }

    // ======================= purchases =======================

    private PurchaseItem buyLine(Product p, String qty, String cost, String discount) {
        PurchaseItem i = new PurchaseItem();
        i.setProductId(p.getProductId());
        i.setQuantity(d(qty));
        i.setUnitCost(d(cost));
        i.setDiscountAmount(d(discount));
        return i;
    }

    private Purchase purchase(int request, String invoiceDiscount, PurchaseItem... items) {
        Purchase p = new Purchase();
        p.setSupplierId(supplier.getSupplierId());
        p.setSupplierInvoiceNo("SINV-" + request);
        p.setDiscountAmount(d(invoiceDiscount));
        p.setItems(new ArrayList<>(List.of(items)));
        p.setRequestId(id(request));
        return p;
    }

    private static String describe(Purchase p) {
        return "purchase " + p.getPurchaseNo() + " " + p.getStatus() + " total=" + p.getTotalAmount() + " paid="
                + p.getPaidAmount();
    }

    private String purchasesScenario() {
        security.as(Role.STOREKEEPER, storekeeper);
        Purchase cash = purchases.saveAndPost(purchase(201, "2.000", buyLine(tap, "20", "5.500", "1.000"),
                buyLine(valve, "10", "2.800", "0")), PaymentType.CASH);
        Purchase onAccount = purchases.saveAndPost(purchase(202, "0", buyLine(pipe, "100", "1.300", "0")),
                PaymentType.CREDIT);
        return describe(cash) + " | " + describe(onAccount);
    }

    private String duplicatePurchase() throws Exception {
        security.as(Role.STOREKEEPER, storekeeper);
        return unchanged(() -> describe(purchases.saveAndPost(purchase(202, "0", buyLine(pipe, "100", "1.300", "0")),
                PaymentType.CREDIT)));
    }

    // ======================= returns =======================

    private ReturnDocument returnRequest(ReturnKind kind, int originalId, int itemId, String qty, int request) {
        ReturnDocument r = new ReturnDocument();
        r.setKind(kind);
        r.setOriginalId(originalId);
        r.setReason(ReturnReason.DEFECTIVE);
        r.setRefundMethod(PaymentMethod.CASH);
        ReturnLine l = new ReturnLine();
        l.setOriginalItemId(itemId);
        l.setQuantity(d(qty));
        r.setLines(new ArrayList<>(List.of(l)));
        r.setRequestId(id(request));
        return r;
    }

    private static String describe(ReturnDocument r) {
        return r.getKind() + " return " + r.getReturnNo() + " total=" + r.getTotalAmount();
    }

    private Sale saleBySaleRequest(int request) {
        return sales.search(null).stream().filter(s -> id(request).equals(s.getRequestId())).findFirst()
                .flatMap(s -> sales.findById(s.getSaleId())).orElseThrow();
    }

    private String saleReturns() {
        security.as(Role.ADMIN, admin);
        Sale creditSale = saleBySaleRequest(103);
        Sale cashSale = saleBySaleRequest(101);
        ReturnDocument a = returns.create(returnRequest(ReturnKind.SALE, creditSale.getSaleId(),
                creditSale.getItems().get(0).getSaleItemId(), "2", 301));
        ReturnDocument b = returns.create(returnRequest(ReturnKind.SALE, cashSale.getSaleId(),
                cashSale.getItems().get(1).getSaleItemId(), "1.5", 302));
        return describe(a) + " | " + describe(b);
    }

    private String purchaseReturn() {
        security.as(Role.ADMIN, admin);
        Purchase p = purchases.search(null).stream().filter(x -> id(202).equals(x.getRequestId())).findFirst()
                .flatMap(x -> purchases.findById(x.getPurchaseId())).orElseThrow();
        ReturnDocument r = returns.create(returnRequest(ReturnKind.PURCHASE, p.getPurchaseId(),
                p.getItems().get(0).getPurchaseItemId(), "10", 303));
        return describe(r);
    }

    // ======================= finance =======================

    private PartyPayment payment(PartyType party, int partyId, String amount, int request) {
        PartyPayment p = new PartyPayment();
        p.setPartyType(party);
        p.setPartyId(partyId);
        p.setAmount(d(amount));
        p.setPaymentMethod(PaymentMethod.CASH);
        p.setRequestId(id(request));
        return p;
    }

    private String partyPayments() {
        security.as(Role.ACCOUNTANT, accountant);
        PartyPayment in = payments.record(payment(PartyType.CUSTOMER, credit.getCustomerId(), "30.000", 401));
        PartyPayment out = payments.record(payment(PartyType.SUPPLIER, supplier.getSupplierId(), "50.000", 402));
        return "collected " + in.getAmount() + " paid " + out.getAmount() + " customer outstanding "
                + payments.outstanding(PartyType.CUSTOMER, credit.getCustomerId()) + " supplier outstanding "
                + payments.outstanding(PartyType.SUPPLIER, supplier.getSupplierId());
    }

    private String expense() {
        security.as(Role.ACCOUNTANT, accountant);
        Expense e = new Expense();
        e.setCategory(ExpenseCategory.ELECTRICITY);
        e.setDescription("كهرباء المعرض");
        e.setAmount(d("42.125"));
        e.setPaymentMethod(PaymentMethod.CASH);
        e.setRequestId(id(501));
        Expense saved = expenses.create(e);
        return "expense " + saved.getAmount() + " " + saved.getCategory();
    }

    // ======================= inventory =======================

    private String adjustments() throws Exception {
        security.as(Role.STOREKEEPER, storekeeper);
        inventory.adjust(new StockAdjustment(valve.getProductId(), MovementType.ADJUSTMENT_IN, d("4"), "جرد"));
        inventory.adjust(new StockAdjustment(pipe.getProductId(), MovementType.ADJUSTMENT_OUT, d("2.5"), "تالف"));
        String negative = refused(() -> inventory.adjust(new StockAdjustment(valve.getProductId(),
                MovementType.ADJUSTMENT_OUT, d("9999"), "خطأ")).toString());
        return "valve=" + productDao.findById(valve.getProductId()).orElseThrow().getQuantity() + " pipe="
                + productDao.findById(pipe.getProductId()).orElseThrow().getQuantity() + " | negative: " + negative;
    }

    // ======================= quotations =======================

    private String quotationConversion() {
        security.as(Role.ADMIN, admin);
        Quotation q = new Quotation();
        q.setCustomerId(credit.getCustomerId());
        q.setPriceType(SaleType.RETAIL);
        q.setValidUntil(LocalDate.now().plusDays(7));
        q.setDiscountAmount(d("1.000"));
        QuotationItem i = new QuotationItem();
        i.setProductId(valve.getProductId());
        i.setProductName(valve.getNameAr());
        i.setQuantity(d("2"));
        i.setUnitPrice(d("7.000"));
        i.setDiscountAmount(d("0"));
        q.setItems(new ArrayList<>(List.of(i)));
        q.setRequestId(id(601));
        Quotation saved = quotations.save(q);
        quotations.send(saved.getQuotationId());
        quotations.accept(saved.getQuotationId(), "موافقة هاتفية");
        QuotationConversion first = quotations.convert(saved.getQuotationId(), id(602));
        QuotationConversion again = quotations.convert(saved.getQuotationId(), id(603));
        Sale posted = sales.post(first.saleId(), false);
        Quotation after = quotations.findById(saved.getQuotationId()).orElseThrow();
        return "quotation " + after.getQuotationNo() + " " + after.getStatus() + " | first: " + first.saleNo()
                + " existing=" + first.alreadyMade() + " | again: " + again.saleNo() + " existing="
                + again.alreadyMade() + " warnings=" + again.warnings() + " | " + describe(posted);
    }

    // ======================= failures =======================

    private String rollbacks() throws Exception {
        security.as(Role.ADMIN, admin);
        String stock = refused(() -> describe(sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "0", 701,
                line(valve, "500", "7.000", "0")), PaymentType.CASH, false)));

        GoldenDatabase.exec("CREATE TRIGGER dbo.TR_Golden_Fail_Cash ON dbo.Cash_Transactions AFTER INSERT AS "
                + "THROW 50001, 'golden: injected cash failure', 1;");
        String cash;
        try {
            cash = refused(() -> describe(sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "0", 702,
                    line(tap, "1", "10.000", "0"), line(valve, "1", "7.000", "0")), PaymentType.CASH, false)));
        } finally {
            GoldenDatabase.exec("DROP TRIGGER dbo.TR_Golden_Fail_Cash");
        }

        GoldenDatabase.exec("CREATE TRIGGER dbo.TR_Golden_Fail_Ledger ON dbo.Account_Ledger AFTER INSERT AS "
                + "THROW 50002, 'golden: injected ledger failure', 1;");
        String ledgerFault;
        String purchaseFault;
        try {
            ledgerFault = refused(() -> describe(sales.saveAndPost(sale(credit, SaleType.RETAIL, "0", 703,
                    line(tap, "1", "10.000", "0")), PaymentType.CREDIT, true)));
            security.as(Role.STOREKEEPER, storekeeper);
            purchaseFault = refused(() -> describe(purchases.saveAndPost(purchase(704, "0",
                    buyLine(tap, "5", "6.000", "0")), PaymentType.CREDIT)));
        } finally {
            GoldenDatabase.exec("DROP TRIGGER dbo.TR_Golden_Fail_Ledger");
        }
        return "stock: " + stock + " | cash fault: " + cash + " | ledger fault (sale): " + ledgerFault
                + " | ledger fault (purchase): " + purchaseFault;
    }

    // ======================= permissions =======================

    private String permissions() throws Exception {
        List<String> out = new ArrayList<>();
        security.as(Role.CASHIER, cashier);
        out.add("cashier purchase: " + refused(() -> describe(purchases.saveAndPost(purchase(801, "0",
                buyLine(tap, "1", "5.000", "0")), PaymentType.CASH))));
        out.add("cashier expense: " + refused(() -> {
            Expense e = new Expense();
            e.setCategory(ExpenseCategory.OTHER);
            e.setDescription("x");
            e.setAmount(d("1.000"));
            e.setPaymentMethod(PaymentMethod.CASH);
            e.setRequestId(id(802));
            return expenses.create(e).toString();
        }));
        security.as(Role.STOREKEEPER, storekeeper);
        out.add("storekeeper sale: " + refused(() -> describe(sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "0", 803,
                line(tap, "1", "10.000", "0")), PaymentType.CASH, false))));
        out.add("storekeeper customer payment: " + refused(() -> payments.record(payment(PartyType.CUSTOMER,
                credit.getCustomerId(), "1.000", 804)).toString()));
        security.as(Role.ACCOUNTANT, accountant);
        out.add("accountant stock adjustment: " + refused(() -> inventory.adjust(new StockAdjustment(
                tap.getProductId(), MovementType.ADJUSTMENT_IN, d("1"), "x")).toString()));
        security.logout();
        out.add("nobody sale: " + refused(() -> describe(sales.saveAndPost(sale(walkIn, SaleType.RETAIL, "0", 805,
                line(tap, "1", "10.000", "0")), PaymentType.CASH, false))));
        return String.join(" | ", out);
    }
}
