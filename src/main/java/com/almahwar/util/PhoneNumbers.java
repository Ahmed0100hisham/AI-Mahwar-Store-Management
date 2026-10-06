package com.almahwar.util;

/**
 * Phone numbers as stored in {@code Customers/Suppliers.phone} (NVARCHAR(20)).
 * <ul>
 *   <li>Kuwaiti numbers are stored as their 8 local digits: {@code "9988 7766"}, {@code "+965 99887766"}
 *       and {@code "00965-9988-7766"} all become {@code "99887766"};</li>
 *   <li>other countries keep the international format: {@code "00971 50 123 4567"} → {@code "+971501234567"}.</li>
 * </ul>
 * Arabic-Indic digits (٠١٢…) are accepted. Storing one form makes searching and duplicate warnings work.
 */
public final class PhoneNumbers {

    public static final String KUWAIT_CODE = "965";
    private static final int KUWAIT_LOCAL_LENGTH = 8;

    private PhoneNumbers() {
    }

    /** @return the stored form, or {@code null} for blank input (invalid input is returned cleaned, see {@link #validate}) */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (ch >= '٠' && ch <= '٩') {
                sb.append((char) ('0' + (ch - '٠')));
            } else if (ch >= '۰' && ch <= '۹') {   // Persian digits
                sb.append((char) ('0' + (ch - '۰')));
            } else if (Character.isDigit(ch) || ch == '+') {
                sb.append(ch);
            } else if (!(Character.isWhitespace(ch) || ch == '-' || ch == '(' || ch == ')' || ch == '.' || ch == '/')) {
                sb.append(ch);   // kept so validate() can reject it
            }
        }
        String s = sb.toString();
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        }
        if (s.startsWith("+" + KUWAIT_CODE) && s.length() == 1 + KUWAIT_CODE.length() + KUWAIT_LOCAL_LENGTH) {
            s = s.substring(1 + KUWAIT_CODE.length());
        }
        return s;
    }

    /** @return an Arabic error message, or {@code null} if the (normalized) number is acceptable */
    public static String validate(String normalized) {
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("+")) {
            return normalized.matches("\\+[1-9][0-9]{6,14}")
                    ? null : "الرقم الدولي يبدأ بـ + أو 00 ثم من 7 إلى 15 رقمًا.";
        }
        if (!normalized.matches("[0-9]+")) {
            return "رقم الهاتف يجب أن يحتوي على أرقام فقط.";
        }
        return normalized.length() == KUWAIT_LOCAL_LENGTH
                ? null : "رقم الكويت يتكون من 8 أرقام؛ للأرقام الدولية ابدأ بـ + أو 00.";
    }

    /** Digits only, for searching: {@code "9988-7766"} → {@code "99887766"}. */
    public static String digits(String text) {
        String n = normalize(text);
        return n == null ? "" : n.replaceAll("[^0-9]", "");
    }

    /** Display form: {@code "99887766"} → {@code "9988 7766"}; international numbers unchanged. */
    public static String format(String stored) {
        if (stored == null) {
            return "";
        }
        if (stored.length() == KUWAIT_LOCAL_LENGTH && stored.matches("[0-9]+")) {
            return stored.substring(0, 4) + " " + stored.substring(4);
        }
        return stored;
    }
}
