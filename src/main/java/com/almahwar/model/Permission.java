package com.almahwar.model;

/**
 * Fine-grained operations a user may perform. Which roles get which permissions
 * is defined in {@code com.almahwar.service.RolePermissions}; which side-menu
 * entries they unlock is defined in {@link NavigationItem}.
 */
public enum Permission {

    DASHBOARD("الرئيسية"),

    SALES("المبيعات ونقطة البيع"),
    SALE_RETURNS("مرتجعات المبيعات"),
    QUOTATIONS("عروض الأسعار"),
    CUSTOMERS("العملاء"),
    CUSTOMER_PAYMENTS("تحصيل العملاء"),

    PRODUCTS("الأصناف والمنتجات"),
    INVENTORY("المخزون"),
    PURCHASES("المشتريات"),
    PURCHASE_RETURNS("مرتجعات المشتريات"),
    SUPPLIERS("الموردون"),

    CASH("الخزنة"),
    EXPENSES("المصروفات"),
    SUPPLIER_PAYMENTS("مدفوعات الموردين"),
    FINANCIAL_REPORTS("التقارير المالية"),

    USERS("المستخدمون والصلاحيات"),
    AUDIT_LOG("سجل العمليات"),
    SETTINGS("الإعدادات");

    private final String labelAr;

    Permission(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }
}
