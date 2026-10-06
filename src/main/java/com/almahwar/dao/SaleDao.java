package com.almahwar.dao;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleFilter;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.SaleType;
import com.almahwar.model.User;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@code Sales} and {@code Sale_Items}.
 * <p>
 * Only DRAFT sales can be changed here ({@code WHERE status = 'DRAFT'} on every write), so a posted
 * invoice can never be edited after it has moved stock, the customer account and cash.
 */
public class SaleDao extends BaseDao {

    private static final String SELECT = """
            SELECT s.sale_id, s.invoice_no, s.sale_date, s.customer_id, s.user_id, s.price_type, s.payment_method,
                   s.subtotal, s.discount_amount, s.total_amount, s.paid_amount, s.cost_total, s.gross_profit,
                   s.status, s.notes, s.request_id, s.posted_at, s.posted_by, s.created_at, s.updated_at,
                   c.customer_code, c.name AS customer_name, c.phone AS customer_phone,
                   u.full_name AS user_name, pb.full_name AS posted_by_name,
                   s.quotation_id, qt.quotation_no
            FROM dbo.Sales s
            JOIN dbo.Customers c ON c.customer_id = s.customer_id
            JOIN dbo.Users u     ON u.user_id = s.user_id
            LEFT JOIN dbo.Users pb ON pb.user_id = s.posted_by
            LEFT JOIN dbo.Quotations qt ON qt.quotation_id = s.quotation_id
            """;

    private static final String SELECT_ITEMS = """
            SELECT i.sale_item_id, i.sale_id, i.product_id, i.quantity, i.unit_price, i.unit_cost, i.discount_amount,
                   pr.product_code, pr.barcode, pr.name_ar AS product_name, un.name_ar AS unit_name, un.allows_decimal
            FROM dbo.Sale_Items i
            JOIN dbo.Products pr ON pr.product_id = i.product_id
            JOIN dbo.Units un    ON un.unit_id = pr.unit_id
            WHERE i.sale_id = ?
            ORDER BY i.sale_item_id
            """;

