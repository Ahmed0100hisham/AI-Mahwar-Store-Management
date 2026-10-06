package com.almahwar.model;

import java.time.LocalDate;

/**
 * Search criteria for the expenses list; {@code null} fields are not filtered, dates are inclusive.
 *
 * @param text matched against the expense number, description, reference and notes
 */
public record ExpenseFilter(String text, LocalDate from, LocalDate to, ExpenseCategory category) {

    public ExpenseFilter {
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static ExpenseFilter all() {
        return new ExpenseFilter(null, null, null, null);
    }
}
