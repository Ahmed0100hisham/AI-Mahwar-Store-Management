package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.CashFilter;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSource;
import com.almahwar.model.CashSummary;
import com.almahwar.model.Permission;
import com.almahwar.model.User;
import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** The cashbox on SQL Server; see {@link CashboxService}. */
public class CashboxServiceImpl implements CashboxService {

    private final CashTransactionDao cashDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public CashboxServiceImpl(CashTransactionDao cashDao, AuditLogDao auditLogDao, SecurityContext security) {
        this.cashDao = cashDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    @Override
    public CashSummary summary() {
        security.requirePermission(Permission.CASH);
        CashSummary s = cashDao.summary();
        return new CashSummary(s.today(), MoneyUtil.of(s.balance()), MoneyUtil.of(s.todayIn()), MoneyUtil.of(s.todayOut()));
    }

    @Override
    public List<CashMovement> search(CashFilter filter) {
        security.requirePermission(Permission.CASH);
        return cashDao.search(filter == null ? CashFilter.all() : filter, MAX_LIST_ROWS);
    }

    @Override
    public List<User> users() {
        security.requirePermission(Permission.CASH);
        return cashDao.findUsers();
    }

    @Override
    public CashMovement deposit(CashMovement deposit) {
        return record(deposit, CashMovement.Direction.IN);
    }

    @Override
    public CashMovement withdraw(CashMovement withdrawal) {
        return record(withdrawal, CashMovement.Direction.OUT);
    }

    private CashMovement record(CashMovement m, CashMovement.Direction direction) {
        security.requirePermission(Permission.CASH_ADJUST);
        int userId = security.currentUser().getUserId();
        m.setDirection(direction);
        m.setSource(direction == CashMovement.Direction.IN ? CashSource.DEPOSIT : CashSource.WITHDRAWAL);
        m.setSourceId(null);
        m.setTransactionDate(null);   // manual operations are dated by the server: now
        m.setDescription(trimToNull(m.getDescription()));
        m.setReferenceNo(trimToNull(m.getReferenceNo()));
        m.setNotes(trimToNull(m.getNotes()));
        validateFields(m).throwIfAny();
        m.setAmount(MoneyUtil.of(m.getAmount()));
        Optional<CashMovement> already = alreadySaved(m);
        if (already.isPresent()) {
            return already.get();
        }
        m.setUserId(userId);
        try {
            Long id = TransactionManager.inTransaction(con -> post(con, m, userId));
            return cashDao.findById(id).orElseThrow();
        } catch (RuntimeException e) {
            m.setTransactionId(null);
            throw e;
        }
    }

    /** @return the new movement's id, or the id saved earlier by the same request */
    private Long post(Connection con, CashMovement m, int userId) {
        // manual operations are serialised: a withdrawal checks the balance, and a retry finds its first attempt
        cashDao.lockCashbox(con);
        if (m.getRequestId() != null) {
            Optional<Long> existing = cashDao.findIdByRequest(m.getRequestId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        boolean out = m.getDirection() == CashMovement.Direction.OUT;
        if (out) {
            BigDecimal balance = MoneyUtil.of(cashDao.balance(con));
            if (m.getAmount().compareTo(balance) > 0) {
                throw new ValidationException(AMOUNT, "رصيد الخزنة الحالي " + MoneyUtil.format(balance)
                        + " أقل من المبلغ المطلوب سحبه " + MoneyUtil.format(m.getAmount()) + ".");
            }
        }
        cashDao.insert(con, m);
        auditLogDao.log(con, userId, out ? AuditLogDao.CASH_WITHDRAWAL : AuditLogDao.CASH_DEPOSIT, "Cash_Transactions",
                String.valueOf(m.getTransactionId()), (out ? "سحب يدوي " : "إيداع يدوي ") + m.getTransactionNo()
                        + " بمبلغ " + MoneyUtil.format(m.getAmount()) + " (" + m.getPaymentMethod().getLabelAr() + "): "
                        + m.getDescription());
        return m.getTransactionId();
    }

    /** Checks that need no database; package-private for unit tests. */
    static Validation validateFields(CashMovement m) {
        Validation v = new Validation();
        FinanceRules.amount(v, AMOUNT, m.getAmount());
        FinanceRules.method(v, METHOD, m.getPaymentMethod());
        v.required(REASON, m.getDescription(), "أدخل سبب العملية.");
        v.maxLength(REASON, m.getDescription(), 250, "السبب");
        v.maxLength(REFERENCE, m.getReferenceNo(), 50, "رقم المرجع");
        v.maxLength(NOTES, m.getNotes(), 500, "الملاحظات");
        return v;
    }

    private Optional<CashMovement> alreadySaved(CashMovement m) {
        return m.getRequestId() == null ? Optional.empty()
                : cashDao.findIdByRequest(m.getRequestId()).flatMap(cashDao::findById);
    }
}
