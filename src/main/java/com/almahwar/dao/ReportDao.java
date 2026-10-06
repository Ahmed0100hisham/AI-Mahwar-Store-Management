package com.almahwar.dao;

import com.almahwar.model.CashSource;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.MovementType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.Reports.AuditRow;
import com.almahwar.model.Reports.CashReportSummary;
import com.almahwar.model.Reports.CashRow;
import com.almahwar.model.Reports.ExpenseRow;
import com.almahwar.model.Reports.InventoryRow;
import com.almahwar.model.Reports.Lookup;
import com.almahwar.model.Reports.LowStockRow;
import com.almahwar.model.Reports.PartyBalanceRow;
import com.almahwar.model.Reports.PartyBalanceSummary;
import com.almahwar.model.Reports.ProductPerformance;
import com.almahwar.model.Reports.ProfitSummary;
import com.almahwar.model.Reports.PurchaseRow;
import com.almahwar.model.Reports.PurchaseSummary;
import com.almahwar.model.Reports.QuotationRow;
import com.almahwar.model.Reports.SalesRow;
import com.almahwar.model.Reports.SalesSummary;
import com.almahwar.model.Reports.SlowMovingRow;
import com.almahwar.model.Reports.StockMovementRow;
import com.almahwar.model.Reports.StockMovementSummary;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only report queries (SELECT only — nothing here writes). Filtering, grouping and totals are done by SQL Server.
 * <p>
 * Dates: {@code from} and {@code to} are inclusive days, applied as the half-open range
 * {@code col >= from AND col < to + 1 day} on the raw column, so a 00:00:00 and a 23:59:59 movement are both inside
 * and the date indexes are used. Each summary is computed over the same FROM / WHERE as its detail rows, so the
 * totals always equal the sum of the details. Detail rows are paged with OFFSET / FETCH.
 */
public class ReportDao extends BaseDao {

    /** Builds a WHERE clause and its parameters together. */
    private static final class Where {
        final StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        final List<Object> params = new ArrayList<>();

        Where and(String condition, Object... values) {
            sql.append(" AND ").append(condition);
            params.addAll(List.of(values));
            return this;
        }

        /** {@code col >= from AND col < to + 1}; open ends are ignored. */
        Where range(String column, LocalDate from, LocalDate to) {
            if (from != null) {
                and(column + " >= ?", from);
            }
            if (to != null) {
                and(column + " < ?", to.plusDays(1));
            }
            return this;
        }

        /** Any of the columns contains the text. */
        Where search(String text, String... columns) {
            if (text == null || text.isBlank()) {
                return this;
            }
            String like = likeContains(text);
            List<String> parts = new ArrayList<>();
            for (String c : columns) {
                parts.add(c + " LIKE ?");
                params.add(like);
            }
            sql.append(" AND (").append(String.join(" OR ", parts)).append(')');
            return this;
        }

        Object[] with(Object... more) {
            List<Object> all = new ArrayList<>(params);
            all.addAll(List.of(more));
            return all.toArray();
        }
    }

    private static final String PAGE = " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

    private static Object[] page(Where w, ReportFilter f) {
        return w.with(f.getPage() * f.getPageSize(), f.getPageSize());
    }

    private static BigDecimal money(ResultSet rs, String col) throws SQLException {
        return MoneyUtil.of(rs.getBigDecimal(col));
    }

    private static BigDecimal qty(ResultSet rs, String col) throws SQLException {
        return QuantityUtil.of(rs.getBigDecimal(col));
    }

    /** The database server's date: "today" for the report presets, like the dashboard. */
    public LocalDate serverDate() {
        return queryOne("SELECT CAST(SYSDATETIME() AS date)", rs -> rs.getObject(1, LocalDate.class)).orElseThrow();
    }

    // ======================= Sales =======================

    private static Where salesWhere(ReportFilter f) {
        Where w = new Where().and("s.status = 'POSTED'").range("s.sale_date", f.getFrom(), f.getTo());
        if (f.getCustomerId() != null) {
            w.and("s.customer_id = ?", f.getCustomerId());
        }
        if (f.getUserId() != null) {
            w.and("s.user_id = ?", f.getUserId());
        }
        return w.search(f.getSearch(), "s.invoice_no", "c.name", "c.customer_code");
    }

    private static Where saleReturnsWhere(ReportFilter f) {
        Where w = new Where().range("r.return_date", f.getFrom(), f.getTo());
        if (f.getCustomerId() != null) {
            w.and("r.customer_id = ?", f.getCustomerId());
        }
        if (f.getUserId() != null) {
            w.and("r.user_id = ?", f.getUserId());
        }
        return w.search(f.getSearch(), "r.return_no", "s.invoice_no", "c.name", "c.customer_code");
    }

    private static final String SALES_FROM = " FROM dbo.Sales s JOIN dbo.Customers c ON c.customer_id = s.customer_id";
    private static final String SALE_RETURNS_FROM = " FROM dbo.Sale_Returns r JOIN dbo.Sales s ON s.sale_id = r.sale_id"
            + " JOIN dbo.Customers c ON c.customer_id = r.customer_id";

