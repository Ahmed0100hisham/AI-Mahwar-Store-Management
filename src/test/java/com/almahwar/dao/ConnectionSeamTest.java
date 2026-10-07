package com.almahwar.dao;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The 1.0.1 seams keep the desktop's behaviour by default (no database needed). */
class ConnectionSeamTest {

    @AfterEach
    void restoreDefault() {
        ConnectionSource.useDefault();
    }

    @Test
    void desktopDefaultIsTheUnchangedDatabaseConnection() {
        assertSame(ConnectionSource.DESKTOP_DEFAULT, ConnectionSource.current(), "the desktop never installs another");
        assertThrows(NullPointerException.class, () -> ConnectionSource.use(null));
    }

    @Test
    void anInstalledProviderIsUsedByEveryDaoAndTransaction() {
        AtomicInteger opened = new AtomicInteger();
        ConnectionSource.use(() -> {
            opened.incrementAndGet();
            throw new SQLException("test provider");
        });
        // TransactionManager and BaseDao both obtain their connection through the seam
        assertThrows(DataAccessException.class, () -> TransactionManager.inTransaction(con -> 1));
        assertThrows(DataAccessException.class, () -> new UnitDao().findAll());
        assertEquals(2, opened.get());
        ConnectionSource.useDefault();
        assertSame(ConnectionSource.DESKTOP_DEFAULT, ConnectionSource.current());
    }

    @Test
    void auditOriginDefaultsToTheComputerNameAsIn100() throws Exception {
        String expected = System.getenv("COMPUTERNAME");
        if (expected == null || expected.isBlank()) {
            expected = System.getenv("HOSTNAME");
        }
        Field origin = AuditLogDao.class.getDeclaredField("origin");
        origin.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<String> desktop = (java.util.function.Supplier<String>) origin.get(new AuditLogDao());
        assertEquals(expected, desktop.get());
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<String> custom =
                (java.util.function.Supplier<String>) origin.get(new AuditLogDao(() -> "API/10.0.0.1"));
        assertEquals("API/10.0.0.1", custom.get());
        assertThrows(NullPointerException.class, () -> new AuditLogDao(null));
    }

    /** Compile-time check that the provider contract is plain JDBC. */
    @SuppressWarnings("unused")
    private static final ConnectionProvider PLAIN_JDBC = () -> (Connection) null;
}
