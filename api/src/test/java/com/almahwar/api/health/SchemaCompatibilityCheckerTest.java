package com.almahwar.api.health;

import com.almahwar.api.health.SchemaCompatibilityChecker.Status;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exact schema 1.10.0, required tables, and refusal to start otherwise. */
class SchemaCompatibilityCheckerTest {

    private final DatabaseStatusRepository db = mock(DatabaseStatusRepository.class);
    private final SchemaCompatibilityChecker checker = new SchemaCompatibilityChecker(db);

    private Status statusFor(String version) {
        when(db.schemaVersion()).thenReturn(Optional.ofNullable(version));
        when(db.missingTables(anyCollection())).thenReturn(List.of());
        return checker.check().status();
    }

    @Test
    void onlyTheExactFrozenSchemaIsCompatible() {
        assertThat(SchemaCompatibilityChecker.REQUIRED_SCHEMA_VERSION)
                .isEqualTo(com.almahwar.service.SettingsService.REQUIRED_SCHEMA_VERSION);   // the frozen desktop's
        assertThat(statusFor("1.10.0")).isEqualTo(Status.COMPATIBLE);
        assertThat(statusFor("1.10.0 ")).isEqualTo(Status.COMPATIBLE);
        assertThat(statusFor("1.9.0")).isEqualTo(Status.VERSION_MISMATCH);
        assertThat(statusFor("1.10.1")).isEqualTo(Status.VERSION_MISMATCH);
        assertThat(statusFor("1.11.0")).isEqualTo(Status.VERSION_MISMATCH);
        assertThat(statusFor("2.0.0")).isEqualTo(Status.VERSION_MISMATCH);
        assertThat(statusFor("garbage")).isEqualTo(Status.VERSION_MISMATCH);
        assertThat(statusFor(null)).isEqualTo(Status.SCHEMA_MISSING);
    }

    @Test
    void missingRequiredTablesAreIncompatible() {
        when(db.schemaVersion()).thenReturn(Optional.of("1.10.0"));
        when(db.missingTables(SchemaCompatibilityChecker.REQUIRED_TABLES)).thenReturn(List.of("Products", "Units"));
        SchemaCompatibilityChecker.Result r = checker.check();
        assertThat(r.status()).isEqualTo(Status.TABLES_MISSING);
        assertThat(r.detail()).contains("Products", "Units");
        assertThat(SchemaCompatibilityChecker.REQUIRED_TABLES).contains("Users", "Roles", "Audit_Log", "Products");
    }

    @Test
    void unreachableOrInaccessibleDatabase() {
        when(db.schemaVersion()).thenThrow(new CannotGetJdbcConnectionException("no", new SQLException("refused")));
        assertThat(checker.check().status()).isEqualTo(Status.UNREACHABLE);

        DatabaseStatusRepository db2 = mock(DatabaseStatusRepository.class);
        when(db2.schemaVersion()).thenThrow(new DataAccessResourceFailureException("x",
                new SQLException("Cannot open database \"AlMahwarDB\" requested by the login.", "S0001", 4060)));
        assertThat(new SchemaCompatibilityChecker(db2).check().status()).isEqualTo(Status.DATABASE_UNAVAILABLE);
    }

    @Test
    void startupIsRefusedOnAnIncompatibleDatabase() {
        when(db.schemaVersion()).thenReturn(Optional.of("1.9.0"));
        StartupSchemaVerifier verifier = new StartupSchemaVerifier(checker);
        assertThatThrownBy(verifier::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERSION_MISMATCH").hasMessageContaining("1.9.0")
                .hasMessageContaining("Nothing was changed");

        when(db.schemaVersion()).thenReturn(Optional.of("1.10.0"));
        when(db.missingTables(anyCollection())).thenReturn(List.of());
        verifier.afterSingletonsInstantiated();   // compatible: no exception
    }
}