    public SalesSummary salesSummary(ReportFilter f) {
        Where sw = salesWhere(f);
        Where rw = saleReturnsWhere(f);
        List<Object> all = new ArrayList<>(sw.params);
        all.addAll(rw.params);
        Object[] params = all.toArray();
        return queryOne("SELECT g.gross, g.invoices, r.returns, r.return_count FROM "
                + "(SELECT COALESCE(SUM(s.total_amount), 0) AS gross, COUNT(*) AS invoices" + SALES_FROM + sw.sql + ") g "
                + "CROSS JOIN (SELECT COALESCE(SUM(r.total_amount), 0) AS returns, COUNT(*) AS return_count"
                + SALE_RETURNS_FROM + rw.sql + ") r", rs -> {
            BigDecimal gross = money(rs, "gross");
            BigDecimal returns = money(rs, "returns");
            long invoices = rs.getLong("invoices");
            BigDecimal average = invoices == 0 ? MoneyUtil.ZERO
                    : gross.divide(BigDecimal.valueOf(invoices), MoneyUtil.SCALE, RoundingMode.HALF_UP);
            return new SalesSummary(gross, returns, MoneyUtil.of(gross.subtract(returns)), invoices, average,
                    rs.getLong("return_count"));
        }, params).orElseThrow();
    }

    public List<SalesRow> salesRows(ReportFilter f) {
        Where w = salesWhere(f);
        LocalDate end = f.getTo() == null ? null : f.getTo().plusDays(1);
        List<Object> params = new ArrayList<>();
        params.add(end);
        params.add(end);
        params.addAll(List.of(page(w, f)));
        return queryList("""
                SELECT s.invoice_no, s.sale_date, c.name AS customer_name, s.payment_status, s.payment_method,
                       s.total_amount, u.full_name AS user_name,
                       (SELECT COALESCE(SUM(x.total_amount), 0) FROM dbo.Sale_Returns x
                         WHERE x.sale_id = s.sale_id AND (? IS NULL OR x.return_date < ?)) AS returned
                """ + SALES_FROM + " JOIN dbo.Users u ON u.user_id = s.user_id" + w.sql
                + " ORDER BY s.sale_date DESC, s.sale_id DESC" + PAGE, rs -> {
            BigDecimal gross = money(rs, "total_amount");
            BigDecimal returned = money(rs, "returned");
            return new SalesRow(rs.getString("invoice_no"), getDateTime(rs, "sale_date"), rs.getString("customer_name"),
                    PaymentStatus.valueOf(rs.getString("payment_status")),
                    PaymentMethod.fromCode(rs.getString("payment_method")), gross, returned,
                    MoneyUtil.of(gross.subtract(returned)), rs.getString("user_name"));
        }, params.toArray());
    }

    // ======================= Profit =======================

    /** Historical cost only: Sales.cost_total (Σ line qty × unit cost at posting) and Sale_Returns.cost_total. */
    public ProfitSummary profit(LocalDate from, LocalDate to) {
        Where s = new Where().and("status = 'POSTED'").range("sale_date", from, to);
        Where r = new Where().range("return_date", from, to);
        Where e = new Where().range("expense_date", from, to);
        List<Object> params = new ArrayList<>(s.params);
        params.addAll(r.params);
        params.addAll(e.params);
        return queryOne("SELECT s.revenue, s.cogs, r.return_revenue, r.returned_cogs, e.expenses FROM "
                + "(SELECT COALESCE(SUM(total_amount), 0) AS revenue, COALESCE(SUM(cost_total), 0) AS cogs FROM dbo.Sales"
                + s.sql + ") s CROSS JOIN (SELECT COALESCE(SUM(total_amount), 0) AS return_revenue, "
                + "COALESCE(SUM(cost_total), 0) AS returned_cogs FROM dbo.Sale_Returns" + r.sql + ") r "
                + "CROSS JOIN (SELECT COALESCE(SUM(amount), 0) AS expenses FROM dbo.Expenses" + e.sql + ") e", rs -> {
            BigDecimal revenue = money(rs, "revenue");
            BigDecimal cogs = money(rs, "cogs");
            BigDecimal before = MoneyUtil.of(revenue.subtract(cogs));
            BigDecimal returnRevenue = money(rs, "return_revenue");
            BigDecimal returnedCogs = money(rs, "returned_cogs");
            BigDecimal reversal = MoneyUtil.of(returnRevenue.subtract(returnedCogs));
            BigDecimal after = MoneyUtil.of(before.subtract(reversal));
            BigDecimal expenses = money(rs, "expenses");
            return new ProfitSummary(revenue, cogs, before, returnRevenue, returnedCogs, reversal, after, expenses,
                    MoneyUtil.of(after.subtract(expenses)));
        }, params.toArray()).orElseThrow();
    }

    // ======================= Purchases =======================

    private static Where purchasesWhere(ReportFilter f) {
        Where w = new Where().and("p.status = 'POSTED'").range("p.purchase_date", f.getFrom(), f.getTo());
        if (f.getSupplierId() != null) {
            w.and("p.supplier_id = ?", f.getSupplierId());
        }
        if (f.getUserId() != null) {
            w.and("p.user_id = ?", f.getUserId());
        }
        return w.search(f.getSearch(), "p.purchase_no", "p.supplier_invoice_no", "sp.name", "sp.supplier_code");
    }

    private static Where purchaseReturnsWhere(ReportFilter f) {
        Where w = new Where().range("r.return_date", f.getFrom(), f.getTo());
        if (f.getSupplierId() != null) {
            w.and("r.supplier_id = ?", f.getSupplierId());
        }
        if (f.getUserId() != null) {
            w.and("r.user_id = ?", f.getUserId());
        }
        return w.search(f.getSearch(), "r.return_no", "p.purchase_no", "p.supplier_invoice_no", "sp.name",
                "sp.supplier_code");
    }

