package com.almahwar.model;

import java.time.LocalDate;

/**
 * Search criteria for the stock movement history. {@code null} fields are not filtered;
 * both dates are inclusive.
 */
public record MovementFilter(Integer productId, MovementType type, LocalDate from, LocalDate to) {

    public static MovementFilter all() {
        return new MovementFilter(null, null, null, null);
    }

    public static MovementFilter forProduct(int productId) {
        return new MovementFilter(productId, null, null, null);
    }
}
