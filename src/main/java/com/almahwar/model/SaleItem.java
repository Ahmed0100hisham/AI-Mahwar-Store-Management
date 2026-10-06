package com.almahwar.model;

import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;

/**
 * Table: Sale_Items. {@code unitCost} is the product's cost captured when the sale was posted; it never
 * changes afterwards, so the invoice's profit stays historical even when purchase prices move.
 */
public class SaleItem {

    private Integer saleItemId;
    private Integer saleId;
    private Integer productId;
    private BigDecimal quantity = QuantityUtil.ZERO;
    private BigDecimal unitPrice = MoneyUtil.ZERO;
    /** Historical cost of one unit; 0 on drafts (captured at posting); {@code null} when hidden. */
    private BigDecimal unitCost = MoneyUtil.ZERO;
    /** Discount on the whole line, in KWD. */
    private BigDecimal discountAmount = MoneyUtil.ZERO;

    // Read-only, joined for display
    private String productCode;
    private String productName;
    private String barcode;
    private String unitName;
    private boolean unitAllowsDecimal;

    public Integer getSaleItemId() { return saleItemId; }
    public void setSaleItemId(Integer saleItemId) { this.saleItemId = saleItemId; }

    public Integer getSaleId() { return saleId; }
    public void setSaleId(Integer saleId) { this.saleId = saleId; }

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public boolean isUnitAllowsDecimal() { return unitAllowsDecimal; }
    public void setUnitAllowsDecimal(boolean unitAllowsDecimal) { this.unitAllowsDecimal = unitAllowsDecimal; }

    /**
     * {@code quantity × unit price − line discount}, rounded like SQL Server's persisted
     * {@code Sale_Items.line_total} (HALF_UP, 3 decimals).
     */
    public BigDecimal getLineTotal() {
        if (unitPrice == null || quantity == null) {
            return null;
        }
        BigDecimal discount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        return MoneyUtil.of(quantity.multiply(unitPrice).subtract(discount));
    }

    /** {@code quantity × historical unit cost} (3 decimals); {@code null} when the cost is hidden. */
    public BigDecimal getCostTotal() {
        if (unitCost == null || quantity == null) {
            return null;
        }
        return MoneyUtil.of(quantity.multiply(unitCost));
    }

    /** Line profit before the invoice-level discount; {@code null} when the cost is hidden. */
    public BigDecimal getLineProfit() {
        BigDecimal total = getLineTotal();
        BigDecimal cost = getCostTotal();
        return total == null || cost == null ? null : MoneyUtil.of(total.subtract(cost));
    }

    public SaleItem copy() {
        SaleItem i = new SaleItem();
        i.saleItemId = saleItemId;
        i.saleId = saleId;
        i.productId = productId;
        i.quantity = quantity;
        i.unitPrice = unitPrice;
        i.unitCost = unitCost;
        i.discountAmount = discountAmount;
        i.productCode = productCode;
        i.productName = productName;
        i.barcode = barcode;
        i.unitName = unitName;
        i.unitAllowsDecimal = unitAllowsDecimal;
        return i;
    }
}