    private static final String PURCHASES_FROM = " FROM dbo.Purchases p JOIN dbo.Suppliers sp ON sp.supplier_id = p.supplier_id";
    private static final String PURCHASE_RETURNS_FROM = " FROM dbo.Purchase_Returns r JOIN dbo.Purchases p"
            + " ON p.purchase_id = r.purchase_id JOIN dbo.Suppliers sp ON sp.supplier_id = r.supplier_id";

    public PurchaseSummary purchaseSummary(ReportFilter f) {
        Where pw = purchasesWhere(f);
        Where rw = purchaseReturnsWhere(f);
        List<Object> params = new ArrayList<>(pw.params);
        params.addAll(rw.params);
        return queryOne("SELECT g.gross, g.purchases, r.returns, r.return_count FROM "
                + "(SELECT COALESCE(SUM(p.total_amount), 0) AS gross, COUNT(*) AS purchases" + PURCHASES_FROM + pw.sql
                + ") g CROSS JOIN (SELECT COALESCE(SUM(r.total_amount), 0) AS returns, COUNT(*) AS return_count"
                + PURCHASE_RETURNS_FROM + rw.sql + ") r", rs -> {
            BigDecimal gross = money(rs, "gross");
            BigDecimal returns = money(rs, "returns");
            return new PurchaseSummary(gross, returns, MoneyUtil.of(gross.subtract(returns)), rs.getLong("purchases"),
                    rs.getLong("return_count"));
        }, params.toArray()).orElseThrow();
    }

    public List<PurchaseRow> purchaseRows(ReportFilter f) {
        Where w = purchasesWhere(f);
        LocalDate end = f.getTo() == null ? null : f.getTo().plusDays(1);
        List<Object> params = new ArrayList<>();
        params.add(end);
        params.add(end);
        params.addAll(List.of(page(w, f)));
        return queryList("""
                SELECT p.purchase_no, p.supplier_invoice_no, p.purchase_date, sp.name AS supplier_name, p.payment_status,
                       p.payment_method, p.total_amount, u.full_name AS user_name,
                       (SELECT COALESCE(SUM(x.total_amount), 0) FROM dbo.Purchase_Returns x
                         WHERE x.purchase_id = p.purchase_id AND (? IS NULL OR x.return_date < ?)) AS returned
                """ + PURCHASES_FROM + " JOIN dbo.Users u ON u.user_id = p.user_id" + w.sql
                + " ORDER BY p.purchase_date DESC, p.purchase_id DESC" + PAGE, rs -> {
            BigDecimal gross = money(rs, "total_amount");
            BigDecimal returned = money(rs, "returned");
            return new PurchaseRow(rs.getString("purchase_no"), rs.getString("supplier_invoice_no"),
                    getDateTime(rs, "purchase_date"), rs.getString("supplier_name"),
                    PaymentStatus.valueOf(rs.getString("payment_status")),
                    PaymentMethod.fromCode(rs.getString("payment_method")), gross, returned,
                    MoneyUtil.of(gross.subtract(returned)), rs.getString("user_name"));
        }, params.toArray());
    }

    // ======================= Expenses =======================

    private static final String EXPENSES_FROM = " FROM dbo.Expenses e JOIN dbo.Users u ON u.user_id = e.user_id";

    private static Where expensesWhere(ReportFilter f) {
        Where w = new Where().range("e.expense_date", f.getFrom(), f.getTo());
        if (f.getExpenseCategory() != null) {
            w.and("e.category = ?", f.getExpenseCategory().name());
        }
        if (f.getUserId() != null) {
            w.and("e.user_id = ?", f.getUserId());
        }
        return w.search(f.getSearch(), "e.expense_no", "e.description", "e.notes", "e.reference_no");
    }

    /** @return total amount and count by category (categories without expenses are absent) */
    public Map<ExpenseCategory, BigDecimal[]> expensesByCategory(ReportFilter f) {
        Where w = expensesWhere(f);
        Map<ExpenseCategory, BigDecimal[]> map = new EnumMap<>(ExpenseCategory.class);
        queryList("SELECT e.category, SUM(e.amount) AS total, COUNT(*) AS n" + EXPENSES_FROM + w.sql + " GROUP BY e.category",
                rs -> map.put(ExpenseCategory.fromCode(rs.getString("category")),
                        new BigDecimal[]{money(rs, "total"), BigDecimal.valueOf(rs.getLong("n"))}),
                w.params.toArray());
        return map;
    }

    public List<ExpenseRow> expenseRows(ReportFilter f) {
        Where w = expensesWhere(f);
        return queryList("SELECT e.expense_no, e.expense_date, e.category, e.amount, e.payment_method, e.description, "
                + "e.notes, u.full_name AS user_name" + EXPENSES_FROM + w.sql
                + " ORDER BY e.expense_date DESC, e.expense_id DESC" + PAGE, rs -> {
            String description = rs.getString("description");
            String notes = rs.getString("notes");
            String text = description == null ? notes : notes == null ? description : description + " — " + notes;
            return new ExpenseRow(rs.getString("expense_no"), getDateTime(rs, "expense_date"),
                    ExpenseCategory.fromCode(rs.getString("category")), money(rs, "amount"),
                    PaymentMethod.fromCode(rs.getString("payment_method")), text, rs.getString("user_name"));
        }, page(w, f));
    }

    // ======================= Cashbox =======================

