package com.almahwar.service;

import java.math.BigDecimal;

/**
 * Decides a product's purchase cost ({@code Products.purchase_price}) after stock is received.
 * <p>
 * The cost of each purchase line is always kept on {@code Purchase_Items} and on its
 * {@code Stock_Movements} row; this policy only sets the product's <i>current</i> cost used for
 * new documents. Old invoices never change.
 * <p>
 * The application uses {@link #LAST_PURCHASE_COST}; switching to {@link #WEIGHTED_AVERAGE} later is a
 * one-line change in {@code AppContext} (plus, ideally, a one-off recalculation of current costs).
 */
@FunctionalInterface
public interface CostingPolicy {

    /**
     * @param currentCost  the product's cost before this receipt
     * @param stockBefore  quantity in stock before this receipt
     * @param receivedQty  quantity received (positive)
     * @param receivedCost net cost of one received unit
     */
    BigDecimal costAfterReceipt(BigDecimal currentCost, BigDecimal stockBefore, BigDecimal receivedQty,
                                BigDecimal receivedCost);

    /** The latest posted purchase cost becomes the product's cost. */
    CostingPolicy LAST_PURCHASE_COST = (current, before, qty, cost) -> cost;

    /** Moving weighted average: (stock × current cost + received × received cost) / new stock. */
    CostingPolicy WEIGHTED_AVERAGE = (current, before, qty, cost) -> {
        BigDecimal stock = before.max(BigDecimal.ZERO);
        BigDecimal newStock = stock.add(qty);
        if (newStock.signum() == 0) {
            return cost;
        }
        return stock.multiply(current).add(qty.multiply(cost))
                .divide(newStock, 3, java.math.RoundingMode.HALF_UP);
    };
}
