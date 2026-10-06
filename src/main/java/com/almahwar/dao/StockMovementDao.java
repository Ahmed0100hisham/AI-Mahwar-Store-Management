package com.almahwar.dao;

import com.almahwar.model.MovementFilter;
import com.almahwar.model.MovementType;
import com.almahwar.model.StockMovement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@code Stock_Movements} and the stock column of {@code Products}.
 * <p>
 * Stock is changed only by {@link #changeQuantity} followed by {@link #insert}, both
 * on the caller's transaction connection, so a product's quantity and its movement
 * history can never disagree.
 */
public class StockMovementDao extends BaseDao {

    /** Result of a stock change: quantities before/after and the product's cost at that moment. */
    public record QuantityChange(BigDecimal before, BigDecimal after, BigDecimal purchasePrice) {
    }

    private static final String SELECT = """
            SELECT m.movement_id, m.product_id, m.movement_date, m.movement_type, m.quantity,
                   m.quantity_before, m.balance_after, m.unit_cost, m.reference_type, m.reference_id,
                   m.notes, m.user_id, m.created_at,
                   p.product_code, p.name_ar AS product_name, un.name_ar AS unit_name, u.full_name AS user_name
            FROM dbo.Stock_Movements m
            JOIN dbo.Products p ON p.product_id = m.product_id
            JOIN dbo.Units un   ON un.unit_id = p.unit_id
            JOIN dbo.Users u    ON u.user_id = m.user_id
            """;

    /**
     * Atomically adds {@code delta} (negative to deduct) to the product's stock, unless
     * the result would be negative. The row stays locked until the transaction ends.
     *
     * @return empty if the product does not exist or the stock is not enough
     */
    public Optional<QuantityChange> changeQuantity(Connection con, int productId, BigDecimal delta) {
        return queryOne(con, """
                UPDATE dbo.Products
                SET quantity = quantity + ?, updated_at = SYSDATETIME()
                OUTPUT deleted.quantity, inserted.quantity, inserted.purchase_price
                WHERE product_id = ? AND quantity + ? >= 0
                """, rs -> new QuantityChange(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                delta, productId, delta);
    }

    /** Current stock, read inside the caller's transaction ({@code null} if the product does not exist). */
    public BigDecimal currentQuantity(Connection con, int productId) {
        return queryOne(con, "SELECT quantity FROM dbo.Products WHERE product_id = ?",
                rs -> rs.getBigDecimal(1), productId).orElse(null);
    }

    /** Inserts the movement and sets its id and date. {@code quantity_before} is computed by SQL Server. */
    public long insert(Connection con, StockMovement m) {
        StockMovement saved = queryOne(con, """
                INSERT INTO dbo.Stock_Movements (product_id, movement_type, quantity, balance_after, unit_cost,
                    reference_type, reference_id, notes, user_id)
                OUTPUT inserted.movement_id, inserted.movement_date, inserted.quantity_before
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rs -> {
                    StockMovement r = new StockMovement();
                    r.setMovementId(rs.getLong(1));
                    r.setMovementDate(getDateTime(rs, "movement_date"));
                    r.setQuantityBefore(rs.getBigDecimal(3));
                    return r;
                },
                m.getProductId(), m.getMovementType().name(), m.getQuantity(), m.getQuantityAfter(), m.getUnitCost(),
                m.getReferenceType(), m.getReferenceId(), m.getReason(), m.getUserId())
                .orElseThrow(() -> new DataAccessException("Stock movement was not inserted"));
        m.setMovementId(saved.getMovementId());
        m.setMovementDate(saved.getMovementDate());
        m.setCreatedAt(saved.getMovementDate());
        m.setQuantityBefore(saved.getQuantityBefore());
        return saved.getMovementId();
    }

    /** History, newest first, at most {@code limit} rows. */
    public List<StockMovement> find(MovementFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT TOP (?) * FROM (").append(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(limit);
        if (filter.productId() != null) {
            sql.append(" AND m.product_id = ?");
            params.add(filter.productId());
        }
        if (filter.type() != null) {
            sql.append(" AND m.movement_type = ?");
            params.add(filter.type().name());
        }
        if (filter.from() != null) {
            sql.append(" AND m.movement_date >= ?");
            params.add(filter.from().atStartOfDay());
        }
        if (filter.to() != null) {
            sql.append(" AND m.movement_date < ?");   // whole "to" day included
            params.add(filter.to().plusDays(1).atStartOfDay());
        }
        sql.append(") x ORDER BY x.movement_date DESC, x.movement_id DESC");
        return queryList(sql.toString(), StockMovementDao::map, params.toArray());
    }

    public List<StockMovement> findByProduct(int productId) {
        return queryList(SELECT + " WHERE m.product_id = ? ORDER BY m.movement_id", StockMovementDao::map, productId);
    }

    /** Sum of all movements of a product; equals its quantity when the ledger is consistent. */
    public BigDecimal sumForProduct(int productId) {
        return queryOne("SELECT COALESCE(SUM(quantity), 0) FROM dbo.Stock_Movements WHERE product_id = ?",
                rs -> rs.getBigDecimal(1), productId).orElse(BigDecimal.ZERO);
    }

    /** Removes a product's movements; for test clean-up only. */
    public void deleteByProduct(int productId) {
        update("DELETE FROM dbo.Stock_Movements WHERE product_id = ?", productId);
    }

    private static StockMovement map(ResultSet rs) throws SQLException {
        StockMovement m = new StockMovement();
        m.setMovementId(rs.getLong("movement_id"));
        m.setProductId(rs.getInt("product_id"));
        m.setMovementDate(getDateTime(rs, "movement_date"));
        m.setMovementType(MovementType.fromCode(rs.getString("movement_type")));
        m.setQuantity(rs.getBigDecimal("quantity"));
        m.setQuantityBefore(rs.getBigDecimal("quantity_before"));
        m.setQuantityAfter(rs.getBigDecimal("balance_after"));
        m.setUnitCost(rs.getBigDecimal("unit_cost"));
        m.setReferenceType(rs.getString("reference_type"));
        m.setReferenceId(getInteger(rs, "reference_id"));
        m.setReason(rs.getString("notes"));
        m.setUserId(rs.getInt("user_id"));
        m.setCreatedAt(getDateTime(rs, "created_at"));
        m.setProductCode(rs.getString("product_code"));
        m.setProductName(rs.getString("product_name"));
        m.setUnitName(rs.getString("unit_name"));
        m.setUserName(rs.getString("user_name"));
        return m;
    }
}