    /** Cash movements with their document number (same rule as the cashbox screen). */
    private static final String CASH_FROM = """
             FROM (SELECT t.transaction_id, t.transaction_date, t.transaction_type, t.amount, t.payment_method,
                          t.source_type, t.description, t.reference_no, t.notes, u.full_name AS user_name,
                          CASE t.source_type
                              WHEN 'SALE' THEN (SELECT x.invoice_no FROM dbo.Sales x WHERE x.sale_id = t.source_id)
                              WHEN 'PURCHASE' THEN (SELECT x.purchase_no FROM dbo.Purchases x WHERE x.purchase_id = t.source_id)
                              WHEN 'CUSTOMER_PAYMENT' THEN (SELECT x.payment_no FROM dbo.Customer_Payments x WHERE x.payment_id = t.source_id)
                              WHEN 'SUPPLIER_PAYMENT' THEN (SELECT x.payment_no FROM dbo.Supplier_Payments x WHERE x.payment_id = t.source_id)
                              WHEN 'EXPENSE' THEN (SELECT x.expense_no FROM dbo.Expenses x WHERE x.expense_id = t.source_id)
                              WHEN 'SALE_RETURN' THEN (SELECT x.return_no FROM dbo.Sale_Returns x WHERE x.return_id = t.source_id)
                              WHEN 'PURCHASE_RETURN' THEN (SELECT x.return_no FROM dbo.Purchase_Returns x WHERE x.return_id = t.source_id)
                          END AS document_no
                   FROM dbo.Cash_Transactions t
                   JOIN dbo.Users u ON u.user_id = t.user_id) x
            """;

    /** Everything but the dates (opening, in and out all use the same movement filter). */
    private static Where cashFilter(ReportFilter f) {
        Where w = new Where();
        if (f.getCashIn() != null) {
            w.and("x.transaction_type = ?", f.getCashIn() ? "IN" : "OUT");
        }
        if (f.getCashSource() != null) {
            w.and("x.source_type = ?", f.getCashSource().name());
        }
        if (f.getPaymentMethod() != null) {
            w.and("x.payment_method = ?", f.getPaymentMethod().code());
        }
        return w.search(f.getSearch(), "x.document_no", "x.reference_no", "x.description", "x.notes");
    }

    public CashReportSummary cashSummary(ReportFilter f) {
        Where w = cashFilter(f);
        LocalDate start = f.getFrom();
        LocalDate end = f.getTo() == null ? null : f.getTo().plusDays(1);
        List<Object> params = new ArrayList<>(List.of(new Object[]{start, start, start, start, end, end, start,
                start, end, end}));
        params.addAll(w.params);
        return queryOne("""
                SELECT
                  COALESCE(SUM(CASE WHEN ? IS NOT NULL AND x.transaction_date < ?
                               THEN CASE WHEN x.transaction_type = 'IN' THEN x.amount ELSE -x.amount END END), 0) AS opening,
                  COALESCE(SUM(CASE WHEN x.transaction_type = 'IN' AND (? IS NULL OR x.transaction_date >= ?)
                                     AND (? IS NULL OR x.transaction_date < ?) THEN x.amount END), 0) AS total_in,
                  COALESCE(SUM(CASE WHEN x.transaction_type = 'OUT' AND (? IS NULL OR x.transaction_date >= ?)
                                     AND (? IS NULL OR x.transaction_date < ?) THEN x.amount END), 0) AS total_out
                """ + CASH_FROM + w.sql, rs -> {
            BigDecimal opening = money(rs, "opening");
            BigDecimal in = money(rs, "total_in");
            BigDecimal out = money(rs, "total_out");
            BigDecimal net = MoneyUtil.of(in.subtract(out));
            return new CashReportSummary(opening, in, out, net, MoneyUtil.of(opening.add(net)));
        }, params.toArray()).orElseThrow();
    }

    private static Where cashWhere(ReportFilter f) {
        Where w = new Where().range("x.transaction_date", f.getFrom(), f.getTo());
        Where m = cashFilter(f);
        w.sql.append(m.sql.substring(" WHERE 1 = 1".length()));
        w.params.addAll(m.params);
        return w;
    }

    public long cashCount(ReportFilter f) {
        Where w = cashWhere(f);
        return queryLong("SELECT COUNT(*)" + CASH_FROM + w.sql, w.params.toArray());
    }

    /** Oldest first, like a cash book. */
    public List<CashRow> cashRows(ReportFilter f) {
        Where w = cashWhere(f);
        return queryList("SELECT x.*" + CASH_FROM + w.sql + " ORDER BY x.transaction_date, x.transaction_id" + PAGE,
                rs -> {
                    String doc = rs.getString("document_no");
                    String ref = rs.getString("reference_no");
                    String description = rs.getString("description");
                    String notes = rs.getString("notes");
                    return new CashRow(getDateTime(rs, "transaction_date"), "IN".equals(rs.getString("transaction_type")),
                            CashSource.valueOf(rs.getString("source_type")),
                            doc != null ? doc : ref, PaymentMethod.fromCode(rs.getString("payment_method")),
                            money(rs, "amount"), rs.getString("user_name"),
                            description == null ? notes : notes == null ? description : description + " — " + notes);
                }, page(w, f));
    }

    // ======================= Inventory =======================

    private static final String PRODUCTS_FROM = """
             FROM dbo.Products p
             JOIN dbo.Units un ON un.unit_id = p.unit_id
             LEFT JOIN dbo.Categories cat ON cat.category_id = p.category_id
             LEFT JOIN dbo.Brands b ON b.brand_id = p.brand_id
            """;

