package com.almahwar.model;

import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Table: Purchase_Items. Keeps the historical cost of this invoice forever; later purchases
 * change only the product's current cost, never this line.
 */
public class PurchaseItem {

    private Integer purchaseItemId;
    private Integer purchaseId;
    private Integer productId;
    private BigDecimal quantity = QuantityUtil.ZERO;
    private BigDecimal unitCost = MoneyUtil.ZERO;
    /** Discount on the whole line, in KWD. */
    private BigDecimal discountAmount = MoneyUtil.ZERO;

    // Read-only, joined for display
    private String productCode;
    private String productName;
    private String unitName;
    private boolean unitAllowsDecimal;

    public Integer getPurchaseItemId() { return purchaseItemId; }
    public void setPurchaseItemId(Integer purchaseItemId) { this.purchaseItemId = purchaseItemId; }

    public Integer getPurchaseId() { return purchaseId; }
    public void setPurchaseId(Integer purchaseId) { this.purchaseId = purchaseId; }

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    /** {@code null} when hidden from a user without the purchase-cost permission. */
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public boolean isUnitAllowsDecimal() { return unitAllowsDecimal; }
    public void setUnitAllowsDecimal(boolean unitAllowsDecimal) { this.unitAllowsDecimal = unitAllowsDecimal; }

    /**
     * {@code quantity × unit cost − discount}, rounded like SQL Server's persisted
     * {@code Purchase_Items.line_total} (HALF_UP, 3 decimals); {@code null} if the cost is hidden.
     */
    public BigDecimal getLineTotal() {
        if (unitCost == null || quantity == null) {
            return null;
        }
        BigDecimal discount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        return MoneyUtil.of(quantity.multiply(unitCost).subtract(discount));
    }

    /** Cost of one unit after the line discount: the cost that enters stock for this line. */
    public BigDecimal getNetUnitCost() {
        BigDecimal total = getLineTotal();
        if (total == null || quantity == null || quantity.signum() == 0) {
            return null;
        }
        return total.divide(quantity, MoneyUtil.SCALE, RoundingMode.HALF_UP);
    }

    public PurchaseItem copy() {
        PurchaseItem i = new PurchaseItem();
        i.purchaseItemId = purchaseItemId;
        i.purchaseId = purchaseId;
        i.productId = productId;
        i.quantity = quantity;
        i.unitCost = unitCost;
        i.discountAmount = discountAmount;
        i.productCode = productCode;
        i.productName = productName;
        i.unitName = unitName;
        i.unitAllowsDecimal = unitAllowsDecimal;
        return i;
    }
}
