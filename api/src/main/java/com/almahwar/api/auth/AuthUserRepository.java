package com.almahwar.api.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The {@code dbo.Users} reads and writes needed for authentication. The SQL is the desktop {@code UserDao}'s (same
 * columns, same server-clock lock arithmetic), so desktop and API share one account state: a lock set by either
 * applies to both, and neither can be used to bypass the other's attempt limit.
 */
@Repository
public class AuthUserRepository {

    /** Login lookup: the hash stays inside authentication services and never enters an HTTP response. */
    private static final String SELECT_LOGIN = """
            SELECT u.user_id, u.username, u.password_hash, u.full_name, u.is_active, u.must_change_password,
                   u.failed_login_attempts, u.locked_until, u.password_changed_at,
                   CASE WHEN u.locked_until > SYSDATETIME()
                        THEN DATEDIFF(SECOND, SYSDATETIME(), u.locked_until) + 1 ELSE 0 END AS lock_seconds_remaining,
                   r.role_code, r.role_name
            FROM dbo.Users u
            JOIN dbo.Roles r ON r.role_id = u.role_id
            WHERE u.username = ?
            """;

    /** Per-request state: map the stored hash to a server-only fingerprint, never retain the hash in UserState. */
    private static final String SELECT_STATE = """
            SELECT u.user_id, u.username, u.full_name, u.is_active, u.must_change_password, u.password_changed_at,
                   u.password_hash,
                   r.role_code, r.role_name
            FROM dbo.Users u
            JOIN dbo.Roles r ON r.role_id = u.role_id
            WHERE u.user_id = ?
            """;

    /** A user as read for login. */
    public record LoginRow(int userId, String username, String passwordHash, String fullName, boolean active,
                           boolean mustChangePassword, int failedLoginAttempts, boolean hasLockedUntil,
                           long lockSecondsRemaining, LocalDateTime passwordChangedAt, String roleCode,
                           String roleName) {

        public boolean locked() {
            return lockSecondsRemaining > 0;
        }

        /** Never print the hash. */
        @Override
        public String toString() {
            return "LoginRow[userId=" + userId + ", username=" + username + ", role=" + roleCode + "]";
        }
    }

    /** What an authenticated request needs to know about its user, re-read on every request. */
    public record UserState(int userId, String username, String fullName, boolean active, boolean mustChangePassword,
                            LocalDateTime passwordChangedAt, String roleCode, String roleName, String credentialFingerprint) {
        public UserState(int userId, String username, String fullName, boolean active, boolean mustChangePassword,
                         LocalDateTime passwordChangedAt, String roleCode, String roleName) {
            this(userId,username,fullName,active,mustChangePassword,passwordChangedAt,roleCode,roleName,null);
        }
        @Override public String toString() { return "UserState[userId="+userId+", role="+roleCode+"]"; }
    }

    /** Result of {@link #recordFailedLogin}. */
    public record LoginFailure(int failedAttempts, long lockSecondsRemaining) {
        public boolean locked() {
            return lockSecondsRemaining > 0;
        }
    }

    private final JdbcClient jdbc;

    public AuthUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Usernames are case-insensitive (Arabic_CI_AS collation), as on the desktop. */
    public Optional<LoginRow> findForLogin(String username) {
        return jdbc.sql(SELECT_LOGIN).param(username).query(AuthUserRepository::mapLogin).optional();
    }

    public Optional<UserState> findState(int userId) {
        return jdbc.sql(SELECT_STATE).param(userId).query(AuthUserRepository::mapState).optional();
    }

