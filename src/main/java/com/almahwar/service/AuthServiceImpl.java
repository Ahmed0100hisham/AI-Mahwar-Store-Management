package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Role;
import com.almahwar.model.User;
import com.almahwar.model.UserSession;
import com.almahwar.service.AuthenticationException.Reason;
import com.almahwar.util.PasswordHasher;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link AuthService} that checks credentials directly against SQL Server
 * (through the DAOs) and keeps the session in the desktop {@link SessionManager}.
 * <p>
 * Security rules:
 * <ul>
 *   <li>Passwords are stored only as PBKDF2 hashes ({@link PasswordHasher}).</li>
 *   <li>Wrong username and wrong password produce the same message and take
 *       the same time, so usernames cannot be discovered.</li>
 *   <li>After {@code security.login.max-attempts} wrong passwords the account is
 *       locked for {@code security.login.lock-seconds}. The lock is stored on the
 *       Users row, so it survives restarts and applies on every PC.</li>
 *   <li>Every login, failed login and logout is written to {@code Audit_Log}.</li>
 * </ul>
 * In the future REST API this logic moves server-side unchanged, except that a
 * successful login issues an access token instead of filling SessionManager.
 */
public class AuthServiceImpl implements AuthService {

    private static final Logger LOG = Logger.getLogger(AuthServiceImpl.class.getName());

    private static final String INVALID_CREDENTIALS = "اسم المستخدم أو كلمة المرور غير صحيحة.";

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final AuditLogDao auditLogDao;
    private final LoginAttemptTracker attemptTracker;
    private final SessionManager session;

    /** Hash checked when the username does not exist, so both paths cost the same. */
    private volatile String dummyHash;

