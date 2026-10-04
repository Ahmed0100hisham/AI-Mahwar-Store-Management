package com.almahwar.dao;

import com.almahwar.model.Product;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@code Products}.
 * <p>
 * {@link #update(Product)} deliberately does not touch {@code quantity}:
 * stock changes only through {@link #adjustQuantity(Connection, int, BigDecimal)}
 * inside the same transaction that records the sale/purchase/stock movement,
 * so two cashiers selling at once can never overwrite each other's stock.
 */
public class ProductDao extends BaseDao {

    private static final String SELECT = """
            SELECT p.product_id, p.barcode, p.product_code, p.name_ar, p.name_en,
                   p.category_id, p.brand_id, p.unit_id, p.size, p.color,
                   p.purchase_price, p.sale_price, p.wholesale_price, p.quantity, p.minimum_stock,
                   p.location, p.is_active, p.created_at, p.updated_at,
                   c.name_ar AS category_name, b.name_ar AS brand_name, u.name_ar AS unit_name
            FROM dbo.Products p
            JOIN dbo.Categories c ON c.category_id = p.category_id
            JOIN dbo.Units u      ON u.unit_id = p.unit_id
            LEFT JOIN dbo.Brands b ON b.brand_id = p.brand_id
            """;

    private static final String ORDER = " ORDER BY p.name_ar";

    public List<Product> findAll() {
        return queryList(SELECT + ORDER, ProductDao::map);
    }

    public List<Product> findAllActive() {
        return queryList(SELECT + " WHERE p.is_active = 1" + ORDER, ProductDao::map);
    }

    public Optional<Product> findById(int productId) {
        return queryOne(SELECT + " WHERE p.product_id = ?", ProductDao::map, productId);
    }

    /** Used by the barcode scanner at the point of sale. */
    public Optional<Product> findByBarcode(String barcode) {
        return queryOne(SELECT + " WHERE p.barcode = ?", ProductDao::map, barcode);
    }

    public Optional<Product> findByCode(String productCode) {
        return queryOne(SELECT + " WHERE p.product_code = ?", ProductDao::map, productCode);
    }

    public List<Product> findByCategory(int categoryId) {
        return queryList(SELECT + " WHERE p.category_id = ?" + ORDER, ProductDao::map, categoryId);
    }

    /**
     * Searches Arabic name, English name, product code and barcode.
     *
     * @param activeOnly {@code true} to hide deactivated products
     */
    public List<Product> search(String text, boolean activeOnly) {
        if (text == null || text.isBlank()) {
            return activeOnly ? findAllActive() : findAll();
        }
        String like = likeContains(text);
        String sql = SELECT + """
                 WHERE (p.name_ar LIKE ? OR p.name_en LIKE ? OR p.product_code LIKE ? OR p.barcode LIKE ?)
                """ + (activeOnly ? " AND p.is_active = 1" : "") + ORDER;
        return queryList(sql, ProductDao::map, like, like, like, like);
    }

    /** Active products at or below their minimum stock level. */
    public List<Product> findLowStock() {
        return queryList(SELECT + " WHERE p.is_active = 1 AND p.quantity <= p.minimum_stock ORDER BY p.quantity",
                ProductDao::map);
    }

    public long countActive() {
        return queryLong("SELECT COUNT(*) FROM dbo.Products WHERE is_active = 1");
    }

    public long countLowStock() {
        return queryLong("SELECT COUNT(*) FROM dbo.Products WHERE is_active = 1 AND quantity <= minimum_stock");
    }

    /**
     * Inserts the product with its opening quantity and sets its generated id.
     * The caller should also record an {@code OPENING} stock movement when quantity &gt; 0.
     */
    public int insert(Product p) {
        return insert(null, p);
    }

    public int insert(Connection con, Product p) {
        String sql = """
                INSERT INTO dbo.Products (barcode, product_code, name_ar, name_en, category_id, brand_id, unit_id,
                    size, color, purchase_price, sale_price, wholesale_price, quantity, minimum_stock, location, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Object[] params = {
                blankToNull(p.getBarcode()), p.getProductCode(), p.getNameAr(), p.getNameEn(),
                p.getCategoryId(), p.getBrandId(), p.getUnitId(), p.getSize(), p.getColor(),
                p.getPurchasePrice(), p.getSalePrice(), p.getWholesalePrice(), p.getQuantity(), p.getMinimumStock(),
                p.getLocation(), p.isActive()
        };
        int id = con == null ? insert(sql, params) : insert(con, sql, params);
        p.setProductId(id);
        return id;
    }

    /** Updates everything except {@code quantity} (see class notes). */
    public void update(Product p) {
        int rows = update("""
                UPDATE dbo.Products
                SET barcode = ?, product_code = ?, name_ar = ?, name_en = ?, category_id = ?, brand_id = ?,
                    unit_id = ?, size = ?, color = ?, purchase_price = ?, sale_price = ?, wholesale_price = ?,
                    minimum_stock = ?, location = ?, is_active = ?, updated_at = SYSDATETIME()
                WHERE product_id = ?
                """,
                blankToNull(p.getBarcode()), p.getProductCode(), p.getNameAr(), p.getNameEn(),
                p.getCategoryId(), p.getBrandId(), p.getUnitId(), p.getSize(), p.getColor(),
                p.getPurchasePrice(), p.getSalePrice(), p.getWholesalePrice(), p.getMinimumStock(),
                p.getLocation(), p.isActive(), p.getProductId());
        requireOneRow(rows, "Product", p.getProductId());
    }

    /**
     * Atomically adds {@code delta} to the stock (negative to deduct) within the
     * caller's transaction.
     *
     * @return the new quantity, to be stored as {@code Stock_Movements.balance_after}
     */
    public BigDecimal adjustQuantity(Connection con, int productId, BigDecimal delta) {
        return queryOne(con, """
                UPDATE dbo.Products
                SET quantity = quantity + ?, updated_at = SYSDATETIME()
                OUTPUT inserted.quantity
                WHERE product_id = ?
                """, rs -> rs.getBigDecimal(1), delta, productId)
                .orElseThrow(() -> new DataAccessException("Product not found: " + productId));
    }

    /** Updates the last purchase cost, typically when a purchase invoice is saved. */
    public void updatePurchasePrice(Connection con, int productId, BigDecimal purchasePrice) {
        update(con, "UPDATE dbo.Products SET purchase_price = ?, updated_at = SYSDATETIME() WHERE product_id = ?",
                purchasePrice, productId);
    }

    public void setActive(int productId, boolean active) {
        int rows = update("UPDATE dbo.Products SET is_active = ?, updated_at = SYSDATETIME() WHERE product_id = ?",
                active, productId);
        requireOneRow(rows, "Product", productId);
    }

    /** Fails with a foreign-key error once the product appears on any invoice; deactivate it instead. */
    public void delete(int productId) {
        requireOneRow(update("DELETE FROM dbo.Products WHERE product_id = ?", productId), "Product", productId);
    }

    /** Empty barcodes must be stored as NULL so the filtered UNIQUE index allows many of them. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Product map(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setProductId(rs.getInt("product_id"));
        p.setBarcode(rs.getString("barcode"));
        p.setProductCode(rs.getString("product_code"));
        p.setNameAr(rs.getString("name_ar"));
        p.setNameEn(rs.getString("name_en"));
        p.setCategoryId(rs.getInt("category_id"));
        p.setBrandId(getInteger(rs, "brand_id"));
        p.setUnitId(rs.getInt("unit_id"));
        p.setSize(rs.getString("size"));
        p.setColor(rs.getString("color"));
        p.setPurchasePrice(rs.getBigDecimal("purchase_price"));
        p.setSalePrice(rs.getBigDecimal("sale_price"));
        p.setWholesalePrice(rs.getBigDecimal("wholesale_price"));
        p.setQuantity(rs.getBigDecimal("quantity"));
        p.setMinimumStock(rs.getBigDecimal("minimum_stock"));
        p.setLocation(rs.getString("location"));
        p.setActive(rs.getBoolean("is_active"));
        p.setCreatedAt(getDateTime(rs, "created_at"));
        p.setUpdatedAt(getDateTime(rs, "updated_at"));
        p.setCategoryName(rs.getString("category_name"));
        p.setBrandName(rs.getString("brand_name"));
        p.setUnitName(rs.getString("unit_name"));
        return p;
    }
}
