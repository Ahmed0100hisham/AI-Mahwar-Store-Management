package com.almahwar.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Side-menu entries, in display order. An entry is shown when the user holds
 * <b>any</b> of its permissions; e.g. "العملاء" serves both the cashier
 * (customers) and the accountant (customer collections).
 */
public enum NavigationItem {

    HOME("الرئيسية", Permission.DASHBOARD),
    POINT_OF_SALE("نقطة البيع", Permission.SALES),
    PRODUCTS("المنتجات", Permission.PRODUCTS),
    INVENTORY("المخزون", Permission.INVENTORY),
    PURCHASES("المشتريات", Permission.PURCHASES),
    CUSTOMERS("العملاء", Permission.CUSTOMERS, Permission.CUSTOMER_PAYMENTS),
    SUPPLIERS("الموردون", Permission.SUPPLIERS, Permission.SUPPLIER_PAYMENTS),
    QUOTATIONS("عروض الأسعار", Permission.QUOTATIONS),
    RETURNS("المرتجعات", Permission.SALE_RETURNS, Permission.PURCHASE_RETURNS),
    CASH("الخزنة", Permission.CASH),
    EXPENSES("المصروفات", Permission.EXPENSES),
    REPORTS("التقارير", Permission.FINANCIAL_REPORTS),
    USERS("المستخدمون", Permission.USERS),
    SETTINGS("الإعدادات", Permission.SETTINGS);

    private final String labelAr;
    private final Set<Permission> anyOf;

    NavigationItem(String labelAr, Permission first, Permission... more) {
        this.labelAr = labelAr;
        this.anyOf = EnumSet.of(first, more);
    }

    public String getLabelAr() {
        return labelAr;
    }

    public Set<Permission> getPermissions() {
        return EnumSet.copyOf(anyOf);
    }

    public boolean isVisibleTo(UserSession session) {
        return session != null && anyOf.stream().anyMatch(session::hasPermission);
    }
}