    private static Where productsWhere(ReportFilter f) {
        Where w = new Where();
        if (f.getActive() != null) {
            w.and("p.is_active = ?", f.getActive());
        }
        if (f.getCategoryId() != null) {
            w.and("p.category_id = ?", f.getCategoryId());
        }
        if (f.getBrandId() != null) {
            w.and("p.brand_id = ?", f.getBrandId());
        }
        return w.search(f.getSearch(), "p.product_code", "p.barcode", "p.name_ar", "p.name_en");
    }

    /** Value per product, rounded like every amount; the total is the sum of the rounded values. */
    private static final String VALUE = "CAST(p.quantity * p.purchase_price AS DECIMAL(18,3))";

    /** @return {count, total value} */
    public BigDecimal[] inventoryTotals(ReportFilter f) {
        Where w = productsWhere(f);
        return queryOne("SELECT COUNT(*) AS n, COALESCE(SUM(" + VALUE + "), 0) AS total" + PRODUCTS_FROM + w.sql,
                rs -> new BigDecimal[]{BigDecimal.valueOf(rs.getLong("n")), money(rs, "total")},
                w.params.toArray()).orElseThrow();
    }

    public List<InventoryRow> inventoryRows(ReportFilter f) {
        Where w = productsWhere(f);
        return queryList("SELECT p.product_code, p.barcode, p.name_ar, cat.name_ar AS category_name, b.name_ar AS brand_name, "
                + "un.name_ar AS unit_name, p.quantity, p.minimum_stock, p.purchase_price, " + VALUE + " AS value, "
                + "p.location, p.is_active" + PRODUCTS_FROM + w.sql + " ORDER BY p.name_ar, p.product_id" + PAGE,
                rs -> new InventoryRow(rs.getString("product_code"), rs.getString("barcode"), rs.getString("name_ar"),
                        rs.getString("category_name"), rs.getString("brand_name"), rs.getString("unit_name"),
                        qty(rs, "quantity"), qty(rs, "minimum_stock"), money(rs, "purchase_price"), money(rs, "value"),
                        rs.getString("location"), rs.getBoolean("is_active")),
                page(w, f));
    }

    /** Same rule as the dashboard card: active products at or below their minimum. */
    private static Where lowStockWhere(ReportFilter f) {
        return productsWhere(f.copy().active(null)).and("p.is_active = 1").and("p.quantity <= p.minimum_stock");
    }

    public long lowStockCount(ReportFilter f) {
        Where w = lowStockWhere(f);
        return queryLong("SELECT COUNT(*)" + PRODUCTS_FROM + w.sql, w.params.toArray());
    }

    public List<LowStockRow> lowStockRows(ReportFilter f) {
        Where w = lowStockWhere(f);
        return queryList("SELECT p.product_code, p.name_ar, cat.name_ar AS category_name, b.name_ar AS brand_name, "
                + "un.name_ar AS unit_name, p.quantity, p.minimum_stock, "
                + "CASE WHEN p.minimum_stock > p.quantity THEN p.minimum_stock - p.quantity ELSE 0 END AS shortage"
                + PRODUCTS_FROM + w.sql + " ORDER BY shortage DESC, p.name_ar" + PAGE,
                rs -> new LowStockRow(rs.getString("product_code"), rs.getString("name_ar"),
                        rs.getString("category_name"), rs.getString("brand_name"), rs.getString("unit_name"),
                        qty(rs, "quantity"), qty(rs, "minimum_stock"), qty(rs, "shortage")),
                page(w, f));
    }

    // ======================= Stock movements =======================

    private static final String MOVES_FROM = """
             FROM dbo.Stock_Movements m
             JOIN dbo.Products p ON p.product_id = m.product_id
             JOIN dbo.Units un ON un.unit_id = p.unit_id
             LEFT JOIN dbo.Users u ON u.user_id = m.user_id
            """;

    private static Where movesWhere(ReportFilter f) {
        Where w = new Where().range("m.movement_date", f.getFrom(), f.getTo());
        if (f.getProductId() != null) {
            w.and("m.product_id = ?", f.getProductId());
        }
        if (f.getMovementType() != null) {
            w.and("m.movement_type = ?", f.getMovementType().name());
        }
        return w.search(f.getSearch(), "p.product_code", "p.name_ar", "p.barcode");
    }

    public StockMovementSummary movementSummary(ReportFilter f) {
        Where w = movesWhere(f);
        return queryOne("SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN m.quantity > 0 THEN m.quantity END), 0) AS qin, "
                + "COALESCE(SUM(CASE WHEN m.quantity < 0 THEN m.quantity END), 0) AS qout" + MOVES_FROM + w.sql,
                rs -> new StockMovementSummary(rs.getLong("n"), qty(rs, "qin"), qty(rs, "qout")),
                w.params.toArray()).orElseThrow();
    }

