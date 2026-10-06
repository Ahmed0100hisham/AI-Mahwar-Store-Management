package com.almahwar.model;

import java.math.BigDecimal;

/**
 * A manual stock adjustment request (stock-taking, damage, correction ...).
 *
 * @param type     {@link MovementType#ADJUSTMENT_IN} or {@link MovementType#ADJUSTMENT_OUT}
 * @param quantity positive amount to add or remove
 * @param reason   required, e.g. "جرد المخزن"
 */
public record StockAdjustment(Integer productId, MovementType type, BigDecimal quantity, String reason) {
}
