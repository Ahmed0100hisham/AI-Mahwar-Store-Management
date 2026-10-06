package com.almahwar.service;

import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.StockMovementDao.QuantityChange;
import com.almahwar.model.MovementType;
import com.almahwar.model.StockMovement;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.sql.Connection;

/**
 * The only way stock changes: updates {@code Products.quantity} and writes the
 * matching {@code Stock_Movements} row on the <b>caller's transaction</b>.
 * <p>
 * Used for opening balances and adjustments now, and by the sales, purchases and
 * returns services in later phases, e.g.
 * {@code ledger.post(con, productId, MovementType.SALE, qty, "SALE", saleId, null, userId)}.
 * Stock never goes below zero: the change is refused and the transaction rolls back.
 * <p>
 * Lock order: a document that changes several products (sale, purchase, later returns) first locks all of them
 * with {@code ProductDao.lockForStockChange} — always {@code product_id} ascending — and then posts its lines in
 * that same order, so concurrent documents sharing products never deadlock.
 */
public class StockLedger {

    public static final String QUANTITY_FIELD = "quantity";

    private final StockMovementDao movementDao;

    public StockLedger(StockMovementDao movementDao) {
        this.movementDao = movementDao;
    }

    /**
     * @param quantity      positive amount; the direction comes from {@code type}
     * @param referenceType source document type ({@code "SALE"}, {@code "PRODUCT"} ...), or {@code null}
     * @return the saved movement with quantity before/after
     * @throws ValidationException if the stock is not enough or the product does not exist
     */
    public StockMovement post(Connection con, int productId, MovementType type, BigDecimal quantity,
                              String referenceType, Integer referenceId, String reason, int userId) {
        return post(con, productId, type, quantity, null, referenceType, referenceId, reason, userId);
    }

    /**
     * Same, recording the document's own cost (e.g. a purchase line's net unit cost) on the movement.
     *
     * @param unitCost {@code null} to record the product's current purchase price
     */
    public StockMovement post(Connection con, int productId, MovementType type, BigDecimal quantity,
                              BigDecimal unitCost, String referenceType, Integer referenceId, String reason,
                              int userId) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Movement quantity must be positive: " + quantity);
        }
        BigDecimal qty = QuantityUtil.of(quantity);
        BigDecimal delta = type.isIncoming() ? qty : qty.negate();

        QuantityChange change = movementDao.changeQuantity(con, productId, delta)
                .orElseThrow(() -> refused(con, productId, qty));
        if (change.before().add(delta).compareTo(change.after()) != 0) {
            throw new IllegalStateException("Stock arithmetic mismatch for product " + productId);
        }

        StockMovement m = new StockMovement();
        m.setProductId(productId);
        m.setMovementType(type);
        m.setQuantity(delta);
        m.setQuantityBefore(change.before());
        m.setQuantityAfter(change.after());
        m.setUnitCost(unitCost != null ? unitCost : change.purchasePrice());
        m.setReferenceType(referenceType);
        m.setReferenceId(referenceId);
        m.setReason(reason);
        m.setUserId(userId);
        movementDao.insert(con, m);
        return m;
    }

    private ValidationException refused(Connection con, int productId, BigDecimal requested) {
        BigDecimal available = movementDao.currentQuantity(con, productId);
        if (available == null) {
            return new ValidationException("productId", "المنتج غير موجود.");
        }
        return new ValidationException(QUANTITY_FIELD, "لا يمكن أن يصبح المخزون بالسالب: الكمية المطلوبة "
                + QuantityUtil.format(requested) + " أكبر من الرصيد المتوفر " + QuantityUtil.format(available) + ".");
    }
}
