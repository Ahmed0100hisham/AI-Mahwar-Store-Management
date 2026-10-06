package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.ExpenseDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSource;
import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseFilter;
import com.almahwar.model.Permission;
import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** Expenses on SQL Server; see {@link ExpenseService}. */
public class ExpenseServiceImpl implements ExpenseService {

    private final ExpenseDao expenseDao;
    private final CashTransactionDao cashDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public ExpenseServiceImpl(ExpenseDao expenseDao, CashTransactionDao cashDao, AuditLogDao auditLogDao,
                              SecurityContext security) {
        this.expenseDao = expenseDao;
        this.cashDao = cashDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    @Override
    public List<Expense> search(ExpenseFilter filter) {
        security.requirePermission(Permission.EXPENSES);
        return expenseDao.search(filter == null ? ExpenseFilter.all() : filter, MAX_LIST_ROWS);
    }

    @Override
    public BigDecimal total(ExpenseFilter filter) {
        security.requirePermission(Permission.EXPENSES);
        return MoneyUtil.of(expenseDao.total(filter == null ? ExpenseFilter.all() : filter));
    }

    @Override
    public String suggestNumber() {
        security.requirePermission(Permission.EXPENSES);
        return expenseDao.nextNumber(null);
    }

    @Override
    public Expense create(Expense e) {
        security.requirePermission(Permission.EXPENSES);
        int userId = security.currentUser().getUserId();
        e.setDescription(trimToNull(e.getDescription()));
        e.setReferenceNo(trimToNull(e.getReferenceNo()));
        e.setNotes(trimToNull(e.getNotes()));
        validateFields(e).throwIfAny();
        e.setAmount(MoneyUtil.of(e.getAmount()));
        Optional<Expense> already = alreadySaved(e);
        if (already.isPresent()) {
            return already.get();
        }
        e.setUserId(userId);
        try {
            Integer id = TransactionManager.inTransaction(con -> post(con, e, userId));
            return expenseDao.findById(id).orElseThrow();
        } catch (RuntimeException ex) {
            e.setExpenseId(null);
            e.setExpenseNo(null);
            e.setExpenseDate(null);
            throw ex;
        }
    }

    /** @return the new expense's id, or the id saved earlier by the same request */
    private Integer post(Connection con, Expense e, int userId) {
        e.setExpenseNo(expenseDao.nextNumber(con));   // locks the number range until commit
        if (e.getRequestId() != null) {
            Optional<Integer> existing = expenseDao.findIdByRequest(e.getRequestId());
            if (existing.isPresent()) {
                return existing.get();   // a concurrent retry of this request saved it first
            }
        }
        if (!expenseDao.insert(con, e)) {
            throw new ValidationException(DATE, "لا يمكن تسجيل مصروف بتاريخ مستقبلي.");
        }
        CashMovement cash = new CashMovement();
        cash.setTransactionDate(e.getExpenseDate());
        cash.setDirection(CashMovement.Direction.OUT);
        cash.setAmount(e.getAmount());
        cash.setPaymentMethod(e.getPaymentMethod());
        cash.setSource(CashSource.EXPENSE);
        cash.setSourceId(e.getExpenseId());
        cash.setDescription("مصروف " + e.getExpenseNo() + " - " + e.getCategory().getLabelAr() + ": " + e.getDescription());
        cash.setReferenceNo(e.getReferenceNo());
        cash.setNotes(e.getNotes());
        cash.setUserId(userId);
        cashDao.insert(con, cash);
        auditLogDao.log(con, userId, AuditLogDao.EXPENSE_CREATED, "Expenses", String.valueOf(e.getExpenseId()),
                "مصروف " + e.getExpenseNo() + " (" + e.getCategory().getLabelAr() + ") بمبلغ "
                        + MoneyUtil.format(e.getAmount()) + " - " + e.getPaymentMethod().getLabelAr());
        return e.getExpenseId();
    }

    /** Checks that need no database; package-private for unit tests. */
    static Validation validateFields(Expense e) {
        Validation v = new Validation();
        if (e.getCategory() == null) {
            v.error(CATEGORY, "اختر نوع المصروف.");
        }
        v.required(DESCRIPTION, e.getDescription(), "أدخل وصف المصروف.");
        v.maxLength(DESCRIPTION, e.getDescription(), 200, "الوصف");
        FinanceRules.amount(v, AMOUNT, e.getAmount());
        FinanceRules.method(v, METHOD, e.getPaymentMethod());
        FinanceRules.day(v, DATE, e.getExpenseDay());
        v.maxLength(REFERENCE, e.getReferenceNo(), 50, "رقم المرجع");
        v.maxLength(NOTES, e.getNotes(), 500, "الملاحظات");
        return v;
    }

    private Optional<Expense> alreadySaved(Expense e) {
        return e.getRequestId() == null ? Optional.empty()
                : expenseDao.findIdByRequest(e.getRequestId()).flatMap(expenseDao::findById);
    }
}
