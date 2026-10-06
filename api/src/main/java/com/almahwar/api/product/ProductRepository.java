package com.almahwar.api.product;

import com.almahwar.api.web.PageQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only product queries for the API, on the desktop's tables and with the desktop's search semantics (Arabic /
 * English name, code, barcode; {@code LIKE} wildcards in the text are escaped the same way). Every value is a bound
 * parameter; the ORDER BY comes from {@link ProductSort} constants only.
 * <p>
 * The purchase price is not even selected unless the caller may see it.
 */
@Repository
public class ProductRepository {

    /** A product row as listed; {@code purchasePrice} is null when it was not selected. */
    public record ProductRow(int id, String code, String barcode, String nameAr, String nameEn, String category,
                             String brand, String unit, String size, String color, BigDecimal salePrice,
                             BigDecimal wholesalePrice, BigDecimal purchasePrice, BigDecimal quantity,
                             BigDecimal minimumStock, boolean active) {
    }

    private static final String FROM = """
             FROM dbo.Products p
             JOIN dbo.Categories c ON c.category_id = p.category_id
             JOIN dbo.Units u      ON u.unit_id = p.unit_id
             LEFT JOIN dbo.Brands b ON b.brand_id = p.brand_id
            """;

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProductRow> findPage(String text, boolean activeOnly, boolean includeCost, ProductSort.Order order,
                                     PageQuery page) {
        List<Object> params = new ArrayList<>();
        String where = where(text, activeOnly, params);
        params.add(page.offset());
        params.add(page.size());
        String sql = """
                SELECT p.product_id, p.product_code, p.barcode, p.name_ar, p.name_en,
                       c.name_ar AS category_name, b.name_ar AS brand_name, u.name_ar AS unit_name,
                       p.size, p.color, p.sale_price, p.wholesale_price, %s AS purchase_price,
                       p.quantity, p.minimum_stock, p.is_active
                """.formatted(includeCost ? "p.purchase_price" : "CAST(NULL AS DECIMAL(18, 3))")
                + FROM + where + " ORDER BY " + order.sql() + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        return jdbc.sql(sql).params(params).query(ProductRepository::map).list();
    }

    public long count(String text, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT_BIG(*)" + FROM + where(text, activeOnly, params);
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static String where(String text, boolean activeOnly, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (text != null && !text.isBlank()) {
            String like = likeContains(text);
            where.append(" AND (p.name_ar LIKE ? OR p.name_en LIKE ? OR p.product_code LIKE ? OR p.barcode LIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if (activeOnly) {
            where.append(" AND p.is_active = 1");
        }
        return where.toString();
    }

    /** The desktop's {@code BaseDao.likeContains}: user text is matched literally. */
    static String likeContains(String text) {
        String escaped = text.trim()
                .replace("[", "[[]")
                .replace("%", "[%]")
                .replace("_", "[_]");
        return "%" + escaped + "%";
    }

    private static ProductRow map(ResultSet rs, int row) throws SQLException {
        return new ProductRow(rs.getInt("product_id"), rs.getString("product_code"), rs.getString("barcode"),
                rs.getString("name_ar"), rs.getString("name_en"), rs.getString("category_name"),
                rs.getString("brand_name"), rs.getString("unit_name"), rs.getString("size"), rs.getString("color"),
                rs.getBigDecimal("sale_price"), rs.getBigDecimal("wholesale_price"), rs.getBigDecimal("purchase_price"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("minimum_stock"), rs.getBoolean("is_active"));
    }
}
