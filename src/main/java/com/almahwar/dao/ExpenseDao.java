package com.almahwar.dao;

import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.ExpenseFilter;
import com.almahwar.model.PaymentMethod;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Data access for {@code Expenses} (EXP-…). Expenses are only inserted, never edited. */
public class ExpenseDao extends BaseDao {

    private static final String SELECT = """
            SELECT e.expense_id, e.expense_no, e.expense_date, e.category, e.description, e.amount, e.payment_method,
                   e.reference_no, e.notes, e.request_id, e.user_id, e.created_at, u.full_name AS user_name
            FROM dbo.Expenses e
            JOIN dbo.Users u ON u.user_id = e.user_id
            """;

    /** Next free number {@code EXP-000001}; the range stays locked until commit inside a transaction. */
    public String nextNumber(Connection con) {
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(expense_no, 5, 20) AS INT)), 0) + 1
                FROM dbo.Expenses WITH (UPDLOCK, HOLDLOCK)
                WHERE expense_no LIKE N'EXP-[0-9][0-9][0-9][0-9][0-9][0-9]'
                """;
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(Locale.ROOT, "EXP-%06d", next);
    }

    /**
     * Inserts the expense and sets its id and stored date (server "now", or 12:00 of an earlier day).
     *
     * @return {@code false} when the day is in the future (nothing inserted)
     */
    public boolean insert(Connection con, Expense e) {
        Optional<Object[]> saved = queryOne(con, """
                INSERT INTO dbo.Expenses (expense_no, expense_date, expense_type, category, amount, payment_method,
                                          reference_no, description, notes, request_id, user_id)
                OUTPUT inserted.expense_id, inserted.expense_date, inserted.created_at
                SELECT ?, CASE WHEN ? IS NULL OR CAST(? AS date) >= CAST(SYSDATETIME() AS date) THEN SYSDATETIME()
                               ELSE DATEADD(HOUR, 12, CAST(CAST(? AS date) AS DATETIME2(0))) END,
                       ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE ? IS NULL OR CAST(? AS date) <= CAST(SYSDATETIME() AS date)
                """, rs -> new Object[]{rs.getInt(1), getDateTime(rs, "expense_date"), getDateTime(rs, "created_at")},
                e.getExpenseNo(), e.getExpenseDay(), e.getExpenseDay(), e.getExpenseDay(),
                e.getCategory().getLabelAr(), e.getCategory().name(), e.getAmount(), e.getPaymentMethod().code(),
                e.getReferenceNo(), e.getDescription(), e.getNotes(),
                e.getRequestId() == null ? null : e.getRequestId().toString(), e.getUserId(),
                e.getExpenseDay(), e.getExpenseDay());
        saved.ifPresent(r -> {
            e.setExpenseId((Integer) r[0]);
            e.setExpenseDate((java.time.LocalDateTime) r[1]);
            e.setCreatedAt((java.time.LocalDateTime) r[2]);
        });
        return saved.isPresent();
    }

    public Optional<Expense> findById(int expenseId) {
        return queryOne(SELECT + " WHERE e.expense_id = ?", ExpenseDao::map, expenseId);
    }

    public Optional<Integer> findIdByRequest(UUID requestId) {
        return queryOne("SELECT expense_id FROM dbo.Expenses WHERE request_id = ?", rs -> rs.getInt(1),
                requestId.toString());
    }

    /** Newest first, at most {@code limit}. */
    public List<Expense> search(ExpenseFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT TOP (?) * FROM (").append(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(limit);
        if (f.text() != null) {
            String like = likeContains(f.text());
            sql.append(" AND (e.expense_no LIKE ? OR e.description LIKE ? OR e.reference_no LIKE ? OR e.notes LIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if (f.from() != null) {
            sql.append(" AND e.expense_date >= ?");
            params.add(f.from().atStartOfDay());
        }
        if (f.to() != null) {
            sql.append(" AND e.expense_date < ?");
            params.add(f.to().plusDays(1).atStartOfDay());
        }
        if (f.category() != null) {
            sql.append(" AND e.category = ?");
            params.add(f.category().name());
        }
        sql.append(") x ORDER BY x.expense_date DESC, x.expense_id DESC");
        return queryList(sql.toString(), ExpenseDao::map, params.toArray());
    }

    /** Σ amount for the same criteria (not limited). */
    public BigDecimal total(ExpenseFilter f) {
        return search(f, Integer.MAX_VALUE).stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Removes an expense; for test clean-up only. */
    public void deleteForTests(int expenseId) {
        update("DELETE FROM dbo.Expenses WHERE expense_id = ?", expenseId);
    }

    private static Expense map(ResultSet rs) throws SQLException {
        Expense e = new Expense();
        e.setExpenseId(rs.getInt("expense_id"));
        e.setExpenseNo(rs.getString("expense_no"));
        e.setExpenseDate(getDateTime(rs, "expense_date"));
        e.setCategory(ExpenseCategory.fromCode(rs.getString("category")));
        e.setDescription(rs.getString("description"));
        e.setAmount(rs.getBigDecimal("amount"));
        e.setPaymentMethod(PaymentMethod.fromCode(rs.getString("payment_method")));
        e.setReferenceNo(rs.getString("reference_no"));
        e.setNotes(rs.getString("notes"));
        String request = rs.getString("request_id");
        e.setRequestId(request == null ? null : UUID.fromString(request));
        e.setUserId(rs.getInt("user_id"));
        e.setCreatedAt(getDateTime(rs, "created_at"));
        e.setUserName(rs.getString("user_name"));
        return e;
    }
}
