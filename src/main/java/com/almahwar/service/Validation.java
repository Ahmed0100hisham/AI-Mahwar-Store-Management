package com.almahwar.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collects field errors during validation and throws them together. */
final class Validation {

    /** DECIMAL(18,3) holds at most 15 digits before the decimal point. */
    private static final BigDecimal MAX_DECIMAL = new BigDecimal("1000000000000000");

    private final Map<String, String> errors = new LinkedHashMap<>();

    void error(String field, String message) {
        errors.putIfAbsent(field, message);
    }

    boolean has(String field) {
        return errors.containsKey(field);
    }

    void required(String field, String value, String message) {
        if (value == null || value.isBlank()) {
            error(field, message);
        }
    }

    void maxLength(String field, String value, int max, String label) {
        if (value != null && value.trim().length() > max) {
            error(field, label + " يجب ألا يزيد عن " + max + " حرفًا.");
        }
    }

    /** Required, ≥ 0, at most 3 decimal places, fits in DECIMAL(18,3). */
    void nonNegative(String field, BigDecimal value, String label) {
        nonNegative(field, value, label, false);
    }

    /** @param feminine Arabic agreement for feminine labels such as "الكمية" (مطلوبة / تكون) */
    void nonNegative(String field, BigDecimal value, String label, boolean feminine) {
        if (value == null) {
            error(field, label + (feminine ? " مطلوبة." : " مطلوب."));
        } else if (value.signum() < 0) {
            error(field, label + (feminine ? " يجب أن تكون" : " يجب أن يكون") + " صفرًا أو أكثر.");
        } else {
            checkDecimal(field, value, label);
        }
    }

    /** Optional amount that may be negative (e.g. an opening balance in the customer's favour). */
    void amount(String field, BigDecimal value, String label) {
        if (value != null) {
            checkDecimal(field, value, label);
        }
    }

    /** Required and &gt; 0, at most 3 decimal places. */
    void positive(String field, BigDecimal value, String label) {
        if (value == null) {
            error(field, label + " مطلوبة.");
        } else if (value.signum() <= 0) {
            error(field, label + " يجب أن تكون أكبر من صفر.");
        } else {
            checkDecimal(field, value, label);
        }
    }

    private void checkDecimal(String field, BigDecimal value, String label) {
        if (value.stripTrailingZeros().scale() > 3) {
            error(field, label + ": الحد الأقصى 3 منازل عشرية.");
        } else if (value.abs().compareTo(MAX_DECIMAL) >= 0) {
            error(field, label + ": القيمة كبيرة جدًا.");
        }
    }

    /** Pieces, sets and cartons are counted in whole numbers; metres and kilos may be fractions. */
    void wholeNumberUnless(boolean allowsDecimal, String field, BigDecimal value, String unitName) {
        if (!allowsDecimal && value != null && value.stripTrailingZeros().scale() > 0) {
            error(field, "الوحدة \"" + unitName + "\" لا تقبل الكسور، أدخل رقمًا صحيحًا.");
        }
    }

    void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
