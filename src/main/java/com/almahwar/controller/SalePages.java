package com.almahwar.controller;

/**
 * Where the sale pages (details, print preview, the POS for a held draft) are shown and where "back" goes.
 * Implemented by the sales module, the point of sale itself, and the customer details page for its
 * invoices tab.
 */
interface SalePages {

    /** Leaves the sale pages (back to the list, the POS, or the customer). */
    void closeSale(String message);

    void showSaleDetails(int saleId, String message);

    void showSalePrint(int saleId);

    /** Opens the point of sale with a held draft. Only called when {@link #canOpenPos()} is true. */
    void showPos(int draftSaleId);

    default boolean canOpenPos() {
        return false;
    }

    /** Whether a return can be started from a posted sale here. */
    default boolean canCreateReturn() {
        return false;
    }

    /** Opens the sales return form for this posted sale. Only called when {@link #canCreateReturn()} is true. */
    default void showReturnForm(int saleId) {
        throw new UnsupportedOperationException("Returns are not available here");
    }
}
