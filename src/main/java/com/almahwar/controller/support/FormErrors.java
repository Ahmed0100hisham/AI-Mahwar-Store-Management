package com.almahwar.controller.support;

import com.almahwar.service.ValidationException;
import javafx.scene.Node;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shows a service's {@link ValidationException} next to the matching fields:
 * the input gets a red border and the message appears in the label under it.
 * Messages for fields not registered here are returned so the form can show them on top.
 */
public final class FormErrors {

    private record Slot(Node input, Label message) {
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    public FormErrors register(String field, Node input, Label message) {
        Slot slot = new Slot(input, message);
        slots.put(field, slot);
        message.getStyleClass().add("field-error-text");
        message.setWrapText(true);
        hide(message);
        // Editing a field clears its error, so fixed fields stop showing red
        if (input instanceof TextInputControl text) {
            text.textProperty().addListener((o, a, b) -> clear(slot));
        } else if (input instanceof ComboBoxBase<?> combo) {
            combo.valueProperty().addListener((o, a, b) -> clear(slot));
        }
        return this;
    }

    private static void clear(Slot slot) {
        slot.input().getStyleClass().remove("field-invalid");
        hide(slot.message());
    }

    public void clear() {
        slots.values().forEach(FormErrors::clear);
    }

    /** @return messages that belong to no registered field (joined), or {@code null} */
    public String show(ValidationException e) {
        clear();
        StringBuilder other = new StringBuilder();
        Node first = null;
        for (Map.Entry<String, String> error : e.getErrors().entrySet()) {
            Slot slot = slots.get(error.getKey());
            if (slot == null) {
                other.append(other.isEmpty() ? "" : "\n").append(error.getValue());
                continue;
            }
            set(slot, error.getValue());
            if (first == null) {
                first = slot.input();
            }
        }
        if (first != null) {
            first.requestFocus();
        }
        return other.isEmpty() ? null : other.toString();
    }

    /** Marks one field as invalid from the screen itself (e.g. text that is not a number). */
    public void set(String field, String message) {
        Slot slot = slots.get(field);
        if (slot != null) {
            set(slot, message);
        }
    }

    private static void set(Slot slot, String message) {
        if (!slot.input().getStyleClass().contains("field-invalid")) {
            slot.input().getStyleClass().add("field-invalid");
        }
        slot.message().setText(message);
        slot.message().setVisible(true);
        slot.message().setManaged(true);
    }

    private static void hide(Label label) {
        label.setText("");
        label.setVisible(false);
        label.setManaged(false);
    }
}
