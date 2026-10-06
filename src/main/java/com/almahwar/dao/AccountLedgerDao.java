package com.almahwar.dao;

import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access for {@code Account_Ledger}: the history behind every customer and supplier balance.
 * <p>
 * Rows are only inserted, never updated or deleted by the application (corrections are new
 * ADJUSTMENT rows), so a statement printed today can always be reproduced.
 */
public class AccountLedgerDao extends BaseDao {

    /** Balance column effect per party, matching {@link PartyType#balanceEffect}. */
    private static String effect(PartyType party) {
        return party == PartyType.CUSTOMER ? "(l.debit - l.credit)" : "(l.credit - l.debit)";
    }

    private static String partyColumn(PartyType party) {
        return party == PartyType.CUSTOMER ? "customer_id" : "supplier_id";
    }

    /** Inserts the entry on the caller's transaction and sets its id and server dates. */
    public long insert(Connection con, LedgerEntry e) {
        LedgerEntry saved = queryOne(con, """
                INSERT INTO dbo.Account_Ledger (party_type, customer_id, supplier_id, entry_date, entry_type, debit, credit,
                    reference_type, reference_id, reference_no, description, user_id)
                OUTPUT inserted.entry_id, inserted.entry_date, inserted.created_at
                VALUES (?, ?, ?, COALESCE(?, SYSDATETIME()), ?, ?, ?, ?, ?, ?, ?, ?)
                """, rs -> {
                    LedgerEntry r = new LedgerEntry();
                    r.setEntryId(rs.getLong(1));
                    r.setEntryDate(getDateTime(rs, "entry_date"));
                    r.setCreatedAt(getDateTime(rs, "created_at"));
                    return r;
                },
                e.getPartyType().name(),
                e.getPartyType() == PartyType.CUSTOMER ? e.getPartyId() : null,
                e.getPartyType() == PartyType.SUPPLIER ? e.getPartyId() : null,
                e.getEntryDate(), e.getEntryType().name(), e.getDebit(), e.getCredit(),
                e.getReferenceType(), e.getReferenceId(), e.getReferenceNo(), e.getDescription(), e.getUserId())
                .orElseThrow(() -> new DataAccessException("Ledger entry was not inserted"));
        e.setEntryId(saved.getEntryId());
        e.setEntryDate(saved.getEntryDate());
        e.setCreatedAt(saved.getCreatedAt());
        return saved.getEntryId();
    }

    /**
     * Entries of one party between {@code from} and {@code to} (inclusive days, either may be null),
     * oldest first, each with the running balance computed over the party's <b>whole</b> history,
     * so the first row of a period continues from the balance brought forward.
     */
    public List<LedgerEntry> findWithRunningBalance(PartyType party, int partyId, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                WITH e AS (
                    SELECT l.*, u.full_name AS user_name,
                           SUM(%s) OVER (ORDER BY l.entry_date, l.entry_id ROWS UNBOUNDED PRECEDING) AS running_balance
                    FROM dbo.Account_Ledger l
                    JOIN dbo.Users u ON u.user_id = l.user_id
                    WHERE l.%s = ?
                )
                SELECT * FROM e WHERE 1 = 1
                """.formatted(effect(party), partyColumn(party)));
        List<Object> params = new ArrayList<>();
        params.add(partyId);
        if (from != null) {
            sql.append(" AND entry_date >= ?");
            params.add(from.atStartOfDay());
        }
        if (to != null) {
            sql.append(" AND entry_date < ?");
            params.add(to.plusDays(1).atStartOfDay());
        }
        sql.append(" ORDER BY entry_date, entry_id");
        return queryList(sql.toString(), rs -> map(rs, party), params.toArray());
    }

    /** Balance from all entries before {@code date} (the "balance brought forward" of a statement). */
    public BigDecimal balanceBefore(PartyType party, int partyId, LocalDate date) {
        return queryOne("SELECT COALESCE(SUM(" + effect(party) + "), 0) FROM dbo.Account_Ledger l WHERE l."
                        + partyColumn(party) + " = ? AND l.entry_date < ?",
                rs -> rs.getBigDecimal(1), partyId, date.atStartOfDay()).orElse(BigDecimal.ZERO);
    }

    /** The balance rebuilt from the ledger alone. */
    public BigDecimal ledgerBalance(PartyType party, int partyId) {
        return queryOne("SELECT COALESCE(SUM(" + effect(party) + "), 0) FROM dbo.Account_Ledger l WHERE l."
                        + partyColumn(party) + " = ?",
                rs -> rs.getBigDecimal(1), partyId).orElse(BigDecimal.ZERO);
    }

    /** Ids of parties whose cached balance differs from their ledger; empty when everything is consistent. */
    public List<Integer> findBalanceMismatches(PartyType party) {
        String table = party == PartyType.CUSTOMER ? "dbo.Customers" : "dbo.Suppliers";
        String id = partyColumn(party);
        return queryList("""
                SELECT p.%1$s FROM %2$s p
                WHERE p.balance <> COALESCE((SELECT SUM(%3$s) FROM dbo.Account_Ledger l WHERE l.%1$s = p.%1$s), 0)
                ORDER BY p.%1$s
                """.formatted(id, table, effect(party)), rs -> rs.getInt(1));
    }

    /** Removes a party's entries; for test clean-up only. */
    public void deleteForParty(PartyType party, int partyId) {
        update("DELETE FROM dbo.Account_Ledger WHERE " + partyColumn(party) + " = ?", partyId);
    }

    private static LedgerEntry map(ResultSet rs, PartyType party) throws SQLException {
        LedgerEntry e = new LedgerEntry();
        e.setEntryId(rs.getLong("entry_id"));
        e.setPartyType(party);
        e.setPartyId(getInteger(rs, party == PartyType.CUSTOMER ? "customer_id" : "supplier_id"));
        e.setEntryDate(getDateTime(rs, "entry_date"));
        e.setEntryType(LedgerEntryType.valueOf(rs.getString("entry_type")));
        e.setDebit(rs.getBigDecimal("debit"));
        e.setCredit(rs.getBigDecimal("credit"));
        e.setReferenceType(rs.getString("reference_type"));
        e.setReferenceId(getInteger(rs, "reference_id"));
        e.setReferenceNo(rs.getString("reference_no"));
        e.setDescription(rs.getString("description"));
        e.setUserId(rs.getInt("user_id"));
        e.setCreatedAt(getDateTime(rs, "created_at"));
        e.setUserName(rs.getString("user_name"));
        e.setRunningBalance(rs.getBigDecimal("running_balance"));
        return e;
    }
}
