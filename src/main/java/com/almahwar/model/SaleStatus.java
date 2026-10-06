package com.almahwar.model;

/**
 * {@code Sales.status}.
 * <ul>
 *   <li>{@link #DRAFT}: a held sale; no effect on stock, customer account or cash; may be continued.</li>
 *   <li>{@link #POSTED}: completed; stock, customer ledger and cash were updated in one transaction.
 *       Never edited — corrections will be sale returns / reversals.</li>
 *   <li>{@link #CANCELLED}: an abandoned draft, kept for the record.</li>
 * </ul>
 */
public enum SaleStatus {

    DRAFT("مسودة"),
    POSTED("معتمدة"),
    CANCELLED("ملغاة");

    private final String labelAr;

    SaleStatus(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }
}