    public List<StockMovementRow> movementRows(ReportFilter f) {
        Where w = movesWhere(f);
        return queryList("""
                SELECT m.movement_date, p.product_code, p.name_ar, un.name_ar AS unit_name, m.movement_type,
                       m.quantity, m.quantity_before, m.balance_after, m.unit_cost, u.full_name AS user_name,
                       CASE m.reference_type
                           WHEN 'SALE' THEN (SELECT x.invoice_no FROM dbo.Sales x WHERE x.sale_id = m.reference_id)
                           WHEN 'PURCHASE' THEN (SELECT x.purchase_no FROM dbo.Purchases x WHERE x.purchase_id = m.reference_id)
                           WHEN 'SALE_RETURN' THEN (SELECT x.return_no FROM dbo.Sale_Returns x WHERE x.return_id = m.reference_id)
                           WHEN 'PURCHASE_RETURN' THEN (SELECT x.return_no FROM dbo.Purchase_Returns x WHERE x.return_id = m.reference_id)
                       END AS document_no, m.notes
                """ + MOVES_FROM + w.sql + " ORDER BY m.movement_date DESC, m.movement_id DESC" + PAGE, rs -> {
            String doc = rs.getString("document_no");
            return new StockMovementRow(getDateTime(rs, "movement_date"), rs.getString("product_code"),
                    rs.getString("name_ar"), rs.getString("unit_name"), MovementType.fromCode(rs.getString("movement_type")),
                    doc != null ? doc : rs.getString("notes"), qty(rs, "quantity"), qty(rs, "quantity_before"),
                    qty(rs, "balance_after"), rs.getBigDecimal("unit_cost") == null ? null : money(rs, "unit_cost"),
                    rs.getString("user_name"));
        }, page(w, f));
    }

    // ======================= Best sellers / slow moving =======================

    /**
     * Sales of the period by product, net of the period's returns. A sale line's revenue is its total less its
     * share of the invoice discount (line total × invoice discount ÷ subtotal, rounded to 3 decimals); a returned
     * line's value is already net of discounts. Cost is the historical cost of each line.
     */
    public List<ProductPerformance> bestSellers(ReportFilter f) {
        Where sold = new Where().and("s.status = 'POSTED'").range("s.sale_date", f.getFrom(), f.getTo());
        Where back = new Where().range("r.return_date", f.getFrom(), f.getTo());
        Where outer = new Where();
        if (f.getCategoryId() != null) {
            outer.and("p.category_id = ?", f.getCategoryId());
        }
        List<Object> params = new ArrayList<>();
        params.add(f.getTopN());
        params.addAll(sold.params);
        params.addAll(back.params);
        params.addAll(outer.params);
        return queryList("""
                WITH sold AS (
                    SELECT si.product_id, SUM(si.quantity) AS qty,
                           SUM(si.line_total - CASE WHEN s.subtotal > 0
                               THEN CAST(si.line_total * s.discount_amount / s.subtotal AS DECIMAL(18,3)) ELSE 0 END) AS revenue,
                           SUM(CAST(si.quantity * si.unit_cost AS DECIMAL(18,3))) AS cost
                    FROM dbo.Sale_Items si JOIN dbo.Sales s ON s.sale_id = si.sale_id
                """ + sold.sql + """
                    GROUP BY si.product_id),
                back AS (
                    SELECT ri.product_id, SUM(ri.quantity) AS qty, SUM(ri.line_total) AS revenue,
                           SUM(CAST(ri.quantity * ri.unit_cost AS DECIMAL(18,3))) AS cost
                    FROM dbo.Sale_Return_Items ri JOIN dbo.Sale_Returns r ON r.return_id = ri.return_id
                """ + back.sql + """
                    GROUP BY ri.product_id)
                SELECT TOP (?) p.product_code, p.name_ar, cat.name_ar AS category_name, un.name_ar AS unit_name,
                       COALESCE(sold.qty, 0) AS gross_qty, COALESCE(back.qty, 0) AS returned_qty,
                       COALESCE(sold.qty, 0) - COALESCE(back.qty, 0) AS net_qty,
                       COALESCE(sold.revenue, 0) - COALESCE(back.revenue, 0) AS net_revenue,
                       COALESCE(sold.revenue, 0) - COALESCE(back.revenue, 0)
                         - (COALESCE(sold.cost, 0) - COALESCE(back.cost, 0)) AS gross_profit
                FROM dbo.Products p
                JOIN dbo.Units un ON un.unit_id = p.unit_id
                LEFT JOIN dbo.Categories cat ON cat.category_id = p.category_id
                LEFT JOIN sold ON sold.product_id = p.product_id
                LEFT JOIN back ON back.product_id = p.product_id
                """ + outer.sql
                + " AND (sold.product_id IS NOT NULL OR back.product_id IS NOT NULL)"
                + " ORDER BY net_qty DESC, net_revenue DESC, p.name_ar", rs ->
                new ProductPerformance(rs.getString("product_code"), rs.getString("name_ar"),
                        rs.getString("category_name"), rs.getString("unit_name"), qty(rs, "gross_qty"),
                        qty(rs, "returned_qty"), qty(rs, "net_qty"), money(rs, "net_revenue"),
                        money(rs, "gross_profit")),
                reorderTop(params, sold.params.size() + back.params.size()));
    }

    /** TOP (?) comes after the CTEs in the SQL text: move the first parameter behind the CTE parameters. */
    private static Object[] reorderTop(List<Object> params, int cteCount) {
        List<Object> ordered = new ArrayList<>(params.subList(1, 1 + cteCount));
        ordered.add(params.get(0));
        ordered.addAll(params.subList(1 + cteCount, params.size()));
        return ordered.toArray();
    }

    private static final String LAST_SALE = """
             OUTER APPLY (SELECT MAX(s.sale_date) AS last_sale FROM dbo.Sale_Items si
                          JOIN dbo.Sales s ON s.sale_id = si.sale_id AND s.status = 'POSTED'
                          WHERE si.product_id = p.product_id) ls
            """;

