package com.almahwar.model;

import java.time.LocalDate;

/**
 * Search criteria for the cashbox movements; {@code null} fields are not filtered, dates are inclusive.
 *
 * @param text matched against the description, notes, reference and the source document's number
 */
public record CashFilter(String text, LocalDate from, LocalDate to, CashMovement.Direction direction,
                         CashSource source, PaymentMethod method, Integer userId) {

    public CashFilter {
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static CashFilter all() {
        return new CashFilter(null, null, null, null, null, null, null);
    }
}
