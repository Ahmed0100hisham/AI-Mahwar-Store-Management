package com.almahwar.model;

/** Values of {@code Customers.customer_type}. */
public enum CustomerType {

    RETAIL("تجزئة"),
    WHOLESALE("جملة"),
    CONTRACTOR("مقاول"),
    COMPANY("شركة");

    private final String labelAr;

    CustomerType(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** Database code, e.g. {@code "CONTRACTOR"}. */
    public String code() {
        return name();
    }

    public static CustomerType fromCode(String code) {
        return code == null ? RETAIL : valueOf(code);
    }

    @Override
    public String toString() {
        return labelAr;
    }
}