    public AuthServiceImpl(UserDao userDao, RoleDao roleDao, AuditLogDao auditLogDao,
                           LoginAttemptTracker attemptTracker, SessionManager session) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.auditLogDao = auditLogDao;
        this.attemptTracker = attemptTracker;
        this.session = session;
    }

    // ---------- Login / logout ----------

    /**
     * Verifies the credentials and starts a session.
     *
     * @throws AuthenticationException with an Arabic message for the user
     */
    @Override
    public UserSession login(String username, char[] password) throws AuthenticationException {
        try {
            if (username == null || username.isBlank() || password == null || password.length == 0) {
                throw new AuthenticationException(Reason.INVALID_INPUT, "أدخل اسم المستخدم وكلمة المرور.");
            }
            String name = username.trim();

            Optional<User> found = userDao.findByUsername(name);
            if (found.isEmpty()) {
                // Unknown usernames are throttled in memory so they behave exactly like real accounts
                long locked = attemptTracker.secondsLocked(name);
                if (locked > 0) {
                    throw lockedException(locked);
                }
                PasswordHasher.verify(password, dummyHash());   // equalize timing
                throw unknownUserFailure(name);
            }

            User user = found.get();
            if (user.isLocked()) {
                // Refused even with the correct password until the lock expires
                audit(user.getUserId(), AuditLogDao.LOGIN_FAILED, "محاولة دخول أثناء الإيقاف المؤقت: " + name);
                throw lockedException(user.getLockSecondsRemaining());
            }
            if (!PasswordHasher.verify(password, user.getPasswordHash())) {
                throw wrongPasswordFailure(user, name);
            }
            if (!user.isActive()) {
                audit(user.getUserId(), AuditLogDao.LOGIN_FAILED, "الحساب موقوف");
                throw new AuthenticationException(Reason.ACCOUNT_DISABLED,
                        "هذا الحساب موقوف. يرجى التواصل مع مدير النظام.");
            }
            if (RolePermissions.forRole(user.getRoleCode()).isEmpty()) {
                audit(user.getUserId(), AuditLogDao.LOGIN_FAILED, "الدور بدون صلاحيات: " + user.getRoleCode());
                throw new AuthenticationException(Reason.NO_PERMISSIONS,
                        "لا توجد صلاحيات مرتبطة بدور هذا المستخدم. يرجى التواصل مع مدير النظام.");
            }

            if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
                userDao.resetFailedLogins(user.getUserId());
            }
            if (PasswordHasher.needsRehash(user.getPasswordHash())) {
                userDao.updatePassword(user.getUserId(), PasswordHasher.hash(password));
            }
            userDao.updateLastLogin(user.getUserId());
            user.setPasswordHash(null);   // never keep the hash in memory for the session
            session.start(user);
            audit(user.getUserId(), AuditLogDao.LOGIN, "تسجيل دخول - " + user.getRoleName());
            return session.requireSession();
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    /** Counts the failure on the account in the database (shared by all PCs) and returns the exception to throw. */
    private AuthenticationException wrongPasswordFailure(User user, String username) {
        UserDao.LoginFailure failure = userDao.recordFailedLogin(user.getUserId(),
                attemptTracker.getMaxAttempts(), (int) attemptTracker.getLockDuration().toSeconds());
        audit(user.getUserId(), AuditLogDao.LOGIN_FAILED, "كلمة مرور خاطئة: " + username
                + (failure.locked() ? " - تم إيقاف الحساب مؤقتًا" : ""));
        if (failure.locked()) {
            return lockedException(failure.lockSecondsRemaining());
        }
        return invalidCredentials(attemptTracker.getMaxAttempts() - failure.failedAttempts());
    }

    /** Counts the failure in memory (there is no row to lock) and returns the exception to throw. */
    private AuthenticationException unknownUserFailure(String username) {
        boolean nowLocked = attemptTracker.recordFailure(username);
        audit(null, AuditLogDao.LOGIN_FAILED, "اسم مستخدم غير موجود: " + username);
        if (nowLocked) {
            return lockedException(attemptTracker.secondsLocked(username));
        }
        return invalidCredentials(attemptTracker.remainingAttempts(username));
    }

    private static AuthenticationException invalidCredentials(int remainingAttempts) {
        String hint = remainingAttempts <= 2
                ? " (متبقٍ " + remainingAttempts + " محاولة قبل الإيقاف المؤقت)" : "";
        return new AuthenticationException(Reason.INVALID_CREDENTIALS, INVALID_CREDENTIALS + hint);
    }

    private static AuthenticationException lockedException(long seconds) {
        return new AuthenticationException(Reason.ACCOUNT_LOCKED,
                "تم إيقاف تسجيل الدخول مؤقتًا بسبب كثرة المحاولات الخاطئة. حاول بعد " + seconds + " ثانية.");
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = PasswordHasher.hash("timing-equalizer".toCharArray());
        }
        return dummyHash;
    }

    /** Ends the current session (no-op if nobody is logged in). */
    @Override
    public void logout(LogoutReason reason) {
        Optional<UserSession> current = session.getSession();
        session.end();
        current.ifPresent(s -> audit(s.getUser().getUserId(), AuditLogDao.LOGOUT, reason.getDescription()));
    }

    // ---------- First run ----------

    /** {@code true} once at least one user exists; the login screen offers setup otherwise. */
    @Override
    public boolean hasUsers() {
        return userDao.count() > 0;
    }

    /**
     * Creates the first administrator. Only allowed while the Users table is empty.
     *
     * @throws IllegalArgumentException with an Arabic message if input is invalid
     * @throws IllegalStateException    if users already exist
     */
    @Override
    public User createInitialAdmin(String fullName, String username, char[] password) {
        try {
            if (hasUsers()) {
                throw new IllegalStateException("تم إنشاء حساب المدير مسبقًا.");
            }
            if (fullName == null || fullName.isBlank()) {
                throw new IllegalArgumentException("أدخل الاسم الكامل.");
            }
            CredentialPolicy.validateUsername(username);
            CredentialPolicy.validatePassword(password);

            Role admin = roleDao.findByCode(Role.ADMIN)
                    .orElseThrow(() -> new IllegalStateException("دور مدير النظام غير موجود في قاعدة البيانات."));
            User user = new User();
            user.setFullName(fullName.trim());
            user.setUsername(username.trim());
            user.setPasswordHash(PasswordHasher.hash(password));
            user.setRoleId(admin.getRoleId());
            userDao.insert(user);
            audit(user.getUserId(), AuditLogDao.INSERT, "Users", String.valueOf(user.getUserId()),
                    "إنشاء حساب مدير النظام الأول");
            return user;
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    // ---------- Audit ----------

    private void audit(Integer userId, String action, String description) {
        audit(userId, action, null, null, description);
    }

    /** Audit failures are logged but never block login/logout. */
    private void audit(Integer userId, String action, String table, String recordId, String description) {
        try {
            auditLogDao.log(userId, action, table, recordId, description);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write audit log entry: " + action, e);
        }
    }
}
