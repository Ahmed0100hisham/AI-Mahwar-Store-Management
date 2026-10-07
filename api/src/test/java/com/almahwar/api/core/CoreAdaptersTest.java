package com.almahwar.api.core;

import com.almahwar.api.security.ApiAuthenticationToken;
import com.almahwar.api.security.ApiUser;
import com.almahwar.dao.ConnectionSource;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.Permission;
import com.almahwar.service.AccessDeniedException;
import com.almahwar.service.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreAdaptersTest {
    private final SpringSecurityContext security = new SpringSecurityContext();
    private final com.almahwar.dao.ConnectionProvider original = ConnectionSource.current();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        ConnectionSource.use(original);
    }

    private void authenticate(String role, boolean mustChange, Set<Permission> permissions) {
        SecurityContextHolder.getContext().setAuthentication(new ApiAuthenticationToken(
                new ApiUser(42, "test", "Test User", role, "Role", mustChange, permissions, "token")));
    }

    @Test
    void identityAndEveryPermissionMatchTheAuthenticatedRole() {
        for (String role : List.of("ADMIN", "ACCOUNTANT", "CASHIER", "STOREKEEPER")) {
            authenticate(role, false, RolePermissions.forRole(role));
            assertThat(security.currentUser().getUserId()).isEqualTo(42);
            assertThat(security.currentUser().getUsername()).isEqualTo("test");
            assertThat(security.currentUser().getRoleCode()).isEqualTo(role);
            assertThat(security.currentUser().getPasswordHash()).isNull();
            for (Permission permission : Permission.values()) {
                assertThat(security.hasPermission(permission)).as(role + ":" + permission)
                        .isEqualTo(RolePermissions.forRole(role).contains(permission));
            }
        }
    }

    @Test
    void mustChangeCarriesNoPermissionsEvenWithAnOverprivilegedPrincipal() {
        authenticate("ADMIN", true, RolePermissions.forRole("ADMIN"));
        assertThat(security.requireSession().isPasswordChangeRequired()).isTrue();
        assertThat(security.requireSession().getPermissions()).isEmpty();
        assertThatThrownBy(() -> security.requirePermission(Permission.PRODUCTS_VIEW))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unknownRoleAndRestrictedPrincipalCannotGainPermissions() {
        authenticate("UNKNOWN", false, Set.of(Permission.PRODUCTS));
        assertThat(security.requireSession().getPermissions()).isEmpty();
        authenticate("ADMIN", false, Set.of(Permission.PRODUCTS_VIEW));
        assertThat(security.requireSession().getPermissions()).containsExactly(Permission.PRODUCTS_VIEW);
    }

    @Test
    void missingOrUnauthenticatedPrincipalDoesNotCreateASession() {
        assertThat(security.getSession()).isEmpty();
        assertThatThrownBy(security::requireSession).isInstanceOf(AccessDeniedException.class);
        authenticate("ADMIN", false, RolePermissions.forRole("ADMIN"));
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        assertThat(security.getSession()).isEmpty();
    }

    @Test
    void requestThreadsDoNotSharePrincipals() throws Exception {
        authenticate("ADMIN", false, RolePermissions.forRole("ADMIN"));
        var other = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        Thread worker = new Thread(() -> other.set(security.isLoggedIn()));
        worker.start(); worker.join();
        assertThat(other.get()).isFalse();
        assertThat(security.isLoggedIn()).isTrue();
    }

    @Test
    void coreCommitsAndClosesTheBorrowedConnection() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(ds.getConnection()).thenReturn(connection);
        CoreConnectionBinding binding = new CoreConnectionBinding(ds);
        Integer result = TransactionManager.inTransaction(con -> { assertThat(con).isSameAs(connection); return 7; });
        assertThat(result)
                .isEqualTo(7);
        var order = inOrder(connection);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).commit();
        order.verify(connection).close();
        verify(connection, never()).rollback();
        binding.destroy();
        assertThat(ConnectionSource.current()).isSameAs(original);
    }

    @Test
    void runtimeAndSqlFailuresRollBackAndCloseWithoutCommit() throws Exception {
        for (boolean sqlFailure : List.of(false, true)) {
            DataSource ds = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            when(ds.getConnection()).thenReturn(connection);
            ConnectionSource.use(new DataSourceConnectionProvider(ds));
            assertThatThrownBy(() -> TransactionManager.inTransaction(con -> {
                if (sqlFailure) throw new SQLException("test");
                throw new IllegalStateException("test");
            })).isInstanceOf(RuntimeException.class);
            verify(connection).setAutoCommit(false);
            verify(connection).rollback();
            verify(connection).close();
            verify(connection, never()).commit();
        }
    }

    @Test
    void acquisitionFailureUsesPhase1UnavailableError() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("test"));
        assertThatThrownBy(() -> new DataSourceConnectionProvider(ds).getConnection())
                .isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    @Test
    void desktopDefaultAndReplacementOwnershipArePreserved() {
        ConnectionSource.useDefault();
        assertThat(ConnectionSource.current()).isSameAs(ConnectionSource.DESKTOP_DEFAULT);
        CoreConnectionBinding binding = new CoreConnectionBinding(mock(DataSource.class));
        binding.destroy();
        assertThat(ConnectionSource.current()).isSameAs(ConnectionSource.DESKTOP_DEFAULT);
        binding = new CoreConnectionBinding(mock(DataSource.class));
        var replacement = new DataSourceConnectionProvider(mock(DataSource.class));
        ConnectionSource.use(replacement);
        binding.destroy();
        assertThat(ConnectionSource.current()).isSameAs(replacement);
    }
}
