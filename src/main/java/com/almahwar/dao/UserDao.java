package com.almahwar.dao;

import com.almahwar.model.User;

import java.sql.Connection;
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
                   u.must_change_password, u.password_changed_at,
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
                INSERT INTO dbo.Users (username, password_hash, full_name, phone, email, role_id, is_active,
                                       must_change_password, password_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, SYSDATETIME())
                """,
                user.getUsername(), user.getPasswordHash(), user.getFullName(), user.getPhone(),
                user.getEmail(), user.getRoleId(), user.isActive(), user.isMustChangePassword());
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

    // ---------- user administration (inside the caller's transaction) ----------

    /** Users matching a text (username / name / phone), role and status; newest login first is not needed: by name. */
    public List<User> search(String text, String roleCode, Boolean active) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new java.util.ArrayList<>();
        if (text != null && !text.isBlank()) {
            String like = likeContains(text);
            sql.append(" AND (u.username LIKE ? OR u.full_name LIKE ? OR u.phone LIKE ? OR u.email LIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if (roleCode != null) {
            sql.append(" AND r.role_code = ?");
            params.add(roleCode);
        }
        if (active != null) {
            sql.append(" AND u.is_active = ?");
            params.add(active);
        }
        sql.append(" ORDER BY u.is_active DESC, u.full_name");
        return queryList(sql.toString(), UserDao::map, params.toArray());
    }

    /**
     * Locks and returns the ids of the active administrators (UPDLOCK + HOLDLOCK: concurrent changes to admins wait
     * for each other, so two of them can never remove the last active admin together).
     */
    public List<Integer> lockActiveAdmins(Connection con) {
        return queryList(con, """
                SELECT u.user_id FROM dbo.Users u WITH (UPDLOCK, HOLDLOCK)
                JOIN dbo.Roles r ON r.role_id = u.role_id
                WHERE r.role_code = 'ADMIN' AND u.is_active = 1
                """, rs -> rs.getInt(1));
    }

    /** The user with its row locked for the rest of the transaction. */
    public Optional<User> lockById(Connection con, int userId) {
        return queryOne(con, SELECT.replace("FROM dbo.Users u", "FROM dbo.Users u WITH (UPDLOCK, ROWLOCK)")
                + " WHERE u.user_id = ?", UserDao::map, userId);
    }

    public int insert(Connection con, User user) {
        int id = insert(con, """
                INSERT INTO dbo.Users (username, password_hash, full_name, phone, email, role_id, is_active,
                                       must_change_password, password_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, SYSDATETIME())
                """,
                user.getUsername(), user.getPasswordHash(), user.getFullName(), user.getPhone(),
                user.getEmail(), user.getRoleId(), user.isActive(), user.isMustChangePassword());
        user.setUserId(id);
        return id;
    }

    /**
     * Inserts the first user only while the table is empty (the check and the insert are one statement, with the
     * table range locked): two first-run setups at the same time cannot both create an administrator.
     *
     * @return the new id, or empty if a user already exists
     */
    public Optional<Integer> insertFirst(Connection con, User user) {
        return queryOne(con, """
                INSERT INTO dbo.Users (username, password_hash, full_name, phone, email, role_id, is_active,
                                       must_change_password, password_changed_at)
                OUTPUT inserted.user_id
                SELECT ?, ?, ?, ?, ?, ?, 1, 0, SYSDATETIME()
                WHERE NOT EXISTS (SELECT 1 FROM dbo.Users WITH (UPDLOCK, HOLDLOCK))
                """, rs -> rs.getInt(1), user.getUsername(), user.getPasswordHash(), user.getFullName(),
                user.getPhone(), user.getEmail(), user.getRoleId());
    }

    public void updateProfile(Connection con, int userId, String fullName, String phone, String email) {
        requireOneRow(update(con, "UPDATE dbo.Users SET full_name = ?, phone = ?, email = ?, updated_at = SYSDATETIME() "
                + "WHERE user_id = ?", fullName, phone, email, userId), "User", userId);
    }

    public void updateRole(Connection con, int userId, int roleId) {
        requireOneRow(update(con, "UPDATE dbo.Users SET role_id = ?, updated_at = SYSDATETIME() WHERE user_id = ?",
                roleId, userId), "User", userId);
    }

    public void setActive(Connection con, int userId, boolean active) {
        requireOneRow(update(con, "UPDATE dbo.Users SET is_active = ?, updated_at = SYSDATETIME() WHERE user_id = ?",
                active, userId), "User", userId);
    }

    /**
     * Stores a new password hash: clears the failed-login counter and any lock, records when it changed, and sets
     * whether the next login must choose a new one (admin reset) or not (the user's own change).
     */
    public void setPassword(Connection con, int userId, String passwordHash, boolean mustChange) {
        requireOneRow(update(con, """
                UPDATE dbo.Users
                SET password_hash = ?, must_change_password = ?, password_changed_at = SYSDATETIME(),
                    failed_login_attempts = 0, locked_until = NULL, updated_at = SYSDATETIME()
                WHERE user_id = ?
                """, passwordHash, mustChange, userId), "User", userId);
    }

    public void unlock(Connection con, int userId) {
        requireOneRow(update(con, "UPDATE dbo.Users SET failed_login_attempts = 0, locked_until = NULL WHERE user_id = ?",
                userId), "User", userId);
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
        u.setMustChangePassword(rs.getBoolean("must_change_password"));
        u.setPasswordChangedAt(getDateTime(rs, "password_changed_at"));
        u.setCreatedAt(getDateTime(rs, "created_at"));
        u.setUpdatedAt(getDateTime(rs, "updated_at"));
        u.setRoleCode(rs.getString("role_code"));
        u.setRoleName(rs.getString("role_name"));
        return u;
    }
}
