package com.almahwar.model;

/**
 * {@code Cash_Transactions.source_type}: the document or operation that moved the money (the values allowed by
 * the table's check constraint). The treasury includes every method (cash, KNET, transfers, cheques).
 */
public enum CashSource {

    SALE("مبيعات"),
    PURCHASE("مشتريات"),
    CUSTOMER_PAYMENT("تحصيل من عميل"),
    SUPPLIER_PAYMENT("سداد لمورد"),
    EXPENSE("مصروف"),
    SALE_RETURN("مرتجع مبيعات"),
    PURCHASE_RETURN("مرتجع مشتريات"),
    OPENING_BALANCE("رصيد افتتاحي"),
    DEPOSIT("إيداع يدوي"),
    WITHDRAWAL("سحب يدوي"),
    ADJUSTMENT("تسوية");

    private final String labelAr;

    CashSource(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** Database code, e.g. {@code "CUSTOMER_PAYMENT"}. */
    public String code() {
        return name();
    }
}
