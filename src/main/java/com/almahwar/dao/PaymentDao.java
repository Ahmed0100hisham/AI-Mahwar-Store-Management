package com.almahwar.dao;

import com.almahwar.model.OutstandingParty;
import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@code Customer_Payments} (RCV-…) and {@code Supplier_Payments} (PAY-…). Payments are only
 * inserted, never edited; corrections will be reversing entries.
 */
public class PaymentDao extends BaseDao {

    /** Table, party id column, party table, party code column and number prefix of each side. */
    private record Side(String table, String partyColumn, String partyTable, String codeColumn, String prefix) {
    }

    private static Side side(PartyType party) {
        return party == PartyType.CUSTOMER
                ? new Side("dbo.Customer_Payments", "customer_id", "dbo.Customers", "customer_code", "RCV")
                : new Side("dbo.Supplier_Payments", "supplier_id", "dbo.Suppliers", "supplier_code", "PAY");
    }

    private static String select(PartyType party) {
        Side s = side(party);
        return """
                SELECT p.payment_id, p.payment_no, p.%2$s AS party_id, p.payment_date, p.amount, p.payment_method,
                       p.reference_no, p.notes, p.request_id, p.user_id, p.created_at,
                       c.%4$s AS party_code, c.name AS party_name, u.full_name AS user_name
                FROM %1$s p
                JOIN %3$s c ON c.%2$s = p.%2$s
                JOIN dbo.Users u ON u.user_id = p.user_id
                """.formatted(s.table(), s.partyColumn(), s.partyTable(), s.codeColumn());
    }

    /**
     * Next free number ({@code RCV-000001} / {@code PAY-000001}). Inside a transaction the range stays locked until
     * commit, so two users saving at the same moment cannot get the same number.
     */
    public String nextNumber(Connection con, PartyType party) {
        Side s = side(party);
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(payment_no, 5, 20) AS INT)), 0) + 1
                FROM %s WITH (UPDLOCK, HOLDLOCK)
                WHERE payment_no LIKE N'%s-[0-9][0-9][0-9][0-9][0-9][0-9]'
                """.formatted(s.table(), s.prefix());
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(Locale.ROOT, "%s-%06d", s.prefix(), next);
    }

    /**
     * Inserts the payment and sets its id and stored date. The date is the server's "now" when no day is given,
     * or 12:00 of an earlier day. A day after the server's today is refused here too.
     *
     * @return {@code false} when the day is in the future (nothing inserted)
     */
    public boolean insert(Connection con, PartyPayment p) {
        Side s = side(p.getPartyType());
        Optional<Object[]> saved = queryOne(con, """
                INSERT INTO %1$s (payment_no, %2$s, payment_date, amount, payment_method, reference_no, notes,
                                  request_id, user_id)
                OUTPUT inserted.payment_id, inserted.payment_date, inserted.created_at
                SELECT ?, ?, CASE WHEN ? IS NULL OR CAST(? AS date) >= CAST(SYSDATETIME() AS date) THEN SYSDATETIME()
                                  ELSE DATEADD(HOUR, 12, CAST(CAST(? AS date) AS DATETIME2(0))) END,
                       ?, ?, ?, ?, ?, ?
                WHERE ? IS NULL OR CAST(? AS date) <= CAST(SYSDATETIME() AS date)
                """.formatted(s.table(), s.partyColumn()),
                rs -> new Object[]{rs.getInt(1), getDateTime(rs, "payment_date"), getDateTime(rs, "created_at")},
                p.getPaymentNo(), p.getPartyId(), p.getPaymentDay(), p.getPaymentDay(), p.getPaymentDay(),
                p.getAmount(), p.getPaymentMethod().code(), p.getReferenceNo(), p.getNotes(),
                p.getRequestId() == null ? null : p.getRequestId().toString(), p.getUserId(),
                p.getPaymentDay(), p.getPaymentDay());
        saved.ifPresent(r -> {
            p.setPaymentId((Integer) r[0]);
            p.setPaymentDate((java.time.LocalDateTime) r[1]);
            p.setCreatedAt((java.time.LocalDateTime) r[2]);
        });
        return saved.isPresent();
    }

    public Optional<PartyPayment> findById(PartyType party, int paymentId) {
        return queryOne(select(party) + " WHERE p.payment_id = ?", rs -> map(rs, party), paymentId);
    }

    /** The party's payments, newest first. */
    public List<PartyPayment> findByParty(PartyType party, int partyId) {
        return queryList(select(party) + " WHERE p." + side(party).partyColumn()
                + " = ? ORDER BY p.payment_date DESC, p.payment_id DESC", rs -> map(rs, party), partyId);
    }

    /** The payment created by this request, if any (duplicate-submission check). */
    public Optional<Integer> findIdByRequest(PartyType party, UUID requestId) {
        return queryOne("SELECT payment_id FROM " + side(party).table() + " WHERE request_id = ?",
                rs -> rs.getInt(1), requestId.toString());
    }

    /** Same, inside the caller's transaction (after the party row was locked). */
    public Optional<Integer> findIdByRequest(Connection con, PartyType party, UUID requestId) {
        return queryOne(con, "SELECT payment_id FROM " + side(party).table() + " WHERE request_id = ?",
                rs -> rs.getInt(1), requestId.toString());
    }

    /** Customers who owe us / suppliers we owe (balance &gt; 0), by name, as payment candidates. */
    public List<OutstandingParty> findOutstanding(PartyType party) {
        Side s = side(party);
        String sql = "SELECT " + s.partyColumn() + " AS party_id, " + s.codeColumn() + " AS party_code, name, phone, "
                + "balance, is_active FROM " + s.partyTable() + " WHERE balance > 0 ORDER BY name";
        return queryList(sql, rs -> new OutstandingParty(party, rs.getInt("party_id"), rs.getString("party_code"),
                rs.getString("name"), rs.getString("phone"), rs.getBigDecimal("balance"), rs.getBoolean("is_active")));
    }

    /** Removes a party's payments; for test clean-up only. */
    public void deleteForTests(PartyType party, int partyId) {
        update("DELETE FROM " + side(party).table() + " WHERE " + side(party).partyColumn() + " = ?", partyId);
    }

    private static PartyPayment map(ResultSet rs, PartyType party) throws SQLException {
        PartyPayment p = new PartyPayment();
        p.setPartyType(party);
        p.setPaymentId(rs.getInt("payment_id"));
        p.setPaymentNo(rs.getString("payment_no"));
        p.setPartyId(rs.getInt("party_id"));
        p.setPaymentDate(getDateTime(rs, "payment_date"));
        p.setAmount(rs.getBigDecimal("amount"));
        p.setPaymentMethod(PaymentMethod.fromCode(rs.getString("payment_method")));
        p.setReferenceNo(rs.getString("reference_no"));
        p.setNotes(rs.getString("notes"));
        String request = rs.getString("request_id");
        p.setRequestId(request == null ? null : UUID.fromString(request));
        p.setUserId(rs.getInt("user_id"));
        p.setCreatedAt(getDateTime(rs, "created_at"));
        p.setPartyCode(rs.getString("party_code"));
        p.setPartyName(rs.getString("party_name"));
        p.setUserName(rs.getString("user_name"));
        return p;
    }
}
