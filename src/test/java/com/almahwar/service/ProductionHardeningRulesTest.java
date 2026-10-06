package com.almahwar.service;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppLogging;
import com.almahwar.config.ConfigProbe;
import com.almahwar.dao.DatabaseHealthDao;
import com.almahwar.model.DatabaseHealth;
import com.almahwar.model.DatabaseHealth.Status;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production hardening rules without a database: configuration precedence and validation, the secure TLS default,
 * the log folder and secret redaction, health status classification. The real behaviour against SQL Server
 * (unreachable server, refused login, missing / offline database, old / new schema, missing table, no admin) is
 * covered by {@code HealthCheckIntegrationTest} and the startup-failure E2E runs.
 */
class ProductionHardeningRulesTest {

    private static Properties bundled() throws Exception {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/application.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void precedenceIsSystemPropertyThenEnvironmentThenFileThenDefault() {
        Properties files = new Properties();
        files.setProperty("db.host", "from-file");
        Map<String, String> env = Map.of("ALMAHWAR_DB_HOST", "from-env", "ALMAHWAR_DB_PORT", "1500");
        Map<String, String> sys = Map.of("db.host", "from-jvm");
        assertEquals("from-jvm", ConfigProbe.resolve("db.host", sys::get, env::get, files), "JVM beats environment");
        assertEquals("from-env", ConfigProbe.resolve("db.host", k -> null, env::get, files), "environment beats file");
        assertEquals("from-file", ConfigProbe.resolve("db.host", k -> null, k -> null, files), "file beats default");
        assertEquals("1500", ConfigProbe.resolve("db.port", k -> null, env::get, files));
        assertEquals(null, ConfigProbe.resolve("db.name", k -> null, k -> null, files));
        for (String[] key : new String[][]{{"db.host", "ALMAHWAR_DB_HOST"}, {"db.port", "ALMAHWAR_DB_PORT"},
                {"db.name", "ALMAHWAR_DB_NAME"}, {"db.user", "ALMAHWAR_DB_USER"}, {"db.password", "ALMAHWAR_DB_PASSWORD"},
                {"db.trust-server-certificate", "ALMAHWAR_DB_TRUST_SERVER_CERTIFICATE"},
                {"backup.server-directory", "ALMAHWAR_BACKUP_SERVER_DIRECTORY"}}) {
            assertEquals(key[1], AppConfig.environmentName(key[0]));
        }
    }

    private static List<String> problems(Map<String, String> values) {
        return ConfigProbe.databaseProblems(values::get);
    }

    private static Map<String, String> complete() {
        Map<String, String> m = new HashMap<>();
        m.put("db.host", "sqlserver01");
        m.put("db.port", "1433");
        m.put("db.name", "AlMahwarDB");
        m.put("db.user", "almahwar_app");
        m.put("db.password", "x");
        return m;
    }

    @Test
    void missingOrInvalidDatabaseSettingsAreNamedNeverTheirValues() {
        assertEquals(List.of(), problems(complete()));
        Map<String, String> m = complete();
        m.put("db.password", "  ");
        List<String> p = problems(m);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("db.password"));
        m = complete();
        m.remove("db.user");
        m.remove("db.password");
        assertEquals(2, problems(m).size());
        m.put("db.integrated-security", "true");   // Windows authentication: no user / password needed
        assertEquals(List.of(), problems(m));
        for (String[] bad : new String[][]{{"db.host", ""}, {"db.host", "srv;user=sa"}, {"db.host", "a=b"},
                {"db.port", "abc"}, {"db.port", "0"}, {"db.port", "70000"}, {"db.name", ""}, {"db.name", "x]; DROP"}}) {
            Map<String, String> c = complete();
            c.put(bad[0], bad[1]);
            List<String> found = problems(c);
            assertEquals(1, found.size(), bad[0] + "=" + bad[1]);
            assertTrue(found.get(0).contains(bad[0]));
        }
        Map<String, String> named = complete();
        named.put("db.host", "SERVER01\\SQLEXPRESS");
        assertEquals(List.of(), problems(named), "named instance");
        named.put("db.host", "192.168.1.20");
        assertEquals(List.of(), problems(named));
        Map<String, String> secret = complete();
        secret.put("db.password", "");
        secret.put("db.user", "");
        assertFalse(String.join(" ", problems(secret)).contains("almahwar_app"));
    }

    @Test
    void bundledConfigurationIsSecureAndHasNoSecrets() throws Exception {
        Properties p = bundled();
        assertEquals("", p.getProperty("db.user"));
        assertEquals("", p.getProperty("db.password"));
        assertEquals("true", p.getProperty("db.encrypt"), "encrypted by default");
        assertEquals("false", p.getProperty("db.trust-server-certificate"), "server certificate validated by default");
        assertEquals("", p.getProperty("app.log.directory"));
        for (String key : p.stringPropertyNames()) {
            String v = p.getProperty(key).toLowerCase();
            assertFalse(v.contains("mahwar20") || v.contains("staff20") || v.contains("passw0rd"), key);
        }
        // the bundled file alone is not a complete database configuration: the program says what is missing
        List<String> missing = ConfigProbe.databaseProblems(p::getProperty);
        assertEquals(2, missing.size(), missing.toString());
    }

