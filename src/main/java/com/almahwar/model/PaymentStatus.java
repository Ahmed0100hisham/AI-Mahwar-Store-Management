package com.almahwar.model;

/** How much of a document has been paid ({@code Purchases.payment_status}, computed by SQL Server). */
public enum PaymentStatus {

    PAID("مدفوعة"),
    PARTIAL("مدفوعة جزئيًا"),
    UNPAID("غير مدفوعة");

    private final String labelAr;

    PaymentStatus(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    public static PaymentStatus of(java.math.BigDecimal total, java.math.BigDecimal paid) {
        if (paid.compareTo(total) >= 0) {
            return PAID;
        }
        return paid.signum() == 0 ? UNPAID : PARTIAL;
    }
}
