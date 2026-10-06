package com.almahwar.dao;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseFilter;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.PurchaseStatus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@code Purchases} and {@code Purchase_Items}.
 * <p>
 * Only DRAFT purchases can be changed here ({@code WHERE status = 'DRAFT'} on every write), so a
 * posted invoice can never be edited after it has moved stock, the supplier account and cash.
 */
public class PurchaseDao extends BaseDao {

    private static final String SELECT = """
            SELECT p.purchase_id, p.purchase_no, p.supplier_invoice_no, p.purchase_date, p.supplier_id, p.user_id,
                   p.payment_method, p.subtotal, p.discount_amount, p.total_amount, p.paid_amount, p.status, p.notes,
                   p.request_id, p.posted_at, p.posted_by, p.created_at, p.updated_at,
                   s.supplier_code, s.name AS supplier_name, u.full_name AS user_name, pb.full_name AS posted_by_name
            FROM dbo.Purchases p
            JOIN dbo.Suppliers s ON s.supplier_id = p.supplier_id
            JOIN dbo.Users u     ON u.user_id = p.user_id
            LEFT JOIN dbo.Users pb ON pb.user_id = p.posted_by
            """;

    private static final String SELECT_ITEMS = """
            SELECT i.purchase_item_id, i.purchase_id, i.product_id, i.quantity, i.unit_cost, i.discount_amount,
                   pr.product_code, pr.name_ar AS product_name, un.name_ar AS unit_name, un.allows_decimal
            FROM dbo.Purchase_Items i
            JOIN dbo.Products pr ON pr.product_id = i.product_id
            JOIN dbo.Units un    ON un.unit_id = pr.unit_id
            WHERE i.purchase_id = ?
            ORDER BY i.purchase_item_id
            """;

