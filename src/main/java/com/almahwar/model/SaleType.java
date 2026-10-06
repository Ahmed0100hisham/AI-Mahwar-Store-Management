package com.almahwar.model;

import java.math.BigDecimal;

/**
 * Price list of a sale ({@code Sales.price_type}): retail uses {@code Products.sale_price},
 * wholesale uses {@code Products.wholesale_price} (or the retail price when no wholesale price is set).
 */
public enum SaleType {

    RETAIL("تجزئة"),
    WHOLESALE("جملة");

    private final String labelAr;

    SaleType(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** The list price of a product for this sale type. */
    public BigDecimal priceOf(BigDecimal salePrice, BigDecimal wholesalePrice) {
        if (this == WHOLESALE && wholesalePrice != null && wholesalePrice.signum() > 0) {
            return wholesalePrice;
        }
        return salePrice;
    }

    public BigDecimal priceOf(Product p) {
        return priceOf(p.getSalePrice(), p.getWholesalePrice());
    }

    /** Database code, e.g. {@code "RETAIL"}. */
    public String code() {
        return name();
    }

    public static SaleType fromCode(String code) {
        return code == null ? RETAIL : valueOf(code);
    }
}
