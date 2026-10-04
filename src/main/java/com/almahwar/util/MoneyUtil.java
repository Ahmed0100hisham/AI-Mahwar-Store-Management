package com.almahwar.util;

import com.almahwar.config.AppConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.Locale;

/**
 * Helpers for Kuwaiti Dinar (KWD) amounts.
 * <p>
 * All money in the system is {@link BigDecimal} with a scale of 3
 * (1 KWD = 1000 fils), matching {@code DECIMAL(18,3)} in SQL Server.
 * Never use {@code double} or {@code float} for money.
 */
public final class MoneyUtil {

    public static final int SCALE = 3;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private static final Locale FORMAT_LOCALE = Locale.US;
    private static final String PATTERN = "#,##0.000";

    private MoneyUtil() {
    }

    /** Normalizes an amount to 3 decimal places; {@code null} becomes zero. */
    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(SCALE, ROUNDING);
    }

    /** Creates an amount from a string such as {@code "12.5"} or {@code "1,250.750"}. */
    public static BigDecimal of(String amount) {
        if (amount == null || amount.isBlank()) {
            return ZERO;
        }
        return of(new BigDecimal(amount.trim().replace(",", "")));
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return of(of(a).add(of(b)));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return of(of(a).subtract(of(b)));
    }

    public static BigDecimal multiply(BigDecimal amount, BigDecimal factor) {
        return of(of(amount).multiply(factor == null ? BigDecimal.ZERO : factor));
    }

    /** Formats as {@code 1,250.750} (Western digits, 3 decimals). */
    public static String format(BigDecimal amount) {
        return newFormat().format(of(amount));
    }

    /** Formats with the currency symbol, e.g. {@code 1,250.750 د.ك}. */
    public static String formatWithCurrency(BigDecimal amount) {
        return format(amount) + " " + AppConfig.getInstance().currencySymbol();
    }

    /** Parses a formatted amount back to a 3-decimal {@link BigDecimal}. */
    public static BigDecimal parse(String text) throws ParseException {
        if (text == null || text.isBlank()) {
            return ZERO;
        }
        DecimalFormat df = newFormat();
        df.setParseBigDecimal(true);
        return of((BigDecimal) df.parse(text.trim()));
    }

    private static DecimalFormat newFormat() {
        // DecimalFormat is not thread-safe; create one per call
        return new DecimalFormat(PATTERN, DecimalFormatSymbols.getInstance(FORMAT_LOCALE));
    }
}
