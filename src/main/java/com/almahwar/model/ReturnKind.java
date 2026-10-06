package com.almahwar.model;

/** Which side a return document belongs to. */
public enum ReturnKind {

    /** مرتجع مبيعات: goods back from a customer ({@code Sale_Returns}, SRN-…). */
    SALE("مرتجع مبيعات", "SRN", PartyType.CUSTOMER),
    /** مرتجع مشتريات: goods back to a supplier ({@code Purchase_Returns}, PRN-…). */
    PURCHASE("مرتجع مشتريات", "PRN", PartyType.SUPPLIER);

    private final String labelAr;
    private final String prefix;
    private final PartyType party;

    ReturnKind(String labelAr, String prefix, PartyType party) {
        this.labelAr = labelAr;
        this.prefix = prefix;
        this.party = party;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** Number prefix, e.g. {@code SRN}. */
    public String getPrefix() {
        return prefix;
    }

    public PartyType getParty() {
        return party;
    }
}
