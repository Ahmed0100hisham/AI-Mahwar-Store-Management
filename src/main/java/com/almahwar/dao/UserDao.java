package com.almahwar.dao;

import com.almahwar.model.User;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@code Users}.
 * <p>
 * Users are never hard-deleted because sales, purchases and the audit log
 * reference them; deactivate with {@link #setActive(int, boolean)} instead.
 */
public class UserDao extends BaseDao {

    private static final String SELECT = """
            SELECT u.user_id, u.username, u.password_hash, u.full_name, u.phone, u.email, u.role_id,
                   u.is_active, u.last_login_at, u.failed_login_attempts, u.locked_until,
                   CASE WHEN u.locked_until > SYSDATETIME()
                        THEN DATEDIFF(SECOND, SYSDATETIME(), u.locked_until) + 1 ELSE 0 END AS lock_seconds_remaining,
                   u.created_at, u.updated_at,
                   r.role_code, r.role_name
            FROM dbo.Users u
            JOIN dbo.Roles r ON r.role_id = u.role_id
            """;

    public List<User> findAll() {
        return queryList(SELECT + " ORDER BY u.full_name", UserDao::map);
    }

    public Optional<User> findById(int userId) {
        return queryOne(SELECT + " WHERE u.user_id = ?", UserDao::map, userId);
    }

    /** Lookup for login. Usernames are case-insensitive (Arabic_CI_AS collation). */
    public Optional<User> findByUsername(String username) {
        return queryOne(SELECT + " WHERE u.username = ?", UserDao::map, username);
    }

    /** Inserts the user (including password hash) and sets its generated id. */
    public int insert(User user) {
        int id = insert("""
                INSERT INTO dbo.Users (username, password_hash, full_name, phone, email, role_id, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                user.getUsername(), user.getPasswordHash(), user.getFullName(), user.getPhone(),
                user.getEmail(), user.getRoleId(), user.isActive());
        user.setUserId(id);
        return id;
    }

    /** Updates profile fields. The password is changed only through {@link #updatePassword}. */
    public void update(User user) {
        int rows = update("""
                UPDATE dbo.Users
                SET username = ?, full_name = ?, phone = ?, email = ?, role_id = ?, is_active = ?,
                    updated_at = SYSDATETIME()
                WHERE user_id = ?
                """,
                user.getUsername(), user.getFullName(), user.getPhone(), user.getEmail(),
                user.getRoleId(), user.isActive(), user.getUserId());
        requireOneRow(rows, "User", user.getUserId());
    }

    public void updatePassword(int userId, String passwordHash) {
        int rows = update("UPDATE dbo.Users SET password_hash = ?, updated_at = SYSDATETIME() WHERE user_id = ?",
                passwordHash, userId);
        requireOneRow(rows, "User", userId);
    }

    public void updateLastLogin(int userId) {
        update("UPDATE dbo.Users SET last_login_at = SYSDATETIME() WHERE user_id = ?", userId);
    }

    /** Result of {@link #recordFailedLogin}. */
    public record LoginFailure(int failedAttempts, long lockSecondsRemaining) {
        public boolean locked() {
            return lockSecondsRemaining > 0;
        }
    }

    /**
     * Atomically counts a wrong password and locks the account once
     * {@code maxAttempts} is reached. A lock that has already expired starts a
     * new count. Uses the database server's clock so all PCs agree.
     */
    public LoginFailure recordFailedLogin(int userId, int maxAttempts, int lockSeconds) {
        // In SET, column references see the values from before this UPDATE
        return queryOne("""
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
                """, rs -> new LoginFailure(rs.getInt(1), rs.getLong(2)), maxAttempts, lockSeconds, userId)
                .orElseThrow(() -> new DataAccessException("User not found: " + userId));
    }

    /** Clears the failed-attempt counter and any lock (successful login, or unlocked by an admin). */
    public void resetFailedLogins(int userId) {
        update("UPDATE dbo.Users SET failed_login_attempts = 0, locked_until = NULL WHERE user_id = ?", userId);
    }

    public void setActive(int userId, boolean active) {
        int rows = update("UPDATE dbo.Users SET is_active = ?, updated_at = SYSDATETIME() WHERE user_id = ?",
                active, userId);
        requireOneRow(rows, "User", userId);
    }

    public long count() {
        return queryLong("SELECT COUNT(*) FROM dbo.Users");
    }

    private static User map(ResultSet rs) throws SQLException {
        User u = new User();
        u.setUserId(rs.getInt("user_id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setFullName(rs.getString("full_name"));
        u.setPhone(rs.getString("phone"));
        u.setEmail(rs.getString("email"));
        u.setRoleId(rs.getInt("role_id"));
        u.setActive(rs.getBoolean("is_active"));
        u.setLastLoginAt(getDateTime(rs, "last_login_at"));
        u.setFailedLoginAttempts(rs.getInt("failed_login_attempts"));
        u.setLockedUntil(getDateTime(rs, "locked_until"));
        u.setLockSecondsRemaining(rs.getLong("lock_seconds_remaining"));
        u.setCreatedAt(getDateTime(rs, "created_at"));
        u.setUpdatedAt(getDateTime(rs, "updated_at"));
        u.setRoleCode(rs.getString("role_code"));
        u.setRoleName(rs.getString("role_name"));
        return u;
    }
}
