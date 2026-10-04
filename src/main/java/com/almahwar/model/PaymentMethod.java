package com.almahwar.model;

/** Values of the {@code payment_method} columns. */
public enum PaymentMethod {

    CASH("نقدًا"),
    KNET("كي نت"),
    CREDIT_CARD("بطاقة ائتمان"),
    BANK_TRANSFER("تحويل بنكي"),
    CHEQUE("شيك"),
    CREDIT("آجل"),
    MIXED("مختلط");

    private final String labelAr;

    PaymentMethod(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** Database code, e.g. {@code "KNET"}. */
    public String code() {
        return name();
    }

    public static PaymentMethod fromCode(String code) {
        return code == null ? CASH : valueOf(code);
    }

    @Override
    public String toString() {
        return labelAr;
    }
}
