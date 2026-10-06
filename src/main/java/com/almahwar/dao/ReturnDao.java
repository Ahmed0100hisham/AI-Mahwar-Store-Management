package com.almahwar.dao;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnFilter;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.model.ReturnReason;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for sales returns ({@code Sale_Returns} / {@code Sale_Return_Items}, SRN-…) and purchase returns
 * ({@code Purchase_Returns} / {@code Purchase_Return_Items}, PRN-…). Returns are only inserted, never edited.
 */
public class ReturnDao extends BaseDao {

    /** Table and column names of one side. */
    private record Side(String header, String originalColumn, String partyColumn, String items, String originalItemColumn,
                        String priceColumn, String costExpression, String originalTable, String originalNoColumn,
                        String partyTable, String partyCodeColumn) {
    }

    private static Side side(ReturnKind kind) {
        return kind == ReturnKind.SALE
                ? new Side("dbo.Sale_Returns", "sale_id", "customer_id", "dbo.Sale_Return_Items", "sale_item_id",
                "unit_price", "i.unit_cost", "dbo.Sales", "invoice_no", "dbo.Customers", "customer_code")
                : new Side("dbo.Purchase_Returns", "purchase_id", "supplier_id", "dbo.Purchase_Return_Items", "purchase_item_id",
                "unit_cost", "i.unit_cost", "dbo.Purchases", "purchase_no", "dbo.Suppliers", "supplier_code");
    }

    private static String select(ReturnKind kind) {
        Side s = side(kind);
        return """
                SELECT r.return_id, r.return_no, r.%2$s AS original_id, o.%9$s AS original_no, r.%3$s AS party_id,
                       p.%11$s AS party_code, p.name AS party_name, r.return_date, r.total_amount, %12$s AS cost_total,
                       r.refund_amount, r.refund_method, r.reason_code, r.notes, r.request_id, r.user_id, r.created_at,
                       u.full_name AS user_name
                FROM %1$s r
                JOIN %8$s o ON o.%2$s = r.%2$s
                JOIN %10$s p ON p.%3$s = r.%3$s
                JOIN dbo.Users u ON u.user_id = r.user_id
                """.formatted(s.header(), s.originalColumn(), s.partyColumn(), s.items(), s.originalItemColumn(),
                s.priceColumn(), s.costExpression(), s.originalTable(), s.originalNoColumn(), s.partyTable(),
                s.partyCodeColumn(), kind == ReturnKind.SALE ? "r.cost_total" : "r.total_amount");
    }

    private static String selectLines(ReturnKind kind) {
        Side s = side(kind);
        return """
                SELECT i.return_item_id, i.%1$s AS original_item_id, i.product_id, i.quantity, i.%2$s AS unit_price,
                       %3$s AS unit_cost, pr.product_code, pr.name_ar AS product_name, un.name_ar AS unit_name,
                       un.allows_decimal
                FROM %4$s i
                JOIN dbo.Products pr ON pr.product_id = i.product_id
                JOIN dbo.Units un ON un.unit_id = pr.unit_id
                WHERE i.return_id = ?
                ORDER BY i.return_item_id
                """.formatted(s.originalItemColumn(), s.priceColumn(), s.costExpression(), s.items());
    }

