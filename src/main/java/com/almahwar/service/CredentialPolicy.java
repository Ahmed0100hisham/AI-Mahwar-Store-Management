package com.almahwar.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rules for usernames and passwords. Shared by every client (desktop form,
 * future mobile app and REST API) so they all validate the same way.
 * <p>
 * Usernames: 3–50 English letters, digits or {@code . _ -}; stored trimmed and in lower case (the database
 * compares them case-insensitively too, so "Admin" and "admin" are the same account).
 * <p>
 * Passwords: at least 8 characters with letters and digits, at most 128, not the username. Deliberately not
 * stricter: long passphrases are welcome, forced symbols / expiry are not required.
 */
public final class CredentialPolicy {

    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 128;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,50}");

    private CredentialPolicy() {
    }

    /** Trimmed, lower-case username (the stored form); {@code null} stays {@code null}. */
    public static String normalizeUsername(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException with an Arabic message */
    public static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException(
                    "اسم المستخدم يجب أن يكون من 3 إلى 50 حرفًا إنجليزيًا أو رقمًا (يُسمح بـ . _ -).");
        }
    }

    /** @throws IllegalArgumentException with an Arabic message */
    public static void validatePassword(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("كلمة المرور يجب ألا تقل عن " + MIN_PASSWORD_LENGTH + " أحرف.");
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("كلمة المرور يجب ألا تزيد عن " + MAX_PASSWORD_LENGTH + " حرفًا.");
        }
        boolean letter = false;
        boolean digit = false;
        for (char c : password) {
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) {
            throw new IllegalArgumentException("كلمة المرور يجب أن تحتوي على حروف وأرقام.");
        }
    }

    /**
     * Full check of a new password for an account: the rules above, not the username, and typed twice the same.
     *
     * @throws IllegalArgumentException with an Arabic message
     */
    public static void validateNewPassword(char[] password, char[] confirm, String username) {
        validatePassword(password);
        if (username != null && sameIgnoringCase(password, username.trim())) {
            throw new IllegalArgumentException("كلمة المرور يجب ألا تكون مثل اسم المستخدم.");
        }
        if (confirm == null || !java.util.Arrays.equals(password, confirm)) {
            throw new IllegalArgumentException("تأكيد كلمة المرور لا يطابق كلمة المرور.");
        }
    }

    /** Compares without turning the password into a String (no extra copy left in memory). */
    private static boolean sameIgnoringCase(char[] password, String text) {
        if (password.length != text.length()) {
            return false;
        }
        for (int i = 0; i < password.length; i++) {
            if (Character.toLowerCase(password[i]) != Character.toLowerCase(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
