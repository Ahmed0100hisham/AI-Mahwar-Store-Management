package com.almahwar.dao;

import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationFilter;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.SaleType;

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
 * Data access for {@code Quotations} / {@code Quotation_Items} (QUO-…). Items and prices change only while a
 * quotation is a DRAFT; every status change is conditional on the current status, so a transition happens once.
 * Nothing here touches stock, accounts or cash.
 */
public class QuotationDao extends BaseDao {

    private static final String SELECT = """
            SELECT q.quotation_id, q.quotation_no, q.quotation_date, q.valid_until, q.customer_id, q.customer_name,
                   q.customer_phone, q.price_type, q.subtotal, q.discount_amount, q.total_amount, q.status,
                   q.converted_sale_id, q.notes, q.terms, q.status_note, q.sent_at, q.decided_at, q.decided_by,
                   q.request_id, q.user_id, q.created_at, q.updated_at,
                   c.customer_code, c.name AS registered_name, c.phone AS registered_phone,
                   u.full_name AS user_name, db.full_name AS decided_by_name,
                   s.invoice_no AS sale_no, s.status AS sale_status
            FROM dbo.Quotations q
            LEFT JOIN dbo.Customers c ON c.customer_id = q.customer_id
            JOIN dbo.Users u ON u.user_id = q.user_id
            LEFT JOIN dbo.Users db ON db.user_id = q.decided_by
            LEFT JOIN dbo.Sales s ON s.sale_id = q.converted_sale_id
            """;

    private static final String SELECT_ITEMS = """
            SELECT i.quotation_item_id, i.quotation_id, i.product_id, i.quantity, i.unit_price, i.discount_amount,
                   p.product_code, p.barcode, p.name_ar AS product_name, p.quantity AS available,
                   un.name_ar AS unit_name, un.allows_decimal
            FROM dbo.Quotation_Items i
            JOIN dbo.Products p ON p.product_id = i.product_id
            JOIN dbo.Units un ON un.unit_id = p.unit_id
            WHERE i.quotation_id = ?
            ORDER BY i.quotation_item_id
            """;

    /** Statuses that a past validity date turns into EXPIRED. */
    private static final String OPEN = "('DRAFT', 'SENT', 'ACCEPTED')";

