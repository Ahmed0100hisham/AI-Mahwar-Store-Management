package com.almahwar.api.config;

import com.almahwar.api.AlMahwarApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The API refuses to start with missing or unsafe configuration, and configuration objects never print secrets.
 * (Starting the application here needs no database: it stops at configuration binding, before any connection.)
 */
class ConfigurationValidationTest {

    private static final String SECRET;

    static {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        SECRET = Base64.getEncoder().encodeToString(key);
    }

    private static Throwable startWith(String... properties) {
        try {
            var args=new java.util.LinkedHashMap<String,String>();
            args.put("server.port","0");
            args.put("almahwar.api.db.user","session-test-user");
            args.put("almahwar.api.db.password","test-only-not-real");
            for(String p:properties) { int eq=p.indexOf('='); args.put(p.substring(0,eq),p.substring(eq+1)); }
            new SpringApplicationBuilder(AlMahwarApiApplication.class).web(WebApplicationType.SERVLET)
                    .logStartupInfo(false)
                    .run(args.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new)).close();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    @Test void missingSessionCredentialsStopStartupWithoutBusinessCredentialFallback() {
        Throwable e=startWith("almahwar.db.user=business-user","almahwar.db.password=test-business-password",
                "almahwar.api.db.user=","almahwar.api.db.password=","almahwar.api.jwt.secret="+SECRET);
        assertThat(e).isNotNull();
        assertThat(allMessages(e)).contains("almahwar.api.db").doesNotContain("test-business-password");
    }

    private static String allMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void missingDatabaseCredentialsStopStartup() {
        Throwable e = startWith("almahwar.db.user=", "almahwar.db.password=", "almahwar.api.jwt.secret=" + SECRET);
        assertThat(e).isNotNull();
        assertThat(allMessages(e)).contains("almahwar.db");
    }

    @Test
    void missingJwtSecretStopsStartup() {
        Throwable e = startWith("almahwar.db.user=u", "almahwar.db.password=dummy-password-value",
                "almahwar.api.jwt.secret=");
        assertThat(e).isNotNull();
        assertThat(allMessages(e)).contains("almahwar.api.jwt").doesNotContain("dummy-password-value");
    }

    @Test
    void weakJwtSecretStopsStartupWithoutEchoingIt() {
        String weak = Base64.getEncoder().encodeToString("short-secret".getBytes());
        Throwable e = startWith("almahwar.db.user=u", "almahwar.db.password=p", "almahwar.api.jwt.secret=" + weak);
        assertThat(e).isNotNull();
        assertThat(allMessages(e)).contains("too short").doesNotContain(weak);
    }

    @Test
    void outOfRangeValuesStopStartup() {
        Throwable e = startWith("almahwar.db.user=u", "almahwar.db.password=p", "almahwar.api.jwt.secret=" + SECRET,
                "almahwar.db.port=70000");
        assertThat(e).isNotNull();
        assertThat(allMessages(e)).contains("port");
    }

    @Test
    void wildcardCorsOriginIsRefused() {
        ApiProperties props = new ApiProperties(new ApiProperties.Jwt(SECRET, "i", "a", Duration.ofMinutes(15)),
                new ApiProperties.Login(5, 300), new ApiProperties.Cors(List.of("https://app.example.com", "*")));
        assertThatThrownBy(() -> new com.almahwar.api.security.SecurityConfigAccess().cors(props))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not '*'");
    }

    @Test
    void secretsAreNeverPrinted() {
        DatabaseProperties db = new DatabaseProperties("sql.local", 1433, "AlMahwarDB", "api_user",
                "Sup3r-Secret-Pa55", true, false, null, 5, 10);
        assertThat(db.toString()).doesNotContain("Sup3r-Secret-Pa55").contains("****");
        assertThat(db.jdbcUrl()).doesNotContain("Sup3r-Secret-Pa55").doesNotContain("api_user")
                .contains("encrypt=true").contains("trustServerCertificate=false");

        ApiProperties.Jwt jwt = new ApiProperties.Jwt(SECRET, "i", "a", Duration.ofMinutes(15));
        assertThat(jwt.toString()).doesNotContain(SECRET);
        assertThat(new ApiProperties(jwt, new ApiProperties.Login(5, 300), new ApiProperties.Cors(List.of())).toString())
                .doesNotContain(SECRET);
    }

    @Test
    void bundledDefaultsContainNoCredentials() throws Exception {
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            java.util.Properties p = new java.util.Properties();
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(p.getProperty("almahwar.db.user")).isEmpty();
            assertThat(p.getProperty("almahwar.db.password")).isEmpty();
            assertThat(p.getProperty("almahwar.api.jwt.secret")).isEmpty();
            assertThat(p.getProperty("almahwar.db.trust-server-certificate")).isEqualTo("false");
            assertThat(p.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(p.getProperty("server.error.include-stacktrace")).isEqualTo("never");
            // the lock is shared with the desktop: same limits as its security.login.* defaults
            assertThat(p.getProperty("almahwar.api.login.max-attempts")).isEqualTo("5");
            assertThat(p.getProperty("almahwar.api.login.lock-seconds")).isEqualTo("300");
        }
    }
}
