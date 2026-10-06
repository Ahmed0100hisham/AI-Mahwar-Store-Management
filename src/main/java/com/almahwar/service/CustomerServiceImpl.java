package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PartyType;
import com.almahwar.model.Permission;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** Customers on SQL Server through the DAOs; balances only through {@link AccountLedger}. */
public class CustomerServiceImpl implements CustomerService {

    static final String TABLE = "Customers";

    private final CustomerDao customerDao;
    private final AccountLedger ledger;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public CustomerServiceImpl(CustomerDao customerDao, AccountLedger ledger, AuditLogDao auditLogDao,
                               SecurityContext security) {
        this.customerDao = customerDao;
        this.ledger = ledger;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    // ---------- Reading ----------

    @Override
    public List<Customer> search(PartyFilter filter) {
        security.requirePermission(Permission.CUSTOMERS_VIEW);
        PartyFilter f = filter == null ? PartyFilter.all() : filter;
        if (!canSeeBalances() && (f.withBalance() || f.overCreditOnly())) {
            f = new PartyFilter(f.text(), f.status(), false, false);   // no balance-based filtering either
        }
        return hideBalances(customerDao.search(f));
    }

    @Override
    public Optional<Customer> findById(int customerId) {
        security.requirePermission(Permission.CUSTOMERS_VIEW);
        return customerDao.findById(customerId).map(c -> hideBalances(List.of(c)).get(0));
    }

    @Override
    public String suggestCode() {
        security.requirePermission(Permission.CUSTOMERS_VIEW);
        return customerDao.nextCode(null);
    }

    @Override
    public List<Customer> findSamePhone(String phone, Integer excludeCustomerId) {
        security.requirePermission(Permission.CUSTOMERS_VIEW);
        String normalized = PhoneNumbers.normalize(phone);
        if (normalized == null || PhoneNumbers.validate(normalized) != null) {
            return List.of();
        }
        return hideBalances(customerDao.findByPhone(normalized, excludeCustomerId));
    }

    private boolean canSeeBalances() {
        return security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW);
    }

    private List<Customer> hideBalances(List<Customer> customers) {
        if (!canSeeBalances()) {
            customers.forEach(c -> {
                c.setBalance(null);
                c.setOpeningBalance(null);
                c.setCreditLimit(null);
            });
        }
        return customers;
    }

    // ---------- Writing ----------

    @Override
    public Customer create(Customer customer, BigDecimal openingBalance) {
        security.requirePermission(Permission.CUSTOMERS_EDIT);
        int userId = security.currentUser().getUserId();
        BigDecimal opening = openingBalance == null ? MoneyUtil.ZERO : openingBalance;
        normalize(customer);
        if (!canSeeBalances()) {
            customer.setCreditLimit(MoneyUtil.ZERO);   // cash only until someone with balance access sets a limit
            if (opening.signum() != 0) {
                throw new AccessDeniedException("ليس لديك صلاحية إدخال رصيد افتتاحي للعملاء.");
            }
        }

        Validation v = validate(customer);
        v.amount(OPENING_BALANCE, opening, "الرصيد الافتتاحي");
        v.throwIfAny();   // input first, then the database check
        if (customer.getCustomerCode() != null && customerDao.existsByCode(customer.getCustomerCode(), null)) {
            v.error(CODE, "كود العميل \"" + customer.getCustomerCode() + "\" مستخدم لعميل آخر.");
        }
        v.throwIfAny();

        customer.setOpeningBalance(MoneyUtil.of(opening));
        int id = unique(() -> TransactionManager.inTransaction(con -> {
            if (customer.getCustomerCode() == null) {
                customer.setCustomerCode(customerDao.nextCode(con));
            }
            int customerId = customerDao.insert(con, customer);
            if (opening.signum() != 0) {
                ledger.post(con, PartyType.CUSTOMER, customerId, LedgerEntryType.OPENING_BALANCE, opening,
                        "CUSTOMER", customerId, customer.getCustomerCode(), "رصيد افتتاحي", userId);
            }
            auditLogDao.log(con, userId, AuditLogDao.INSERT, TABLE, String.valueOf(customerId),
                    "إضافة عميل " + customer.getCustomerCode() + " - " + customer.getName()
                            + (opening.signum() != 0 ? " برصيد افتتاحي " + MoneyUtil.format(opening) : ""));
            return customerId;
        }));
        return findById(id).orElseThrow();
    }

