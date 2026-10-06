package com.almahwar.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Kinds of account ledger entry ({@code Account_Ledger.entry_type}).
 * <p>
 * This phase writes only {@link #OPENING_BALANCE}; sales, purchases, payments,
 * returns and adjustments are posted by their own modules later, through the same
 * {@code AccountLedger} service class.
 */
public enum LedgerEntryType {

    OPENING_BALANCE("رصيد افتتاحي", EnumSet.allOf(PartyType.class)),
    SALE("فاتورة بيع", EnumSet.of(PartyType.CUSTOMER)),
    SALE_RETURN("مرتجع مبيعات", EnumSet.of(PartyType.CUSTOMER)),
    PURCHASE("فاتورة مشتريات", EnumSet.of(PartyType.SUPPLIER)),
    PURCHASE_RETURN("مرتجع مشتريات", EnumSet.of(PartyType.SUPPLIER)),
    PAYMENT("دفعة", EnumSet.allOf(PartyType.class)),
    ADJUSTMENT("تسوية", EnumSet.allOf(PartyType.class));

    private final String labelAr;
    private final Set<PartyType> parties;

    LedgerEntryType(String labelAr, Set<PartyType> parties) {
        this.labelAr = labelAr;
        this.parties = parties;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** Matches the database CHECK constraint {@code CK_Account_Ledger_type}. */
    public boolean allowedFor(PartyType party) {
        return parties.contains(party);
    }
}
