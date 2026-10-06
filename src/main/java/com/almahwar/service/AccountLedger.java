package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyType;
import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The only way a customer's or supplier's balance changes: writes one {@code Account_Ledger}
 * row and updates the cached {@code balance} column on the <b>caller's transaction</b>, so the
 * two can never disagree (if either fails, both roll back).
 * <p>
 * Used for opening balances now, and later by sales, purchases, payments and returns, e.g.
 * {@code ledger.post(con, PartyType.CUSTOMER, customerId, LedgerEntryType.SALE, total, "SALE", saleId, invoiceNo, "فاتورة بيع", userId)}.
 */
public class AccountLedger {

    private final AccountLedgerDao ledgerDao;
    private final CustomerDao customerDao;
    private final SupplierDao supplierDao;

    public AccountLedger(AccountLedgerDao ledgerDao, CustomerDao customerDao, SupplierDao supplierDao) {
        this.ledgerDao = ledgerDao;
        this.customerDao = customerDao;
        this.supplierDao = supplierDao;
    }

    /**
     * @param amount signed effect on the party's balance: positive = the customer owes us more /
     *               we owe the supplier more; negative = the opposite. Stored as debit or credit.
     * @return the saved entry; its running balance is the party's new balance
     */
    public LedgerEntry post(Connection con, PartyType party, int partyId, LedgerEntryType type, BigDecimal amount,
                            String referenceType, Integer referenceId, String referenceNo, String description,
                            int userId) {
        return post(con, party, partyId, type, amount, referenceType, referenceId, referenceNo, description, userId, null);
    }

    /**
     * Same, dated {@code entryDate} (e.g. a payment recorded for an earlier day keeps that day in the statement);
     * {@code null} = the server's "now".
     */
    public LedgerEntry post(Connection con, PartyType party, int partyId, LedgerEntryType type, BigDecimal amount,
                            String referenceType, Integer referenceId, String referenceNo, String description,
                            int userId, java.time.LocalDateTime entryDate) {
        if (!type.allowedFor(party)) {
            throw new IllegalArgumentException(type + " cannot be posted to a " + party);
        }
        BigDecimal value = MoneyUtil.of(amount);
        if (value.signum() == 0) {
            throw new IllegalArgumentException("A ledger entry needs a non-zero amount");
        }
        LedgerEntry e = new LedgerEntry();
        e.setPartyType(party);
        e.setPartyId(partyId);
        e.setEntryDate(entryDate);
        e.setEntryType(type);
        // customer: + is a debit; supplier: + is a credit
        boolean debitSide = (party == PartyType.CUSTOMER) == (value.signum() > 0);
        e.setDebit(debitSide ? value.abs() : BigDecimal.ZERO);
        e.setCredit(debitSide ? BigDecimal.ZERO : value.abs());
        e.setReferenceType(referenceType);
        e.setReferenceId(referenceId);
        e.setReferenceNo(referenceNo);
        e.setDescription(description);
        e.setUserId(userId);
        ledgerDao.insert(con, e);

        BigDecimal newBalance = party == PartyType.CUSTOMER
                ? customerDao.adjustBalance(con, partyId, value)
                : supplierDao.adjustBalance(con, partyId, value);
        e.setRunningBalance(newBalance);
        return e;
    }

    /**
     * Account statement for a period. Running balances always include entries outside the period and
     * entries hidden by {@code search}, so every shown balance is the true balance at that moment.
     */
    public AccountStatement statement(PartyType party, int partyId, String code, String name,
                                      LocalDate from, LocalDate to, String search) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from", "تاريخ البداية يجب أن يكون قبل تاريخ النهاية.");
        }
        BigDecimal opening = from == null ? MoneyUtil.ZERO : MoneyUtil.of(ledgerDao.balanceBefore(party, partyId, from));
        List<LedgerEntry> all = ledgerDao.findWithRunningBalance(party, partyId, from, to);
        BigDecimal closing = all.isEmpty() ? opening : all.get(all.size() - 1).getRunningBalance();

        String q = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<LedgerEntry> shown = q == null ? all : all.stream().filter(e -> matches(e, q)).toList();
        BigDecimal debit = MoneyUtil.of(shown.stream().map(LedgerEntry::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal credit = MoneyUtil.of(shown.stream().map(LedgerEntry::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new AccountStatement(party, partyId, code, name, from, to, opening, shown, debit, credit,
                MoneyUtil.of(closing), q != null);
    }

    private static boolean matches(LedgerEntry e, String q) {
        for (String text : new String[]{e.getDescription(), e.getReferenceNo(), e.getEntryType().getLabelAr(),
                e.getUserName()}) {
            if (text != null && text.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** The balance rebuilt from the ledger alone (for audits). */
    public BigDecimal rebuiltBalance(PartyType party, int partyId) {
        return MoneyUtil.of(ledgerDao.ledgerBalance(party, partyId));
    }

    /** Parties whose cached balance disagrees with the ledger; must always be empty. */
    public List<Integer> balanceMismatches(PartyType party) {
        return ledgerDao.findBalanceMismatches(party);
    }
}