    /**
     * Atomically counts a wrong password and locks the account once {@code maxAttempts} is reached; an expired lock
     * starts a new count. The desktop's statement, unchanged (server clock, so every client agrees).
     */
    public LoginFailure recordFailedLogin(int userId, int maxAttempts, int lockSeconds) {
        return jdbc.sql("""
                        UPDATE dbo.Users
                        SET failed_login_attempts = CASE WHEN locked_until <= SYSDATETIME() THEN 1
                                                         ELSE failed_login_attempts + 1 END,
                            locked_until = CASE
                                WHEN (CASE WHEN locked_until <= SYSDATETIME() THEN 1 ELSE failed_login_attempts + 1 END) >= ?
                                    THEN DATEADD(SECOND, ?, SYSDATETIME())
                                WHEN locked_until <= SYSDATETIME() THEN NULL
                                ELSE locked_until END
                        OUTPUT inserted.failed_login_attempts,
                               CASE WHEN inserted.locked_until > SYSDATETIME()
                                    THEN DATEDIFF(SECOND, SYSDATETIME(), inserted.locked_until) + 1 ELSE 0 END
                        WHERE user_id = ?
                        """)
                .params(maxAttempts, lockSeconds, userId)
                .query((rs, n) -> new LoginFailure(rs.getInt(1), rs.getLong(2)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
    }

    /** Clears the failed-attempt counter and any lock (successful login). */
    public void resetFailedLogins(int userId) {
        jdbc.sql("UPDATE dbo.Users SET failed_login_attempts = 0, locked_until = NULL WHERE user_id = ?")
                .param(userId).update();
    }

    /** Re-hash with the current settings on login (does not count as a password change, as on the desktop). */
    public boolean upgradePasswordHash(int userId, String oldHash, String newHash) {
        return jdbc.sql("UPDATE dbo.Users SET password_hash = ?, updated_at = SYSDATETIME() WHERE user_id = ? AND password_hash COLLATE Latin1_General_100_BIN2 = ?")
                .params(newHash, userId, oldHash).update() == 1;
    }

    public Optional<LoginRow> findCredentials(int userId) {
        return jdbc.sql(SELECT_LOGIN.replace("WHERE u.username = ?", "WHERE u.user_id = ?"))
                .param(userId).query(AuthUserRepository::mapLogin).optional();
    }

    /** Optimistic credential CAS: a concurrent Desktop reset must never be overwritten by a stale request.
     * DATETIME2(0) requires a monotonic second to invalidate pwv even for two changes in the same second. */
    public boolean changePassword(int userId, String oldHash, String newHash) {
        return jdbc.sql("""
                UPDATE dbo.Users SET password_hash=?, must_change_password=0,
                    password_changed_at=CASE WHEN password_changed_at>=CONVERT(datetime2(0),SYSDATETIME())
                        THEN DATEADD(SECOND,1,password_changed_at) ELSE SYSDATETIME() END,
                    failed_login_attempts=0,locked_until=NULL,updated_at=SYSDATETIME()
                WHERE user_id=? AND is_active=1 AND password_hash COLLATE Latin1_General_100_BIN2=?
                """).params(newHash,userId,oldHash).update()==1;
    }

    /** Extra server-only check closes same-second Desktop password-reset gaps in DATETIME2(0) pwv. */
    public static String fingerprint(String hash) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public void updateLastLogin(int userId) {
        jdbc.sql("UPDATE dbo.Users SET last_login_at = SYSDATETIME() WHERE user_id = ?").param(userId).update();
    }

    private static LoginRow mapLogin(ResultSet rs, int row) throws SQLException {
        return new LoginRow(rs.getInt("user_id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("full_name"), rs.getBoolean("is_active"), rs.getBoolean("must_change_password"),
                rs.getInt("failed_login_attempts"), rs.getTimestamp("locked_until") != null,
                rs.getLong("lock_seconds_remaining"), dateTime(rs, "password_changed_at"),
                rs.getString("role_code"), rs.getString("role_name"));
    }

    private static UserState mapState(ResultSet rs, int row) throws SQLException {
        return new UserState(rs.getInt("user_id"), rs.getString("username"), rs.getString("full_name"),
                rs.getBoolean("is_active"), rs.getBoolean("must_change_password"),
                dateTime(rs, "password_changed_at"), rs.getString("role_code"), rs.getString("role_name"),
                fingerprint(rs.getString("password_hash")));
    }

    private static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }
}
