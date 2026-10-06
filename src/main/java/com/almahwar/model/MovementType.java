package com.almahwar.model;

/**
 * Kinds of stock movement ({@code Stock_Movements.movement_type}).
 * <p>
 * The direction is fixed per type: the signed quantity stored in the database
 * is {@code +qty} for stock in and {@code -qty} for stock out. Sales, purchases
 * and returns are recorded by their own modules in later phases; this phase
 * creates {@link #OPENING_BALANCE} and the two adjustments.
 */
public enum MovementType {

    OPENING_BALANCE("رصيد افتتاحي", 1),
    PURCHASE("مشتريات", 1),
    SALE("مبيعات", -1),
    SALE_RETURN("مرتجع مبيعات", 1),
    PURCHASE_RETURN("مرتجع مشتريات", -1),
    ADJUSTMENT_IN("تسوية بالزيادة", 1),
    ADJUSTMENT_OUT("تسوية بالنقص", -1),
    /** Kept for compatibility with the original schema; new damage is recorded as {@link #ADJUSTMENT_OUT}. */
    DAMAGED("تالف", -1);

    private final String labelAr;
    private final int direction;

    MovementType(String labelAr, int direction) {
        this.labelAr = labelAr;
        this.direction = direction;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** {@code +1} adds to stock, {@code -1} removes from it. */
    public int getDirection() {
        return direction;
    }

    public boolean isIncoming() {
        return direction > 0;
    }

    /** Movements a user may enter by hand on the stock adjustment screen. */
    public boolean isManualAdjustment() {
        return this == ADJUSTMENT_IN || this == ADJUSTMENT_OUT;
    }

    public static MovementType fromCode(String code) {
        return valueOf(code);
    }
}