    /** Next free number {@code QUO-000001}; the range stays locked until commit inside a transaction. */
    public String nextNumber(Connection con) {
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(quotation_no, 5, 20) AS INT)), 0) + 1
                FROM dbo.Quotations WITH (UPDLOCK, HOLDLOCK)
                WHERE quotation_no LIKE N'QUO-[0-9][0-9][0-9][0-9][0-9][0-9]'
                """;
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(Locale.ROOT, "QUO-%06d", next);
    }

    /** Inserts a DRAFT and sets its id. */
    public void insert(Connection con, Quotation q) {
        Object[] saved = queryOne(con, """
                INSERT INTO dbo.Quotations (quotation_no, valid_until, customer_id, customer_name, customer_phone, price_type,
                    subtotal, discount_amount, total_amount, status, notes, terms, request_id, user_id)
                OUTPUT inserted.quotation_id, inserted.quotation_date
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                """, rs -> new Object[]{rs.getInt(1), getDateTime(rs, "quotation_date")},
                q.getQuotationNo(), q.getValidUntil(), q.getCustomerId(), q.getProspectName(), q.getProspectPhone(),
                q.getPriceType().code(), q.getSubtotal(), q.getDiscountAmount(), q.getTotalAmount(), q.getNotes(),
                q.getTerms(), q.getRequestId() == null ? null : q.getRequestId().toString(), q.getUserId())
                .orElseThrow(() -> new DataAccessException("Quotation was not inserted"));
        q.setQuotationId((Integer) saved[0]);
        q.setQuotationDate((java.time.LocalDateTime) saved[1]);
    }

    /** Updates a draft's header; returns false if it is no longer a draft. */
    public boolean updateDraft(Connection con, Quotation q) {
        return update(con, """
                UPDATE dbo.Quotations
                SET valid_until = ?, customer_id = ?, customer_name = ?, customer_phone = ?, price_type = ?, subtotal = ?,
                    discount_amount = ?, total_amount = ?, notes = ?, terms = ?, updated_at = SYSDATETIME()
                WHERE quotation_id = ? AND status = 'DRAFT'
                """, q.getValidUntil(), q.getCustomerId(), q.getProspectName(), q.getProspectPhone(), q.getPriceType().code(),
                q.getSubtotal(), q.getDiscountAmount(), q.getTotalAmount(), q.getNotes(), q.getTerms(),
                q.getQuotationId()) == 1;
    }

    /** Replaces a draft's items. */
    public void replaceItems(Connection con, int quotationId, List<QuotationItem> items) {
        update(con, """
                DELETE i FROM dbo.Quotation_Items i JOIN dbo.Quotations q ON q.quotation_id = i.quotation_id
                WHERE i.quotation_id = ? AND q.status = 'DRAFT'
                """, quotationId);
        for (QuotationItem item : items) {
            int id = insert(con, """
                    INSERT INTO dbo.Quotation_Items (quotation_id, product_id, quantity, unit_price, discount_amount)
                    VALUES (?, ?, ?, ?, ?)
                    """, quotationId, item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.getDiscountAmount());
            item.setQuotationItemId(id);
            item.setQuotationId(quotationId);
        }
    }

    /** Locks the quotation row until the transaction ends and returns its status. */
    public Optional<QuotationStatus> lockStatus(Connection con, int quotationId) {
        return queryOne(con, "SELECT status FROM dbo.Quotations WITH (UPDLOCK, ROWLOCK) WHERE quotation_id = ?",
                rs -> QuotationStatus.valueOf(rs.getString(1)), quotationId);
    }

    /**
     * Moves the quotation from {@code from} to {@code to} (only if it is still in {@code from}); records when it was
     * sent, or who decided and when (accept / reject), with an optional note.
     */
    public boolean changeStatus(Connection con, int quotationId, QuotationStatus from, QuotationStatus to, Integer userId,
                                String note) {
        String extra = switch (to) {
            case SENT -> ", sent_at = SYSDATETIME()";
            case ACCEPTED, REJECTED -> ", decided_at = SYSDATETIME(), decided_by = ?, status_note = ?";
            case DRAFT -> ", sent_at = NULL, decided_at = NULL, decided_by = NULL, status_note = NULL";
            default -> "";
        };
        String sql = "UPDATE dbo.Quotations SET status = ?, updated_at = SYSDATETIME()" + extra
                + " WHERE quotation_id = ? AND status = ?";
        return (to == QuotationStatus.ACCEPTED || to == QuotationStatus.REJECTED
                ? update(con, sql, to.name(), userId, note, quotationId, from.name())
                : update(con, sql, to.name(), quotationId, from.name())) == 1;
    }

    /**
     * DRAFT / SENT / ACCEPTED quotations whose validity date has passed become EXPIRED (server date). Called before
     * reading and before any status change, so no scheduler is needed.
     *
     * @return the numbers that expired now
     */
    public List<String> expireOverdue() {
        return queryList("""
                UPDATE dbo.Quotations SET status = 'EXPIRED', updated_at = SYSDATETIME()
                OUTPUT inserted.quotation_no
                WHERE status IN """ + OPEN + """
                 AND valid_until < CAST(SYSDATETIME() AS date)
                """, rs -> rs.getString(1));
    }

    /**
     * Whether the quotation's validity date has passed (server date), reading only the quotation row: safe to call
     * while holding its lock (no join with sales or products, which a concurrent sale posting may hold).
     */
    public boolean isPastValidity(Connection con, int quotationId) {
        return queryOne(con, "SELECT CASE WHEN valid_until < CAST(SYSDATETIME() AS date) THEN 1 ELSE 0 END "
                + "FROM dbo.Quotations WHERE quotation_id = ?", rs -> rs.getInt(1) == 1, quotationId).orElse(false);
    }

    /** Remembers the sale made from the quotation (the quotation stays ACCEPTED until that sale is posted). */
    public void linkSale(Connection con, int quotationId, int saleId) {
        update(con, "UPDATE dbo.Quotations SET converted_sale_id = ?, updated_at = SYSDATETIME() WHERE quotation_id = ?",
                saleId, quotationId);
    }

    /**
     * Called by the sale posting, in its transaction: ACCEPTED → CONVERTED, only while still valid (server date).
     *
     * @return false if the quotation is not ACCEPTED any more or has expired (the posting must then fail)
     */
    public boolean markConverted(Connection con, int quotationId, int saleId) {
        return update(con, """
                UPDATE dbo.Quotations
                SET status = 'CONVERTED', converted_sale_id = ?, updated_at = SYSDATETIME()
                WHERE quotation_id = ? AND status = 'ACCEPTED'
                  AND (valid_until IS NULL OR valid_until >= CAST(SYSDATETIME() AS date))
                """, saleId, quotationId) == 1;
    }

    /** The live (not cancelled) sale made from this quotation, if any. */
    public Optional<Integer> liveSaleId(int quotationId) {
        return queryOne("SELECT sale_id FROM dbo.Sales WHERE quotation_id = ? AND status <> 'CANCELLED'",
                rs -> rs.getInt(1), quotationId);
    }

    /** The agreed unit price of each product of a quotation (a sale made from it may use these prices). */
    public Map<Integer, BigDecimal> agreedPrices(Connection con, int quotationId) {
        String sql = "SELECT product_id, unit_price FROM dbo.Quotation_Items WHERE quotation_id = ?";
        List<Object[]> rows = con == null ? queryList(sql, rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2)}, quotationId)
                : queryList(con, sql, rs -> new Object[]{rs.getInt(1), rs.getBigDecimal(2)}, quotationId);
        Map<Integer, BigDecimal> map = new HashMap<>();
        rows.forEach(r -> map.put((Integer) r[0], (BigDecimal) r[1]));
        return map;
    }

    /** Deletes a DRAFT (items cascade); returns false if it is not a draft. */
    public boolean deleteDraft(Connection con, int quotationId) {
        return update(con, "DELETE FROM dbo.Quotations WHERE quotation_id = ? AND status = 'DRAFT'", quotationId) == 1;
    }

    public Optional<Quotation> findById(int quotationId) {
        return findById(null, quotationId);
    }

    public Optional<Quotation> findById(Connection con, int quotationId) {
        Optional<Quotation> q = con == null
                ? queryOne(SELECT + " WHERE q.quotation_id = ?", QuotationDao::map, quotationId)
                : queryOne(con, SELECT + " WHERE q.quotation_id = ?", QuotationDao::map, quotationId);
        q.ifPresent(x -> x.setItems(con == null ? queryList(SELECT_ITEMS, QuotationDao::mapItem, quotationId)
                : queryList(con, SELECT_ITEMS, QuotationDao::mapItem, quotationId)));
        return q;
    }

    public Optional<Integer> findIdByRequest(Connection con, UUID requestId) {
        String sql = "SELECT quotation_id FROM dbo.Quotations WHERE request_id = ?";
        return con == null ? queryOne(sql, rs -> rs.getInt(1), requestId.toString())
                : queryOne(con, sql, rs -> rs.getInt(1), requestId.toString());
    }

    /** Headers only, newest first, at most {@code limit}. */
    public List<Quotation> search(QuotationFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT TOP (?) * FROM (").append(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        params.add(limit);
        if (f.text() != null) {
            String like = likeContains(f.text());
            sql.append(" AND (q.quotation_no LIKE ? OR c.name LIKE ? OR c.customer_code LIKE ? OR c.phone LIKE ?"
                    + " OR q.customer_name LIKE ? OR q.customer_phone LIKE ?)");
            params.addAll(List.of(like, like, like, like, like, like));
        }
        if (f.from() != null) {
            sql.append(" AND q.quotation_date >= ?");
            params.add(f.from().atStartOfDay());
        }
        if (f.to() != null) {
            sql.append(" AND q.quotation_date < ?");
            params.add(f.to().plusDays(1).atStartOfDay());
        }
        if (f.status() != null) {
            sql.append(" AND q.status = ?");
            params.add(f.status().name());
        }
        if (f.customerId() != null) {
            sql.append(" AND q.customer_id = ?");
            params.add(f.customerId());
        }
        if (f.priceType() != null) {
            sql.append(" AND q.price_type = ?");
            params.add(f.priceType().code());
        }
        sql.append(") x ORDER BY x.quotation_date DESC, x.quotation_id DESC");
        return queryList(sql.toString(), QuotationDao::map, params.toArray());
    }

    /** Removes a customer's quotations; for test clean-up only. */
    public void deleteForTests(int customerId) {
        update("UPDATE dbo.Sales SET quotation_id = NULL WHERE quotation_id IN (SELECT quotation_id FROM dbo.Quotations WHERE customer_id = ?)",
                customerId);
        update("DELETE FROM dbo.Quotations WHERE customer_id = ?", customerId);
    }

    private static Quotation map(ResultSet rs) throws SQLException {
        Quotation q = new Quotation();
        q.setQuotationId(rs.getInt("quotation_id"));
        q.setQuotationNo(rs.getString("quotation_no"));
        q.setQuotationDate(getDateTime(rs, "quotation_date"));
        q.setValidUntil(getDate(rs, "valid_until"));
        q.setCustomerId(getInteger(rs, "customer_id"));
        q.setProspectName(rs.getString("customer_name"));
        q.setProspectPhone(rs.getString("customer_phone"));
        q.setPriceType(SaleType.fromCode(rs.getString("price_type")));
        q.setSubtotal(rs.getBigDecimal("subtotal"));
        q.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        q.setTotalAmount(rs.getBigDecimal("total_amount"));
        q.setStatus(QuotationStatus.valueOf(rs.getString("status")));
        q.setConvertedSaleId(getInteger(rs, "converted_sale_id"));
        q.setNotes(rs.getString("notes"));
        q.setTerms(rs.getString("terms"));
        q.setStatusNote(rs.getString("status_note"));
        q.setSentAt(getDateTime(rs, "sent_at"));
        q.setDecidedAt(getDateTime(rs, "decided_at"));
        q.setDecidedBy(getInteger(rs, "decided_by"));
        String request = rs.getString("request_id");
        q.setRequestId(request == null ? null : UUID.fromString(request));
        q.setUserId(rs.getInt("user_id"));
        q.setCreatedAt(getDateTime(rs, "created_at"));
        q.setUpdatedAt(getDateTime(rs, "updated_at"));
        q.setCustomerCode(rs.getString("customer_code"));
        q.setCustomerName(rs.getString("registered_name"));
        q.setCustomerPhone(rs.getString("registered_phone"));
        q.setUserName(rs.getString("user_name"));
        q.setDecidedByName(rs.getString("decided_by_name"));
        q.setConvertedSaleNo(rs.getString("sale_no"));
        String saleStatus = rs.getString("sale_status");
        q.setConvertedSaleStatus(saleStatus == null ? null : SaleStatus.valueOf(saleStatus));
        return q;
    }

    private static QuotationItem mapItem(ResultSet rs) throws SQLException {
        QuotationItem i = new QuotationItem();
        i.setQuotationItemId(rs.getInt("quotation_item_id"));
        i.setQuotationId(rs.getInt("quotation_id"));
        i.setProductId(rs.getInt("product_id"));
        i.setQuantity(rs.getBigDecimal("quantity"));
        i.setUnitPrice(rs.getBigDecimal("unit_price"));
        i.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        i.setProductCode(rs.getString("product_code"));
        i.setBarcode(rs.getString("barcode"));
        i.setProductName(rs.getString("product_name"));
        i.setAvailable(rs.getBigDecimal("available"));
        i.setUnitName(rs.getString("unit_name"));
        i.setUnitAllowsDecimal(rs.getBoolean("allows_decimal"));
        return i;
    }
}
