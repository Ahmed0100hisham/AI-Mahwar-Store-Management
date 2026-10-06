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

            // Sells: may look products up and give discounts, but never sees costs or profit, never changes
            // list prices and never sells beyond a customer's credit limit
            Role.CASHIER, EnumSet.of(DASHBOARD, PRODUCTS_VIEW,
                    SALES_VIEW, SALES_CREATE, SALES_POST, SALES_DISCOUNT,
                    SALE_RETURNS, CUSTOMERS_VIEW, CUSTOMERS_EDIT, CUSTOMER_PAYMENTS,
                    // quotations: prepares, sends, records the customer's answer and converts; no manual prices
                    QUOTATIONS_VIEW, QUOTATIONS_CREATE, QUOTATIONS_EDIT, QUOTATIONS_SEND, QUOTATIONS_ACCEPT,
                    QUOTATIONS_CONVERT, QUOTATIONS_DISCOUNT,
                    // reports: the sales summary only (no cost, no profit)
                    REPORTS_VIEW, REPORTS_SALES),

            Role.STOREKEEPER, EnumSet.of(DASHBOARD,
                    PRODUCTS_VIEW, PRODUCTS, PRODUCT_COST, INVENTORY, INVENTORY_ADJUST,
                    PURCHASES_VIEW, PURCHASES_CREATE, PURCHASES_POST, PURCHASE_COST_VIEW,
                    SUPPLIERS_VIEW,
                    // reports: stock only (inventory value with PRODUCT_COST, never profit)
                    REPORTS_VIEW, REPORTS_INVENTORY),   // purchase returns move money / the supplier account: not on the storekeeper alone

            // Reads products and costs for the accounts; adjusting stock needs an explicit INVENTORY_ADJUST.
            // Owns customer and supplier accounts: balances, opening balances, statements.
            Role.ACCOUNTANT, EnumSet.of(DASHBOARD, PRODUCTS_VIEW, PRODUCT_COST,
                    CUSTOMERS_VIEW, CUSTOMERS_EDIT, CUSTOMER_BALANCE_VIEW,
                    SUPPLIERS_VIEW, SUPPLIERS_EDIT, SUPPLIER_BALANCE_VIEW,
                    PURCHASES_VIEW, PURCHASE_COST_VIEW,
                    SALES_VIEW, SALES_COST_VIEW, SALES_PROFIT_VIEW, SALE_RETURNS, PURCHASE_RETURNS,
                    QUOTATIONS_VIEW, QUOTATIONS_ACCEPT,
                    CASH, CASH_ADJUST, EXPENSES, CUSTOMER_PAYMENTS, SUPPLIER_PAYMENTS, FINANCIAL_REPORTS,
                    // reports: all financial reports and their export (stock reports are the storekeeper's)
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
