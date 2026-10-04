package com.almahwar.model;

import com.almahwar.service.RolePermissions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.almahwar.model.NavigationItem.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NavigationItemTest {

    private static List<NavigationItem> menuFor(String roleCode) {
        User user = new User();
        user.setRoleCode(roleCode);
        UserSession session = new UserSession(user, RolePermissions.forRole(roleCode), LocalDateTime.now());
        return Arrays.stream(NavigationItem.values()).filter(i -> i.isVisibleTo(session)).toList();
    }

    @Test
    void menuIsInTheRequestedOrder() {
        assertEquals(List.of("الرئيسية", "نقطة البيع", "المنتجات", "المخزون", "المشتريات", "العملاء", "الموردون",
                        "عروض الأسعار", "المرتجعات", "الخزنة", "المصروفات", "التقارير", "المستخدمون", "الإعدادات"),
                Arrays.stream(NavigationItem.values()).map(NavigationItem::getLabelAr).toList());
    }

    @Test
    void adminSeesEverything() {
        assertEquals(List.of(NavigationItem.values()), menuFor(Role.ADMIN));
    }

    @Test
    void cashierMenu() {
        assertEquals(List.of(HOME, POINT_OF_SALE, CUSTOMERS, QUOTATIONS, RETURNS), menuFor(Role.CASHIER));
    }

    @Test
    void storekeeperMenu() {
        assertEquals(List.of(HOME, PRODUCTS, INVENTORY, PURCHASES, SUPPLIERS, RETURNS), menuFor(Role.STOREKEEPER));
    }

    @Test
    void accountantMenu() {
        assertEquals(List.of(HOME, CUSTOMERS, SUPPLIERS, CASH, EXPENSES, REPORTS), menuFor(Role.ACCOUNTANT));
    }

    @Test
    void nothingWithoutSession() {
        assertFalse(HOME.isVisibleTo(null));
        assertEquals(List.of(), menuFor(Role.MANAGER));
    }
}
