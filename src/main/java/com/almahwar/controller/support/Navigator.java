package com.almahwar.controller.support;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.controller.LoginController;
import com.almahwar.service.AuthService;
import com.almahwar.service.AuthService.LogoutReason;
import javafx.animation.PauseTransition;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Switches the main window between the login screen and the application,
 * and logs the user out automatically after a period of inactivity
 * ({@code app.session.timeout-minutes}, 0 = never).
 * <p>
 * Every way of leaving the current page — side menu, dashboard links, logout, inactivity timeout, closing the
 * window — passes through {@link #leave(Runnable)}, which asks the registered {@link LeaveGuard} (the POS
 * with an unsaved cart) first. Pages never repeat this check themselves.
 */
public final class Navigator {

    private static Stage stage;
    private static Scene scene;
    private static PauseTransition idleTimer;
    private static LeaveGuard leaveGuard;

    private Navigator() {
    }

    /** Called once from {@code MainApp.start}. */
    public static void init(Stage primaryStage, Scene primaryScene) {
        stage = primaryStage;
        scene = primaryScene;

        int minutes = AppConfig.getInstance().getInt("app.session.timeout-minutes", 30);
        if (minutes > 0) {
            idleTimer = new PauseTransition(Duration.minutes(minutes));
            idleTimer.setOnFinished(e -> timeout(minutes));
            // Any mouse or keyboard activity restarts the countdown while logged in
            scene.addEventFilter(InputEvent.ANY, e -> {
                if (AppContext.get().security().isLoggedIn()) {
                    idleTimer.playFromStart();
                }
            });
        }

        // Closing the window with unsaved work: ask first (the window closes only if the user chooses to leave)
        stage.setOnCloseRequest(e -> {
            if (hasUnsavedWork()) {
                e.consume();
                leaveGuard.askToLeave(stage::close);
            }
        });
    }

    // ---------- Leave guard ----------

    /** The page whose unsaved work must be protected; {@code null} when there is none. */
    public static void setLeaveGuard(LeaveGuard guard) {
        leaveGuard = guard;
    }

    /** Removes {@code guard} if it is still the registered one (a newer page may have replaced it). */
    public static void clearLeaveGuard(LeaveGuard guard) {
        if (leaveGuard == guard) {
            leaveGuard = null;
        }
    }

    public static boolean hasUnsavedWork() {
        return leaveGuard != null && leaveGuard.hasUnsavedWork();
    }

    /**
     * Runs {@code navigation} now if nothing would be lost, otherwise only after the user saved or discarded
     * the work (or never, if they chose to stay).
     */
    public static void leave(Runnable navigation) {
        if (hasUnsavedWork()) {
            leaveGuard.askToLeave(navigation);
        } else {
            navigation.run();
        }
    }

    private static void timeout(int minutes) {
        String message = "تم تسجيل خروجك تلقائيًا بعد " + minutes + " دقيقة من عدم النشاط.";
        if (hasUnsavedWork()) {
            // security first: the session still ends, but the cart is held as a draft before that
            leaveGuard.saveBeforeForcedExit(() -> endSession(LogoutReason.TIMEOUT, message));
        } else {
            endSession(LogoutReason.TIMEOUT, message);
        }
    }

    /** Shows the login screen, optionally with an information message. */
    public static void showLogin(String message) {
        if (idleTimer != null) {
            idleTimer.stop();
        }
        setRoot(ViewLoader.<LoginController>load("login.fxml", c -> c.showInfo(message)));
        stage.setTitle(baseTitle());
    }

    /**
     * Shows the main window for the logged-in user — or, when the account must change its password first (after an
     * admin reset), the change-password page: such a session has no permissions, so nothing else would work anyway.
     */
    public static void showMain() {
        boolean mustChange = AppContext.get().security().getSession().map(s -> s.isPasswordChangeRequired()).orElse(false);
        if (mustChange) {
            showChangePassword(true);
            return;
        }
        String user = AppContext.get().security().currentUser().getFullName();
        setRoot(ViewLoader.load("main.fxml"));
        stage.setTitle(baseTitle() + " - " + user);
        if (idleTimer != null) {
            idleTimer.playFromStart();
        }
    }

    /**
     * The change-password page of the logged-in user.
     *
     * @param forced the password must be changed before using the program (no way back, only logout)
     */
    public static void showChangePassword(boolean forced) {
        leaveGuard = null;
        setRoot(ViewLoader.<com.almahwar.controller.ChangePasswordController>load("change-password.fxml",
                c -> c.open(forced)));
        stage.setTitle(baseTitle() + " - تغيير كلمة المرور");
        if (idleTimer != null) {
            idleTimer.playFromStart();
        }
    }

    /** After a password change the session has ended: back to the login screen with a message. */
    public static void passwordChanged() {
        leaveGuard = null;
        showLogin("تم تغيير كلمة المرور. سجّل الدخول بكلمة المرور الجديدة.");
    }

    /**
     * The database was restored from a backup (or a restore failed after it had replaced it): the session has
     * already ended, every page belongs to the old data — back to the login screen with the outcome.
     */
    public static void databaseRestored(String message) {
        leaveGuard = null;
        showLogin(message);
    }

    /** User pressed "تسجيل الخروج" (guarded: unsaved work is protected first). */
    public static void logout() {
        leave(() -> endSession(LogoutReason.USER, "تم تسجيل الخروج بنجاح."));
    }

    private static void endSession(LogoutReason reason, String message) {
        if (!AppContext.get().security().isLoggedIn()) {
            return;
        }
        AuthService auth = AppContext.get().auth();
        // The audit-log write must not freeze the UI
        Thread t = new Thread(() -> auth.logout(reason), "logout");
        t.setDaemon(true);
        t.start();
        leaveGuard = null;
        showLogin(message);
    }

    private static void setRoot(Parent root) {
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        scene.setRoot(root);
    }

    private static String baseTitle() {
        AppConfig cfg = AppConfig.getInstance();
        return cfg.appName() + " - " + cfg.appNameEn();
    }
}
