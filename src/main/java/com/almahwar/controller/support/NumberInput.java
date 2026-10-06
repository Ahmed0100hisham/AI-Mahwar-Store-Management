package com.almahwar.controller.support;

import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

import java.math.BigDecimal;

/**
 * Numeric text fields for prices and quantities: accepts Western and Arabic-Indic
 * digits (٠١٢…), a decimal point (. or ٫) and thousands separators; the value is parsed
 * to an exact {@link BigDecimal} (no rounding — the service rejects more than 3 decimals).
 */
public final class NumberInput {

    private NumberInput() {
    }

    /** Blocks characters that can never be part of a number. */
    public static void install(TextField field) {
        field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("[0-9٠-٩.,٫٬\s]*") ? change : null));
        field.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
    }

    /**
     * @return {@code null} for blank text
     * @throws NumberFormatException if the text is not a number
     */
    public static BigDecimal parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : text.trim().toCharArray()) {
            if (ch >= '٠' && ch <= '٩') {
                sb.append((char) ('0' + (ch - '٠')));
            } else if (ch == '٫') {
                sb.append('.');
            } else if (ch != ',' && ch != '٬' && !Character.isWhitespace(ch)) {
                sb.append(ch);
            }
        }
        return new BigDecimal(sb.toString());
    }

    /** Plain text for an edit field ({@code 12.500 → "12.5"}); empty for {@code null}. */
    public static String text(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
