package com.almahwar.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * SQL Server connection of the API ({@code almahwar.db.*}). Nothing secret has a default: user and password come from
 * the environment ({@code ALMAHWAR_DB_USER}, {@code ALMAHWAR_DB_PASSWORD}) or an external, git-ignored
 * {@code config/application.properties}; the API refuses to start without them.
 * <p>
 * Production: a dedicated least-privilege SQL login for the API, never {@code sa} (see docs/API_ARCHITECTURE.md).
 */
@Validated
@ConfigurationProperties("almahwar.db")
public record DatabaseProperties(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]+") String host,
        @DefaultValue("1433") @Min(1) @Max(65535) int port,
        @DefaultValue("AlMahwarDB") @NotBlank @Pattern(regexp = "[A-Za-z0-9_]+") String name,
        @NotBlank(message = "set ALMAHWAR_DB_USER (or almahwar.db.user)") String user,
        @NotBlank(message = "set ALMAHWAR_DB_PASSWORD (or almahwar.db.password)") String password,
        @DefaultValue("true") boolean encrypt,
        @DefaultValue("false") boolean trustServerCertificate,
        @Pattern(regexp = "[A-Za-z0-9.*-]*") String hostNameInCertificate,
        @DefaultValue("5") @Min(1) @Max(60) int loginTimeoutSeconds,
        @DefaultValue("10") @Min(1) @Max(100) int maxPoolSize) {

    /** JDBC URL without credentials (user and password are passed separately, never inside the URL or a log). */
    public String jdbcUrl() {
        StringBuilder url = new StringBuilder("jdbc:sqlserver://").append(host).append(':').append(port)
                .append(";databaseName=").append(name)
                .append(";encrypt=").append(encrypt)
                .append(";trustServerCertificate=").append(trustServerCertificate)
                .append(";loginTimeout=").append(loginTimeoutSeconds)
                .append(";applicationName=AlMahwar-API");
        if (hostNameInCertificate != null && !hostNameInCertificate.isBlank()) {
            url.append(";hostNameInCertificate=").append(hostNameInCertificate);
        }
        return url.toString();
    }

    /** Never prints the password (records print every component by default). */
    @Override
    public String toString() {
        return "DatabaseProperties[host=" + host + ", port=" + port + ", name=" + name + ", user=" + user
                + ", password=****, encrypt=" + encrypt + ", trustServerCertificate=" + trustServerCertificate + "]";
    }
}
