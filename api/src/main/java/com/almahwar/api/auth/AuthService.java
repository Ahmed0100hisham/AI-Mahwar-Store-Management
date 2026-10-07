package com.almahwar.api.auth;

import com.almahwar.api.audit.AuditLogRepository;
import com.almahwar.api.auth.AuthUserRepository.LoginFailure;
import com.almahwar.api.auth.AuthUserRepository.LoginRow;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.error.ApiException;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.util.PasswordHasher;
import com.almahwar.model.Permission;
import com.almahwar.service.RolePermissions;
import com.almahwar.api.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Login — the desktop's {@code AuthServiceImpl.login} rules, step for step, with a token instead of a desktop session:
 * <ol>
 *   <li>Unknown username: throttled in memory with the same limits, and a dummy hash is checked so it takes as long
 *       as a real account. Same answer as a wrong password.</li>
 *   <li>Locked account (database, shared with the desktop): refused even with the correct password.</li>
 *   <li>Wrong password: counted atomically on the Users row; the account locks at the limit.</li>
 *   <li>Disabled account / role without permissions: refused — only after a correct password, as on the desktop.</li>
 *   <li>Success: counter cleared, an old-format hash upgraded, last login set, audit entry, token issued.</li>
 * </ol>
 * <b>Not transactional on purpose</b>: the failure counter and the audit entries must be written even though the
 * request then fails; a rollback would hand an attacker unlimited attempts.
 * <p>
 * PBKDF2 with 600,000 iterations is deliberately expensive. At most {@link #MAX_CONCURRENT_HASHES} logins hash at the
 * same time; more wait briefly, then get 429, so a burst of logins cannot exhaust the CPU of the server.
 */
@Service
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    static final int MAX_CONCURRENT_HASHES = Math.max(2, Runtime.getRuntime().availableProcessors());

    private final AuthUserRepository users;
    private final AuditLogRepository audit;
    private final LoginAttemptTracker tracker;
    private final SessionService sessions;
    private final Semaphore hashing = new Semaphore(MAX_CONCURRENT_HASHES, true);

    /** Checked when the username does not exist, so both paths cost the same. */
    private volatile String dummyHash;

    public AuthService(AuthUserRepository users, AuditLogRepository audit, LoginAttemptTracker tracker,
                       SessionService sessions) {
        this.users = users;
        this.audit = audit;
        this.tracker = tracker;
        this.sessions = sessions;
    }

    /**
     * @param password wiped before returning
     * @throws ApiException INVALID_CREDENTIALS, ACCOUNT_LOCKED, ACCOUNT_DISABLED, NO_PERMISSIONS, TOO_MANY_REQUESTS
     */
    public LoginResponse login(String username, char[] password, String clientAddress) {
        return login(username,password,clientAddress,null);
    }

    public LoginResponse login(String username, char[] password, String clientAddress, String deviceLabel) {
        try {
            if (username == null || username.isBlank() || password == null || password.length == 0) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "أدخل اسم المستخدم وكلمة المرور.");
            }
            acquireHashingSlot();
            try {
                return doLogin(username.trim(), password, clientAddress, deviceLabel);
            } finally {
                hashing.release();
            }
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    private LoginResponse doLogin(String name, char[] password, String client, String label) {
        SessionService.safeLabel(label);
        Optional<LoginRow> found = users.findForLogin(name);
        if (found.isEmpty()) {
            long locked = tracker.secondsLocked(name);
            if (locked > 0) {
                throw locked(locked);
            }
            PasswordHasher.verify(password, dummyHash());   // equalize timing
            boolean nowLocked = tracker.recordFailure(name);
            audit.logQuietly(null, AuditLogRepository.LOGIN_FAILED, null, null, "اسم مستخدم غير موجود: " + name, client);
            if (nowLocked) {
                throw locked(tracker.secondsLocked(name));
            }
            throw invalidCredentials();
        }

        LoginRow user = found.get();
        if (user.locked()) {
            audit.logQuietly(user.userId(), AuditLogRepository.LOGIN_FAILED, null, null,
                    "محاولة دخول أثناء الإيقاف المؤقت: " + name, client);
            throw locked(user.lockSecondsRemaining());
        }
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            LoginFailure failure = users.recordFailedLogin(user.userId(), tracker.maxAttempts(), tracker.lockSeconds());
            audit.logQuietly(user.userId(), AuditLogRepository.LOGIN_FAILED, null, null, "كلمة مرور خاطئة: " + name
                    + (failure.locked() ? " - تم إيقاف الحساب مؤقتًا" : ""), client);
            if (failure.locked()) {
                audit.logQuietly(user.userId(), AuditLogRepository.ACCOUNT_LOCKED, "Users", String.valueOf(user.userId()),
                        "إيقاف مؤقت للحساب " + name + " لمدة " + failure.lockSecondsRemaining() + " ثانية بعد "
                                + failure.failedAttempts() + " محاولات خاطئة", client);
                throw locked(failure.lockSecondsRemaining());
            }
            throw invalidCredentials();
        }
        if (!user.active()) {
            audit.logQuietly(user.userId(), AuditLogRepository.LOGIN_FAILED, null, null, "الحساب موقوف", client);
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        Set<Permission> rolePermissions = RolePermissions.forRole(user.roleCode());
        if (rolePermissions.isEmpty()) {
            audit.logQuietly(user.userId(), AuditLogRepository.LOGIN_FAILED, null, null,
                    "الدور بدون صلاحيات: " + user.roleCode(), client);
            throw new ApiException(ErrorCode.NO_PERMISSIONS);
        }

        if (user.failedLoginAttempts() > 0 || user.hasLockedUntil()) {
            users.resetFailedLogins(user.userId());
        }
        String expectedHash = user.passwordHash();
        if (PasswordHasher.needsRehash(expectedHash)) {
            String upgraded = PasswordHasher.hash(password);
            if (!users.upgradePasswordHash(user.userId(),expectedHash,upgraded)) throw invalidCredentials();
            expectedHash = upgraded;
        }
        users.updateLastLogin(user.userId());
        LoginResponse response = sessions.login(user,expectedHash,label);
        audit.logQuietly(user.userId(), AuditLogRepository.LOGIN, null, null, "تسجيل دخول - " + user.roleName()
                + (user.mustChangePassword() ? " (مطلوب تغيير كلمة المرور قبل المتابعة)" : ""), client);

        LOG.info("Login: user {} ({})", user.userId(), user.roleCode());
        return response;
    }

    private void acquireHashingSlot() {
        try {
            if (!hashing.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS.defaultMessage(), 5L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** One answer for unknown username and wrong password; no "attempts left" hint (it would only help guessing). */
    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }

    private static ApiException locked(long seconds) {
        return new ApiException(ErrorCode.ACCOUNT_LOCKED,
                "تم إيقاف تسجيل الدخول مؤقتًا بسبب كثرة المحاولات الخاطئة. حاول بعد " + seconds + " ثانية.", seconds);
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = PasswordHasher.hash("timing-equalizer".toCharArray());
        }
        return dummyHash;
    }
}