    /**
     * Next free number ({@code SRN-000001} / {@code PRN-000001}). Inside a transaction the range stays locked until
     * commit, so two users saving at the same moment cannot get the same number.
     */
    public String nextNumber(Connection con, ReturnKind kind) {
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(return_no, 5, 20) AS INT)), 0) + 1
                FROM %s WITH (UPDLOCK, HOLDLOCK)
                WHERE return_no LIKE N'%s-[0-9][0-9][0-9][0-9][0-9][0-9]'
                """.formatted(side(kind).header(), kind.getPrefix());
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(Locale.ROOT, "%s-%06d", kind.getPrefix(), next);
    }

    /** Inserts the header and sets its id and date (the server's "now"). */
    public void insert(Connection con, ReturnDocument d) {
        Side s = side(d.getKind());
        boolean sale = d.getKind() == ReturnKind.SALE;
        String sql = """
                INSERT INTO %1$s (return_no, %2$s, %3$s, total_amount, refund_amount, refund_method,%4$s reason_code,
                                  reason, notes, request_id, user_id)
                OUTPUT inserted.return_id, inserted.return_date, inserted.created_at
                VALUES (?, ?, ?, ?, ?, ?,%5$s ?, ?, ?, ?, ?)
                """.formatted(s.header(), s.originalColumn(), s.partyColumn(), sale ? " cost_total," : "", sale ? " ?," : "");
        List<Object> params = new ArrayList<>(List.of(d.getReturnNo(), d.getOriginalId(), d.getPartyId(),
                d.getTotalAmount(), d.getRefundAmount(), d.getRefundMethod().code()));
        if (sale) {
            params.add(d.getCostTotal());
        }
        params.add(d.getReason().name());
        params.add(d.getReason().getLabelAr());
        params.add(d.getNotes());
        params.add(d.getRequestId() == null ? null : d.getRequestId().toString());
        params.add(d.getUserId());
        Object[] saved = queryOne(con, sql, rs -> new Object[]{rs.getInt(1), getDateTime(rs, "return_date"),
                getDateTime(rs, "created_at")}, params.toArray())
                .orElseThrow(() -> new DataAccessException("Return was not inserted"));
        d.setReturnId((Integer) saved[0]);
        d.setReturnDate((java.time.LocalDateTime) saved[1]);
        d.setCreatedAt((java.time.LocalDateTime) saved[2]);
    }

    /** Inserts one line and sets its id. */
    public void insertLine(Connection con, ReturnKind kind, int returnId, ReturnLine l) {
        Side s = side(kind);
        int id = kind == ReturnKind.SALE
                ? insert(con, "INSERT INTO " + s.items() + " (return_id, sale_item_id, product_id, quantity, unit_price, unit_cost)"
                + " VALUES (?, ?, ?, ?, ?, ?)", returnId, l.getOriginalItemId(), l.getProductId(), l.getQuantity(),
                l.getUnitPrice(), l.getUnitCost())
                : insert(con, "INSERT INTO " + s.items() + " (return_id, purchase_item_id, product_id, quantity, unit_cost)"
                + " VALUES (?, ?, ?, ?, ?)", returnId, l.getOriginalItemId(), l.getProductId(), l.getQuantity(),
                l.getUnitPrice());
        l.setReturnItemId(id);
    }

    /** Quantity already returned per original line ({@code sale_item_id} / {@code purchase_item_id}). */
    public Map<Integer, BigDecimal> returnedQuantities(Connection con, ReturnKind kind, int originalId) {
        Side s = side(kind);
        Map<Integer, BigDecimal> map = new HashMap<>();
        String sql = "SELECT i." + s.originalItemColumn() + ", SUM(i.quantity) FROM " + s.items() + " i JOIN " + s.header()
                + " r ON r.return_id = i.return_id WHERE r." + s.originalColumn() + " = ? GROUP BY i." + s.originalItemColumn();
        (con == null ? queryList(sql, rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2)}, originalId)
                : queryList(con, sql, rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2)}, originalId))
                .forEach(r -> map.put((Integer) r[0], (BigDecimal) r[1]));
        return map;
    }

    /** Σ value of the returns already made against this sale / purchase. */
    public BigDecimal returnedValue(Connection con, ReturnKind kind, int originalId) {
        Side s = side(kind);
        String sql = "SELECT COALESCE(SUM(total_amount), 0) FROM " + s.header() + " WHERE " + s.originalColumn() + " = ?";
        return (con == null ? queryOne(sql, rs -> rs.getBigDecimal(1), originalId)
                : queryOne(con, sql, rs -> rs.getBigDecimal(1), originalId)).orElse(BigDecimal.ZERO);
    }

    public Optional<ReturnDocument> findById(ReturnKind kind, int returnId) {
        Optional<ReturnDocument> d = queryOne(select(kind) + " WHERE r.return_id = ?", rs -> map(rs, kind), returnId);
        d.ifPresent(x -> x.setLines(queryList(selectLines(kind), ReturnDao::mapLine, returnId)));
        return d;
    }

    /** The return created by this request, if any (duplicate-submission check). */
    public Optional<Integer> findIdByRequest(Connection con, ReturnKind kind, UUID requestId) {
        String sql = "SELECT return_id FROM " + side(kind).header() + " WHERE request_id = ?";
        return con == null ? queryOne(sql, rs -> rs.getInt(1), requestId.toString())
                : queryOne(con, sql, rs -> rs.getInt(1), requestId.toString());
    }

    /** Headers only, newest first, at most {@code limit}. */
    public List<ReturnDocument> search(ReturnKind kind, ReturnFilter f, int limit) {
        Side s = side(kind);
        StringBuilder sql = new StringBuilder("SELECT TOP (?) * FROM (").append(select(kind)).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(limit);
        if (f.text() != null) {
            String like = likeContains(f.text());
            sql.append(" AND (r.return_no LIKE ? OR o.").append(s.originalNoColumn()).append(" LIKE ? OR p.name LIKE ? OR p.")
                    .append(s.partyCodeColumn()).append(" LIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if (f.from() != null) {
            sql.append(" AND r.return_date >= ?");
            params.add(f.from().atStartOfDay());
        }
        if (f.to() != null) {
            sql.append(" AND r.return_date < ?");
            params.add(f.to().plusDays(1).atStartOfDay());
        }
        if (f.partyId() != null) {
            sql.append(" AND r.").append(s.partyColumn()).append(" = ?");
            params.add(f.partyId());
        }
        if (f.originalId() != null) {
            sql.append(" AND r.").append(s.originalColumn()).append(" = ?");
            params.add(f.originalId());
        }
        sql.append(") x ORDER BY x.return_date DESC, x.return_id DESC");
        return queryList(sql.toString(), rs -> map(rs, kind), params.toArray());
    }

    /** Removes the returns of a sale / purchase; for test clean-up only. */
    public void deleteForTests(ReturnKind kind, int originalId) {
        update("DELETE FROM " + side(kind).header() + " WHERE " + side(kind).originalColumn() + " = ?", originalId);
    }

    private static ReturnDocument map(ResultSet rs, ReturnKind kind) throws SQLException {
        ReturnDocument d = new ReturnDocument();
        d.setKind(kind);
        d.setReturnId(rs.getInt("return_id"));
        d.setReturnNo(rs.getString("return_no"));
        d.setOriginalId(rs.getInt("original_id"));
        d.setOriginalNo(rs.getString("original_no"));
        d.setPartyId(rs.getInt("party_id"));
        d.setPartyCode(rs.getString("party_code"));
        d.setPartyName(rs.getString("party_name"));
        d.setReturnDate(getDateTime(rs, "return_date"));
        d.setTotalAmount(rs.getBigDecimal("total_amount"));
        d.setCostTotal(rs.getBigDecimal("cost_total"));
        d.setRefundAmount(rs.getBigDecimal("refund_amount"));
        d.setRefundMethod(PaymentMethod.fromCode(rs.getString("refund_method")));
        d.setReason(ReturnReason.fromCode(rs.getString("reason_code")));
        d.setNotes(rs.getString("notes"));
        String request = rs.getString("request_id");
        d.setRequestId(request == null ? null : UUID.fromString(request));
        d.setUserId(rs.getInt("user_id"));
        d.setCreatedAt(getDateTime(rs, "created_at"));
        d.setUserName(rs.getString("user_name"));
        return d;
    }

    private static ReturnLine mapLine(ResultSet rs) throws SQLException {
        ReturnLine l = new ReturnLine();
        l.setReturnItemId(rs.getInt("return_item_id"));
        l.setOriginalItemId(rs.getInt("original_item_id"));
        l.setProductId(rs.getInt("product_id"));
        l.setQuantity(rs.getBigDecimal("quantity"));
        l.setUnitPrice(rs.getBigDecimal("unit_price"));
        l.setUnitCost(rs.getBigDecimal("unit_cost"));
        l.setProductCode(rs.getString("product_code"));
        l.setProductName(rs.getString("product_name"));
        l.setUnitName(rs.getString("unit_name"));
        l.setUnitAllowsDecimal(rs.getBoolean("allows_decimal"));
        return l;
    }
}
