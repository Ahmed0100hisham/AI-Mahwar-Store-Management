package com.almahwar.model;

/**
 * Who an {@code Account_Ledger} entry belongs to, and which side increases the balance:
 * a customer's balance (what they owe us) grows with debits; a supplier's balance
 * (what we owe them) grows with credits.
 */
public enum PartyType {

    CUSTOMER("عميل"),
    SUPPLIER("مورد");

    private final String labelAr;

    PartyType(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** Effect of one entry on this party's balance. */
    public java.math.BigDecimal balanceEffect(java.math.BigDecimal debit, java.math.BigDecimal credit) {
        return this == CUSTOMER ? debit.subtract(credit) : credit.subtract(debit);
    }
}
