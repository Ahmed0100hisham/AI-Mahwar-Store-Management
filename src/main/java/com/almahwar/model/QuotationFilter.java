package com.almahwar.model;

import java.time.LocalDate;

/**
 * Search criteria for the quotations list; {@code null} fields are not filtered, dates are inclusive.
 *
 * @param text matched against the quotation number, the customer's name / code / phone and the prospect's name / phone
 */
public record QuotationFilter(String text, LocalDate from, LocalDate to, QuotationStatus status, Integer customerId,
                              SaleType priceType) {

    public QuotationFilter {
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static QuotationFilter all() {
        return new QuotationFilter(null, null, null, null, null, null);
    }

    public static QuotationFilter forCustomer(int customerId) {
        return new QuotationFilter(null, null, null, null, customerId, null);
    }
}
