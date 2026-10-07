package com.almahwar.api.session;

import com.almahwar.api.config.SessionDatabaseProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** One baseline, no migration library. Never creates a database or repairs an unknown/partial schema. */
@Component
public class ApiSessionSchemaRepository implements SmartInitializingSingleton {
    public static final String VERSION = "1.0.0";
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final SessionDatabaseProperties settings;
    private final String script;
    private final String checksum;

    public ApiSessionSchemaRepository(@Qualifier("sessionDataSource") DataSource source, SessionDatabaseProperties settings) {
        this.jdbc = JdbcClient.create(source);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.settings = settings;
        try {
            byte[] bytes = new ClassPathResource("db/api/V1__sessions.sql").getContentAsByteArray();
            // Git may check out CRLF on Windows; the same baseline must verify on every deployment platform.
            this.script = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            this.checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot read API baseline", e); }
    }

    @Override public void afterSingletonsInstantiated() {
        try { initializeOrVerify(); }
        catch (RuntimeException e) {
            Integer code=null;
            for(Throwable cause=e;cause!=null;cause=cause.getCause())
                if(cause instanceof java.sql.SQLException sql) code=sql.getErrorCode();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("API schema verification failed ({}, SQL code {})",
                    e.getClass().getSimpleName(),code);
            // Connection exceptions can contain URLs; never attach them to startup messages.
            throw new IllegalStateException("API session database unavailable or incompatible; requires schema " + VERSION);
        }
    }

    public void initializeOrVerify() {
        transactions.executeWithoutResult(status -> {
            Integer lock = jdbc.sql("""
                    -- A table SELECT starts the driver's implicit transaction; BEGIN TRAN would nest it twice.
                    DECLARE @started int; SELECT @started=COUNT(*) FROM sys.tables;
                    DECLARE @r int;
                    EXEC @r = sys.sp_getapplock @Resource=N'AlMahwarApi:schema', @LockMode='Exclusive',
                        @LockOwner='Transaction', @LockTimeout=10000;
                    SELECT @r;
                    """).query(Integer.class).single();
            if (lock < 0) throw new IllegalStateException("API schema lock unavailable");
            String catalog = jdbc.sql("SELECT DB_NAME()").query(String.class).single();
            if (!catalog.equalsIgnoreCase(settings.name()) || !catalog.matches("(?i)AlMahwarApi[A-Za-z0-9_]*"))
                throw new IllegalStateException("Unsafe API database target");
            int count = jdbc.sql("SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped=0").query(Integer.class).single();
            if (count == 0 && settings.initialize()) {
                jdbc.sql(script).update();
                jdbc.sql("INSERT dbo.api_schema_version(id,version,script_sha256) VALUES(1,?,?)")
                        .params(VERSION, checksum).update();
            }
            if (!check()) throw new IllegalStateException("Incompatible API session schema");
        });
    }

    public boolean compatible() {
        try { return check(); }
        catch (RuntimeException e) { return false; }
    }

    private boolean check() {
        if (jdbc.sql("SELECT COUNT(*) FROM sys.tables WHERE object_id=OBJECT_ID('dbo.api_schema_version','U')")
                .query(Integer.class).single()!=1) return false;
        return jdbc.sql("""
                    SELECT COUNT(*) FROM dbo.api_schema_version WHERE id=1 AND version=? AND script_sha256=?
                      AND OBJECT_ID('dbo.api_sessions','U') IS NOT NULL
                      AND OBJECT_ID('dbo.api_refresh_tokens','U') IS NOT NULL
                    """).params(VERSION, checksum).query(Integer.class).single() == 1;
    }
}
