package com.almahwar.api.manager;

import com.almahwar.api.core.CoreConnectionBinding;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.DashboardDao;
import com.almahwar.dao.ReportDao;
import com.almahwar.model.DashboardStats;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.Reports.*;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static com.almahwar.api.manager.ManagerResponses.money;

/** Reuses released calculations; API SQL adds bounded trend buckets and a stable slow-stock page. */
@Repository
public class ManagerAnalyticsRepository extends BaseDao {
    private final ReportDao reports=new ReportDao();
    private final DashboardDao dashboard=new DashboardDao();
    public ManagerAnalyticsRepository(CoreConnectionBinding binding) { }
    public LocalDate today() { return reports.serverDate(); }
    public DashboardStats dashboard() { return dashboard.loadStats(); }
    public SalesSummary sales(ManagerDateRange range) { return reports.salesSummary(range.filter()); }
    public ProfitSummary profit(ManagerDateRange range) { return reports.profit(range.from(),range.to()); }
    public Map<ExpenseCategory,BigDecimal[]> expenses(ManagerDateRange range) { return reports.expensesByCategory(range.filter()); }
    public CashReportSummary cashbox(ManagerDateRange range) { return reports.cashSummary(range.filter()); }
    public List<ProductPerformance> top(ManagerDateRange range,int limit) { return reports.bestSellers(range.filter().topN(limit)); }
    public long slowCount(ReportFilter filter) { return reports.slowMovingCount(filter); }
    public List<SlowMovingRow> slow(ReportFilter filter) {
        // The released page orders by last sale/name only. Add the product key for tied names without changing eligibility.
        var params=new java.util.ArrayList<Object>();params.add(filter.getDays());
        String search="";
        if(filter.getSearch()!=null && !filter.getSearch().isBlank()) {
            search=" AND (p.product_code LIKE ? OR p.barcode LIKE ? OR p.name_ar LIKE ? OR p.name_en LIKE ?)";
            String like=likeContains(filter.getSearch());params.addAll(List.of(like,like,like,like));
        }
        params.add(filter.getPage()*filter.getPageSize());params.add(filter.getPageSize());
        return queryList("""
                SELECT p.product_code,p.name_ar,un.name_ar AS unit_name,p.quantity,
                       CAST(p.quantity*p.purchase_price AS decimal(18,3)) AS value,
                       CAST(ls.last_sale AS date) AS last_sale_day,
                       DATEDIFF(DAY,ls.last_sale,CAST(SYSDATETIME() AS date)) AS days_since
                FROM dbo.Products p JOIN dbo.Units un ON un.unit_id=p.unit_id
                OUTER APPLY (SELECT MAX(s.sale_date) AS last_sale FROM dbo.Sale_Items si
                    JOIN dbo.Sales s ON s.sale_id=si.sale_id AND s.status='POSTED'
                    WHERE si.product_id=p.product_id) ls
                WHERE p.is_active=1 AND p.quantity>0
                    AND (ls.last_sale IS NULL OR ls.last_sale<DATEADD(DAY,-?,CAST(SYSDATETIME() AS date)))
                """+search+" ORDER BY CASE WHEN ls.last_sale IS NULL THEN 0 ELSE 1 END,ls.last_sale,p.name_ar,p.product_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                rs -> { var last=rs.getObject("last_sale_day",LocalDate.class);
                    return new SlowMovingRow(rs.getString("product_code"),rs.getString("name_ar"),rs.getString("unit_name"),
                            rs.getBigDecimal("quantity"),last,last==null?null:rs.getLong("days_since"),rs.getBigDecimal("value")); },params.toArray());
    }
    public StockMovementSummary movementSummary(ReportFilter filter) { return reports.movementSummary(filter); }
    public List<StockMovementRow> movements(ReportFilter filter) { return reports.movementRows(filter); }

    public enum Grouping {
        daily, weekly, monthly;
        public LocalDate bucket(LocalDate day) {
            return switch(this) {
                case daily -> day;
                case weekly -> day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
                case monthly -> day.withDayOfMonth(1);
            };
        }
        public LocalDate next(LocalDate day) {
            return switch(this) { case daily -> day.plusDays(1); case weekly -> day.plusWeeks(1); case monthly -> day.plusMonths(1); };
        }
    }
    public List<ManagerResponses.TrendPoint> trend(ManagerDateRange range,Grouping grouping) {
        String bucket=switch(grouping) {
            case daily -> "CAST(event_date AS date)";
            case weekly -> "DATEADD(DAY,-((DATEDIFF(DAY,CONVERT(date,'19000107'),event_date)%7+7)%7),CAST(event_date AS date))";
            case monthly -> "DATEFROMPARTS(YEAR(event_date),MONTH(event_date),1)";
        };
        // Raw date predicates use the released indexes. Returns follow their own accounting date, not sale date.
        return queryList("""
                WITH events AS (
                    SELECT sale_date AS event_date,total_amount AS gross,CAST(0 AS decimal(18,3)) AS returned,CAST(1 AS bigint) AS invoices
                    FROM dbo.Sales WHERE status='POSTED' AND sale_date>=? AND sale_date<?
                    UNION ALL
                    SELECT return_date,CAST(0 AS decimal(18,3)),total_amount,CAST(0 AS bigint)
                    FROM dbo.Sale_Returns WHERE return_date>=? AND return_date<?
                )
                SELECT %s AS bucket,SUM(gross) AS gross,SUM(returned) AS returned,SUM(invoices) AS invoices
                FROM events GROUP BY %s ORDER BY bucket
                """.formatted(bucket,bucket),rs -> new ManagerResponses.TrendPoint(rs.getObject("bucket",LocalDate.class),
                money(rs.getBigDecimal("gross")),money(rs.getBigDecimal("returned")),
                money(rs.getBigDecimal("gross").subtract(rs.getBigDecimal("returned"))),rs.getLong("invoices")),
                range.from().atStartOfDay(),range.to().plusDays(1).atStartOfDay(),
                range.from().atStartOfDay(),range.to().plusDays(1).atStartOfDay());
    }
}
