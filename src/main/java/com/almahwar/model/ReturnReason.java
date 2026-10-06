package com.almahwar.model;

/** Why goods were returned ({@code reason_code}; the Arabic label is stored in {@code reason}). */
public enum ReturnReason {

    DEFECTIVE("عيب في المنتج"),
    NOT_SUITABLE("منتج غير مناسب"),
    WRONG_QUANTITY("خطأ في الكمية"),
    WRONG_ORDER("خطأ في الطلب"),
    DAMAGED("تالف"),
    OTHER("أخرى");

    private final String labelAr;

    ReturnReason(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    public static ReturnReason fromCode(String code) {
        return code == null ? OTHER : valueOf(code);
    }
}
