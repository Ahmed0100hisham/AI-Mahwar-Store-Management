package com.almahwar.service;

import java.util.regex.Pattern;

/**
 * Rules for usernames and passwords. Shared by every client (desktop form,
 * future mobile app and REST API) so they all validate the same way.
 */
public final class CredentialPolicy {

    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,50}");

    private CredentialPolicy() {
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
}
