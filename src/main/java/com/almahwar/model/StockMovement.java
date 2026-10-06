package com.almahwar.model;

import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Table: Stock_Movements. Every change to a product's quantity has exactly one row here.
 * <p>
 * {@code quantity} is signed (+ in, − out) and
 * {@code quantityBefore + quantity = quantityAfter}.
 */
public class StockMovement {

    private Long movementId;
    private Integer productId;
    private MovementType movementType;
    private BigDecimal quantity = QuantityUtil.ZERO;
    private BigDecimal quantityBefore = QuantityUtil.ZERO;
    private BigDecimal quantityAfter = QuantityUtil.ZERO;
    private BigDecimal unitCost = MoneyUtil.ZERO;
    private String referenceType;
    private Integer referenceId;
    private String reason;
    private Integer userId;
    private LocalDateTime movementDate;
    private LocalDateTime createdAt;

    // Read-only, joined for display
    private String productCode;
    private String productName;
    private String unitName;
    private String userName;

    public Long getMovementId() { return movementId; }
    public void setMovementId(Long movementId) { this.movementId = movementId; }

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public MovementType getMovementType() { return movementType; }
    public void setMovementType(MovementType movementType) { this.movementType = movementType; }

    /** Signed: positive for stock in, negative for stock out. */
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = QuantityUtil.of(quantity); }

    public BigDecimal getQuantityBefore() { return quantityBefore; }
    public void setQuantityBefore(BigDecimal quantityBefore) { this.quantityBefore = QuantityUtil.of(quantityBefore); }

    public BigDecimal getQuantityAfter() { return quantityAfter; }
    public void setQuantityAfter(BigDecimal quantityAfter) { this.quantityAfter = QuantityUtil.of(quantityAfter); }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = MoneyUtil.of(unitCost); }

    /** Source document, e.g. {@code "SALE"} with the sale id; {@code null} for manual adjustments. */
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }

    public Integer getReferenceId() { return referenceId; }
    public void setReferenceId(Integer referenceId) { this.referenceId = referenceId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public LocalDateTime getMovementDate() { return movementDate; }
    public void setMovementDate(LocalDateTime movementDate) { this.movementDate = movementDate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
}