    @Override
    public Customer update(Customer customer) {
        security.requirePermission(Permission.CUSTOMERS_EDIT);
        if (customer.getCustomerId() == null) {
            throw new IllegalArgumentException("update() needs a saved customer; use create()");
        }
        int userId = security.currentUser().getUserId();
        Customer existing = customerDao.findById(customer.getCustomerId())
                .orElseThrow(() -> new ValidationException("customerId", "العميل غير موجود."));
        normalize(customer);
        if (!canSeeBalances()) {
            customer.setCreditLimit(existing.getCreditLimit());   // cannot see it, so cannot change it
        }
        if (existing.isCashCustomer()) {
            customer.setCustomerCode(existing.getCustomerCode());   // the walk-in customer's code is fixed
            customer.setActive(true);
        }

        Validation v = validate(customer);
        if (customer.getCustomerCode() == null) {
            v.error(CODE, "كود العميل مطلوب.");
        } else if (customerDao.existsByCode(customer.getCustomerCode(), customer.getCustomerId())) {
            v.error(CODE, "كود العميل \"" + customer.getCustomerCode() + "\" مستخدم لعميل آخر.");
        }
        v.throwIfAny();

        unique(() -> {
            customerDao.update(customer);
            return null;
        });
        auditLogDao.log(userId, AuditLogDao.UPDATE, TABLE, String.valueOf(customer.getCustomerId()),
                "تعديل عميل " + customer.getCustomerCode() + " - " + customer.getName());
        return findById(customer.getCustomerId()).orElseThrow();
    }

    @Override
    public void setActive(int customerId, boolean active) {
        security.requirePermission(Permission.CUSTOMERS_EDIT);
        Customer existing = customerDao.findById(customerId)
                .orElseThrow(() -> new ValidationException("customerId", "العميل غير موجود."));
        if (!active && existing.isCashCustomer()) {
            throw new ValidationException("customerId", "لا يمكن تعطيل العميل النقدي الافتراضي.");
        }
        customerDao.setActive(customerId, active);
        auditLogDao.log(security.currentUser().getUserId(), active ? AuditLogDao.ACTIVATE : AuditLogDao.DEACTIVATE,
                TABLE, String.valueOf(customerId), (active ? "تفعيل" : "تعطيل") + " عميل " + existing.getCustomerCode());
    }

    // ---------- Accounts ----------

    @Override
    public CreditStatus creditStatus(int customerId) {
        security.requirePermission(Permission.CUSTOMER_BALANCE_VIEW);
        Customer c = customerDao.findById(customerId)
                .orElseThrow(() -> new ValidationException("customerId", "العميل غير موجود."));
        return CreditStatus.of(c.getCreditLimit(), c.getBalance());
    }

    @Override
    public CreditDecision checkCredit(int customerId, BigDecimal newCreditAmount) {
        security.requirePermission(Permission.CUSTOMERS_VIEW);
        Customer c = customerDao.findById(customerId)
                .orElseThrow(() -> new ValidationException("customerId", "العميل غير موجود."));
        return decide(c, newCreditAmount);
    }

