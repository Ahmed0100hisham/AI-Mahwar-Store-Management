package com.almahwar.model;

import java.math.BigDecimal;

/**
 * A customer who owes us or a supplier we owe, as offered on the payment screen.
 *
 * @param outstanding the amount still due (the party's balance, &gt; 0)
 */
public record OutstandingParty(PartyType partyType, int partyId, String code, String name, String phone,
                               BigDecimal outstanding, boolean active) {
}
