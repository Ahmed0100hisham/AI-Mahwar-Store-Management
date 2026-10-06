package com.almahwar.controller;

/**
 * Where the purchase pages (details, form, print preview) are shown and where "back" goes.
 * Implemented by the purchases module, and by the supplier details page for its purchases tab.
 */
interface PurchasePages {

    /** Leaves the purchase pages (back to the list, or to the supplier). */
    void closePurchase(String message);

    void showPurchaseDetails(int purchaseId, String message);

    /** {@code null} id = new purchase. Only called when {@link #canEditPurchases()} is true. */
    void showPurchaseForm(Integer purchaseId);

    void showPurchasePrint(int purchaseId);

    default boolean canEditPurchases() {
        return true;
    }

    /** Whether a return can be started from a posted purchase here. */
    default boolean canCreateReturn() {
        return false;
    }

    /** Opens the purchase return form for this posted purchase. Only called when {@link #canCreateReturn()} is true. */
    default void showReturnForm(int purchaseId) {
        throw new UnsupportedOperationException("Returns are not available here");
    }
}
