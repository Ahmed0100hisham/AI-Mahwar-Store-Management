package com.almahwar.service;

import com.almahwar.model.Permission;
import com.almahwar.model.Role;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.almahwar.model.Permission.*;

/**
 * Role → permission matrix.
 * <p>
 * Kept in code (not in the database) because the four roles are fixed for this
 * business. A role with no entry here (e.g. the seeded {@code MANAGER}) has no
 * permissions and cannot log in.
 */
public final class RolePermissions {

    private static final Map<String, Set<Permission>> MATRIX = Map.of(
            Role.ADMIN, EnumSet.allOf(Permission.class),

            Role.CASHIER, EnumSet.of(DASHBOARD,
                    SALES, SALE_RETURNS, QUOTATIONS, CUSTOMERS, CUSTOMER_PAYMENTS),

            Role.STOREKEEPER, EnumSet.of(DASHBOARD,
                    PRODUCTS, INVENTORY, PURCHASES, PURCHASE_RETURNS, SUPPLIERS),

            Role.ACCOUNTANT, EnumSet.of(DASHBOARD,
                    CASH, EXPENSES, CUSTOMER_PAYMENTS, SUPPLIER_PAYMENTS, FINANCIAL_REPORTS)
    );

    private RolePermissions() {
    }

    /** Immutable set of permissions for a role code; empty for unknown roles. */
    public static Set<Permission> forRole(String roleCode) {
        Set<Permission> permissions = roleCode == null ? null : MATRIX.get(roleCode);
        return permissions == null ? Collections.emptySet() : Collections.unmodifiableSet(permissions);
    }

    /** Arabic title of the dashboard for a role. */
    public static String dashboardTitle(String roleCode) {
        if (roleCode == null) {
            return "لوحة التحكم";
        }
        return switch (roleCode) {
            case Role.ADMIN -> "لوحة تحكم مدير النظام";
            case Role.CASHIER -> "لوحة المبيعات والتحصيل";
            case Role.STOREKEEPER -> "لوحة المخزون والمشتريات";
            case Role.ACCOUNTANT -> "لوحة الحسابات والمالية";
            default -> "لوحة التحكم";
        };
    }
}
