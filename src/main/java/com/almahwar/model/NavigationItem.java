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
    POINT_OF_SALE("نقطة البيع", Permission.SALES_CREATE),
    SALES("المبيعات", Permission.SALES_VIEW),
    PRODUCTS("المنتجات", Permission.PRODUCTS_VIEW, Permission.PRODUCTS),
    INVENTORY("المخزون", Permission.INVENTORY, Permission.INVENTORY_ADJUST),
    PURCHASES("المشتريات", Permission.PURCHASES_VIEW),
    CUSTOMERS("العملاء", Permission.CUSTOMERS_VIEW),
    SUPPLIERS("الموردون", Permission.SUPPLIERS_VIEW),
    QUOTATIONS("عروض الأسعار", Permission.QUOTATIONS_VIEW),
    RETURNS("المرتجعات", Permission.SALE_RETURNS, Permission.PURCHASE_RETURNS),
    CASH("الخزنة", Permission.CASH),
    EXPENSES("المصروفات", Permission.EXPENSES),
    REPORTS("التقارير", Permission.REPORTS_VIEW),
    USERS("المستخدمون", Permission.USERS_VIEW),
    SETTINGS("الإعدادات", Permission.SETTINGS_VIEW),
    BACKUP("النسخ الاحتياطي", Permission.BACKUP_VIEW);

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
