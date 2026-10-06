package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.PaymentDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSource;
import com.almahwar.model.Customer;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.model.Permission;
import com.almahwar.model.Supplier;
import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** Customer / supplier payments on SQL Server; see {@link PaymentService} for the transaction. */
public class PaymentServiceImpl implements PaymentService {

    private final PaymentDao paymentDao;
    private final CustomerDao customerDao;
    private final SupplierDao supplierDao;
    private final AccountLedger accountLedger;
    private final CashTransactionDao cashDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public PaymentServiceImpl(PaymentDao paymentDao, CustomerDao customerDao, SupplierDao supplierDao,
                              AccountLedger accountLedger, CashTransactionDao cashDao, AuditLogDao auditLogDao,
                              SecurityContext security) {
        this.paymentDao = paymentDao;
        this.customerDao = customerDao;
        this.supplierDao = supplierDao;
        this.accountLedger = accountLedger;
        this.cashDao = cashDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    private static Permission permission(PartyType party) {
        return party == PartyType.CUSTOMER ? Permission.CUSTOMER_PAYMENTS : Permission.SUPPLIER_PAYMENTS;
    }

    private static Permission balancePermission(PartyType party) {
        return party == PartyType.CUSTOMER ? Permission.CUSTOMER_BALANCE_VIEW : Permission.SUPPLIER_BALANCE_VIEW;
    }

    // ======================= Reading =======================

    @Override
    public List<PartyPayment> history(PartyType party, int partyId) {
        if (!security.hasPermission(permission(party))) {
            security.requirePermission(balancePermission(party));
        }
        return paymentDao.findByParty(party, partyId);
    }

    @Override
    public BigDecimal outstanding(PartyType party, int partyId) {
        security.requirePermission(permission(party));
        BigDecimal balance = party == PartyType.CUSTOMER
                ? customerDao.findById(partyId).map(Customer::getBalance).orElse(null)
                : supplierDao.findById(partyId).map(Supplier::getBalance).orElse(null);
        if (balance == null) {
            throw new ValidationException(PARTY, party == PartyType.CUSTOMER ? "العميل غير موجود." : "المورد غير موجود.");
        }
        return MoneyUtil.of(balance.max(BigDecimal.ZERO));
    }

    @Override
    public List<com.almahwar.model.OutstandingParty> outstandingParties(PartyType party) {
        security.requirePermission(permission(party));
        return paymentDao.findOutstanding(party);
    }

    @Override
    public String suggestNumber(PartyType party) {
        security.requirePermission(permission(party));
        return paymentDao.nextNumber(null, party);
    }

    // ======================= Recording =======================

    @Override
    public PartyPayment record(PartyPayment p) {
        if (p.getPartyType() == null) {
            throw new IllegalArgumentException("Party type is required");
        }
        PartyType party = p.getPartyType();
        security.requirePermission(permission(party));
        int userId = security.currentUser().getUserId();

        p.setReferenceNo(trimToNull(p.getReferenceNo()));
        p.setNotes(trimToNull(p.getNotes()));
        Validation v = validateFields(p);
        v.throwIfAny();
        p.setAmount(MoneyUtil.of(p.getAmount()));

        Optional<PartyPayment> already = alreadySaved(p);
        if (already.isPresent()) {
            return already.get();
        }
        p.setUserId(userId);
        try {
            BigDecimal after = TransactionManager.inTransaction(con -> post(con, p, userId));
            if (after == null) {
                return alreadySaved(p).orElseThrow();   // a concurrent retry of this request saved it first
            }
            PartyPayment saved = paymentDao.findById(party, p.getPaymentId()).orElseThrow();
            saved.setBalanceAfter(after);
            return saved;
        } catch (RuntimeException e) {
            // rolled back: forget the id / number, so a retry starts clean
            p.setPaymentId(null);
            p.setPaymentNo(null);
            p.setPaymentDate(null);
            if (e instanceof DataAccessException dae && p.getRequestId() != null
                    && dae.violates(party == PartyType.CUSTOMER ? "UX_Customer_Payments_request" : "UX_Supplier_Payments_request")) {
                // the same request was saved a moment ago by a concurrent retry: return that one
                return alreadySaved(p).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /**
     * The whole operation, on the caller's connection.
     *
     * @return the party's balance after the payment, or {@code null} if this request was already saved
     */
    private BigDecimal post(Connection con, PartyPayment p, int userId) {
        PartyType party = p.getPartyType();
        boolean customer = party == PartyType.CUSTOMER;

        // 1) lock the party and check the amount against what is owed now
        String name;
        BigDecimal owed;
        if (customer) {
            Customer c = customerDao.lockForUpdate(con, p.getPartyId())
                    .orElseThrow(() -> new ValidationException(PARTY, "العميل غير موجود."));
            name = c.getName();
            owed = c.getBalance();
        } else {
            Supplier s = supplierDao.lockForUpdate(con, p.getPartyId())
                    .orElseThrow(() -> new ValidationException(PARTY, "المورد غير موجود."));
            name = s.getName();
            owed = s.getBalance();
        }
        // a retry of the same request waited for the lock above: it must not pay a second time
        if (p.getRequestId() != null && paymentDao.findIdByRequest(con, party, p.getRequestId()).isPresent()) {
            return null;
        }
        owed = MoneyUtil.of(owed);
        if (owed.signum() <= 0) {
            throw new ValidationException(AMOUNT, customer
                    ? "لا يوجد مبلغ مستحق على العميل \"" + name + "\" لتحصيله."
                    : "لا يوجد مبلغ مستحق للمورد \"" + name + "\" لسداده.");
        }
        if (p.getAmount().compareTo(owed) > 0) {
            throw new ValidationException(AMOUNT, "المبلغ " + MoneyUtil.format(p.getAmount()) + " أكبر من "
                    + (customer ? "المستحق على العميل " : "المستحق للمورد ") + MoneyUtil.format(owed)
                    + "؛ لا يُسمح بدفع أكثر من المستحق.");
        }

        // 2) the payment itself
        p.setPaymentNo(paymentDao.nextNumber(con, party));
        if (!paymentDao.insert(con, p)) {
            throw new ValidationException(DATE, "لا يمكن تسجيل عملية بتاريخ مستقبلي.");
        }
        String reference = customer ? CashSource.CUSTOMER_PAYMENT.code() : CashSource.SUPPLIER_PAYMENT.code();
        String label = (customer ? "سند قبض " : "سند صرف ") + p.getPaymentNo();

        // 3) the party's account: the only way its balance changes
        LedgerEntry entry = accountLedger.post(con, party, p.getPartyId(), LedgerEntryType.PAYMENT, p.getAmount().negate(),
                reference, p.getPaymentId(), p.getPaymentNo(),
                label + " (" + p.getPaymentMethod().getLabelAr() + ")" + (p.getReferenceNo() == null ? "" : " مرجع " + p.getReferenceNo()),
                userId, p.getPaymentDate());

        // 4) the treasury: IN from a customer, OUT to a supplier, on the same date
        CashMovement cash = new CashMovement();
        cash.setTransactionDate(p.getPaymentDate());
        cash.setDirection(customer ? CashMovement.Direction.IN : CashMovement.Direction.OUT);
        cash.setAmount(p.getAmount());
        cash.setPaymentMethod(p.getPaymentMethod());
        cash.setSource(customer ? CashSource.CUSTOMER_PAYMENT : CashSource.SUPPLIER_PAYMENT);
        cash.setSourceId(p.getPaymentId());
        cash.setDescription((customer ? "تحصيل من العميل " : "سداد للمورد ") + name + " - " + label);
        cash.setReferenceNo(p.getReferenceNo());
        cash.setNotes(p.getNotes());
        cash.setUserId(userId);
        cashDao.insert(con, cash);

        // 5) audit
        auditLogDao.log(con, userId, customer ? AuditLogDao.CUSTOMER_PAYMENT_CREATED : AuditLogDao.SUPPLIER_PAYMENT_CREATED,
                customer ? "Customer_Payments" : "Supplier_Payments", String.valueOf(p.getPaymentId()),
                label + (customer ? " من العميل " : " للمورد ") + name + " بمبلغ " + MoneyUtil.format(p.getAmount())
                        + " (" + p.getPaymentMethod().getLabelAr() + ")، الرصيد بعده " + MoneyUtil.format(entry.getRunningBalance()));
        return MoneyUtil.of(entry.getRunningBalance());
    }

    /** Checks that need no database; package-private for unit tests. */
    static Validation validateFields(PartyPayment p) {
        Validation v = new Validation();
        if (p.getPartyId() == null) {
            v.error(PARTY, p.getPartyType() == PartyType.SUPPLIER ? "اختر المورد." : "اختر العميل.");
        }
        FinanceRules.amount(v, AMOUNT, p.getAmount());
        FinanceRules.method(v, METHOD, p.getPaymentMethod());
        FinanceRules.day(v, DATE, p.getPaymentDay());
        v.maxLength(REFERENCE, p.getReferenceNo(), 50, "رقم المرجع");
        v.maxLength(NOTES, p.getNotes(), 500, "الملاحظات");
        return v;
    }

    private Optional<PartyPayment> alreadySaved(PartyPayment p) {
        if (p.getRequestId() == null) {
            return Optional.empty();
        }
        return paymentDao.findIdByRequest(p.getPartyType(), p.getRequestId())
                .flatMap(id -> paymentDao.findById(p.getPartyType(), id))
                .map(saved -> {
                    BigDecimal balance = p.getPartyType() == PartyType.CUSTOMER
                            ? customerDao.findById(saved.getPartyId()).map(Customer::getBalance).orElse(null)
                            : supplierDao.findById(saved.getPartyId()).map(Supplier::getBalance).orElse(null);
                    saved.setBalanceAfter(balance == null ? null : MoneyUtil.of(balance));
                    return saved;
                });
    }
}