    /**
     * Next free number {@code PUR-000001 ...}. Inside a transaction the range stays locked until commit,
     * so two users saving at the same moment cannot get the same number.
     */
    public String nextNumber(Connection con) {
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(purchase_no, 5, 20) AS INT)), 0) + 1
                FROM dbo.Purchases WITH (UPDLOCK, HOLDLOCK)
                WHERE purchase_no LIKE N'PUR-[0-9][0-9][0-9][0-9][0-9][0-9]'
                """;
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(Locale.ROOT, "PUR-%06d", next);
    }

    /** Inserts the header as a DRAFT (totals included) and sets its id. */
    public int insert(Connection con, Purchase p) {
        int id = insert(con, """
                INSERT INTO dbo.Purchases (purchase_no, supplier_invoice_no, supplier_id, user_id, payment_method,
                    subtotal, discount_amount, total_amount, paid_amount, status, notes, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                """,
                p.getPurchaseNo(), p.getSupplierInvoiceNo(), p.getSupplierId(), p.getUserId(), p.getPaymentMethod().code(),
                p.getSubtotal(), p.getDiscountAmount(), p.getTotalAmount(), p.getPaidAmount(), p.getNotes(),
                p.getRequestId() == null ? null : p.getRequestId().toString());
        p.setPurchaseId(id);
        return id;
    }

    /** Updates a draft's header; returns false if it is no longer a draft. */
    public boolean updateDraft(Connection con, Purchase p) {
        return update(con, """
                UPDATE dbo.Purchases
                SET supplier_invoice_no = ?, supplier_id = ?, payment_method = ?, subtotal = ?, discount_amount = ?,
                    total_amount = ?, paid_amount = ?, notes = ?, updated_at = SYSDATETIME()
                WHERE purchase_id = ? AND status = 'DRAFT'
                """,
                p.getSupplierInvoiceNo(), p.getSupplierId(), p.getPaymentMethod().code(), p.getSubtotal(),
                p.getDiscountAmount(), p.getTotalAmount(), p.getPaidAmount(), p.getNotes(), p.getPurchaseId()) == 1;
    }

    /** Replaces a draft's items. */
    public void replaceItems(Connection con, int purchaseId, List<PurchaseItem> items) {
        update(con, """
                DELETE i FROM dbo.Purchase_Items i
                JOIN dbo.Purchases p ON p.purchase_id = i.purchase_id
                WHERE i.purchase_id = ? AND p.status = 'DRAFT'
                """, purchaseId);
        for (PurchaseItem item : items) {
            int itemId = insert(con, """
                    INSERT INTO dbo.Purchase_Items (purchase_id, product_id, quantity, unit_cost, discount_amount)
                    VALUES (?, ?, ?, ?, ?)
                    """, purchaseId, item.getProductId(), item.getQuantity(), item.getUnitCost(), item.getDiscountAmount());
            item.setPurchaseItemId(itemId);
            item.setPurchaseId(purchaseId);
        }
    }

    /**
     * Locks the purchase row until the transaction ends and returns its status, so two users
     * posting the same draft at once are serialised and the second one sees POSTED.
     */
    public Optional<PurchaseStatus> lockStatus(Connection con, int purchaseId) {
        return queryOne(con, "SELECT status FROM dbo.Purchases WITH (UPDLOCK, ROWLOCK) WHERE purchase_id = ?",
                rs -> PurchaseStatus.valueOf(rs.getString(1)), purchaseId);
    }

    /** DRAFT → POSTED; returns false if it was not a draft (already posted or cancelled). */
    public boolean markPosted(Connection con, int purchaseId, int userId) {
        return update(con, """
                UPDATE dbo.Purchases
                SET status = 'POSTED', purchase_date = SYSDATETIME(), posted_at = SYSDATETIME(), posted_by = ?,
                    updated_at = SYSDATETIME()
                WHERE purchase_id = ? AND status = 'DRAFT'
                """, userId, purchaseId) == 1;
    }

    /** DRAFT → CANCELLED; returns false if it was not a draft. */
    public boolean cancelDraft(Connection con, int purchaseId) {
        return update(con, """
                UPDATE dbo.Purchases SET status = 'CANCELLED', updated_at = SYSDATETIME()
                WHERE purchase_id = ? AND status = 'DRAFT'
                """, purchaseId) == 1;
    }

    public Optional<Purchase> findById(int purchaseId) {
        return findById(null, purchaseId);
    }

    public Optional<Purchase> findById(Connection con, int purchaseId) {
        Optional<Purchase> p = con == null
                ? queryOne(SELECT + " WHERE p.purchase_id = ?", PurchaseDao::map, purchaseId)
                : queryOne(con, SELECT + " WHERE p.purchase_id = ?", PurchaseDao::map, purchaseId);
        p.ifPresent(x -> x.setItems(con == null
                ? queryList(SELECT_ITEMS, PurchaseDao::mapItem, purchaseId)
                : queryList(con, SELECT_ITEMS, PurchaseDao::mapItem, purchaseId)));
        return p;
    }

    /** The purchase created by this request, if any (duplicate-submission check). */
    public Optional<Integer> findIdByRequest(UUID requestId) {
        return queryOne("SELECT purchase_id FROM dbo.Purchases WHERE request_id = ?",
                rs -> rs.getInt(1), requestId.toString());
    }

    public boolean existsByNumber(String purchaseNo) {
        return queryLong("SELECT COUNT(*) FROM dbo.Purchases WHERE purchase_no = ?", purchaseNo) > 0;
    }

    /** Another purchase of this supplier with the same supplier invoice number. */
    public boolean existsSupplierInvoice(int supplierId, String supplierInvoiceNo, Integer excludeId) {
        return queryLong("""
                SELECT COUNT(*) FROM dbo.Purchases
                WHERE supplier_id = ? AND supplier_invoice_no = ? AND purchase_id <> ? AND status <> 'CANCELLED'
                """, supplierId, supplierInvoiceNo, excludeId == null ? 0 : excludeId) > 0;
    }

    /** Headers only (no items), newest first, at most {@code limit}. */
    public List<Purchase> search(PurchaseFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT TOP (?) * FROM (").append(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(limit);
        if (f.text() != null) {
            String like = likeContains(f.text());
            sql.append(" AND (p.purchase_no LIKE ? OR p.supplier_invoice_no LIKE ? OR s.name LIKE ? OR s.supplier_code LIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if (f.from() != null) {
            sql.append(" AND p.purchase_date >= ?");
            params.add(f.from().atStartOfDay());
        }
        if (f.to() != null) {
            sql.append(" AND p.purchase_date < ?");
            params.add(f.to().plusDays(1).atStartOfDay());
        }
        if (f.supplierId() != null) {
            sql.append(" AND p.supplier_id = ?");
            params.add(f.supplierId());
        }
        if (f.paymentStatus() != null) {
            sql.append(" AND p.payment_status = ?");
            params.add(f.paymentStatus().name());
        }
        if (f.status() != null) {
            sql.append(" AND p.status = ?");
            params.add(f.status().name());
        }
        sql.append(") x ORDER BY x.purchase_date DESC, x.purchase_id DESC");
        return queryList(sql.toString(), PurchaseDao::map, params.toArray());
    }

    /** Removes a purchase and its items; for test clean-up only (never used by the application). */
    public void deleteForTests(int purchaseId) {
        update("DELETE FROM dbo.Purchases WHERE purchase_id = ?", purchaseId);
    }

    private static Purchase map(ResultSet rs) throws SQLException {
        Purchase p = new Purchase();
        p.setPurchaseId(rs.getInt("purchase_id"));
        p.setPurchaseNo(rs.getString("purchase_no"));
        p.setSupplierInvoiceNo(rs.getString("supplier_invoice_no"));
        p.setPurchaseDate(getDateTime(rs, "purchase_date"));
        p.setSupplierId(rs.getInt("supplier_id"));
        p.setUserId(rs.getInt("user_id"));
        p.setPaymentMethod(PaymentMethod.fromCode(rs.getString("payment_method")));
        p.setSubtotal(rs.getBigDecimal("subtotal"));
        p.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        p.setTotalAmount(rs.getBigDecimal("total_amount"));
        p.setPaidAmount(rs.getBigDecimal("paid_amount"));
        p.setStatus(PurchaseStatus.valueOf(rs.getString("status")));
        p.setNotes(rs.getString("notes"));
        String request = rs.getString("request_id");
        p.setRequestId(request == null ? null : UUID.fromString(request));
        p.setPostedAt(getDateTime(rs, "posted_at"));
        p.setPostedBy(getInteger(rs, "posted_by"));
        p.setCreatedAt(getDateTime(rs, "created_at"));
        p.setUpdatedAt(getDateTime(rs, "updated_at"));
        p.setSupplierCode(rs.getString("supplier_code"));
        p.setSupplierName(rs.getString("supplier_name"));
        p.setUserName(rs.getString("user_name"));
        p.setPostedByName(rs.getString("posted_by_name"));
        return p;
    }

    private static PurchaseItem mapItem(ResultSet rs) throws SQLException {
        PurchaseItem i = new PurchaseItem();
        i.setPurchaseItemId(rs.getInt("purchase_item_id"));
        i.setPurchaseId(rs.getInt("purchase_id"));
        i.setProductId(rs.getInt("product_id"));
        i.setQuantity(rs.getBigDecimal("quantity"));
        i.setUnitCost(rs.getBigDecimal("unit_cost"));
        i.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        i.setProductCode(rs.getString("product_code"));
        i.setProductName(rs.getString("product_name"));
        i.setUnitName(rs.getString("unit_name"));
        i.setUnitAllowsDecimal(rs.getBoolean("allows_decimal"));
        return i;
    }
}