    @Test
    void externalConfigurationFileIsFoundForInstalledPrograms(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path explicit = dir.resolve("site.properties");
        java.nio.file.Files.writeString(explicit, "db.host=x\n");
        Path appFolder = dir.resolve("AlMahwar");
        java.nio.file.Files.createDirectories(appFolder.resolve("config"));
        java.nio.file.Files.writeString(appFolder.resolve("config").resolve("application.properties"), "db.host=y\n");
        // an explicit file wins (-Dalmahwar.config / ALMAHWAR_CONFIG)
        assertEquals(explicit, ConfigProbe.externalFile(explicit.toString(), null, List.of(appFolder)).orElseThrow());
        assertEquals(explicit, ConfigProbe.externalFile(null, explicit.toString(), List.of(appFolder)).orElseThrow());
        // otherwise the working folder, then the program folder
        Path expectedFallback = java.nio.file.Files.isRegularFile(Path.of("config", "application.properties"))
                ? Path.of("config", "application.properties") : appFolder.resolve("config").resolve("application.properties");
        assertEquals(expectedFallback, ConfigProbe.externalFile(dir.resolve("missing.properties").toString(), null,
                List.of(appFolder)).orElseThrow(), "a missing explicit file falls back");
    }

    @Test
    void logFolderAndRedaction() {
        assertEquals(Path.of("D:/logs/almahwar"), ConfigProbe.logDirectory(" D:/logs/almahwar ", "C:/Users/u/AppData/Local", "C:/Users/u"));
        assertEquals(Path.of("C:/Users/u/AppData/Local", "AlMahwar", "logs"),
                ConfigProbe.logDirectory("", "C:/Users/u/AppData/Local", "C:/Users/u"));
        assertEquals(Path.of("/home/u", ".almahwar", "logs"), ConfigProbe.logDirectory(null, null, "/home/u"));
        assertEquals("jdbc:sqlserver://h;password=*****;user=x", AppLogging.redact("jdbc:sqlserver://h;password=Secret1;user=x"));
        assertEquals("db.password = *****", AppLogging.redact("db.password = Secret1"));
        assertEquals("PWD=*****", AppLogging.redact("PWD=abc"));
        assertEquals("token: *****", AppLogging.redact("token: abc.def"));
        assertEquals("no secrets here", AppLogging.redact("no secrets here"));
    }

    @Test
    void connectionFailuresAreClassified() {
        assertEquals(Status.LOGIN_FAILED, HealthCheckServiceImpl.classify(18456, "S0001", "Login failed for user 'x'."));
        assertEquals(Status.SERVER_UNREACHABLE, HealthCheckServiceImpl.classify(0, "08S01",
                "The TCP/IP connection to the host localhost, port 1 has failed."));
        assertEquals(Status.CONFIGURATION_ERROR, HealthCheckServiceImpl.classify(0, "08S01",
                "The driver could not establish a secure connection to SQL Server by using Secure Sockets Layer (SSL) "
                        + "encryption. Error: PKIX path building failed"));
        assertEquals(Status.DATABASE_UNAVAILABLE, HealthCheckServiceImpl.classify(4060, "S0001", "Cannot open database"));
    }

    @Test
    void missingSqlPermissionsAreReportedAsSuchNotAsFileProblems() {
        String text = "CREATE DATABASE permission denied in database 'master'.";
        assertTrue(BackupPolicy.permissionDenied(262, text));
        assertTrue(BackupPolicy.permissionDenied(3110, "User does not have permission to RESTORE database"));
        assertFalse(BackupPolicy.permissionDenied(3201, "Operating system error 3"));
        String msg = BackupPolicy.describe(262, "S0001", text);
        assertTrue(msg.contains("CREATE DATABASE") && msg.contains("README"), msg);
    }

    @Test
    void healthStatusesBlockOrAllowTheProgram() {
        for (Status s : Status.values()) {
            boolean allowed = s == Status.HEALTHY || s == Status.NO_ACTIVE_ADMIN;
            assertEquals(!allowed, s.blocking(), s.name());
        }
        // an incomplete configuration stops before any connection attempt
        HealthCheckServiceImpl h = new HealthCheckServiceImpl(() -> List.of("كلمة مرور قاعدة البيانات غير محددة (db.password)"),
                new DatabaseHealthDao("AlMahwarDB") {
                    @Override
                    public java.util.Optional<String> databaseState() {
                        throw new AssertionError("must not connect");
                    }
                }, "AlMahwarDB", "1.10.0");
        DatabaseHealth r = h.check();
        assertEquals(Status.CONFIGURATION_ERROR, r.status());
        assertEquals("إعدادات الاتصال بقاعدة البيانات غير مكتملة.", r.message());
        assertFalse(r.canStart());
    }
}
