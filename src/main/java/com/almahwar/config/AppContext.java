package com.almahwar.config;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.DashboardDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.UserDao;
import com.almahwar.service.AuthService;
import com.almahwar.service.AuthServiceImpl;
import com.almahwar.service.DashboardService;
import com.almahwar.service.DashboardServiceImpl;
import com.almahwar.service.LoginAttemptTracker;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.SessionManager;
import com.almahwar.service.SystemStatusService;
import com.almahwar.service.SystemStatusServiceImpl;

import java.time.Clock;
import java.time.Duration;

/**
 * Composition root: the single place that decides which implementation of each
 * service the application uses. Controllers ask this class for services and
 * never construct them.
 * <p>
 * Today every service is the local implementation (Service → DAO → SQL Server).
 * When the REST API exists, this is where the desktop app switches to the
 * HTTP-client implementations (e.g. a {@code RestAuthService}), with no changes
 * to controllers.
 */
public final class AppContext {

    private static final AppContext INSTANCE = new AppContext();

    private final SessionManager sessionManager;
    private final AuthService authService;
    private final DashboardService dashboardService;
    private final SystemStatusService systemStatusService;

    private AppContext() {
        AppConfig cfg = AppConfig.getInstance();
        sessionManager = SessionManager.getInstance();

        LoginAttemptTracker attemptTracker = new LoginAttemptTracker(
                cfg.getInt("security.login.max-attempts", 5),
                Duration.ofSeconds(cfg.getInt("security.login.lock-seconds", 60)),
                Clock.systemUTC());
        authService = new AuthServiceImpl(new UserDao(), new RoleDao(), new AuditLogDao(),
                attemptTracker, sessionManager);
        dashboardService = new DashboardServiceImpl(new DashboardDao(), sessionManager);
        systemStatusService = new SystemStatusServiceImpl(cfg);
    }

    public static AppContext get() {
        return INSTANCE;
    }

    public AppConfig config() {
        return AppConfig.getInstance();
    }

    /** The current user and permissions of this desktop session. */
    public SecurityContext security() {
        return sessionManager;
    }

    public AuthService auth() {
        return authService;
    }

    public DashboardService dashboard() {
        return dashboardService;
    }

    public SystemStatusService systemStatus() {
        return systemStatusService;
    }
}
