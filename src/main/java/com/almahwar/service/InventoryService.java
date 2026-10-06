package com.almahwar.service;

import com.almahwar.model.MovementFilter;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.StockAdjustment;
import com.almahwar.model.StockMovement;

import java.util.List;

/**
 * Stock balances, manual adjustments and the movement history.
 * Every change to a quantity produces exactly one stock movement (see {@link StockLedger}).
 */
public interface InventoryService {

    String PRODUCT = "productId";
    String TYPE = "type";
    String QUANTITY = StockLedger.QUANTITY_FIELD;
    String REASON = "reason";

    int MAX_HISTORY_ROWS = 1000;

    /** Products with their current stock. */
    List<Product> stockList(ProductFilter filter);

    /**
     * Applies an {@code ADJUSTMENT_IN}/{@code ADJUSTMENT_OUT} in one transaction, recording the
     * user and the server time.
     *
     * @throws ValidationException for invalid input or if the stock would go below zero
     */
    StockMovement adjust(StockAdjustment adjustment);

    /** Newest first, at most {@link #MAX_HISTORY_ROWS}. */
    List<StockMovement> history(MovementFilter filter);

    /** Common reasons offered on the adjustment screen; any other text is accepted. */
    List<String> suggestedReasons();

    long countLowStock();
}
