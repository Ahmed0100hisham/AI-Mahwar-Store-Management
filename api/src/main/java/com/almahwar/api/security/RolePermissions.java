package com.almahwar.api.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.almahwar.api.security.Permission.*;

/**
 * Role → permission matrix: the desktop's {@code com.almahwar.service.RolePermissions}, unchanged
 * ({@code PermissionMatrixParityTest} fails if the two ever differ). The four roles are fixed; a role with no entry
 * (e.g. the seeded {@code MANAGER}) has no permissions and cannot log in, exactly as on the desktop.
 */
public final class RolePermissions {

    public static final String ADMIN = "ADMIN";
    public static final String CASHIER = "CASHIER";
    public static final String STOREKEEPER = "STOREKEEPER";
    public static final String ACCOUNTANT = "ACCOUNTANT";

    private static final Map<String, Set<Permission>> MATRIX = Map.of(
            ADMIN, EnumSet.allOf(Permission.class),

            CASHIER, EnumSet.of(DASHBOARD, PRODUCTS_VIEW,
                    SALES_VIEW, SALES_CREATE, SALES_POST, SALES_DISCOUNT,
                    SALE_RETURNS, CUSTOMERS_VIEW, CUSTOMERS_EDIT, CUSTOMER_PAYMENTS,
                    QUOTATIONS_VIEW, QUOTATIONS_CREATE, QUOTATIONS_EDIT, QUOTATIONS_SEND, QUOTATIONS_ACCEPT,
                    QUOTATIONS_CONVERT, QUOTATIONS_DISCOUNT,
                    REPORTS_VIEW, REPORTS_SALES),

            STOREKEEPER, EnumSet.of(DASHBOARD,
                    PRODUCTS_VIEW, PRODUCTS, PRODUCT_COST, INVENTORY, INVENTORY_ADJUST,
                    PURCHASES_VIEW, PURCHASES_CREATE, PURCHASES_POST, PURCHASE_COST_VIEW,
                    SUPPLIERS_VIEW,
                    REPORTS_VIEW, REPORTS_INVENTORY),

            ACCOUNTANT, EnumSet.of(DASHBOARD, PRODUCTS_VIEW, PRODUCT_COST,
                    CUSTOMERS_VIEW, CUSTOMERS_EDIT, CUSTOMER_BALANCE_VIEW,
                    SUPPLIERS_VIEW, SUPPLIERS_EDIT, SUPPLIER_BALANCE_VIEW,
                    PURCHASES_VIEW, PURCHASE_COST_VIEW,
                    SALES_VIEW, SALES_COST_VIEW, SALES_PROFIT_VIEW, SALE_RETURNS, PURCHASE_RETURNS,
                    QUOTATIONS_VIEW, QUOTATIONS_ACCEPT,
                    CASH, CASH_ADJUST, EXPENSES, CUSTOMER_PAYMENTS, SUPPLIER_PAYMENTS, FINANCIAL_REPORTS,
                    REPORTS_VIEW, REPORTS_SALES, REPORTS_PROFIT, REPORTS_PURCHASES, REPORTS_EXPENSES, REPORTS_CASHBOX,
                    REPORTS_PARTIES, REPORTS_QUOTATIONS, REPORTS_EXPORT)
    );

    private RolePermissions() {
    }

    /** Immutable set of permissions for a role code; empty for unknown roles. */
    public static Set<Permission> forRole(String roleCode) {
        Set<Permission> permissions = roleCode == null ? null : MATRIX.get(roleCode);
        return permissions == null ? Collections.emptySet() : Collections.unmodifiableSet(permissions);
    }
}
