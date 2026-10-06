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
     * Tables without their own empty-state text get an Arabic one (JavaFX's default is English); tables inside
     * tabs, scroll panes and split panes are included.
     */
    static void arabicPlaceholders(javafx.scene.Node node) {
        if (node instanceof javafx.scene.control.TableView<?> table && table.getPlaceholder() == null) {
            javafx.scene.control.Label empty = new javafx.scene.control.Label("لا توجد بيانات لعرضها");
            empty.getStyleClass().add("muted");
            table.setPlaceholder(empty);
        }
        if (node instanceof javafx.scene.control.TabPane tabs) {
            tabs.getTabs().forEach(t -> {
                if (t.getContent() != null) {
                    arabicPlaceholders(t.getContent());
                }
            });
        } else if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            arabicPlaceholders(scroll.getContent());
        } else if (node instanceof javafx.scene.control.SplitPane split) {
            split.getItems().forEach(ViewLoader::arabicPlaceholders);
        } else if (node instanceof javafx.scene.control.TitledPane titled && titled.getContent() != null) {
            arabicPlaceholders(titled.getContent());
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(ViewLoader::arabicPlaceholders);
        }
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
            arabicPlaceholders(root);
            controllerSetup.accept(loader.getController());
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load FXML: " + fxmlName, e);
        }
    }
}
