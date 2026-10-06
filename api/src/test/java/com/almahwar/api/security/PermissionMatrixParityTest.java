package com.almahwar.api.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API's permissions and role matrix must be exactly the frozen desktop's ({@code com.almahwar.model.Permission},
 * {@code com.almahwar.service.RolePermissions}) — same names, same grants per role, no new role.
 */
class PermissionMatrixParityTest {

    private static final List<String> ROLES = List.of("ADMIN", "CASHIER", "STOREKEEPER", "ACCOUNTANT", "MANAGER",
            "UNKNOWN", "admin");

    @Test
    void samePermissionsInTheSameOrder() {
        List<String> desktop = Arrays.stream(com.almahwar.model.Permission.values()).map(Enum::name).toList();
        List<String> api = Arrays.stream(Permission.values()).map(Enum::name).toList();
        assertThat(api).containsExactlyElementsOf(desktop);
    }

    @Test
    void sameGrantsForEveryRole() {
        for (String role : ROLES) {
            Set<String> desktop = com.almahwar.service.RolePermissions.forRole(role).stream().map(Enum::name)
                    .collect(Collectors.toSet());
            Set<String> api = RolePermissions.forRole(role).stream().map(Enum::name).collect(Collectors.toSet());
            assertThat(api).as(role).isEqualTo(desktop);
        }
        assertThat(RolePermissions.forRole(null)).isEmpty();
    }

    @Test
    void sameRoleCodes() {
        assertThat(RolePermissions.ADMIN).isEqualTo(com.almahwar.model.Role.ADMIN);
        assertThat(RolePermissions.CASHIER).isEqualTo(com.almahwar.model.Role.CASHIER);
        assertThat(RolePermissions.STOREKEEPER).isEqualTo(com.almahwar.model.Role.STOREKEEPER);
        assertThat(RolePermissions.ACCOUNTANT).isEqualTo(com.almahwar.model.Role.ACCOUNTANT);
    }

    @Test
    void keyRulesOfTheProofOfConceptEndpoint() {
        // products: every working role may view; cost only for admin, storekeeper, accountant; manage only admin/storekeeper
        for (String role : List.of("ADMIN", "CASHIER", "STOREKEEPER", "ACCOUNTANT")) {
            assertThat(RolePermissions.forRole(role)).contains(Permission.PRODUCTS_VIEW);
        }
        assertThat(RolePermissions.forRole("CASHIER")).doesNotContain(Permission.PRODUCT_COST, Permission.PRODUCTS);
        assertThat(RolePermissions.forRole("ACCOUNTANT")).contains(Permission.PRODUCT_COST).doesNotContain(Permission.PRODUCTS);
        assertThat(RolePermissions.forRole("STOREKEEPER")).contains(Permission.PRODUCT_COST, Permission.PRODUCTS);
        assertThat(RolePermissions.forRole("MANAGER")).isEmpty();
    }
}
