package com.almahwar.service;

import com.almahwar.model.User;
import com.almahwar.model.UserSession;

/**
 * Authentication as seen by the clients (JavaFX today, Flutter later).
 * <p>
 * Implementations:
 * <ul>
 *   <li>{@link AuthServiceImpl} (current): checks SQL Server directly through the DAOs.</li>
 *   <li>Future {@code RestAuthService}: calls {@code POST /api/auth/login} on the
 *       backend, keeps the returned access token in the session, and sends it with
 *       every later request. Controllers do not change; only
 *       {@code AppContext} chooses the other implementation.</li>
 * </ul>
 * All methods may block (password hashing, network/database), so clients must
 * call them off the UI thread.
 */
public interface AuthService {

    /** Why a session ended; stored in the audit log. */
    enum LogoutReason {
        USER("تسجيل خروج"),
        TIMEOUT("انتهاء الجلسة بسبب عدم النشاط"),
        APP_EXIT("إغلاق البرنامج");

        private final String description;

        LogoutReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Verifies the credentials and starts a session. The password array is wiped afterwards.
     *
     * @throws AuthenticationException with an Arabic message that is safe to show
     */
    UserSession login(String username, char[] password) throws AuthenticationException;

    /** Ends the current session (no-op if nobody is logged in). */
    void logout(LogoutReason reason);

    /** {@code false} on first run, when the system administrator must be created. */
    boolean hasUsers();

    /**
     * Creates the first administrator; only allowed while there are no users.
     *
     * @throws IllegalArgumentException with an Arabic message if input is invalid
     * @throws IllegalStateException    if users already exist
     */
    User createInitialAdmin(String fullName, String username, char[] password);
}
