package com.almahwar.model;

import java.time.LocalDate;

/**
 * Search criteria for the sales list; {@code null} fields are not filtered, dates are inclusive.
 *
 * @param text matched against the sale number, the customer's name / code and phone numbers
 */
public record SaleFilter(String text, LocalDate from, LocalDate to, Integer customerId, Integer cashierId,
                         PaymentStatus paymentStatus, SaleType saleType, SaleStatus status) {

    public SaleFilter {
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static SaleFilter all() {
        return new SaleFilter(null, null, null, null, null, null, null, null);
    }

    public static SaleFilter forCustomer(int customerId) {
        return new SaleFilter(null, null, null, customerId, null, null, null, null);
    }
}
