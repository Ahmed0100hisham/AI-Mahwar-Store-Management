package com.almahwar.api.session;

import com.almahwar.api.config.ApiProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** All mutations serialize by business user in SQL Server, across processes and API instances. */
@Repository
public class ApiSessionRepository {
    public record Session(UUID sid, int userId, String passwordVersion, String credentialFingerprint, boolean restricted, String deviceLabel,
                          Instant createdAt, Instant lastActivityAt, Instant idleExpiresAt, Instant absoluteExpiresAt,
                          Instant revokedAt, boolean active) {
        @Override public String toString() { return "Session[sid="+sid+", userId="+userId+", active="+active+"]"; }
    }
    public enum RotationStatus { ROTATED, INVALID, REUSED }
    public record Rotation(RotationStatus status, Session session) { }
    private record Token(UUID sid, int userId, boolean consumed) { }

    private static final String SELECT_SESSION = """
            SELECT *, CASE WHEN revoked_at IS NULL AND idle_expires_at > SYSUTCDATETIME()
                AND absolute_expires_at > SYSUTCDATETIME() THEN 1 ELSE 0 END AS active
            FROM dbo.api_sessions
            """;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final long restrictedSeconds;

    public ApiSessionRepository(@Qualifier("sessionDataSource") DataSource source, ApiProperties properties) {
        this.jdbc = JdbcClient.create(source);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.restrictedSeconds = properties.jwt().accessTokenTtl().toSeconds();
    }

    public Session create(int userId, String version, String fingerprint, String label, boolean restricted, byte[] refreshHash) {
        return forUser(userId, () -> {
            UUID sid = UUID.randomUUID();
            jdbc.sql("""
                    DECLARE @now datetime2(7)=SYSUTCDATETIME();
                    INSERT dbo.api_sessions(sid,user_id,password_version,credential_fingerprint,restricted,device_label,created_at,
                        last_activity_at,idle_expires_at,absolute_expires_at)
                    VALUES(?,?,?,?,?,?,@now,@now,DATEADD(SECOND,?,@now),DATEADD(SECOND,?,@now));
                    """).params(sid.toString(), userId, version, fingerprint, restricted, label,
                    restricted ? restrictedSeconds : 8 * 3600L,
                    restricted ? restrictedSeconds : 7 * 86400L).update();
            if (!restricted) insertToken(sid, refreshHash);
            return session(sid);
        });
    }

    /** The callback re-reads authoritative business state while the API user lock is held. */
    public Rotation rotate(byte[] presentedHash, byte[] replacementHash, Predicate<Session> liveUser) {
        Token candidate = token(presentedHash);
        if (candidate == null) return new Rotation(RotationStatus.INVALID, null);
        return forUser(candidate.userId(), () -> {
            // Re-read AFTER acquiring the lock: a concurrent request may have consumed it while we waited.
            Token token = token(presentedHash);
            if (token == null) return new Rotation(RotationStatus.INVALID, null);
            Session session = session(token.sid());
            if (token.consumed()) {
                revokeLocked(session.sid(), session.userId(), "REFRESH_REUSE");
                return new Rotation(RotationStatus.REUSED, session);
            }
            if (!session.active() || session.restricted()) return new Rotation(RotationStatus.INVALID, session);
            if (!liveUser.test(session)) {
                revokeLocked(session.sid(), session.userId(), "USER_STATE_CHANGED");
                return new Rotation(RotationStatus.INVALID, session);
            }
            int consumed = jdbc.sql("""
                    UPDATE dbo.api_refresh_tokens SET consumed_at=SYSUTCDATETIME()
                    WHERE token_hash=? AND consumed_at IS NULL
                    """).param(presentedHash).update();
            if (consumed != 1) throw new IllegalStateException("Refresh consumption invariant failed");
            jdbc.sql("""
                    DECLARE @now datetime2(7)=SYSUTCDATETIME();
                    UPDATE dbo.api_sessions SET last_activity_at=@now,
                        idle_expires_at=CASE WHEN DATEADD(HOUR,8,@now)<absolute_expires_at
                            THEN DATEADD(HOUR,8,@now) ELSE absolute_expires_at END WHERE sid=?;
                    """).param(session.sid().toString()).update();
            insertToken(session.sid(), replacementHash);
            return new Rotation(RotationStatus.ROTATED, session(session.sid()));
        });
    }

