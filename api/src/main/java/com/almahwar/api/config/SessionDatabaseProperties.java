package com.almahwar.api.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Independent credentials; no fallback to the business connection. */
@Validated
@ConfigurationProperties("almahwar.api.db")
public record SessionDatabaseProperties(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]+") String host,
        @DefaultValue("1433") @Min(1) @Max(65535) int port,
        @DefaultValue("AlMahwarApiDB") @Pattern(regexp = "AlMahwarApi[A-Za-z0-9_]*") String name,
        @NotBlank String user, @NotBlank String password,
        @DefaultValue("true") boolean encrypt,
        @DefaultValue("false") boolean trustServerCertificate,
        @Pattern(regexp = "[A-Za-z0-9.*-]*") String hostNameInCertificate,
        @DefaultValue("5") @Min(1) @Max(60) int loginTimeoutSeconds,
        @DefaultValue("10") @Min(1) @Max(100) int maxPoolSize,
        @DefaultValue("false") boolean initialize) {

    public void validateAgainst(DatabaseProperties business, boolean dev) {
        if (name.equalsIgnoreCase(business.name()))
            throw new IllegalStateException("API and business databases must be distinct");
        if ("sa".equalsIgnoreCase(user.trim()) || "sa".equalsIgnoreCase(business.user().trim()))
            throw new IllegalStateException("The API must not use sa");
        if (!dev && (!encrypt || trustServerCertificate || !business.encrypt() || business.trustServerCertificate()))
            throw new IllegalStateException("Production requires encrypted, certificate-validated database connections");
    }

    public String jdbcUrl() {
        return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + name
                + ";encrypt=" + encrypt + ";trustServerCertificate=" + trustServerCertificate
                + ";loginTimeout=" + loginTimeoutSeconds + ";applicationName=AlMahwar-API-Sessions"
                + (hostNameInCertificate == null || hostNameInCertificate.isBlank() ? ""
                : ";hostNameInCertificate=" + hostNameInCertificate);
    }

    @Override public String toString() { return "SessionDatabaseProperties[credentials=****]"; }
}