    /** Active products in stock not sold since {@code days} days before the server date (or never sold). */
    private static Where slowWhere(ReportFilter f) {
        return productsWhere(f.copy().active(null).brandId(null)).and("p.is_active = 1").and("p.quantity > 0")
                .and("(ls.last_sale IS NULL OR ls.last_sale < DATEADD(DAY, -?, CAST(SYSDATETIME() AS date)))", f.getDays());
    }

    public long slowMovingCount(ReportFilter f) {
        Where w = slowWhere(f);
        return queryLong("SELECT COUNT(*)" + PRODUCTS_FROM + LAST_SALE + w.sql, w.params.toArray());
    }

    public List<SlowMovingRow> slowMovingRows(ReportFilter f) {
        Where w = slowWhere(f);
        return queryList("SELECT p.product_code, p.name_ar, un.name_ar AS unit_name, p.quantity, " + VALUE + " AS value, "
                + "CAST(ls.last_sale AS date) AS last_sale_day, "
                + "DATEDIFF(DAY, ls.last_sale, CAST(SYSDATETIME() AS date)) AS days_since"
                + PRODUCTS_FROM + LAST_SALE + w.sql
                + " ORDER BY CASE WHEN ls.last_sale IS NULL THEN 0 ELSE 1 END, ls.last_sale, p.name_ar" + PAGE,
                rs -> {
                    LocalDate last = rs.getObject("last_sale_day", LocalDate.class);
                    return new SlowMovingRow(rs.getString("product_code"), rs.getString("name_ar"),
                            rs.getString("unit_name"), qty(rs, "quantity"), last,
                            last == null ? null : rs.getLong("days_since"), money(rs, "value"));
                }, page(w, f));
    }

    // ======================= Customers & suppliers =======================

    private static String balancesBase(boolean customers) {
        return customers ? """
                 FROM (SELECT customer_id AS party_id, SUM(debit - credit) AS balance FROM dbo.Account_Ledger
                       WHERE party_type = 'CUSTOMER' GROUP BY customer_id HAVING SUM(debit - credit) > 0) b
                 JOIN dbo.Customers c ON c.customer_id = b.party_id
                """ : """
                 FROM (SELECT supplier_id AS party_id, SUM(credit - debit) AS balance FROM dbo.Account_Ledger
                       WHERE party_type = 'SUPPLIER' GROUP BY supplier_id HAVING SUM(credit - debit) > 0) b
                 JOIN dbo.Suppliers c ON c.supplier_id = b.party_id
                """;
    }

    private static Where balancesWhere(ReportFilter f, boolean customers) {
        return new Where().search(f.getSearch(), customers ? "c.customer_code" : "c.supplier_code", "c.name", "c.phone",
                "c.area");
    }

    public PartyBalanceSummary balanceSummary(ReportFilter f, boolean customers) {
        Where w = balancesWhere(f, customers);
        return queryOne("SELECT COUNT(*) AS n, COALESCE(SUM(b.balance), 0) AS total" + balancesBase(customers) + w.sql,
                rs -> new PartyBalanceSummary(rs.getLong("n"), money(rs, "total")), w.params.toArray()).orElseThrow();
    }