    /** Pure rule, package-private for unit tests. */
    static CreditDecision decide(Customer c, BigDecimal newCreditAmount) {
        if (!c.isActive()) {
            return new CreditDecision(false, "العميل \"" + c.getName() + "\" غير نشط.");
        }
        if (c.isCashCustomer()) {
            return new CreditDecision(MoneyUtil.of(newCreditAmount).signum() <= 0,
                    "العميل النقدي لا يُباع له بالآجل.");
        }
        CreditStatus s = CreditStatus.of(c.getCreditLimit(), c.getBalance());
        if (s.allows(newCreditAmount)) {
            return new CreditDecision(true, null);
        }
        if (s.creditLimit().signum() == 0) {
            return new CreditDecision(false, "العميل \"" + c.getName() + "\" ليس له حد ائتمان (بيع نقدي فقط).");
        }
        return new CreditDecision(false, "المبلغ الآجل " + MoneyUtil.formatWithCurrency(newCreditAmount)
                + " يتجاوز الائتمان المتاح للعميل \"" + c.getName() + "\".");
    }

    @Override
    public Customer requireActive(int customerId) {
        security.requirePermission(Permission.CUSTOMERS_VIEW);
        Customer c = customerDao.findById(customerId)
                .orElseThrow(() -> new ValidationException("customerId", "العميل غير موجود."));
        if (!c.isActive()) {
            throw new ValidationException("customerId",
                    "العميل \"" + c.getName() + "\" غير نشط؛ أعد تفعيله أولًا لاستخدامه في عملية جديدة.");
        }
        return hideBalances(List.of(c)).get(0);
    }

    @Override
    public AccountStatement statement(int customerId, LocalDate from, LocalDate to, String search) {
        security.requirePermission(Permission.CUSTOMER_BALANCE_VIEW);
        Customer c = customerDao.findById(customerId)
                .orElseThrow(() -> new ValidationException("customerId", "العميل غير موجود."));
        return ledger.statement(PartyType.CUSTOMER, customerId, c.getCustomerCode(), c.getName(), from, to, search);
    }

    @Override
    public List<Integer> balanceMismatches() {
        security.requirePermission(Permission.CUSTOMER_BALANCE_VIEW);
        return ledger.balanceMismatches(PartyType.CUSTOMER);
    }

    // ---------- Validation ----------

    private static void normalize(Customer c) {
        c.setCustomerCode(trimToNull(c.getCustomerCode()));
        c.setName(trimToNull(c.getName()));
        c.setPhone(PhoneNumbers.normalize(c.getPhone()));
        c.setPhone2(PhoneNumbers.normalize(c.getPhone2()));
        c.setEmail(trimToNull(c.getEmail()));
        c.setArea(trimToNull(c.getArea()));
        c.setAddress(trimToNull(c.getAddress()));
        c.setNotes(trimToNull(c.getNotes()));
    }

    /** Checks that need no database; package-private for unit tests. Expects {@link #normalize} first. */
    static Validation validate(Customer c) {
        Validation v = new Validation();
        v.maxLength(CODE, c.getCustomerCode(), 30, "كود العميل");
        v.required(NAME, c.getName(), "اسم العميل مطلوب.");
        v.maxLength(NAME, c.getName(), 150, "اسم العميل");
        PartyRules.phone(v, PHONE, c.getPhone());
        PartyRules.phone(v, PHONE2, c.getPhone2());
        PartyRules.email(v, EMAIL, c.getEmail());
        v.maxLength(AREA, c.getArea(), 100, "المنطقة");
        v.maxLength(ADDRESS, c.getAddress(), 250, "العنوان");
        v.maxLength(NOTES, c.getNotes(), 500, "الملاحظات");
        v.nonNegative(CREDIT_LIMIT, c.getCreditLimit(), "حد الائتمان");
        return v;
    }

    private static <T> T unique(java.util.function.Supplier<T> save) {
        try {
            return save.get();
        } catch (DataAccessException e) {
            if (e.violates("UQ_Customers_code")) {
                throw new ValidationException(CODE, "كود العميل مستخدم لعميل آخر.");
            }
            throw e;
        }
    }
}
