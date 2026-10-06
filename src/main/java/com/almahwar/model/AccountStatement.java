package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A customer or supplier account statement (كشف حساب) for a period.
 *
 * @param openingBalance balance brought forward: everything before {@code from} (0 when {@code from} is null)
 * @param entries        entries in the period, oldest first, each with its running balance
 * @param closingBalance balance after the last entry of the period
 * @param filtered       {@code true} if a search text hid some entries; running balances still count them
 */
public record AccountStatement(PartyType partyType, int partyId, String partyCode, String partyName,
                               LocalDate from, LocalDate to, BigDecimal openingBalance,
                               List<LedgerEntry> entries, BigDecimal totalDebit, BigDecimal totalCredit,
                               BigDecimal closingBalance, boolean filtered) {
}