    public List<PartyBalanceRow> balanceRows(ReportFilter f, boolean customers) {
        Where w = balancesWhere(f, customers);
        String docs = customers
                ? "(SELECT MAX(x.sale_date) FROM dbo.Sales x WHERE x.customer_id = c.customer_id AND x.status = 'POSTED')"
                : "(SELECT MAX(x.purchase_date) FROM dbo.Purchases x WHERE x.supplier_id = c.supplier_id AND x.status = 'POSTED')";
        String pays = customers
                ? "(SELECT MAX(x.payment_date) FROM dbo.Customer_Payments x WHERE x.customer_id = c.customer_id)"
                : "(SELECT MAX(x.payment_date) FROM dbo.Supplier_Payments x WHERE x.supplier_id = c.supplier_id)";
        return queryList("SELECT b.party_id, " + (customers ? "c.customer_code" : "c.supplier_code") + " AS code, c.name, "
                + "c.phone, c.area, b.balance, " + docs + " AS last_doc, " + pays + " AS last_pay"
                + balancesBase(customers) + w.sql + " ORDER BY b.balance DESC, c.name" + PAGE,
                rs -> new PartyBalanceRow(rs.getInt("party_id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("phone"), rs.getString("area"), money(rs, "balance"), getDateTime(rs, "last_doc"),
                        getDateTime(rs, "last_pay")),
                page(w, f));
    }

    // ======================= Quotations =======================

    /** The stored status, or EXPIRED for an open quotation past its validity (read-only: nothing is updated). */
    private static final String EFFECTIVE_STATUS = "CASE WHEN q.status IN ('DRAFT', 'SENT', 'ACCEPTED') "
            + "AND q.valid_until < CAST(SYSDATETIME() AS date) THEN 'EXPIRED' ELSE q.status END";

    private static final String QUOTATIONS_FROM = " FROM dbo.Quotations q JOIN dbo.Customers c ON c.customer_id = q.customer_id";

    private static Where quotationsWhere(ReportFilter f) {
        Where w = new Where().range("q.quotation_date", f.getFrom(), f.getTo());
        if (f.getCustomerId() != null) {
            w.and("q.customer_id = ?", f.getCustomerId());
        }
        if (f.getQuotationStatus() != null) {
            w.and(EFFECTIVE_STATUS + " = ?", f.getQuotationStatus().name());
        }
        return w.search(f.getSearch(), "q.quotation_no", "c.name", "c.customer_code", "q.customer_name", "q.customer_phone");
    }

    /** @return per effective status: {count, value} */
    public Map<QuotationStatus, BigDecimal[]> quotationsByStatus(ReportFilter f) {
        Where w = quotationsWhere(f);
        Map<QuotationStatus, BigDecimal[]> map = new EnumMap<>(QuotationStatus.class);
        queryList("SELECT " + EFFECTIVE_STATUS + " AS status, COUNT(*) AS n, COALESCE(SUM(q.total_amount), 0) AS total"
                + QUOTATIONS_FROM + w.sql + " GROUP BY " + EFFECTIVE_STATUS,
                rs -> map.put(QuotationStatus.valueOf(rs.getString("status")),
                        new BigDecimal[]{BigDecimal.valueOf(rs.getLong("n")), money(rs, "total")}),
                w.params.toArray());
        return map;
    }

    public List<QuotationRow> quotationRows(ReportFilter f) {
        Where w = quotationsWhere(f);
        return queryList("SELECT q.quotation_no, q.quotation_date, c.name, c.customer_code, q.customer_name AS prospect, "
                + EFFECTIVE_STATUS + " AS status, q.total_amount, q.valid_until, s.invoice_no, u.full_name AS user_name"
                + QUOTATIONS_FROM + " JOIN dbo.Users u ON u.user_id = q.user_id"
                + " LEFT JOIN dbo.Sales s ON s.sale_id = q.converted_sale_id" + w.sql
                + " ORDER BY q.quotation_date DESC, q.quotation_id DESC" + PAGE, rs -> {
            String prospect = rs.getString("prospect");
            boolean walkIn = com.almahwar.model.Customer.CASH_CUSTOMER_CODE.equals(rs.getString("customer_code"));
            return new QuotationRow(rs.getString("quotation_no"), getDateTime(rs, "quotation_date"),
                    walkIn && prospect != null ? prospect : rs.getString("name"),
                    QuotationStatus.valueOf(rs.getString("status")), money(rs, "total_amount"),
                    getDate(rs, "valid_until"), rs.getString("invoice_no"), rs.getString("user_name"));
        }, page(w, f));
    }

    // ======================= Audit =======================

    private static final String AUDIT_FROM = " FROM dbo.Audit_Log a LEFT JOIN dbo.Users u ON u.user_id = a.user_id";

    private static Where auditWhere(ReportFilter f) {
        Where w = new Where().range("a.created_at", f.getFrom(), f.getTo());
        if (f.getUserId() != null) {
            w.and("a.user_id = ?", f.getUserId());
        }
        if (f.getAction() != null && !f.getAction().isBlank()) {
            w.and("a.action = ?", f.getAction());
        }
        return w.search(f.getSearch(), "a.description", "a.table_name", "a.record_id", "a.action", "u.full_name");
    }

    public long auditCount(ReportFilter f) {
        Where w = auditWhere(f);
        return queryLong("SELECT COUNT(*)" + AUDIT_FROM + w.sql, w.params.toArray());
    }

    /** Only the description is exposed (never old / new values). */
    public List<AuditRow> auditRows(ReportFilter f) {
        Where w = auditWhere(f);
        return queryList("SELECT a.created_at, u.full_name, a.action, a.table_name, a.record_id, a.description"
                + AUDIT_FROM + w.sql + " ORDER BY a.created_at DESC, a.log_id DESC" + PAGE,
                rs -> new AuditRow(getDateTime(rs, "created_at"), rs.getString("full_name"), rs.getString("action"),
                        rs.getString("table_name"), rs.getString("record_id"), rs.getString("description")),
                page(w, f));
    }

    // ======================= Lookups for the filters =======================

    public List<String> auditActions() {
        return queryList("SELECT DISTINCT action FROM dbo.Audit_Log ORDER BY action", rs -> rs.getString(1));
    }

    public List<Lookup> users() {
        return queryList("SELECT user_id, full_name FROM dbo.Users ORDER BY full_name",
                rs -> new Lookup(rs.getInt(1), rs.getString(2)));
    }

    public List<Lookup> customers() {
        return queryList("SELECT customer_id, name + N' (' + customer_code + N')' FROM dbo.Customers ORDER BY name",
                rs -> new Lookup(rs.getInt(1), rs.getString(2)));
    }

    public List<Lookup> suppliers() {
        return queryList("SELECT supplier_id, name + N' (' + supplier_code + N')' FROM dbo.Suppliers ORDER BY name",
                rs -> new Lookup(rs.getInt(1), rs.getString(2)));
    }

    public List<Lookup> categories() {
        return queryList("SELECT category_id, name_ar FROM dbo.Categories ORDER BY name_ar",
                rs -> new Lookup(rs.getInt(1), rs.getString(2)));
    }

    public List<Lookup> brands() {
        return queryList("SELECT brand_id, name_ar FROM dbo.Brands ORDER BY name_ar",
                rs -> new Lookup(rs.getInt(1), rs.getString(2)));
    }

    public List<Lookup> products(String text, int limit) {
        Where w = new Where().search(text, "product_code", "barcode", "name_ar");
        return queryList("SELECT TOP (?) product_id, product_code + N' — ' + name_ar FROM dbo.Products"
                        + w.sql + " ORDER BY name_ar",
                rs -> new Lookup(rs.getInt(1), rs.getString(2)), prepend(limit, w.params));
    }

    private static Object[] prepend(Object first, List<Object> rest) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all.toArray();
    }
}
