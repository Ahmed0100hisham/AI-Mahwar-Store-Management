package com.almahwar.service;

import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Product management: search, add, edit, activate/deactivate.
 * <p>
 * Stock is never edited here: a new product's opening quantity is posted as an
 * {@code OPENING_BALANCE} stock movement, and later changes go through
 * {@link InventoryService}.
 * <p>
 * Users with only {@code PRODUCTS_VIEW} (e.g. the cashier) see active products only,
 * and {@link Product#getPurchasePrice()} is {@code null} for users without {@code PRODUCT_COST}.
 */
public interface ProductService {

    // Field names used in ValidationException.getErrors()
    String PRODUCT_CODE = "productCode";
    String BARCODE = "barcode";
    String NAME_AR = "nameAr";
    String NAME_EN = "nameEn";
    String CATEGORY = "categoryId";
    String BRAND = "brandId";
    String UNIT = "unitId";
    String SIZE = "size";
    String COLOR = "color";
    String PURCHASE_PRICE = "purchasePrice";
    String SALE_PRICE = "salePrice";
    String WHOLESALE_PRICE = "wholesalePrice";
    String OPENING_QUANTITY = "openingQuantity";
    String MINIMUM_STOCK = "minimumStock";
    String LOCATION = "location";
    String NOTES = "notes";

    List<Product> search(ProductFilter filter);

    Optional<Product> findById(int productId);

    /**
     * Adds a product and, when {@code openingQuantity > 0}, its {@code OPENING_BALANCE}
     * movement, in one transaction. {@code product.getQuantity()} is ignored.
     *
     * @return the saved product as stored in the database
     * @throws ValidationException with Arabic messages per field
     */
    Product create(Product product, BigDecimal openingQuantity);

    /** Saves everything except the quantity. */
    Product update(Product product);

    void setActive(int productId, boolean active);

    long countLowStock();
}
