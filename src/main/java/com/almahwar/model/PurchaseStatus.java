package com.almahwar.model;

/**
 * {@code Purchases.status}.
 * <ul>
 *   <li>{@link #DRAFT}: being prepared; no effect on stock, supplier account or cash; may be edited.</li>
 *   <li>{@link #POSTED}: approved; stock, supplier ledger and cash were updated in one transaction.
 *       Never edited — corrections will be purchase returns / reversals.</li>
 *   <li>{@link #CANCELLED}: an abandoned draft, kept for the record.</li>
 * </ul>
 */
public enum PurchaseStatus {

    DRAFT("مسودة"),
    POSTED("معتمدة"),
    CANCELLED("ملغاة");

    private final String labelAr;

    PurchaseStatus(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }
}
