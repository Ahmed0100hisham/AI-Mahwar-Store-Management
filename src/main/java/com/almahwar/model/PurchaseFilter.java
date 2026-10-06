package com.almahwar.model;

import java.time.LocalDate;

/**
 * Search criteria for the purchases list; {@code null} fields are not filtered, dates are inclusive.
 *
 * @param text matched against the purchase number, the supplier's invoice number and the supplier name
 */
public record PurchaseFilter(String text, LocalDate from, LocalDate to, Integer supplierId,
                             PaymentStatus paymentStatus, PurchaseStatus status) {

    public PurchaseFilter {
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static PurchaseFilter all() {
        return new PurchaseFilter(null, null, null, null, null, null);
    }

    public static PurchaseFilter forSupplier(int supplierId) {
        return new PurchaseFilter(null, null, null, supplierId, null, null);
    }
}
