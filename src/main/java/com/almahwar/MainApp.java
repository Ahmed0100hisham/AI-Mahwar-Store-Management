package com.almahwar;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
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

/**
 * JavaFX entry point for نظام إدارة شركة المحور.
 */
public class MainApp extends Application {

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
        try {
            AppContext.get().auth().logout(AuthService.LogoutReason.APP_EXIT);
        } catch (RuntimeException ignored) {
            // the database may be unreachable at shutdown; nothing else to do
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
