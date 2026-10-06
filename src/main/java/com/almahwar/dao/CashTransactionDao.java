package com.almahwar.dao;

import com.almahwar.model.CashFilter;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSource;
import com.almahwar.model.CashSummary;
import com.almahwar.model.PaymentMethod;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes to {@code Cash_Transactions} (the treasury: cash box, KNET, bank, cheques). Money paid or received by a
 * document is recorded here in the same transaction as the document itself. The balance is never stored: it is
 * always Σ IN − Σ OUT of these rows.
 */
public class CashTransactionDao extends BaseDao {

    public static final String IN = "IN";
    public static final String OUT = "OUT";

    /** One cash movement, as read back for checks and statements. */
    public record CashTransaction(long id, String type, BigDecimal amount, PaymentMethod method, String sourceType,
                                  Integer sourceId, String description, int userId) {
    }

    private static final String SELECT = """
            SELECT t.transaction_id, t.transaction_date, t.transaction_type, t.amount, t.payment_method, t.source_type,
                   t.source_id, t.description, t.reference_no, t.notes, t.request_id, t.user_id, u.full_name AS user_name,
                   CASE t.source_type
                       WHEN 'SALE' THEN (SELECT x.invoice_no FROM dbo.Sales x WHERE x.sale_id = t.source_id)
                       WHEN 'PURCHASE' THEN (SELECT x.purchase_no FROM dbo.Purchases x WHERE x.purchase_id = t.source_id)
                       WHEN 'CUSTOMER_PAYMENT' THEN (SELECT x.payment_no FROM dbo.Customer_Payments x WHERE x.payment_id = t.source_id)
                       WHEN 'SUPPLIER_PAYMENT' THEN (SELECT x.payment_no FROM dbo.Supplier_Payments x WHERE x.payment_id = t.source_id)
                       WHEN 'EXPENSE' THEN (SELECT x.expense_no FROM dbo.Expenses x WHERE x.expense_id = t.source_id)
                       WHEN 'SALE_RETURN' THEN (SELECT x.return_no FROM dbo.Sale_Returns x WHERE x.return_id = t.source_id)
                       WHEN 'PURCHASE_RETURN' THEN (SELECT x.return_no FROM dbo.Purchase_Returns x WHERE x.return_id = t.source_id)
                   END AS document_no
            FROM dbo.Cash_Transactions t
            JOIN dbo.Users u ON u.user_id = t.user_id
            """;