    public boolean live(UUID sid, int userId, String version, String fingerprint, boolean mayBeRestricted) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM dbo.api_sessions WHERE sid=? AND user_id=? AND password_version=?
                    AND credential_fingerprint=? AND (restricted=0 OR ?=1)
                    AND revoked_at IS NULL AND idle_expires_at>SYSUTCDATETIME()
                    AND absolute_expires_at>SYSUTCDATETIME()
                """).params(sid.toString(), userId, version, fingerprint, mayBeRestricted).query(Integer.class).single() == 1;
    }

    public List<Session> list(int userId) {
        return jdbc.sql(SELECT_SESSION + " WHERE user_id=? ORDER BY created_at DESC")
                .param(userId).query(ApiSessionRepository::map).list();
    }

    public boolean revoke(UUID sid, int userId, String reason) {
        return forUser(userId, () -> revokeLocked(sid, userId, reason) == 1);
    }

    public int revokeAll(int userId, String reason) {
        return forUser(userId, () -> jdbc.sql("""
                UPDATE dbo.api_sessions SET revoked_at=SYSUTCDATETIME(),revocation_reason=?
                WHERE user_id=? AND revoked_at IS NULL
                """).params(reason, userId).update());
    }

    private int revokeLocked(UUID sid, int userId, String reason) {
        return jdbc.sql("""
                UPDATE dbo.api_sessions SET revoked_at=SYSUTCDATETIME(),revocation_reason=?
                WHERE sid=? AND user_id=? AND revoked_at IS NULL
                """).params(reason, sid.toString(), userId).update();
    }

    private Session session(UUID sid) {
        return jdbc.sql(SELECT_SESSION + " WHERE sid=?").param(sid.toString())
                .query(ApiSessionRepository::map).single();
    }

    private Token token(byte[] hash) {
        return jdbc.sql("""
                SELECT t.sid,s.user_id,t.consumed_at FROM dbo.api_refresh_tokens t
                    JOIN dbo.api_sessions s ON s.sid=t.sid WHERE t.token_hash=?
                """).param(hash).query((rs, n) -> new Token(UUID.fromString(rs.getString(1)), rs.getInt(2),
                rs.getTimestamp(3) != null)).optional().orElse(null);
    }

    private void insertToken(UUID sid, byte[] hash) {
        if (hash == null || hash.length != 32) throw new IllegalArgumentException("Expected SHA-256 hash");
        jdbc.sql("INSERT dbo.api_refresh_tokens(sid,token_hash) VALUES(?,?)").params(sid.toString(), hash).update();
    }

    private <T> T forUser(int userId, Supplier<T> work) {
        return transactions.execute(status -> {
            int result = jdbc.sql("""
                    -- Start exactly one JDBC implicit transaction before asking for a transaction-owned lock.
                    DECLARE @started int; SELECT @started=COUNT(*) FROM sys.tables;
                    DECLARE @r int;
                    EXEC @r=sys.sp_getapplock @Resource=?,@LockMode='Exclusive',@LockOwner='Transaction',@LockTimeout=10000;
                    SELECT @r;
                    """).param("AlMahwarApi:user:" + userId).query(Integer.class).single();
            if (result < 0) throw new DataAccessResourceFailureException("API session lock unavailable");
            return work.get();
        });
    }

    private static Session map(ResultSet rs, int row) throws SQLException {
        return new Session(UUID.fromString(rs.getString("sid")), rs.getInt("user_id"),
                rs.getString("password_version"), rs.getString("credential_fingerprint"),rs.getBoolean("restricted"), rs.getString("device_label"),
                instant(rs,"created_at"), instant(rs,"last_activity_at"), instant(rs,"idle_expires_at"),
                instant(rs,"absolute_expires_at"), instant(rs,"revoked_at"), rs.getBoolean("active"));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
