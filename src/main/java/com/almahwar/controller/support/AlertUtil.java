package com.almahwar.controller.support;

import javafx.geometry.NodeOrientation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.util.Optional;

/**
 * Arabic, right-to-left dialog helpers.
 */
public final class AlertUtil {

    private AlertUtil() {
    }

    public static void info(String title, String message) {
        build(Alert.AlertType.INFORMATION, title, message).showAndWait();
    }

    public static void warning(String title, String message) {
        build(Alert.AlertType.WARNING, title, message).showAndWait();
    }

    public static void error(String title, String message) {
        build(Alert.AlertType.ERROR, title, message).showAndWait();
    }

    /** @return {@code true} if the user pressed OK */
    public static boolean confirm(String title, String message) {
        Optional<ButtonType> result = build(Alert.AlertType.CONFIRMATION, title, message).showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private static Alert build(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        alert.getDialogPane().getStylesheets().add(ViewLoader.STYLESHEET);
        return alert;
    }
}
