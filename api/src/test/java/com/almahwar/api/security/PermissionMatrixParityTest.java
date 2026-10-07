package com.almahwar.api.security;

import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import com.almahwar.service.RolePermissions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Compare the adopted core to grants frozen from the actual Phase 1 source, not to another call to itself. */
class PermissionMatrixParityTest {
    @Test
    void sameGrantsForEveryReleasedRole() throws Exception {
        Properties baseline = new Properties();
        try (var in = getClass().getResourceAsStream("/phase1-permissions.properties")) {
            baseline.load(in);
        }
        for (String role : List.of(Role.ADMIN, Role.CASHIER, Role.STOREKEEPER, Role.ACCOUNTANT)) {
            String frozen = baseline.getProperty(role);
            Set<String> expected = frozen.equals("*")
                    ? Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toSet())
                    : Set.of(frozen.split(","));
            assertThat(RolePermissions.forRole(role).stream().map(Enum::name).collect(Collectors.toSet()))
                    .as(role).isEqualTo(expected);
        }
        for (String unknown : List.of("MANAGER", "UNKNOWN", "admin")) {
            assertThat(RolePermissions.forRole(unknown)).isEmpty();
        }
        assertThat(RolePermissions.forRole(null)).isEmpty();
    }

    @Test
    void keyRulesOfTheProofOfConceptEndpoint() {
        for (String role : List.of(Role.ADMIN, Role.CASHIER, Role.STOREKEEPER, Role.ACCOUNTANT)) {
            assertThat(RolePermissions.forRole(role)).contains(Permission.PRODUCTS_VIEW);
        }
        assertThat(RolePermissions.forRole(Role.CASHIER)).doesNotContain(Permission.PRODUCT_COST, Permission.PRODUCTS);
        assertThat(RolePermissions.forRole(Role.ACCOUNTANT)).contains(Permission.PRODUCT_COST).doesNotContain(Permission.PRODUCTS);
        assertThat(RolePermissions.forRole(Role.STOREKEEPER)).contains(Permission.PRODUCT_COST, Permission.PRODUCTS);
    }
}
