package com.almahwar.dao;

import com.almahwar.model.DashboardLists.LowStockItem;
import com.almahwar.model.DashboardLists.RecentInvoice;
import com.almahwar.model.DashboardLists.SalesPoint;
import com.almahwar.model.DashboardLists.TopProduct;
import com.almahwar.model.DashboardStats;
import com.almahwar.model.PaymentMethod;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only aggregates for the dashboard.
 * <p>
 * "Today" and "this month" are computed from the database server's clock
 * ({@code SYSDATETIME()}), so every PC shows the same figures. Date filters are
 * half-open ranges on the raw column ({@code col >= start AND col < end}) so
 * the date indexes can be used.
 */
public class DashboardDao extends BaseDao {

    /** Server-side "today" and first day of the month, shared by the queries below. */
    private static final String DATES = """
            WITH d AS (
                SELECT CAST(SYSDATETIME() AS date) AS today,
                       DATEFROMPARTS(YEAR(SYSDATETIME()), MONTH(SYSDATETIME()), 1) AS month_start
            )
            """;

    private static final String STATS_SQL = DATES + """
            SELECT
              d.today AS server_today,
              (SELECT COALESCE(SUM(total_amount), 0) FROM dbo.Sales
                 WHERE status = 'POSTED' AND sale_date >= d.today AND sale_date < DATEADD(DAY, 1, d.today))
                AS today_sales,
              (SELECT COALESCE(SUM(total_amount), 0) FROM dbo.Sale_Returns
                 WHERE return_date >= d.today AND return_date < DATEADD(DAY, 1, d.today))
                AS today_returns,
              (SELECT COALESCE(SUM(total_amount), 0) FROM dbo.Sales
                 WHERE status = 'POSTED' AND sale_date >= DATEADD(DAY, -1, d.today) AND sale_date < d.today)
                AS yesterday_sales,
              (SELECT COALESCE(SUM(total_amount), 0) FROM dbo.Sale_Returns
                 WHERE return_date >= DATEADD(DAY, -1, d.today) AND return_date < d.today)
                AS yesterday_returns,
              (SELECT COUNT(*) FROM dbo.Sales
                 WHERE status = 'POSTED' AND sale_date >= d.today AND sale_date < DATEADD(DAY, 1, d.today))
                AS today_invoices,
              (SELECT COALESCE(SUM(total_amount), 0) FROM dbo.Sales
                 WHERE status = 'POSTED' AND sale_date >= d.month_start AND sale_date < DATEADD(MONTH, 1, d.month_start))
                AS month_sales,
              (SELECT COALESCE(SUM(total_amount), 0) FROM dbo.Sale_Returns
                 WHERE return_date >= d.month_start AND return_date < DATEADD(MONTH, 1, d.month_start))
                AS month_returns,
              -- gross profit of posted sales: total (after line and invoice discounts) − historical cost total
              (SELECT COALESCE(SUM(gross_profit), 0) FROM dbo.Sales
                 WHERE status = 'POSTED' AND sale_date >= d.month_start AND sale_date < DATEADD(MONTH, 1, d.month_start))
              -- returned goods: the value given back minus the historical cost that comes back into stock
              - (SELECT COALESCE(SUM(r.total_amount - r.cost_total), 0)
                 FROM dbo.Sale_Returns r
                 WHERE r.return_date >= d.month_start AND r.return_date < DATEADD(MONTH, 1, d.month_start))
                AS month_gross_profit,
              (SELECT COALESCE(SUM(amount), 0) FROM dbo.Expenses
                 WHERE expense_date >= d.month_start AND expense_date < DATEADD(MONTH, 1, d.month_start))
                AS month_expenses,
              (SELECT COALESCE(SUM(CASE WHEN transaction_type = 'IN' THEN amount ELSE -amount END), 0)
                 FROM dbo.Cash_Transactions)
                AS cash_balance,
              (SELECT COALESCE(SUM(balance), 0) FROM dbo.Customers WHERE balance > 0) AS receivables,
              (SELECT COALESCE(SUM(balance), 0) FROM dbo.Suppliers WHERE balance > 0) AS payables,
              (SELECT COUNT(*) FROM dbo.Products WHERE is_active = 1 AND quantity <= minimum_stock) AS low_stock
            FROM d
            """;

    public DashboardStats loadStats() {
        return queryOne(STATS_SQL, rs -> new DashboardStats(
                rs.getObject("server_today", LocalDate.class),
                MoneyUtil.of(rs.getBigDecimal("today_sales")),
                MoneyUtil.of(rs.getBigDecimal("today_returns")),
                MoneyUtil.of(rs.getBigDecimal("yesterday_sales")),
                MoneyUtil.of(rs.getBigDecimal("yesterday_returns")),
                rs.getLong("today_invoices"),
                MoneyUtil.of(rs.getBigDecimal("month_sales")),
                MoneyUtil.of(rs.getBigDecimal("month_returns")),
                MoneyUtil.of(rs.getBigDecimal("month_gross_profit")),
                MoneyUtil.of(rs.getBigDecimal("month_expenses")),
                MoneyUtil.of(rs.getBigDecimal("cash_balance")),
                MoneyUtil.of(rs.getBigDecimal("receivables")),
                MoneyUtil.of(rs.getBigDecimal("payables")),
                rs.getLong("low_stock")))
                .orElseThrow();
    }

