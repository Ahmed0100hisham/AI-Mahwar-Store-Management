package com.almahwar.controller.support;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Central place for loading FXML views and resolving stylesheet paths.
 */
public final class ViewLoader {

    private static final String FXML_DIR = "/fxml/";

    public static final String STYLESHEET =
            Objects.requireNonNull(ViewLoader.class.getResource("/css/styles.css"), "styles.css not found")
                    .toExternalForm();

    private ViewLoader() {
    }

    /**
     * Loads a view from {@code src/main/resources/fxml}.
     *
     * @param fxmlName file name, e.g. {@code "main.fxml"}
     */
    public static Parent load(String fxmlName) {
        return load(fxmlName, controller -> { });
    }

    /**
     * Loads a view and passes its controller to {@code controllerSetup}
     * (e.g. to hand it a message to display).
     */
    public static <C> Parent load(String fxmlName, Consumer<C> controllerSetup) {
        URL url = ViewLoader.class.getResource(FXML_DIR + fxmlName);
        if (url == null) {
            throw new IllegalArgumentException("FXML not found: " + FXML_DIR + fxmlName);
        }
        try {
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            controllerSetup.accept(loader.getController());
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load FXML: " + fxmlName, e);
        }
    }
}
