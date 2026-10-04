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
 */
public final class Navigator {

    private static Stage stage;
    private static Scene scene;
    private static PauseTransition idleTimer;

    private Navigator() {
    }

    /** Called once from {@code MainApp.start}. */
    public static void init(Stage primaryStage, Scene primaryScene) {
        stage = primaryStage;
        scene = primaryScene;

        int minutes = AppConfig.getInstance().getInt("app.session.timeout-minutes", 30);
        if (minutes > 0) {
            idleTimer = new PauseTransition(Duration.minutes(minutes));
            idleTimer.setOnFinished(e -> endSession(LogoutReason.TIMEOUT,
                    "تم تسجيل خروجك تلقائيًا بعد " + minutes + " دقيقة من عدم النشاط."));
            // Any mouse or keyboard activity restarts the countdown while logged in
            scene.addEventFilter(InputEvent.ANY, e -> {
                if (AppContext.get().security().isLoggedIn()) {
                    idleTimer.playFromStart();
                }
            });
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

    /** Shows the main window for the logged-in user. */
    public static void showMain() {
        String user = AppContext.get().security().currentUser().getFullName();
        setRoot(ViewLoader.load("main.fxml"));
        stage.setTitle(baseTitle() + " - " + user);
        if (idleTimer != null) {
            idleTimer.playFromStart();
        }
    }

    /** User pressed "تسجيل الخروج". */
    public static void logout() {
        endSession(LogoutReason.USER, "تم تسجيل الخروج بنجاح.");
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