    /**
     * Next free number {@code SAL-000001 ...}. Inside a transaction the range stays locked until commit,
     * so two cashiers completing a sale at the same moment cannot get the same number.
     */
    public String nextNumber(Connection con) {
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(invoice_no, 5, 20) AS INT)), 0) + 1
                FROM dbo.Sales WITH (UPDLOCK, HOLDLOCK)
                WHERE invoice_no LIKE N'SAL-[0-9][0-9][0-9][0-9][0-9][0-9]'
                """;
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(Locale.ROOT, "SAL-%06d", next);
    }

    /** Inserts the header as a DRAFT (totals included) and sets its id. */
    public int insert(Connection con, Sale s) {
        int id = insert(con, """
                INSERT INTO dbo.Sales (invoice_no, customer_id, user_id, price_type, payment_method,
                    subtotal, discount_amount, total_amount, paid_amount, status, notes, request_id, quotation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """,
                s.getSaleNo(), s.getCustomerId(), s.getUserId(), s.getSaleType().code(), s.getPaymentMethod().code(),
                s.getSubtotal(), s.getDiscountAmount(), s.getTotalAmount(), s.getPaidAmount(), s.getNotes(),
                s.getRequestId() == null ? null : s.getRequestId().toString(), s.getQuotationId());
        s.setSaleId(id);
        return id;
    }

    /** Updates a draft's header; returns false if it is no longer a draft. */
    public boolean updateDraft(Connection con, Sale s) {
        return update(con, """
                UPDATE dbo.Sales
                SET customer_id = ?, price_type = ?, payment_method = ?, subtotal = ?, discount_amount = ?,
                    total_amount = ?, paid_amount = ?, notes = ?, updated_at = SYSDATETIME()
                WHERE sale_id = ? AND status = 'DRAFT'
                """,
                s.getCustomerId(), s.getSaleType().code(), s.getPaymentMethod().code(), s.getSubtotal(),
                s.getDiscountAmount(), s.getTotalAmount(), s.getPaidAmount(), s.getNotes(), s.getSaleId()) == 1;
    }

    /** Replaces a draft's items (their cost is captured later, at posting). */
    public void replaceItems(Connection con, int saleId, List<SaleItem> items) {
        update(con, """
                DELETE i FROM dbo.Sale_Items i
                JOIN dbo.Sales s ON s.sale_id = i.sale_id
                WHERE i.sale_id = ? AND s.status = 'DRAFT'
                """, saleId);
        for (SaleItem item : items) {
            int itemId = insert(con, """
                    INSERT INTO dbo.Sale_Items (sale_id, product_id, quantity, unit_price, unit_cost, discount_amount)
                    VALUES (?, ?, ?, ?, 0, ?)
                    """, saleId, item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.getDiscountAmount());
            item.setSaleItemId(itemId);
            item.setSaleId(saleId);
        }
    }

    /**
     * Locks the sale row until the transaction ends and returns its status, so two users posting the
     * same draft at once are serialised and the second one sees POSTED.
     */
    public Optional<SaleStatus> lockStatus(Connection con, int saleId) {
        return queryOne(con, "SELECT status FROM dbo.Sales WITH (UPDLOCK, ROWLOCK) WHERE sale_id = ?",
                rs -> SaleStatus.valueOf(rs.getString(1)), saleId);
    }

    /** Fixes a line's historical unit cost (only while the sale is still a draft being posted). */
    public void setItemCost(Connection con, int saleItemId, BigDecimal unitCost) {
        requireOneRow(update(con, """
                UPDATE i SET i.unit_cost = ?
                FROM dbo.Sale_Items i JOIN dbo.Sales s ON s.sale_id = i.sale_id
                WHERE i.sale_item_id = ? AND s.status = 'DRAFT'
                """, unitCost, saleItemId), "Sale item", saleItemId);
    }

    /** DRAFT → POSTED with its cost total; returns false if it was not a draft (already posted or cancelled). */
    public boolean markPosted(Connection con, int saleId, int userId, BigDecimal costTotal) {
        return update(con, """
                UPDATE dbo.Sales
                SET status = 'POSTED', cost_total = ?, sale_date = SYSDATETIME(), posted_at = SYSDATETIME(),
                    posted_by = ?, updated_at = SYSDATETIME()
                WHERE sale_id = ? AND status = 'DRAFT'
                """, costTotal, userId, saleId) == 1;
    }

    /** DRAFT → CANCELLED; returns false if it was not a draft. */
    public boolean cancelDraft(Connection con, int saleId) {
        return update(con, """
                UPDATE dbo.Sales SET status = 'CANCELLED', updated_at = SYSDATETIME()
                WHERE sale_id = ? AND status = 'DRAFT'
                """, saleId) == 1;
    }

    public Optional<Sale> findById(int saleId) {
        return findById(null, saleId);
    }

    public Optional<Sale> findById(Connection con, int saleId) {
        Optional<Sale> s = con == null
                ? queryOne(SELECT + " WHERE s.sale_id = ?", SaleDao::map, saleId)
                : queryOne(con, SELECT + " WHERE s.sale_id = ?", SaleDao::map, saleId);
        s.ifPresent(x -> x.setItems(con == null
                ? queryList(SELECT_ITEMS, SaleDao::mapItem, saleId)
                : queryList(con, SELECT_ITEMS, SaleDao::mapItem, saleId)));
        return s;
    }

    /** The sale created by this request, if any (duplicate-submission check). */
    public Optional<Integer> findIdByRequest(UUID requestId) {
        return queryOne("SELECT sale_id FROM dbo.Sales WHERE request_id = ?", rs -> rs.getInt(1), requestId.toString());
    }

    /** Headers only (no items), newest first, at most {@code limit}. */
    public List<Sale> search(SaleFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT TOP (?) * FROM (").append(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(limit);
        if (f.text() != null) {
            String like = likeContains(f.text());
            sql.append(" AND (s.invoice_no LIKE ? OR c.name LIKE ? OR c.customer_code LIKE ? OR c.phone LIKE ?"
                    + " OR c.phone2 LIKE ?)");
            params.addAll(List.of(like, like, like, like, like));
        }
        if (f.from() != null) {
            sql.append(" AND s.sale_date >= ?");
            params.add(f.from().atStartOfDay());
        }
        if (f.to() != null) {
            sql.append(" AND s.sale_date < ?");
            params.add(f.to().plusDays(1).atStartOfDay());
        }
        if (f.customerId() != null) {
            sql.append(" AND s.customer_id = ?");
            params.add(f.customerId());
        }
        if (f.cashierId() != null) {
            sql.append(" AND s.user_id = ?");
            params.add(f.cashierId());
        }
        if (f.paymentStatus() != null) {
            sql.append(" AND s.payment_status = ?");
            params.add(f.paymentStatus().name());
        }
        if (f.saleType() != null) {
            sql.append(" AND s.price_type = ?");
            params.add(f.saleType().code());
        }
        if (f.status() != null) {
            sql.append(" AND s.status = ?");
            params.add(f.status().name());
        }
        sql.append(") x ORDER BY x.sale_date DESC, x.sale_id DESC");
        return queryList(sql.toString(), SaleDao::map, params.toArray());
    }

    /** Users who have created at least one sale (for the cashier filter), by name. */
    public List<User> findCashiers() {
        return queryList("""
                SELECT u.user_id, u.full_name FROM dbo.Users u
                WHERE EXISTS (SELECT 1 FROM dbo.Sales s WHERE s.user_id = u.user_id)
                ORDER BY u.full_name
                """, rs -> {
            User u = new User();
            u.setUserId(rs.getInt("user_id"));
            u.setFullName(rs.getString("full_name"));
            return u;
        });
    }

    /** Removes a sale and its items; for test clean-up only (never used by the application). */
    public void deleteForTests(int saleId) {
        update("DELETE FROM dbo.Sales WHERE sale_id = ?", saleId);
    }

    private static Sale map(ResultSet rs) throws SQLException {
        Sale s = new Sale();
        s.setSaleId(rs.getInt("sale_id"));
        s.setSaleNo(rs.getString("invoice_no"));
        s.setSaleDate(getDateTime(rs, "sale_date"));
        s.setCustomerId(rs.getInt("customer_id"));
        s.setUserId(rs.getInt("user_id"));
        s.setSaleType(SaleType.fromCode(rs.getString("price_type")));
        s.setPaymentMethod(PaymentMethod.fromCode(rs.getString("payment_method")));
        s.setSubtotal(rs.getBigDecimal("subtotal"));
        s.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        s.setTotalAmount(rs.getBigDecimal("total_amount"));
        s.setPaidAmount(rs.getBigDecimal("paid_amount"));
        s.setCostTotal(rs.getBigDecimal("cost_total"));
        s.setStatus(SaleStatus.valueOf(rs.getString("status")));
        s.setGrossProfit(s.getStatus() == SaleStatus.POSTED ? rs.getBigDecimal("gross_profit") : null);
        s.setNotes(rs.getString("notes"));
        String request = rs.getString("request_id");
        s.setRequestId(request == null ? null : UUID.fromString(request));
        s.setPostedAt(getDateTime(rs, "posted_at"));
        s.setPostedBy(getInteger(rs, "posted_by"));
        s.setCreatedAt(getDateTime(rs, "created_at"));
        s.setUpdatedAt(getDateTime(rs, "updated_at"));
        s.setCustomerCode(rs.getString("customer_code"));
        s.setCustomerName(rs.getString("customer_name"));
        s.setCustomerPhone(rs.getString("customer_phone"));
        s.setUserName(rs.getString("user_name"));
        s.setPostedByName(rs.getString("posted_by_name"));
        s.setQuotationId(getInteger(rs, "quotation_id"));
        s.setQuotationNo(rs.getString("quotation_no"));
        return s;
    }

    private static SaleItem mapItem(ResultSet rs) throws SQLException {
        SaleItem i = new SaleItem();
        i.setSaleItemId(rs.getInt("sale_item_id"));
        i.setSaleId(rs.getInt("sale_id"));
        i.setProductId(rs.getInt("product_id"));
        i.setQuantity(rs.getBigDecimal("quantity"));
        i.setUnitPrice(rs.getBigDecimal("unit_price"));
        i.setUnitCost(rs.getBigDecimal("unit_cost"));
        i.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        i.setProductCode(rs.getString("product_code"));
        i.setBarcode(rs.getString("barcode"));
        i.setProductName(rs.getString("product_name"));
        i.setUnitName(rs.getString("unit_name"));
        i.setUnitAllowsDecimal(rs.getBoolean("allows_decimal"));
        return i;
    }
}
