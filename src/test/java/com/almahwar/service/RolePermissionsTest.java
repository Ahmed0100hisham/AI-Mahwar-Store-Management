package com.almahwar.service;

import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePermissionsTest {

    @Test
    void adminHasEverything() {
        assertEquals(EnumSet.allOf(Permission.class), RolePermissions.forRole(Role.ADMIN));
    }

    @Test
    void cashierIsLimitedToSalesAndCustomers() {
        Set<Permission> p = RolePermissions.forRole(Role.CASHIER);
        assertTrue(p.containsAll(Set.of(Permission.SALES_VIEW, Permission.SALES_CREATE, Permission.SALES_POST,
                Permission.SALES_DISCOUNT, Permission.CUSTOMERS_VIEW, Permission.CUSTOMERS_EDIT,
                Permission.CUSTOMER_PAYMENTS)));
        // no cost / profit, no manual prices, no selling beyond credit limits
        assertFalse(p.contains(Permission.SALES_COST_VIEW));
        assertFalse(p.contains(Permission.SALES_PROFIT_VIEW));
        assertFalse(p.contains(Permission.SALES_PRICE_OVERRIDE));
        assertFalse(p.contains(Permission.CUSTOMER_CREDIT_OVERRIDE));
        assertFalse(p.contains(Permission.SUPPLIERS_VIEW));
        assertFalse(p.contains(Permission.CASH));
        assertFalse(p.contains(Permission.PRODUCTS));
        assertFalse(p.contains(Permission.USERS_VIEW));
    }

    @Test
    void storekeeperIsLimitedToStock() {
        Set<Permission> p = RolePermissions.forRole(Role.STOREKEEPER);
        assertTrue(p.containsAll(Set.of(Permission.PRODUCTS, Permission.INVENTORY, Permission.PURCHASES_VIEW)));
        assertTrue(p.stream().noneMatch(x -> x.name().startsWith("SALES")), "no sales permissions");
        assertFalse(p.contains(Permission.CASH));
    }

    @Test
    void accountantIsLimitedToFinance() {
        Set<Permission> p = RolePermissions.forRole(Role.ACCOUNTANT);
        assertTrue(p.containsAll(Set.of(Permission.CASH, Permission.EXPENSES,
                Permission.SUPPLIER_PAYMENTS, Permission.FINANCIAL_REPORTS)));
        // reads sales with cost and profit, but does not sell
        assertTrue(p.containsAll(Set.of(Permission.SALES_VIEW, Permission.SALES_COST_VIEW, Permission.SALES_PROFIT_VIEW)));
        assertFalse(p.contains(Permission.SALES_CREATE));
        assertFalse(p.contains(Permission.SALES_POST));
        assertFalse(p.contains(Permission.CUSTOMER_CREDIT_OVERRIDE));
        assertFalse(p.contains(Permission.PRODUCTS));
    }

    @Test
    void financialOperations() {
        // accountant: everything financial, including manual cash deposits / withdrawals
        assertTrue(RolePermissions.forRole(Role.ACCOUNTANT).containsAll(Set.of(Permission.CUSTOMER_PAYMENTS,
                Permission.SUPPLIER_PAYMENTS, Permission.EXPENSES, Permission.CASH, Permission.CASH_ADJUST)));
        // cashier: collects from customers only; no cashbox, expenses or supplier payments
        Set<Permission> cashier = RolePermissions.forRole(Role.CASHIER);
        assertTrue(cashier.contains(Permission.CUSTOMER_PAYMENTS));
        for (Permission p : Set.of(Permission.SUPPLIER_PAYMENTS, Permission.EXPENSES, Permission.CASH, Permission.CASH_ADJUST)) {
            assertFalse(cashier.contains(p), p.name());
        }
        // storekeeper: no financial management at all
        Set<Permission> store = RolePermissions.forRole(Role.STOREKEEPER);
        for (Permission p : Set.of(Permission.CUSTOMER_PAYMENTS, Permission.SUPPLIER_PAYMENTS, Permission.EXPENSES,
                Permission.CASH, Permission.CASH_ADJUST)) {
            assertFalse(store.contains(p), p.name());
        }
    }

    @Test
    void quotations() {
        // admin: everything, including manual prices on quotations
        assertTrue(RolePermissions.forRole(Role.ADMIN).containsAll(Set.of(Permission.QUOTATIONS_VIEW,
                Permission.QUOTATIONS_CREATE, Permission.QUOTATIONS_EDIT, Permission.QUOTATIONS_SEND,
                Permission.QUOTATIONS_ACCEPT, Permission.QUOTATIONS_CONVERT, Permission.QUOTATIONS_DISCOUNT,
                Permission.QUOTATIONS_PRICE_OVERRIDE)));
        // cashier: the whole quotation workflow, but list prices only
        Set<Permission> cashier = RolePermissions.forRole(Role.CASHIER);
        assertTrue(cashier.containsAll(Set.of(Permission.QUOTATIONS_VIEW, Permission.QUOTATIONS_CREATE,
                Permission.QUOTATIONS_EDIT, Permission.QUOTATIONS_SEND, Permission.QUOTATIONS_ACCEPT,
                Permission.QUOTATIONS_CONVERT, Permission.QUOTATIONS_DISCOUNT)));
        assertFalse(cashier.contains(Permission.QUOTATIONS_PRICE_OVERRIDE));
        // accountant: reads and decides, but neither prepares nor converts
        Set<Permission> accountant = RolePermissions.forRole(Role.ACCOUNTANT);
        assertTrue(accountant.containsAll(Set.of(Permission.QUOTATIONS_VIEW, Permission.QUOTATIONS_ACCEPT)));
        assertFalse(accountant.contains(Permission.QUOTATIONS_CREATE));
        assertFalse(accountant.contains(Permission.QUOTATIONS_CONVERT));
        // storekeeper: none
        assertTrue(RolePermissions.forRole(Role.STOREKEEPER).stream().noneMatch(x -> x.name().startsWith("QUOTATIONS")));
    }

    @Test
    void everyRoleSeesTheDashboard() {
        for (String role : new String[]{Role.ADMIN, Role.CASHIER, Role.STOREKEEPER, Role.ACCOUNTANT}) {
            assertTrue(RolePermissions.forRole(role).contains(Permission.DASHBOARD), role);
        }
    }

    @Test
    void unknownRolesGetNothing() {
        assertTrue(RolePermissions.forRole(Role.MANAGER).isEmpty());
        assertTrue(RolePermissions.forRole(null).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> RolePermissions.forRole(Role.CASHIER).add(Permission.USERS_VIEW));
    }
}