    /** @param method must be an immediate method (CASH, KNET, BANK_TRANSFER, CHEQUE, CREDIT_CARD) */
    public long insert(Connection con, String type, BigDecimal amount, PaymentMethod method, String sourceType,
                       Integer sourceId, String description, int userId) {
        return queryOne(con, """
                INSERT INTO dbo.Cash_Transactions (transaction_type, amount, payment_method, source_type, source_id,
                    description, user_id)
                OUTPUT inserted.transaction_id
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, rs -> rs.getLong(1), type, amount, method.code(), sourceType, sourceId, description, userId)
                .orElseThrow(() -> new DataAccessException("Cash transaction was not inserted"));
    }

    /**
     * Inserts a movement with every detail: its date ({@code null} = server now; a payment or expense passes its own
     * stored date so both rows agree), reference, notes and request id. Sets the id and the stored date.
     */
    public long insert(Connection con, CashMovement m) {
        Object[] saved = queryOne(con, """
                INSERT INTO dbo.Cash_Transactions (transaction_date, transaction_type, amount, payment_method,
                    source_type, source_id, description, reference_no, notes, request_id, user_id)
                OUTPUT inserted.transaction_id, inserted.transaction_date
                VALUES (COALESCE(?, SYSDATETIME()), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rs -> new Object[]{rs.getLong(1), getDateTime(rs, "transaction_date")},
                m.getTransactionDate(), m.getDirection().name(), m.getAmount(), m.getPaymentMethod().code(),
                m.getSource().code(), m.getSourceId(), m.getDescription(), m.getReferenceNo(), m.getNotes(),
                m.getRequestId() == null ? null : m.getRequestId().toString(), m.getUserId())
                .orElseThrow(() -> new DataAccessException("Cash transaction was not inserted"));
        m.setTransactionId((Long) saved[0]);
        m.setTransactionDate((java.time.LocalDateTime) saved[1]);
        return m.getTransactionId();
    }

    /**
     * Serialises operations that check the balance before taking money out (manual withdrawals) until the
     * transaction ends: two withdrawals can never both spend the same balance.
     */
    public void lockCashbox(Connection con) {
        // an application lock owned by the transaction: released automatically on COMMIT / ROLLBACK
        update(con, """
                DECLARE @r INT;
                -- with IMPLICIT_TRANSACTIONS (JDBC auto-commit off) EXEC alone does not open the transaction
                -- the lock belongs to; a read does
                SELECT @r = COUNT(*) FROM dbo.Cash_Transactions WHERE 1 = 0;
                EXEC @r = sp_getapplock @Resource = 'AlMahwar.Cashbox', @LockMode = 'Exclusive',
                                        @LockOwner = 'Transaction', @LockTimeout = 30000;
                IF @r < 0 THROW 50001, 'Cashbox lock not granted', 1;
                """);
    }

    public List<CashTransaction> findBySource(String sourceType, int sourceId) {
        return queryList("""
                SELECT transaction_id, transaction_type, amount, payment_method, source_type, source_id, description, user_id
                FROM dbo.Cash_Transactions WHERE source_type = ? AND source_id = ? ORDER BY transaction_id
                """, rs -> new CashTransaction(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                PaymentMethod.fromCode(rs.getString(4)), rs.getString(5), getInteger(rs, "source_id"),
                rs.getString(7), rs.getInt(8)), sourceType, sourceId);
    }

    /** Net cash balance: all IN minus all OUT (same rule as the dashboard card). */
    public BigDecimal balance() {
        return queryOne("SELECT COALESCE(SUM(CASE WHEN transaction_type = 'IN' THEN amount ELSE -amount END), 0) "
                + "FROM dbo.Cash_Transactions", rs -> rs.getBigDecimal(1)).orElse(BigDecimal.ZERO);
    }

    /** The same, read inside the caller's transaction (after {@link #lockCashbox}). */
    public BigDecimal balance(Connection con) {
        return queryOne(con, "SELECT COALESCE(SUM(CASE WHEN transaction_type = 'IN' THEN amount ELSE -amount END), 0) "
                + "FROM dbo.Cash_Transactions", rs -> rs.getBigDecimal(1)).orElse(BigDecimal.ZERO);
    }

    /** Balance and today's IN / OUT (server date). */
    public CashSummary summary() {
        return queryOne("""
                WITH d AS (SELECT CAST(SYSDATETIME() AS date) AS today)
                SELECT d.today,
                  (SELECT COALESCE(SUM(CASE WHEN transaction_type = 'IN' THEN amount ELSE -amount END), 0)
                     FROM dbo.Cash_Transactions) AS balance,
                  (SELECT COALESCE(SUM(amount), 0) FROM dbo.Cash_Transactions WHERE transaction_type = 'IN'
                     AND transaction_date >= d.today AND transaction_date < DATEADD(DAY, 1, d.today)) AS today_in,
                  (SELECT COALESCE(SUM(amount), 0) FROM dbo.Cash_Transactions WHERE transaction_type = 'OUT'
                     AND transaction_date >= d.today AND transaction_date < DATEADD(DAY, 1, d.today)) AS today_out
                FROM d
                """, rs -> new CashSummary(rs.getObject("today", LocalDate.class), rs.getBigDecimal("balance"),
                rs.getBigDecimal("today_in"), rs.getBigDecimal("today_out"))).orElseThrow();
    }

    public Optional<CashMovement> findById(long transactionId) {
        return queryOne(SELECT + " WHERE t.transaction_id = ?", CashTransactionDao::map, transactionId);
    }

    /** The manual operation created by this request, if any (duplicate-submission check). */
    public Optional<Long> findIdByRequest(UUID requestId) {
        return queryOne("SELECT transaction_id FROM dbo.Cash_Transactions WHERE request_id = ?",
                rs -> rs.getLong(1), requestId.toString());
    }

    /** Newest first, at most {@code limit}. */
    public List<CashMovement> search(CashFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT TOP (?) * FROM (").append(SELECT).append(") x WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(limit);
        if (f.text() != null) {
            String like = likeContains(f.text());
            sql.append(" AND (x.description LIKE ? OR x.notes LIKE ? OR x.reference_no LIKE ? OR x.document_no LIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if (f.from() != null) {
            sql.append(" AND x.transaction_date >= ?");
            params.add(f.from().atStartOfDay());
        }
        if (f.to() != null) {
            sql.append(" AND x.transaction_date < ?");
            params.add(f.to().plusDays(1).atStartOfDay());
        }
        if (f.direction() != null) {
            sql.append(" AND x.transaction_type = ?");
            params.add(f.direction().name());
        }
        if (f.source() != null) {
            sql.append(" AND x.source_type = ?");
            params.add(f.source().code());
        }
        if (f.method() != null) {
            sql.append(" AND x.payment_method = ?");
            params.add(f.method().code());
        }
        if (f.userId() != null) {
            sql.append(" AND x.user_id = ?");
            params.add(f.userId());
        }
        sql.append(" ORDER BY x.transaction_date DESC, x.transaction_id DESC");
        return queryList(sql.toString(), CashTransactionDao::map, params.toArray());
    }

    /** Users who have recorded at least one movement (for the user filter). */
    public List<com.almahwar.model.User> findUsers() {
        return queryList("""
                SELECT u.user_id, u.full_name FROM dbo.Users u
                WHERE EXISTS (SELECT 1 FROM dbo.Cash_Transactions t WHERE t.user_id = u.user_id)
                ORDER BY u.full_name
                """, rs -> {
            com.almahwar.model.User u = new com.almahwar.model.User();
            u.setUserId(rs.getInt("user_id"));
            u.setFullName(rs.getString("full_name"));
            return u;
        });
    }

    /** Removes a document's cash movements; for test clean-up only. */
    public void deleteBySourceForTests(String sourceType, int sourceId) {
        update("DELETE FROM dbo.Cash_Transactions WHERE source_type = ? AND source_id = ?", sourceType, sourceId);
    }

    /** Removes a user's manual movements; for test clean-up only. */
    public void deleteManualForTests(int userId) {
        update("DELETE FROM dbo.Cash_Transactions WHERE user_id = ? AND source_type IN ('DEPOSIT', 'WITHDRAWAL')", userId);
    }

    private static CashMovement map(ResultSet rs) throws SQLException {
        CashMovement m = new CashMovement();
        m.setTransactionId(rs.getLong("transaction_id"));
        m.setTransactionDate(getDateTime(rs, "transaction_date"));
        m.setDirection(CashMovement.Direction.valueOf(rs.getString("transaction_type")));
        m.setAmount(rs.getBigDecimal("amount"));
        m.setPaymentMethod(PaymentMethod.fromCode(rs.getString("payment_method")));
        m.setSource(CashSource.valueOf(rs.getString("source_type")));
        m.setSourceId(getInteger(rs, "source_id"));
        m.setDescription(rs.getString("description"));
        m.setReferenceNo(rs.getString("reference_no"));
        m.setNotes(rs.getString("notes"));
        String request = rs.getString("request_id");
        m.setRequestId(request == null ? null : UUID.fromString(request));
        m.setUserId(rs.getInt("user_id"));
        m.setUserName(rs.getString("user_name"));
        m.setDocumentNo(rs.getString("document_no"));
        return m;
    }
}
