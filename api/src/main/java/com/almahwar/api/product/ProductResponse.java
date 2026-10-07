package com.almahwar.api.product;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.almahwar.model.Product;

import java.math.BigDecimal;

/**
 * A product in a list (API contract — not the database row). Money and quantities are decimal <b>strings</b>
 * ({@code "12.500"}): exact, never rounded through a floating-point number on the client. {@code purchasePrice} (cost)
 * is present only for users with the {@code PRODUCT_COST} permission — otherwise the field is absent.
 */
public record ProductResponse(
        int id, String code, String barcode, String nameAr, String nameEn,
        String category, String brand, String unit, String size, String color,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salePrice,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal wholesalePrice,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal purchasePrice,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minimumStock,
        boolean active) {

    /** Maps a row; the cost is copied only when the caller may see it (it is also not selected otherwise). */
    static ProductResponse of(Product r, boolean includeCost) {
        return new ProductResponse(r.getProductId(), r.getProductCode(), r.getBarcode(), r.getNameAr(), r.getNameEn(),
                r.getCategoryName(), r.getBrandName(), r.getUnitName(), r.getSize(), r.getColor(), r.getSalePrice(),
                r.getWholesalePrice(), includeCost ? r.getPurchasePrice() : null, r.getQuantity(), r.getMinimumStock(),
                r.isActive());
    }
}
