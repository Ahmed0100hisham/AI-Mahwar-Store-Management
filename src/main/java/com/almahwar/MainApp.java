package com.almahwar;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.config.AppLogging;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.service.AuthService;
import com.almahwar.controller.support.Navigator;
import com.almahwar.controller.support.ViewLoader;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFX entry point for نظام إدارة شركة المحور.
 */
public class MainApp extends Application {

    private static final Logger LOG = Logger.getLogger(MainApp.class.getName());
    private static final long ALERT_INTERVAL_MS = 3000;
    private static volatile long lastAlert;
    private static volatile boolean alerting;

    @Override
    public void init() {
        Locale.setDefault(Locale.forLanguageTag(AppConfig.getInstance().get("app.locale", "ar-KW")));
    }

    @Override
    public void start(Stage stage) {
        AppConfig cfg = AppConfig.getInstance();

        // Never open larger than the usable screen area (taskbar excluded)
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        int width = cfg.getInt("app.window.width", 1280);
        int height = cfg.getInt("app.window.height", 780);
        boolean fitsScreen = width <= screen.getWidth() && height <= screen.getHeight();

        // The root is replaced by the login screen / main window via Navigator
        Scene scene = new Scene(new StackPane(), Math.min(width, screen.getWidth()), Math.min(height, screen.getHeight()));
        scene.getStylesheets().add(ViewLoader.STYLESHEET);

        stage.setMinWidth(Math.min(cfg.getInt("app.window.min-width", 1024), screen.getWidth()));
        stage.setMinHeight(Math.min(cfg.getInt("app.window.min-height", 640), screen.getHeight()));
        stage.setScene(scene);
        stage.setMaximized(cfg.getBoolean("app.window.maximized", false) || !fitsScreen);

        Navigator.init(stage, scene);
        Navigator.showLogin(null);
        stage.show();
    }

    /** Records the logout in the audit log if the window is closed while logged in. */
    @Override
    public void stop() {
        LOG.info("Shutting down");
        try {
            AppContext.get().auth().logout(AuthService.LogoutReason.APP_EXIT);
        } catch (RuntimeException e) {
            // the database may be unreachable at shutdown; nothing else to do
            LOG.log(Level.WARNING, "Logout at shutdown failed", e);
        }
        LOG.info("Stopped");
    }

    public static void main(String[] args) {
        // Apache POI (Excel export) logs through log4j-api without an implementation: use its simple logger
        // (errors only) instead of the "could not find a logging implementation" warning on the console
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        AppLogging.init();
        Thread.setDefaultUncaughtExceptionHandler(MainApp::uncaught);
        AppConfig cfg = AppConfig.getInstance();
        LOG.info("Starting " + cfg.appNameEn() + " " + cfg.appVersion() + " (Java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.name") + "); log folder: " + AppLogging.directory());
        launch(args);
    }

    /**
     * Last line of defence for errors nothing else caught: the technical details go to the application log, the
     * user gets one safe Arabic message (at most every few seconds, never a stack trace), and the program goes on.
     */
    static void uncaught(Thread thread, Throwable error) {
        LOG.log(Level.SEVERE, "Unexpected error on thread " + thread.getName(), error);
        long now = System.currentTimeMillis();
        if (alerting || now - lastAlert < ALERT_INTERVAL_MS) {
            return;
        }
        lastAlert = now;
        Runnable show = () -> {
            alerting = true;
            try {
                AlertUtil.error("خطأ غير متوقع", "حدث خطأ غير متوقع ولم تكتمل العملية. سُجّلت التفاصيل التقنية في سجل"
                        + " البرنامج. إن تكرر الخطأ فأعد تشغيل البرنامج أو تواصل مع الدعم الفني.");
            } catch (RuntimeException ignored) {
                // the window may be closing
            } finally {
                alerting = false;
            }
        };
        try {
            if (javafx.application.Platform.isFxApplicationThread()) {
                show.run();
            } else {
                javafx.application.Platform.runLater(show);
            }
        } catch (IllegalStateException toolkitNotRunning) {
            // before start / after exit: the log has it
        }
    }
}
