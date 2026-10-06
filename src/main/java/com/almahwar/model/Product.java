package com.almahwar.model;

import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Products */
public class Product {

    private Integer productId;
    private String barcode;
    private String productCode;
    private String nameAr;
    private String nameEn;
    private Integer categoryId;
    private Integer brandId;
    private Integer unitId;
    private String size;
    private String color;
    private BigDecimal purchasePrice = MoneyUtil.ZERO;
    private BigDecimal salePrice = MoneyUtil.ZERO;
    private BigDecimal wholesalePrice = MoneyUtil.ZERO;
    private BigDecimal quantity = QuantityUtil.ZERO;
    private BigDecimal minimumStock = QuantityUtil.ZERO;
    private String location;
    private String notes;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Read-only, joined for display
    private String categoryName;
    private String brandName;
    private String unitName;

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getNameAr() { return nameAr; }
    public void setNameAr(String nameAr) { this.nameAr = nameAr; }

    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }

    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }

    public Integer getBrandId() { return brandId; }
    public void setBrandId(Integer brandId) { this.brandId = brandId; }

    public Integer getUnitId() { return unitId; }
    public void setUnitId(Integer unitId) { this.unitId = unitId; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    /** {@code null} when hidden from a user who may not see purchase costs. */
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice == null ? null : MoneyUtil.of(purchasePrice);
    }

    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = MoneyUtil.of(salePrice); }

    public BigDecimal getWholesalePrice() { return wholesalePrice; }
    public void setWholesalePrice(BigDecimal wholesalePrice) { this.wholesalePrice = MoneyUtil.of(wholesalePrice); }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = QuantityUtil.of(quantity); }

    public BigDecimal getMinimumStock() { return minimumStock; }
    public void setMinimumStock(BigDecimal minimumStock) { this.minimumStock = QuantityUtil.of(minimumStock); }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    /** Stock is at or below the re-order level. */
    public boolean isLowStock() {
        return quantity.compareTo(minimumStock) <= 0;
    }

    /** Sale price for the requested price list. */
    public BigDecimal priceFor(boolean wholesale) {
        return wholesale ? wholesalePrice : salePrice;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Product other && productId != null && productId.equals(other.productId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(productId);
    }

    @Override
    public String toString() {
        return nameAr;
    }
}
