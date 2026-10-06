package com.almahwar.service;

import com.almahwar.util.PhoneNumbers;

/** Validation shared by customers and suppliers. */
final class PartyRules {

    private PartyRules() {
    }

    /** Expects a number already normalized by {@link PhoneNumbers#normalize}. */
    static void phone(Validation v, String field, String normalized) {
        String error = PhoneNumbers.validate(normalized);
        if (error != null) {
            v.error(field, error);
        } else {
            v.maxLength(field, normalized, 20, "رقم الهاتف");
        }
    }

    static void email(Validation v, String field, String email) {
        if (email == null) {
            return;
        }
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            v.error(field, "البريد الإلكتروني غير صحيح.");
        } else {
            v.maxLength(field, email, 100, "البريد الإلكتروني");
        }
    }
}
