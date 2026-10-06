package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * One line of a return ({@code Sale_Return_Items} / {@code Purchase_Return_Items}), or one original line offered for
 * return (with how much was bought / sold, already returned and still returnable).
 */
public class ReturnLine {

    private Integer returnItemId;
    /** The original invoice line ({@code sale_item_id} / {@code purchase_item_id}). */
    private Integer originalItemId;
    private Integer productId;
    private String productCode;
    private String productName;
    private String unitName;
    private boolean unitAllowsDecimal;
    /** Quantity returned by this document (or requested on screen). */
    private BigDecimal quantity;
    /** Net value of one unit as actually invoiced (after line and invoice discounts). */
    private BigDecimal unitPrice;
    /** Historical cost of one unit (sale returns: the sale line's cost snapshot; purchase returns: = unit price). */
    private BigDecimal unitCost;

    // Only on lines offered for return
    private BigDecimal originalQuantity;
    private BigDecimal returnedQuantity;

    public Integer getReturnItemId() { return returnItemId; }
    public void setReturnItemId(Integer returnItemId) { this.returnItemId = returnItemId; }

    public Integer getOriginalItemId() { return originalItemId; }
    public void setOriginalItemId(Integer originalItemId) { this.originalItemId = originalItemId; }

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public boolean isUnitAllowsDecimal() { return unitAllowsDecimal; }
    public void setUnitAllowsDecimal(boolean unitAllowsDecimal) { this.unitAllowsDecimal = unitAllowsDecimal; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public BigDecimal getOriginalQuantity() { return originalQuantity; }
    public void setOriginalQuantity(BigDecimal originalQuantity) { this.originalQuantity = originalQuantity; }

    public BigDecimal getReturnedQuantity() { return returnedQuantity; }
    public void setReturnedQuantity(BigDecimal returnedQuantity) { this.returnedQuantity = returnedQuantity; }

    /** What can still be returned of the original line. */
    public BigDecimal getRemainingQuantity() {
        if (originalQuantity == null) {
            return null;
        }
        return originalQuantity.subtract(returnedQuantity == null ? BigDecimal.ZERO : returnedQuantity);
    }

    /** {@code quantity × unit price}, rounded like SQL Server's persisted {@code line_total}. */
    public BigDecimal getLineTotal() {
        return quantity == null || unitPrice == null ? null : MoneyUtil.of(quantity.multiply(unitPrice));
    }

    /** {@code quantity × unit cost} (3 decimals). */
    public BigDecimal getCostTotal() {
        return quantity == null || unitCost == null ? null : MoneyUtil.of(quantity.multiply(unitCost));
    }

    public ReturnLine copy() {
        ReturnLine l = new ReturnLine();
        l.returnItemId = returnItemId;
        l.originalItemId = originalItemId;
        l.productId = productId;
        l.productCode = productCode;
        l.productName = productName;
        l.unitName = unitName;
        l.unitAllowsDecimal = unitAllowsDecimal;
        l.quantity = quantity;
        l.unitPrice = unitPrice;
        l.unitCost = unitCost;
        l.originalQuantity = originalQuantity;
        l.returnedQuantity = returnedQuantity;
        return l;
    }
}
