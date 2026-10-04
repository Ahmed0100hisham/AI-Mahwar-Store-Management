package com.almahwar.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Stock quantities are {@link BigDecimal} with scale 3, matching
 * {@code DECIMAL(18,3)}; pipes and cables are sold by fractions of a metre.
 */
public final class QuantityUtil {

    public static final int SCALE = 3;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    private QuantityUtil() {
    }

    /** Normalizes a quantity to 3 decimal places; {@code null} becomes zero. */
    public static BigDecimal of(BigDecimal quantity) {
        return quantity == null ? ZERO : quantity.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Display form without trailing zeros: {@code 12.000 → "12"}, {@code 2.500 → "2.5"}. */
    public static String format(BigDecimal quantity) {
        return of(quantity).stripTrailingZeros().toPlainString();
    }
}
