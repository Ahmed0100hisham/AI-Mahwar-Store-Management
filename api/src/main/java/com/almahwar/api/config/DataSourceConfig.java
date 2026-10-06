package com.almahwar.api.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Connection pool to the existing SQL Server database (schema 1.10.0). Built from {@link DatabaseProperties} so the
 * configuration keys match the desktop's ({@code ALMAHWAR_DB_*}); credentials go to the pool, never into the URL.
 * The pool starts lazily-validated: {@link com.almahwar.api.health.StartupSchemaVerifier} proves the connection and
 * the schema before the API serves requests.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(DatabaseProperties db) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("almahwar-api");
        cfg.setJdbcUrl(db.jdbcUrl());
        cfg.setUsername(db.user());
        cfg.setPassword(db.password());
        cfg.setMaximumPoolSize(db.maxPoolSize());
        cfg.setMinimumIdle(Math.min(2, db.maxPoolSize()));
        cfg.setConnectionTimeout(db.loginTimeoutSeconds() * 1000L + 5_000L);
        // do not open a connection while the context starts: the schema check reports a failure in a safe way
        cfg.setInitializationFailTimeout(-1);
        cfg.setAutoCommit(true);
        return new HikariDataSource(cfg);
    }
}
