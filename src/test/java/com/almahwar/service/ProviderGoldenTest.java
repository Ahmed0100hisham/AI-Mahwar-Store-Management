package com.almahwar.service;

import com.almahwar.config.AppConfig;
import com.almahwar.config.DatabaseConnection;
import com.almahwar.dao.ConnectionSource;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same golden scenarios, but every DAO / transaction connection comes from a {@link DataSource} installed through
 * the 1.0.1 {@code ConnectionSource} seam — the way a server host (e.g. a connection pool) will provide them. The
 * result must equal the v1.0.0 record exactly. Uses only {@code javax.sql} and the SQL Server driver (no Spring).
 * <pre>mvn test -Ddb.it=true -Dgolden=true -Ddb.name=AlMahwarProviderIT -Dtest=ProviderGoldenTest</pre>
 */
@EnabledIfSystemProperty(named = "golden", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderGoldenTest {

    private final AtomicInteger opened = new AtomicInteger();

    @BeforeAll
    void installDataSourceProvider() throws Exception {
        GoldenDatabase.create();   // through master, as in the golden test
        AppConfig cfg = AppConfig.getInstance();
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setServerName(cfg.get("db.host", "localhost"));
        ds.setPortNumber(cfg.getInt("db.port", 1433));
        ds.setDatabaseName(DatabaseConnection.databaseName());
        ds.setUser(cfg.get("db.user"));
        ds.setPassword(cfg.get("db.password"));
        ds.setEncrypt(String.valueOf(cfg.getBoolean("db.encrypt", true)));
        ds.setTrustServerCertificate(cfg.getBoolean("db.trust-server-certificate", false));
        ds.setApplicationName("AlMahwarStore-ProviderTest");
        DataSource dataSource = ds;
        ConnectionSource.use(() -> {
            opened.incrementAndGet();
            return dataSource.getConnection();
        });
    }

    @AfterAll
    void restore() throws Exception {
        ConnectionSource.useDefault();
        GoldenDatabase.drop();
    }

    @Test
    void sameBusinessResultThroughADataSource() throws Exception {
        String actual = new GoldenScenarios().runAll();
        Path out = Path.of("target", "golden", "actual-datasource.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, actual, StandardCharsets.UTF_8);
        assertTrue(opened.get() > 100, "the DataSource provider served the business connections: " + opened.get());
        assertTrue(!ConnectionSource.current().equals(ConnectionSource.DESKTOP_DEFAULT));
        GoldenBehaviorTest.compare(Files.readString(GoldenBehaviorTest.RECORD, StandardCharsets.UTF_8), actual);
        assertSame(ConnectionSource.current(), ConnectionSource.current());
    }
}
