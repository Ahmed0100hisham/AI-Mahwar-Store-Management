package com.almahwar.api.product;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.security.ApiAuthenticationToken;
import com.almahwar.api.security.ApiUser;
import com.almahwar.api.web.PageQuery;
import com.almahwar.model.Permission;
import com.almahwar.service.AccessDeniedException;
import com.almahwar.service.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Call the real query service without a Spring proxy: the core must still enforce all domain restrictions. */
class ProductCoreAuthorizationTest {
    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductQueryService service = new ProductQueryService(repository, new SpringSecurityContext());

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void authenticate(String role, boolean mustChange) {
        SecurityContextHolder.getContext().setAuthentication(new ApiAuthenticationToken(
                new ApiUser(1, "test", "Test", role, role, mustChange, RolePermissions.forRole(role), null)));
    }

    @Test
    void absentUnknownAndMustChangeUsersAreRejectedBeforeAnySql() {
        assertThatThrownBy(() -> service.list(null, true, null, new PageQuery(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        authenticate("UNKNOWN", false);
        assertThatThrownBy(() -> service.list(null, true, null, new PageQuery(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        authenticate("ADMIN", true);
        assertThatThrownBy(() -> service.list(null, true, null, new PageQuery(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void coreOverridesInactiveFilterAndStripsCostEvenIfRepositoryReturnsIt() {
        for (String role : List.of("ADMIN", "ACCOUNTANT", "CASHIER", "STOREKEEPER")) {
            reset(repository);
            authenticate(role, false);
            when(repository.count(any(), anyBoolean())).thenReturn(1L);
            when(repository.findPage(any(), anyBoolean(), anyBoolean(), any(), any())).thenReturn(List.of(
                    new ProductRepository.ProductRow(1, "P1", null, "Test", null, "C", null, "U", null, null,
                            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, true)));
            var page = service.list(" Test ", true, "code", new PageQuery(0, 20));
            boolean manage = RolePermissions.forRole(role).contains(Permission.PRODUCTS);
            boolean cost = RolePermissions.forRole(role).contains(Permission.PRODUCT_COST);
            verify(repository).count("Test", !manage);
            if (cost) {
                assertThat(page.items().get(0).purchasePrice()).isEqualByComparingTo(BigDecimal.ONE);
            } else {
                assertThat(page.items().get(0).purchasePrice()).isNull();
            }
        }
    }
}