    /** Most critical first: lowest quantity relative to the minimum. */
    public List<LowStockItem> findLowStock(int limit) {
        return queryList("""
                SELECT TOP (?) p.product_code, p.name_ar, p.quantity, p.minimum_stock, u.name_ar AS unit_name
                FROM dbo.Products p
                JOIN dbo.Units u ON u.unit_id = p.unit_id
                WHERE p.is_active = 1 AND p.quantity <= p.minimum_stock
                ORDER BY CASE WHEN p.minimum_stock = 0 THEN 0 ELSE p.quantity / p.minimum_stock END, p.name_ar
                """, rs -> new LowStockItem(
                        rs.getString("product_code"),
                        rs.getString("name_ar"),
                        QuantityUtil.of(rs.getBigDecimal("quantity")),
                        QuantityUtil.of(rs.getBigDecimal("minimum_stock")),
                        rs.getString("unit_name")),
                limit);
    }

    public List<RecentInvoice> findRecentInvoices(int limit) {
        return queryList("""
                SELECT TOP (?) s.invoice_no, s.sale_date, c.name AS customer_name, s.total_amount,
                       s.remaining_amount, s.payment_method, s.status
                FROM dbo.Sales s
                JOIN dbo.Customers c ON c.customer_id = s.customer_id
                WHERE s.status = 'POSTED'
                ORDER BY s.sale_date DESC, s.sale_id DESC
                """, rs -> new RecentInvoice(
                        rs.getString("invoice_no"),
                        getDateTime(rs, "sale_date"),
                        rs.getString("customer_name"),
                        MoneyUtil.of(rs.getBigDecimal("total_amount")),
                        MoneyUtil.of(rs.getBigDecimal("remaining_amount")),
                        PaymentMethod.fromCode(rs.getString("payment_method")),
                        rs.getString("status")),
                limit);
    }

    /** Best sellers by value over the last {@code days} days (including today). */
    public List<TopProduct> findTopProducts(int limit, int days) {
        return queryList(DATES + """
                SELECT TOP (?) p.product_code, p.name_ar, u.name_ar AS unit_name,
                       SUM(si.quantity) AS quantity, SUM(si.line_total) AS sales_amount
                FROM d
                JOIN dbo.Sales s ON s.sale_date >= DATEADD(DAY, 1 - ?, d.today) AND s.sale_date < DATEADD(DAY, 1, d.today)
                JOIN dbo.Sale_Items si ON si.sale_id = s.sale_id
                JOIN dbo.Products p ON p.product_id = si.product_id
                JOIN dbo.Units u ON u.unit_id = p.unit_id
                WHERE s.status = 'POSTED'
                GROUP BY p.product_id, p.product_code, p.name_ar, u.name_ar
                ORDER BY sales_amount DESC
                """, rs -> new TopProduct(
                        rs.getString("product_code"),
                        rs.getString("name_ar"),
                        rs.getString("unit_name"),
                        QuantityUtil.of(rs.getBigDecimal("quantity")),
                        MoneyUtil.of(rs.getBigDecimal("sales_amount"))),
                limit, days);
    }

    /** The database server's current date. */
    public LocalDate serverDate() {
        return queryOne("SELECT CAST(SYSDATETIME() AS date)", rs -> rs.getObject(1, LocalDate.class)).orElseThrow();
    }

    /** Daily totals for the last {@code days} days; days without sales are not returned. */
    public List<SalesPoint> salesByDay(int days) {
        return queryList(DATES + """
                SELECT CAST(s.sale_date AS date) AS period, SUM(s.total_amount) AS total, COUNT(*) AS invoices
                FROM d
                JOIN dbo.Sales s ON s.sale_date >= DATEADD(DAY, 1 - ?, d.today) AND s.sale_date < DATEADD(DAY, 1, d.today)
                WHERE s.status = 'POSTED'
                GROUP BY CAST(s.sale_date AS date)
                """, DashboardDao::mapPoint, days);
    }

    /** Monthly totals for the last {@code months} months; months without sales are not returned. */
    public List<SalesPoint> salesByMonth(int months) {
        return queryList(DATES + """
                SELECT DATEFROMPARTS(YEAR(s.sale_date), MONTH(s.sale_date), 1) AS period,
                       SUM(s.total_amount) AS total, COUNT(*) AS invoices
                FROM d
                JOIN dbo.Sales s ON s.sale_date >= DATEADD(MONTH, 1 - ?, d.month_start)
                                AND s.sale_date < DATEADD(MONTH, 1, d.month_start)
                WHERE s.status = 'POSTED'
                GROUP BY DATEFROMPARTS(YEAR(s.sale_date), MONTH(s.sale_date), 1)
                """, DashboardDao::mapPoint, months);
    }

    private static SalesPoint mapPoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SalesPoint(rs.getObject("period", LocalDate.class),
                MoneyUtil.of(rs.getBigDecimal("total")), rs.getLong("invoices"));
    }
}
