package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CashTransactionDao.CashTransaction;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.ExpenseDao;
import com.almahwar.dao.PaymentDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UserDao;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.CashFilter;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSource;
import com.almahwar.model.Customer;
import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.ExpenseFilter;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
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
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Customer / supplier payments, expenses and the cashbox against a real SQL Server: the transactions, the ledger
 * and cash effects, no overpayment, duplicates, concurrency, rollback and permissions. Enable with
 * {@code -Ddb.it=true}. Rows created here are removed.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    private final PaymentDao paymentDao = new PaymentDao();
    private final ExpenseDao expenseDao = new ExpenseDao();
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();
    private final AccountLedgerDao ledgerDao = new AccountLedgerDao();
    private final CashTransactionDao cashDao = new CashTransactionDao();
    private final AccountLedger accountLedger = new AccountLedger(ledgerDao, customerDao, supplierDao);
    private final TestSecurity security = new TestSecurity();
    private final PaymentService payments = payments(accountLedger, cashDao);
    private final ExpenseService expenses = new ExpenseServiceImpl(expenseDao, cashDao, new AuditLogDao(), security);
    private final CashboxService cashbox = new CashboxServiceImpl(cashDao, new AuditLogDao(), security);

    private int userId;
    private final List<Customer> customers = new ArrayList<>();
    private final List<Supplier> suppliers = new ArrayList<>();

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

    private PaymentService payments(AccountLedger ledger, CashTransactionDao cash) {
        return new PaymentServiceImpl(paymentDao, customerDao, supplierDao, ledger, cash, new AuditLogDao(), security);
    }

    @BeforeAll
    void setUp() {
        User u = new User();
        u.setUsername("fin_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("محاسب اختبار");
        u.setRoleId(new RoleDao().findByCode(Role.ACCOUNTANT).orElseThrow().getRoleId());
        userId = new UserDao().insert(u);
    }

    @BeforeEach
    void loginAsAccountant() {
        security.as(Role.ACCOUNTANT, userId);
    }

    @AfterAll
    void cleanUp() {
        for (Customer c : customers) {
            paymentDao.findByParty(PartyType.CUSTOMER, c.getCustomerId())
                    .forEach(p -> cashDao.deleteBySourceForTests("CUSTOMER_PAYMENT", p.getPaymentId()));
            paymentDao.deleteForTests(PartyType.CUSTOMER, c.getCustomerId());
            ledgerDao.deleteForParty(PartyType.CUSTOMER, c.getCustomerId());
            sql.exec("DELETE FROM dbo.Customers WHERE customer_id = ?", c.getCustomerId());
        }
        for (Supplier s : suppliers) {
            paymentDao.findByParty(PartyType.SUPPLIER, s.getSupplierId())
                    .forEach(p -> cashDao.deleteBySourceForTests("SUPPLIER_PAYMENT", p.getPaymentId()));
            paymentDao.deleteForTests(PartyType.SUPPLIER, s.getSupplierId());
            ledgerDao.deleteForParty(PartyType.SUPPLIER, s.getSupplierId());
            sql.exec("DELETE FROM dbo.Suppliers WHERE supplier_id = ?", s.getSupplierId());
        }
        sql.exec("DELETE c FROM dbo.Cash_Transactions c JOIN dbo.Expenses e ON e.expense_id = c.source_id "
                + "WHERE c.source_type = 'EXPENSE' AND e.user_id = ?", userId);
        sql.exec("DELETE FROM dbo.Expenses WHERE user_id = ?", userId);
        cashDao.deleteManualForTests(userId);
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

    /** A customer owing {@code debt} (an opening balance through the ledger, like a real account). */
    private Customer customerOwing(String debt) {
        Customer c = new Customer();
        c.setCustomerCode("FC" + customers.size() + "-" + suffix);
        c.setName("عميل مالية " + customers.size() + " " + suffix);
        c.setCreditLimit(d("1000"));
        c.setCustomerId(customerDao.insert(c));
        if (d(debt).signum() != 0) {
            TransactionManager.inTransaction(con -> accountLedger.post(con, PartyType.CUSTOMER, c.getCustomerId(),
                    LedgerEntryType.OPENING_BALANCE, d(debt), null, null, null, "رصيد افتتاحي", userId));
        }
        customers.add(c);
        return c;
    }

    private Supplier supplierOwed(String owed) {
        Supplier s = new Supplier();
        s.setSupplierCode("FS" + suppliers.size() + "-" + suffix);
        s.setName("مورد مالية " + suppliers.size() + " " + suffix);
        s.setSupplierId(supplierDao.insert(s));
        if (d(owed).signum() != 0) {
            TransactionManager.inTransaction(con -> accountLedger.post(con, PartyType.SUPPLIER, s.getSupplierId(),
                    LedgerEntryType.OPENING_BALANCE, d(owed), null, null, null, "رصيد افتتاحي", userId));
        }
        suppliers.add(s);
        return s;
    }

    private PartyPayment payment(PartyType party, int partyId, String amount, PaymentMethod method) {
        PartyPayment p = new PartyPayment();
        p.setPartyType(party);
        p.setPartyId(partyId);
        p.setAmount(d(amount));
        p.setPaymentMethod(method);
        p.setRequestId(UUID.randomUUID());
        return p;
    }

    private BigDecimal balance(Customer c) {
        return customerDao.findById(c.getCustomerId()).orElseThrow().getBalance();
    }

    private BigDecimal balance(Supplier s) {
        return supplierDao.findById(s.getSupplierId()).orElseThrow().getBalance();
    }

    private BigDecimal stockTotal() {
        return sql.decimal("SELECT COALESCE(SUM(quantity), 0) FROM dbo.Products");
    }

    /** The critical consistency checks: cached balances = ledger, cashbox = Σ IN − Σ OUT. */
    private void assertConsistent() {
        for (Customer c : customers) {
            assertMoney(accountLedger.rebuiltBalance(PartyType.CUSTOMER, c.getCustomerId()).toPlainString(), balance(c),
                    "customer balance = ledger");
        }
        for (Supplier s : suppliers) {
            assertMoney(accountLedger.rebuiltBalance(PartyType.SUPPLIER, s.getSupplierId()).toPlainString(), balance(s),
                    "supplier balance = ledger");
        }
        assertTrue(accountLedger.balanceMismatches(PartyType.CUSTOMER).isEmpty(), "no customer mismatch anywhere");
        assertTrue(accountLedger.balanceMismatches(PartyType.SUPPLIER).isEmpty(), "no supplier mismatch anywhere");
        BigDecimal in = sql.decimal("SELECT COALESCE(SUM(amount), 0) FROM dbo.Cash_Transactions WHERE transaction_type = 'IN'");
        BigDecimal out = sql.decimal("SELECT COALESCE(SUM(amount), 0) FROM dbo.Cash_Transactions WHERE transaction_type = 'OUT'");
        assertMoney(in.subtract(out).toPlainString(), cashbox.summary().balance(), "cashbox = Σ IN − Σ OUT");
    }

    // ======================= Customer payments =======================

    @Test
    void partialThenFullCustomerPayment() {
        Customer c = customerOwing("50");
        BigDecimal cashBefore = cashDao.balance();
        BigDecimal stockBefore = stockTotal();

        PartyPayment first = payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), "20", PaymentMethod.CASH));
        assertTrue(first.getPaymentNo().matches("RCV-\\d{6}"), first.getPaymentNo());
        assertMoney("30", first.getBalanceAfter(), "balance after = 50 − 20");
        assertMoney("30", balance(c), "customer balance");
        assertMoney(cashBefore.add(d("20")).toPlainString(), cashDao.balance(), "cash +20");
        List<CashTransaction> cash = cashDao.findBySource("CUSTOMER_PAYMENT", first.getPaymentId());
        assertEquals(1, cash.size());
        assertEquals("IN", cash.get(0).type());
        assertEquals(PaymentMethod.CASH, cash.get(0).method());

        PartyPayment rest = payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), "30", PaymentMethod.KNET));
        assertMoney("0", rest.getBalanceAfter(), "fully paid");
        assertMoney("0", payments.outstanding(PartyType.CUSTOMER, c.getCustomerId()), "nothing outstanding");
        ValidationException none = assertThrows(ValidationException.class, () -> payments.record(
                payment(PartyType.CUSTOMER, c.getCustomerId(), "1", PaymentMethod.CASH)));
        assertTrue(none.getMessage().contains("لا يوجد مبلغ مستحق"), none.getMessage());

        // statement: OPENING 50, PAYMENT 20 → 30, PAYMENT 30 → 0, in time order
        AccountStatement st = accountLedger.statement(PartyType.CUSTOMER, c.getCustomerId(), c.getCustomerCode(),
                c.getName(), null, null, null);
        assertEquals(List.of("OPENING_BALANCE=50.000", "PAYMENT=30.000", "PAYMENT=0.000"), st.entries().stream()
                .map(e -> e.getEntryType() + "=" + e.getRunningBalance().setScale(3)).toList());
        LedgerEntry pay = st.entries().get(1);
        assertEquals(first.getPaymentNo(), pay.getReferenceNo());
        assertMoney("20", pay.getCredit(), "PAYMENT is a credit");
        assertEquals(2, payments.history(PartyType.CUSTOMER, c.getCustomerId()).size());
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'CUSTOMER_PAYMENT_CREATED' AND record_id = ?",
                String.valueOf(first.getPaymentId())));
        assertMoney(stockBefore.toPlainString(), stockTotal(), "a payment does not change stock");
        assertConsistent();
    }

    @Test
    void customerOverpaymentIsRejected() {
        Customer c = customerOwing("10");
        BigDecimal cashBefore = cashDao.balance();
        PartyPayment p = payment(PartyType.CUSTOMER, c.getCustomerId(), "10.001", PaymentMethod.CASH);
        ValidationException e = assertThrows(ValidationException.class, () -> payments.record(p));
        assertTrue(e.errorFor(PaymentService.AMOUNT).contains("أكبر من المستحق"), e.getMessage());
        assertNull(p.getPaymentId());
        assertMoney("10", balance(c), "unchanged");
        assertMoney(cashBefore.toPlainString(), cashDao.balance(), "no cash");
        assertTrue(payments.history(PartyType.CUSTOMER, c.getCustomerId()).isEmpty());
    }

    @Test
    void customerDoubleSubmitSavesOnce() {
        Customer c = customerOwing("40");
        BigDecimal cashBefore = cashDao.balance();
        PartyPayment p = payment(PartyType.CUSTOMER, c.getCustomerId(), "15", PaymentMethod.CASH);
        PartyPayment first = payments.record(p);
        PartyPayment again = payment(PartyType.CUSTOMER, c.getCustomerId(), "15", PaymentMethod.CASH);
        again.setRequestId(p.getRequestId());
        PartyPayment second = payments.record(again);
        assertEquals(first.getPaymentId(), second.getPaymentId());
        assertMoney("25", balance(c), "paid once");
        assertMoney(cashBefore.add(d("15")).toPlainString(), cashDao.balance(), "cash once");
        assertEquals(1, payments.history(PartyType.CUSTOMER, c.getCustomerId()).size());
    }

    @Test
    void concurrentCustomerPaymentsCannotGoBelowZero() throws Exception {
        Customer c = customerOwing("10");
        BigDecimal cashBefore = cashDao.balance();
        List<Object> results = race(
                () -> payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), "10", PaymentMethod.CASH)),
                () -> payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), "10", PaymentMethod.KNET)));
        assertEquals(1, results.stream().filter(r -> r instanceof PartyPayment).count(), "exactly one succeeds: " + results);
        assertEquals(1, results.stream().filter(r -> r instanceof ValidationException).count(), "the other is refused");
        assertMoney("0", balance(c), "0, never −10");
        assertMoney(cashBefore.add(d("10")).toPlainString(), cashDao.balance(), "cash +10 once");
        assertConsistent();
    }

    @Test
    void concurrentRetriesOfTheSameRequestPayOnce() throws Exception {
        Customer c = customerOwing("10");
        UUID request = UUID.randomUUID();
        List<Object> results = race(() -> {
            PartyPayment p = payment(PartyType.CUSTOMER, c.getCustomerId(), "10", PaymentMethod.CASH);
            p.setRequestId(request);
            return payments.record(p);
        }, () -> {
            PartyPayment p = payment(PartyType.CUSTOMER, c.getCustomerId(), "10", PaymentMethod.CASH);
            p.setRequestId(request);
            return payments.record(p);
        });
        assertTrue(results.stream().allMatch(r -> r instanceof PartyPayment), "both return the payment: " + results);
        assertEquals(((PartyPayment) results.get(0)).getPaymentId(), ((PartyPayment) results.get(1)).getPaymentId());
        assertEquals(1, payments.history(PartyType.CUSTOMER, c.getCustomerId()).size());
        assertMoney("0", balance(c), "paid once");
    }

    @Test
    void backDatedPaymentKeepsItsDayInTheStatementAndFutureIsRefused() {
        Customer c = customerOwing("30");
        PartyPayment p = payment(PartyType.CUSTOMER, c.getCustomerId(), "5", PaymentMethod.BANK_TRANSFER);
        p.setPaymentDay(LocalDate.now().minusDays(3));
        p.setReferenceNo("TRF-778");
        PartyPayment saved = payments.record(p);
        assertEquals(LocalDate.now().minusDays(3), saved.getPaymentDate().toLocalDate());
        LedgerEntry entry = ledgerDao.findWithRunningBalance(PartyType.CUSTOMER, c.getCustomerId(), null, null).stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.PAYMENT).findFirst().orElseThrow();
        assertEquals(saved.getPaymentDate(), entry.getEntryDate(), "ledger entry dated like the payment");
        CashMovement cash = cashbox.search(new CashFilter(saved.getPaymentNo(), null, null, null, null, null, null)).get(0);
        assertEquals(saved.getPaymentDate(), cash.getTransactionDate(), "cash dated like the payment");
        assertEquals("TRF-778", cash.getReferenceNo());
        assertEquals(PaymentMethod.BANK_TRANSFER, cash.getPaymentMethod());

        PartyPayment future = payment(PartyType.CUSTOMER, c.getCustomerId(), "1", PaymentMethod.CASH);
        future.setPaymentDay(LocalDate.now().plusDays(1));
        assertThrows(ValidationException.class, () -> payments.record(future));
    }

    @Test
    void inactiveCustomerCanStillSettleTheirDebt() {
        Customer c = customerOwing("12");
        customerDao.setActive(c.getCustomerId(), false);
        PartyPayment p = payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), "12", PaymentMethod.CASH));
        assertMoney("0", p.getBalanceAfter(), "an old debt of an inactive customer can be collected");
    }

    @Test
    void invalidAmountsAreRefused() {
        Customer c = customerOwing("10");
        for (String bad : new String[]{"0", "-5", "1.0005"}) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), bad, PaymentMethod.CASH)), bad);
            assertTrue(e.getErrors().containsKey(PaymentService.AMOUNT), bad);
        }
        PartyPayment noMethod = payment(PartyType.CUSTOMER, c.getCustomerId(), "1", PaymentMethod.CREDIT);
        assertTrue(assertThrows(ValidationException.class, () -> payments.record(noMethod)).getErrors()
                .containsKey(PaymentService.METHOD), "credit is not a way to pay");
        assertMoney("10", balance(c), "nothing changed");
    }

    // ======================= Supplier payments =======================

    @Test
    void partialAndFullSupplierPayment() {
        Supplier s = supplierOwed("80");
        BigDecimal cashBefore = cashDao.balance();
        BigDecimal stockBefore = stockTotal();
        PartyPayment first = payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "30", PaymentMethod.BANK_TRANSFER));
        assertTrue(first.getPaymentNo().matches("PAY-\\d{6}"), first.getPaymentNo());
        assertMoney("50", balance(s), "80 − 30");
        assertMoney(cashBefore.subtract(d("30")).toPlainString(), cashDao.balance(), "cash −30");
        CashTransaction out = cashDao.findBySource("SUPPLIER_PAYMENT", first.getPaymentId()).get(0);
        assertEquals("OUT", out.type());
        assertEquals(PaymentMethod.BANK_TRANSFER, out.method());
        LedgerEntry entry = ledgerDao.findWithRunningBalance(PartyType.SUPPLIER, s.getSupplierId(), null, null).get(1);
        assertEquals(LedgerEntryType.PAYMENT, entry.getEntryType());
        assertMoney("30", entry.getDebit(), "a supplier PAYMENT is a debit");
        assertMoney("50", entry.getRunningBalance(), "running balance");

        payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "50", PaymentMethod.CASH));
        assertMoney("0", balance(s), "fully paid");
        assertThrows(ValidationException.class,
                () -> payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "0.001", PaymentMethod.CASH)));
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'SUPPLIER_PAYMENT_CREATED' AND record_id = ?",
                String.valueOf(first.getPaymentId())));
        assertMoney(stockBefore.toPlainString(), stockTotal(), "a supplier payment does not change stock");
        assertConsistent();
    }

    @Test
    void supplierOverpaymentAndDoubleSubmit() {
        Supplier s = supplierOwed("25");
        assertThrows(ValidationException.class,
                () -> payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "25.5", PaymentMethod.CASH)));
        PartyPayment p = payment(PartyType.SUPPLIER, s.getSupplierId(), "10", PaymentMethod.CASH);
        PartyPayment first = payments.record(p);
        PartyPayment copy = payment(PartyType.SUPPLIER, s.getSupplierId(), "10", PaymentMethod.CASH);
        copy.setRequestId(p.getRequestId());
        assertEquals(first.getPaymentId(), payments.record(copy).getPaymentId());
        assertMoney("15", balance(s), "paid once");
        assertEquals(1, cashDao.findBySource("SUPPLIER_PAYMENT", first.getPaymentId()).size());
    }

    @Test
    void concurrentSupplierPaymentsCannotGoBelowZero() throws Exception {
        Supplier s = supplierOwed("10");
        List<Object> results = race(
                () -> payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "10", PaymentMethod.CASH)),
                () -> payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "10", PaymentMethod.CASH)));
        assertEquals(1, results.stream().filter(r -> r instanceof PartyPayment).count(), "exactly one succeeds: " + results);
        assertMoney("0", balance(s), "0, never −10");
        assertConsistent();
    }

    // ======================= Rollback =======================

    @Test
    void cashFailureRollsBackThePayment() {
        Customer c = customerOwing("20");
        long ledgerRows = sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE customer_id = ?", c.getCustomerId());
        PaymentService broken = payments(accountLedger, new CashTransactionDao() {
            @Override
            public long insert(Connection con, CashMovement m) {
                throw new IllegalStateException("simulated cash failure");
            }
        });
        PartyPayment p = payment(PartyType.CUSTOMER, c.getCustomerId(), "5", PaymentMethod.CASH);
        assertThrows(IllegalStateException.class, () -> broken.record(p));
        assertNull(p.getPaymentId(), "the id of the rolled back payment is forgotten");
        assertTrue(payments.history(PartyType.CUSTOMER, c.getCustomerId()).isEmpty(), "no payment row");
        assertEquals(ledgerRows, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger WHERE customer_id = ?", c.getCustomerId()));
        assertMoney("20", balance(c), "balance unchanged");
        assertMoney("20", payments.record(p).getBalanceAfter().add(d("5")), "the same request then succeeds");
    }

    @Test
    void ledgerFailureRollsBackThePayment() {
        Supplier s = supplierOwed("20");
        BigDecimal cashBefore = cashDao.balance();
        AccountLedger failing = new AccountLedger(ledgerDao, customerDao, supplierDao) {
            @Override
            public LedgerEntry post(Connection con, PartyType party, int partyId, LedgerEntryType type, BigDecimal amount,
                                    String referenceType, Integer referenceId, String referenceNo, String description,
                                    int userId, LocalDateTime entryDate) {
                throw new IllegalStateException("simulated ledger failure");
            }
        };
        PaymentService broken = payments(failing, cashDao);
        assertThrows(IllegalStateException.class,
                () -> broken.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "5", PaymentMethod.CASH)));
        assertTrue(payments.history(PartyType.SUPPLIER, s.getSupplierId()).isEmpty(), "no payment row");
        assertMoney("20", balance(s), "balance unchanged");
        assertMoney(cashBefore.toPlainString(), cashDao.balance(), "no cash");
    }

    @Test
    void expenseCashFailureRollsBackTheExpense() {
        ExpenseService broken = new ExpenseServiceImpl(expenseDao, new CashTransactionDao() {
            @Override
            public long insert(Connection con, CashMovement m) {
                throw new IllegalStateException("simulated cash failure");
            }
        }, new AuditLogDao(), security);
        Expense e = expense(ExpenseCategory.WATER, "فاتورة ماء " + suffix, "7.250");
        assertThrows(IllegalStateException.class, () -> broken.create(e));
        assertTrue(expenses.search(new ExpenseFilter("فاتورة ماء " + suffix, null, null, null)).isEmpty(), "no expense row");
    }

    // ======================= Expenses =======================

    private Expense expense(ExpenseCategory category, String description, String amount) {
        Expense e = new Expense();
        e.setCategory(category);
        e.setDescription(description);
        e.setAmount(d(amount));
        e.setPaymentMethod(PaymentMethod.CASH);
        e.setRequestId(UUID.randomUUID());
        return e;
    }

    @Test
    void expenseGoesOutOfTheCashboxOnlyAndOnce() {
        BigDecimal cashBefore = cashDao.balance();
        BigDecimal stockBefore = stockTotal();
        long ledgerBefore = sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger");
        Expense e = expense(ExpenseCategory.ELECTRICITY, "كهرباء المعرض " + suffix, "42.125");
        e.setReferenceNo("MEW-55");
        e.setNotes("شهر سبتمبر");
        Expense saved = expenses.create(e);
        assertTrue(saved.getExpenseNo().matches("EXP-\\d{6}"), saved.getExpenseNo());
        assertEquals(ExpenseCategory.ELECTRICITY, saved.getCategory());
        assertEquals("شهر سبتمبر", saved.getNotes());
        assertMoney(cashBefore.subtract(d("42.125")).toPlainString(), cashDao.balance(), "cash −42.125");
        CashTransaction out = cashDao.findBySource("EXPENSE", saved.getExpenseId()).get(0);
        assertEquals("OUT", out.type());
        assertEquals(ledgerBefore, sql.count("SELECT COUNT(*) FROM dbo.Account_Ledger"), "no party ledger entry");
        assertMoney(stockBefore.toPlainString(), stockTotal(), "an expense does not change stock");
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'EXPENSE_CREATED' AND record_id = ?",
                String.valueOf(saved.getExpenseId())));
        // double submit
        Expense copy = expense(ExpenseCategory.ELECTRICITY, "كهرباء المعرض " + suffix, "42.125");
        copy.setRequestId(e.getRequestId());
        assertEquals(saved.getExpenseId(), expenses.create(copy).getExpenseId());
        assertMoney(cashBefore.subtract(d("42.125")).toPlainString(), cashDao.balance(), "cash once");
        // filters
        assertEquals(1, expenses.search(new ExpenseFilter(suffix, null, null, ExpenseCategory.ELECTRICITY)).size());
        assertTrue(expenses.search(new ExpenseFilter(suffix, null, null, ExpenseCategory.RENT)).isEmpty());
        assertMoney("42.125", expenses.total(new ExpenseFilter(suffix, null, null, null)), "total");
        // invalid
        assertThrows(ValidationException.class, () -> expenses.create(expense(ExpenseCategory.OTHER, " ", "1")));
        assertThrows(ValidationException.class, () -> expenses.create(expense(ExpenseCategory.OTHER, "x", "0")));
        assertConsistent();
    }

    // ======================= Cashbox =======================

    private CashMovement manual(String amount, String reason) {
        CashMovement m = new CashMovement();
        m.setAmount(d(amount));
        m.setPaymentMethod(PaymentMethod.CASH);
        m.setDescription(reason);
        m.setRequestId(UUID.randomUUID());
        return m;
    }

    @Test
    void depositAndWithdrawal() {
        BigDecimal before = cashbox.summary().balance();
        BigDecimal todayInBefore = cashbox.summary().todayIn();
        CashMovement dep = manual("100", "إيداع رأس مال " + suffix);
        dep.setReferenceNo("DEP-1");
        CashMovement savedDep = cashbox.deposit(dep);
        assertEquals(CashSource.DEPOSIT, savedDep.getSource());
        assertTrue(savedDep.getTransactionNo().startsWith("TRX-"));
        assertMoney(before.add(d("100")).toPlainString(), cashbox.summary().balance(), "deposit +100");
        assertMoney(todayInBefore.add(d("100")).toPlainString(), cashbox.summary().todayIn(), "today's IN");
        // double submit
        CashMovement again = manual("100", "إيداع رأس مال " + suffix);
        again.setRequestId(dep.getRequestId());
        assertEquals(savedDep.getTransactionId(), cashbox.deposit(again).getTransactionId());
        assertMoney(before.add(d("100")).toPlainString(), cashbox.summary().balance(), "deposited once");

        CashMovement w = cashbox.withdraw(manual("40", "سحب للبنك " + suffix));
        assertEquals(CashMovement.Direction.OUT, w.getDirection());
        assertMoney(before.add(d("60")).toPlainString(), cashbox.summary().balance(), "−40");
        BigDecimal all = cashbox.summary().balance();
        ValidationException tooMuch = assertThrows(ValidationException.class,
                () -> cashbox.withdraw(manual(all.add(d("0.001")).toPlainString(), "أكثر من الرصيد")));
        assertTrue(tooMuch.getMessage().contains("أقل من المبلغ المطلوب"), tooMuch.getMessage());
        assertThrows(ValidationException.class, () -> cashbox.deposit(manual("5", "  ")), "a reason is required");
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'CASH_DEPOSIT' AND record_id = ?",
                String.valueOf(savedDep.getTransactionId())));
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = 'CASH_WITHDRAWAL' AND record_id = ?",
                String.valueOf(w.getTransactionId())));
        List<CashMovement> found = cashbox.search(new CashFilter("سحب للبنك " + suffix, null, null, CashMovement.Direction.OUT,
                CashSource.WITHDRAWAL, PaymentMethod.CASH, userId));
        assertEquals(1, found.size());
        assertConsistent();
    }

    @Test
    void concurrentWithdrawalsCannotSpendTheSameBalance() throws Exception {
        cashbox.deposit(manual("50", "تجهيز اختبار " + suffix));
        BigDecimal all = cashbox.summary().balance();
        List<Object> results = race(() -> cashbox.withdraw(manual(all.toPlainString(), "سحب أ " + suffix)),
                () -> cashbox.withdraw(manual(all.toPlainString(), "سحب ب " + suffix)));
        assertEquals(1, results.stream().filter(r -> r instanceof CashMovement).count(), "exactly one succeeds: " + results);
        assertMoney("0", cashbox.summary().balance(), "the balance was withdrawn once");
        // put it back so the shared test database keeps its cash
        cashbox.deposit(manual(all.toPlainString(), "إرجاع بعد الاختبار " + suffix));
        assertConsistent();
    }

    @Test
    void concurrentDepositsBothCount() throws Exception {
        BigDecimal before = cashbox.summary().balance();
        List<Object> results = race(() -> cashbox.deposit(manual("3", "إيداع أ " + suffix)),
                () -> cashbox.deposit(manual("4", "إيداع ب " + suffix)));
        assertTrue(results.stream().allMatch(r -> r instanceof CashMovement), String.valueOf(results));
        assertMoney(before.add(d("7")).toPlainString(), cashbox.summary().balance(), "+7");
    }

    // ======================= Permissions =======================

    @Test
    void permissionsAreCheckedByTheServices() {
        Customer c = customerOwing("9");
        Supplier s = supplierOwed("9");
        security.as(Role.CASHIER, userId);
        assertNotNull(payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), "1", PaymentMethod.CASH)),
                "a cashier collects from customers");
        assertThrows(AccessDeniedException.class,
                () -> payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "1", PaymentMethod.CASH)));
        assertThrows(AccessDeniedException.class, () -> expenses.create(expense(ExpenseCategory.OTHER, "x", "1")));
        assertThrows(AccessDeniedException.class, cashbox::summary);
        assertThrows(AccessDeniedException.class, () -> cashbox.deposit(manual("1", "x")));

        security.as(Role.STOREKEEPER, userId);
        assertThrows(AccessDeniedException.class,
                () -> payments.record(payment(PartyType.CUSTOMER, c.getCustomerId(), "1", PaymentMethod.CASH)));
        assertThrows(AccessDeniedException.class, () -> expenses.search(ExpenseFilter.all()));
        assertThrows(AccessDeniedException.class, cashbox::summary);

        security.as(Role.ACCOUNTANT, userId);
        assertNotNull(payments.record(payment(PartyType.SUPPLIER, s.getSupplierId(), "1", PaymentMethod.CASH)));
        assertNotNull(cashbox.summary());
        assertConsistent();
    }

    // ---------- concurrency helper ----------

    /** Runs both at the same moment; each result is the returned value or the thrown error. */
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
