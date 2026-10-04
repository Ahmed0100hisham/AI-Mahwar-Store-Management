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
        assertTrue(p.containsAll(Set.of(Permission.SALES, Permission.CUSTOMERS, Permission.CUSTOMER_PAYMENTS)));
        assertFalse(p.contains(Permission.CASH));
        assertFalse(p.contains(Permission.PRODUCTS));
        assertFalse(p.contains(Permission.USERS));
    }

    @Test
    void storekeeperIsLimitedToStock() {
        Set<Permission> p = RolePermissions.forRole(Role.STOREKEEPER);
        assertTrue(p.containsAll(Set.of(Permission.PRODUCTS, Permission.INVENTORY, Permission.PURCHASES)));
        assertFalse(p.contains(Permission.SALES));
        assertFalse(p.contains(Permission.CASH));
    }

    @Test
    void accountantIsLimitedToFinance() {
        Set<Permission> p = RolePermissions.forRole(Role.ACCOUNTANT);
        assertTrue(p.containsAll(Set.of(Permission.CASH, Permission.EXPENSES,
                Permission.SUPPLIER_PAYMENTS, Permission.FINANCIAL_REPORTS)));
        assertFalse(p.contains(Permission.SALES));
        assertFalse(p.contains(Permission.PRODUCTS));
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
                () -> RolePermissions.forRole(Role.CASHIER).add(Permission.USERS));
    }
}
