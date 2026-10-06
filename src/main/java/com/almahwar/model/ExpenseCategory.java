package com.almahwar.model;

/** {@code Expenses.category}; shown in Arabic. */
public enum ExpenseCategory {

    RENT("إيجار"),
    ELECTRICITY("كهرباء"),
    WATER("ماء"),
    INTERNET("إنترنت واتصالات"),
    TRANSPORT("نقل وتوصيل"),
    MAINTENANCE("صيانة"),
    SALARIES("رواتب"),
    OFFICE("مستلزمات مكتبية"),
    OTHER("أخرى");

    private final String labelAr;

    ExpenseCategory(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    public static ExpenseCategory fromCode(String code) {
        return code == null ? OTHER : valueOf(code);
    }
}
