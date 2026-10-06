package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UserDao;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PartyType;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.Role;
import com.almahwar.model.Supplier;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Customers, suppliers and the account ledger against a real SQL Server database.
 * Enable with {@code -Ddb.it=true} (see DaoIntegrationTest). Rows created here are removed.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerSupplierIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final AccountLedgerDao ledgerDao = new AccountLedgerDao();
    private final AccountLedger ledger = new AccountLedger(ledgerDao, customerDao, supplierDao);
    private final TestSecurity security = new TestSecurity();
    private final CustomerService customers = new CustomerServiceImpl(customerDao, ledger, new AuditLogDao(), security);
    private final SupplierService suppliers = new SupplierServiceImpl(supplierDao, ledger, new AuditLogDao(), security);

    private final List<Integer> customerIds = new ArrayList<>();
    private final List<Integer> supplierIds = new ArrayList<>();
    private int userId;
    private int counter;

    private static final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }
    }

    private final Sql sql = new Sql();

    @BeforeAll
    void createUser() {
        User u = new User();
        u.setUsername("acc_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("محاسب اختبار");
        u.setRoleId(new RoleDao().findByCode(Role.ACCOUNTANT).orElseThrow().getRoleId());
        userId = new UserDao().insert(u);
    }

    @BeforeEach
    void loginAsAdmin() {
        security.admin(userId);
    }

    @AfterAll
    void cleanUp() {
        for (Integer id : customerIds) {
            ledgerDao.deleteForParty(PartyType.CUSTOMER, id);
            sql.exec("DELETE FROM dbo.Customers WHERE customer_id = ?", id);
        }
        for (Integer id : supplierIds) {
            ledgerDao.deleteForParty(PartyType.SUPPLIER, id);
            sql.exec("DELETE FROM dbo.Suppliers WHERE supplier_id = ?", id);
        }
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id = ?", userId);
        sql.exec("DELETE FROM dbo.Users WHERE user_id = ?", userId);
    }

    private Customer newCustomer(String code) {
        Customer c = new Customer();
        c.setCustomerCode(code == null ? null : code + "-" + suffix + "-" + (++counter));
        c.setName("مؤسسة اختبار " + suffix + " " + (++counter));
        c.setPhone("9988 " + String.format(java.util.Locale.ROOT, "%04d", counter));
        c.setArea("حولي");
        c.setCreditLimit(new BigDecimal("500"));
        return c;
    }

    private Customer createCustomer(Customer c, String opening) {
        Customer saved = customers.create(c, opening == null ? null : new BigDecimal(opening));
        customerIds.add(saved.getCustomerId());
        return saved;
    }

    private Supplier newSupplier(String code) {
        Supplier s = new Supplier();
        s.setSupplierCode(code == null ? null : code + "-" + suffix + "-" + (++counter));
        s.setName("مورد اختبار " + suffix + " " + (++counter));
        s.setContactPerson("أبو محمد");
        s.setPhone("+971 50 123 " + String.format(java.util.Locale.ROOT, "%04d", counter));
        s.setCountry("الإمارات");
        s.setArea("دبي");
        return s;
    }

    private Supplier createSupplier(Supplier s, String opening) {
        Supplier saved = suppliers.create(s, opening == null ? null : new BigDecimal(opening));
        supplierIds.add(saved.getSupplierId());
        return saved;
    }

    private void assertLedgerMatches(PartyType party, int id) {
        BigDecimal cached = party == PartyType.CUSTOMER
                ? customerDao.findById(id).orElseThrow().getBalance()
                : supplierDao.findById(id).orElseThrow().getBalance();
        assertEquals(0, cached.compareTo(ledger.rebuiltBalance(party, id)), "cached balance must equal the ledger");
        assertFalse(ledger.balanceMismatches(party).contains(id));
    }

    /** Simulates what the future sales / payments modules will post, through the same ledger. */
    private LedgerEntry post(PartyType party, int id, LedgerEntryType type, String amount, String ref, String text) {
        return TransactionManager.inTransaction(con ->
                ledger.post(con, party, id, type, new BigDecimal(amount), type.name(), 1, ref, text, userId));
    }

    // ---------- Customers ----------

    @Test
    void addCustomerWithAutomaticCodeAndNormalizedPhone() {
        Customer saved = createCustomer(newCustomer(null), null);
        assertTrue(saved.getCustomerCode().matches("C-\\d{4,}"), saved.getCustomerCode());
        assertTrue(saved.getPhone().matches("9988\\d{4}"), "stored without spaces: " + saved.getPhone());
        assertEquals("حولي", saved.getArea());
        assertEquals(new BigDecimal("0.000"), saved.getBalance());
        assertEquals(new BigDecimal("500.000"), saved.getCreditLimit());
        assertTrue(ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, saved.getCustomerId(), null, null).isEmpty(),
                "no opening balance, no ledger entry");
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id = ? AND table_name = 'Customers' "
                + "AND action = 'INSERT' AND record_id = ?", userId, String.valueOf(saved.getCustomerId())));
        // searching by a differently formatted phone finds it
        String spaced = saved.getPhone().substring(0, 4) + " " + saved.getPhone().substring(4);
        assertTrue(customers.search(PartyFilter.search(spaced)).stream()
                .anyMatch(c -> c.getCustomerId().equals(saved.getCustomerId())));
    }

    @Test
    void editCustomerKeepsBalanceAndOpeningBalance() {
        Customer saved = createCustomer(newCustomer("ED"), "120");
        saved.setName("اسم معدل " + suffix);
        saved.setPhone("+965 6655 4433");
        saved.setCreditLimit(new BigDecimal("900"));
        saved.setBalance(new BigDecimal("9999"));          // ignored
        saved.setOpeningBalance(new BigDecimal("9999"));   // ignored
        Customer updated = customers.update(saved);
        assertEquals("اسم معدل " + suffix, updated.getName());
        assertEquals("66554433", updated.getPhone());
        assertEquals(new BigDecimal("900.000"), updated.getCreditLimit());
        assertEquals(new BigDecimal("120.000"), updated.getBalance());
        assertEquals(new BigDecimal("120.000"), updated.getOpeningBalance());
        assertLedgerMatches(PartyType.CUSTOMER, updated.getCustomerId());
    }

    @Test
    void duplicateCustomerCodeIsRejected() {
        Customer first = createCustomer(newCustomer("DUP"), null);
        Customer again = newCustomer(null);
        again.setCustomerCode(first.getCustomerCode().toLowerCase());
        ValidationException e = assertThrows(ValidationException.class, () -> createCustomer(again, null));
        assertTrue(e.errorFor(CustomerService.CODE).contains("مستخدم لعميل آخر"));

        Customer other = createCustomer(newCustomer("DUP2"), null);
        other.setCustomerCode(first.getCustomerCode());
        assertThrows(ValidationException.class, () -> customers.update(other));
    }

    @Test
    void customerOpeningBalanceIsALedgerEntry() {
        Customer saved = createCustomer(newCustomer("OPN"), "250.500");
        assertEquals(new BigDecimal("250.500"), saved.getBalance());
        assertEquals(new BigDecimal("250.500"), saved.getOpeningBalance());

        List<LedgerEntry> entries = ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, saved.getCustomerId(), null, null);
        assertEquals(1, entries.size());
        LedgerEntry e = entries.get(0);
        assertEquals(LedgerEntryType.OPENING_BALANCE, e.getEntryType());
        assertEquals(new BigDecimal("250.500"), e.getDebit(), "a customer's opening debt is a debit");
        assertEquals(new BigDecimal("0.000"), e.getCredit());
        assertEquals("CUSTOMER", e.getReferenceType());
        assertEquals(saved.getCustomerId(), e.getReferenceId());
        assertEquals(saved.getCustomerCode(), e.getReferenceNo());
        assertEquals("رصيد افتتاحي", e.getDescription());
        assertEquals(userId, e.getUserId());
        assertNotNull(e.getEntryDate());
        assertLedgerMatches(PartyType.CUSTOMER, saved.getCustomerId());
    }

    @Test
    void customerInCreditOpeningIsACredit() {
        Customer saved = createCustomer(newCustomer("CRD"), "-40");
        assertEquals(new BigDecimal("-40.000"), saved.getBalance());
        LedgerEntry e = ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, saved.getCustomerId(), null, null).get(0);
        assertEquals(new BigDecimal("40.000"), e.getCredit());
        assertEquals(new BigDecimal("0.000"), e.getDebit());
    }

    // ---------- Statements & running balance ----------

    @Test
    void customerStatementRunningBalance() {
        Customer c = createCustomer(newCustomer("ST"), "100");
        int id = c.getCustomerId();
        post(PartyType.CUSTOMER, id, LedgerEntryType.SALE, "250.750", "INV-1", "فاتورة بيع");
        post(PartyType.CUSTOMER, id, LedgerEntryType.PAYMENT, "-150", "RCV-1", "سند قبض");
        post(PartyType.CUSTOMER, id, LedgerEntryType.SALE_RETURN, "-20.250", "RET-1", "مرتجع");

        AccountStatement s = customers.statement(id, null, null, null);
        assertEquals(4, s.entries().size());
        List<String> running = s.entries().stream().map(e -> e.getRunningBalance().toPlainString()).toList();
        assertEquals(List.of("100.000", "350.750", "200.750", "180.500"), running);
        assertEquals(new BigDecimal("350.750"), s.totalDebit());
        assertEquals(new BigDecimal("170.250"), s.totalCredit());
        assertEquals(new BigDecimal("0.000"), s.openingBalance());
        assertEquals(new BigDecimal("180.500"), s.closingBalance());
        assertEquals(new BigDecimal("180.500"), customerDao.findById(id).orElseThrow().getBalance());
        assertLedgerMatches(PartyType.CUSTOMER, id);

        // search hides rows but every shown balance stays the real one
        AccountStatement found = customers.statement(id, null, null, "سند قبض");
        assertTrue(found.filtered());
        assertEquals(1, found.entries().size());
        assertEquals(new BigDecimal("200.750"), found.entries().get(0).getRunningBalance());
        assertEquals(new BigDecimal("180.500"), found.closingBalance());

        // a period after all entries: everything is brought forward
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        AccountStatement later = customers.statement(id, tomorrow, tomorrow.plusDays(5), null);
        assertTrue(later.entries().isEmpty());
        assertEquals(new BigDecimal("180.500"), later.openingBalance());
        assertEquals(new BigDecimal("180.500"), later.closingBalance());

        assertThrows(ValidationException.class, () -> customers.statement(id, tomorrow, LocalDate.now(), null));
    }

    @Test
    void creditLimitCalculation() {
        Customer c = createCustomer(newCustomer("CL"), "450");   // limit 500
        CreditStatus s = customers.creditStatus(c.getCustomerId());
        assertEquals(new BigDecimal("500.000"), s.creditLimit());
        assertEquals(new BigDecimal("450.000"), s.currentDebt());
        assertEquals(new BigDecimal("50.000"), s.availableCredit());
        assertFalse(s.overLimit());
        assertTrue(customers.checkCredit(c.getCustomerId(), new BigDecimal("50")).allowed());
        assertFalse(customers.checkCredit(c.getCustomerId(), new BigDecimal("50.001")).allowed());

        post(PartyType.CUSTOMER, c.getCustomerId(), LedgerEntryType.SALE, "100", "INV-2", "فاتورة");
        CreditStatus over = customers.creditStatus(c.getCustomerId());
        assertTrue(over.overLimit());
        assertEquals(new BigDecimal("0.000"), over.availableCredit());
        assertTrue(customers.search(new PartyFilter(null, ActiveStatus.ALL, false, true)).stream()
                .anyMatch(x -> x.getCustomerId().equals(c.getCustomerId())), "over-limit filter");
        assertTrue(customers.search(new PartyFilter(null, ActiveStatus.ALL, true, false)).stream()
                .anyMatch(x -> x.getCustomerId().equals(c.getCustomerId())), "with-balance filter");
    }

    // ---------- Suppliers ----------

    @Test
    void addAndEditSupplier() {
        Supplier saved = createSupplier(newSupplier(null), null);
        assertTrue(saved.getSupplierCode().matches("S-\\d{4,}"), saved.getSupplierCode());
        assertTrue(saved.getPhone().startsWith("+97150123"), saved.getPhone());
        assertEquals("دبي", saved.getArea());

        saved.setName("مورد معدل " + suffix);
        saved.setArea("الشويخ");
        saved.setBalance(new BigDecimal("777"));   // ignored
        Supplier updated = suppliers.update(saved);
        assertEquals("مورد معدل " + suffix, updated.getName());
        assertEquals("الشويخ", updated.getArea());
        assertEquals(new BigDecimal("0.000"), updated.getBalance());
    }

    @Test
    void duplicateSupplierCodeIsRejected() {
        Supplier first = createSupplier(newSupplier("SD"), null);
        Supplier again = newSupplier(null);
        again.setSupplierCode(first.getSupplierCode());
        ValidationException e = assertThrows(ValidationException.class, () -> createSupplier(again, null));
        assertTrue(e.errorFor(SupplierService.CODE).contains("مستخدم لمورد آخر"));
    }

    @Test
    void supplierOpeningBalanceAndStatement() {
        Supplier s = createSupplier(newSupplier("SO"), "1500");
        int id = s.getSupplierId();
        assertEquals(new BigDecimal("1500.000"), s.getBalance());
        LedgerEntry opening = ledgerDao.findWithRunningBalance(PartyType.SUPPLIER, id, null, null).get(0);
        assertEquals(new BigDecimal("1500.000"), opening.getCredit(), "what we owe a supplier is a credit");

        post(PartyType.SUPPLIER, id, LedgerEntryType.PURCHASE, "800", "PUR-1", "فاتورة مشتريات");
        post(PartyType.SUPPLIER, id, LedgerEntryType.PAYMENT, "-1000", "PAY-1", "دفعة");
        post(PartyType.SUPPLIER, id, LedgerEntryType.PURCHASE_RETURN, "-50.500", "PRT-1", "مرتجع");

        AccountStatement st = suppliers.statement(id, null, null, null);
        assertEquals(List.of("1500.000", "2300.000", "1300.000", "1249.500"),
                st.entries().stream().map(e -> e.getRunningBalance().toPlainString()).toList());
        assertEquals(new BigDecimal("1050.500"), st.totalDebit());
        assertEquals(new BigDecimal("2300.000"), st.totalCredit());
        assertEquals(new BigDecimal("1249.500"), st.closingBalance());
        assertLedgerMatches(PartyType.SUPPLIER, id);
    }

    // ---------- Active / inactive ----------

    @Test
    void deactivatedPartiesAreKeptButCannotBeUsed() {
        Customer c = createCustomer(newCustomer("IN"), "10");
        customers.setActive(c.getCustomerId(), false);
        ValidationException e = assertThrows(ValidationException.class, () -> customers.requireActive(c.getCustomerId()));
        assertTrue(e.getMessage().contains("غير نشط"));
        assertFalse(customers.checkCredit(c.getCustomerId(), BigDecimal.ONE).allowed());
        assertFalse(customers.search(new PartyFilter(c.getCustomerCode(), ActiveStatus.ACTIVE, false, false)).stream()
                .anyMatch(x -> x.getCustomerId().equals(c.getCustomerId())));
        assertTrue(customers.search(new PartyFilter(c.getCustomerCode(), ActiveStatus.INACTIVE, false, false)).stream()
                .anyMatch(x -> x.getCustomerId().equals(c.getCustomerId())));
        assertEquals(1, customers.statement(c.getCustomerId(), null, null, null).entries().size(), "history kept");
        customers.setActive(c.getCustomerId(), true);
        assertEquals(c.getCustomerId(), customers.requireActive(c.getCustomerId()).getCustomerId());

        Supplier s = createSupplier(newSupplier("SIN"), null);
        suppliers.setActive(s.getSupplierId(), false);
        assertThrows(ValidationException.class, () -> suppliers.requireActive(s.getSupplierId()));
        suppliers.setActive(s.getSupplierId(), true);
        assertTrue(suppliers.requireActive(s.getSupplierId()).isActive());
    }

    @Test
    void walkInCashCustomerCannotBeDeactivated() {
        int cashId = customerDao.findCashCustomer().orElseThrow().getCustomerId();
        assertThrows(ValidationException.class, () -> customers.setActive(cashId, false));
        assertTrue(customerDao.findById(cashId).orElseThrow().isActive());
    }

    // ---------- Phones ----------

    @Test
    void samePhoneIsAWarningNotAnError() {
        Customer a = createCustomer(newCustomer("PH"), null);
        Customer b = newCustomer("PH");
        b.setPhone(a.getPhone());
        Customer saved = createCustomer(b, null);   // allowed
        List<Customer> same = customers.findSamePhone("+965 " + a.getPhone(), saved.getCustomerId());
        assertTrue(same.stream().anyMatch(x -> x.getCustomerId().equals(a.getCustomerId())));
        assertTrue(customers.findSamePhone("123", null).isEmpty(), "invalid numbers are not looked up");
    }

    // ---------- Permissions ----------

    @Test
    void cashierSeesCustomersWithoutBalances() {
        Customer c = createCustomer(newCustomer("PRM"), "75");
        security.as(Role.CASHIER, userId);
        Customer seen = customers.findById(c.getCustomerId()).orElseThrow();
        assertNull(seen.getBalance());
        assertNull(seen.getCreditLimit());
        assertNull(seen.getOpeningBalance());
        assertEquals(c.getName(), seen.getName());
        assertThrows(AccessDeniedException.class, () -> customers.statement(c.getCustomerId(), null, null, null));

        // the cashier may add a cash customer and edit contact data, never the limit
        Customer added = createCustomer(newCustomer("CSH"), null);
        security.admin(userId);
        assertEquals(new BigDecimal("0.000"), customers.findById(added.getCustomerId()).orElseThrow().getCreditLimit());
        security.as(Role.CASHIER, userId);
        seen.setName("اسم من الكاشير " + suffix);
        customers.update(seen);
        security.admin(userId);
        Customer after = customers.findById(c.getCustomerId()).orElseThrow();
        assertEquals("اسم من الكاشير " + suffix, after.getName());
        assertEquals(new BigDecimal("500.000"), after.getCreditLimit(), "hidden credit limit unchanged");
        assertEquals(new BigDecimal("75.000"), after.getBalance());
    }

    @Test
    void storekeeperSeesSuppliersWithoutBalances() {
        Supplier s = createSupplier(newSupplier("PRS"), "300");
        security.as(Role.STOREKEEPER, userId);
        Supplier seen = suppliers.findById(s.getSupplierId()).orElseThrow();
        assertNull(seen.getBalance());
        assertThrows(AccessDeniedException.class, () -> suppliers.update(seen));
        assertThrows(AccessDeniedException.class, () -> customers.findById(1));
    }

    // ---------- Rollback ----------

    @Test
    void failureRollsBackPartyLedgerAndBalance() {
        Customer c = newCustomer("RB");
        c.setOpeningBalance(new BigDecimal("60"));
        assertThrows(IllegalStateException.class, () -> TransactionManager.inTransaction(con -> {
            int id = customerDao.insert(con, c);
            ledger.post(con, PartyType.CUSTOMER, id, LedgerEntryType.OPENING_BALANCE, new BigDecimal("60"),
                    "CUSTOMER", id, c.getCustomerCode(), "رصيد افتتاحي", userId);
            throw new IllegalStateException("simulated failure after the ledger entry");
        }));
        assertTrue(customerDao.findByCode(c.getCustomerCode()).isEmpty(), "customer rolled back");
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE reference_no = ?", c.getCustomerCode()));

        // a failing ledger post leaves the existing balance untouched
        Customer existing = createCustomer(newCustomer("RB2"), "10");
        assertThrows(RuntimeException.class, () -> TransactionManager.inTransaction(con -> {
            ledger.post(con, PartyType.CUSTOMER, existing.getCustomerId(), LedgerEntryType.SALE, new BigDecimal("99"),
                    "SALE", 1, "INV-X", "x", userId);
            ledger.post(con, PartyType.CUSTOMER, existing.getCustomerId(), LedgerEntryType.SALE, new BigDecimal("1"),
                    "SALE", 1, "INV-Y", "x", -1);   // unknown user → FK violation
            return null;
        }));
        assertEquals(new BigDecimal("10.000"), customerDao.findById(existing.getCustomerId()).orElseThrow().getBalance());
        assertLedgerMatches(PartyType.CUSTOMER, existing.getCustomerId());

        // the database itself refuses an entry for the wrong party type
        assertThrows(RuntimeException.class, () -> sql.exec("""
                INSERT INTO dbo.Account_Ledger (party_type, customer_id, entry_type, debit, user_id)
                VALUES ('CUSTOMER', ?, 'PURCHASE', 1, ?)""", existing.getCustomerId(), userId));
    }
}
