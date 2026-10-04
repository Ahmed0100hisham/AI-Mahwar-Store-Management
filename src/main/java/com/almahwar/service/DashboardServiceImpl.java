package com.almahwar.service;

import com.almahwar.dao.DashboardDao;
import com.almahwar.model.DashboardLists.SalesPoint;
import com.almahwar.model.DashboardStats;
import com.almahwar.model.Permission;
import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.almahwar.model.Permission.*;

/**
 * {@link DashboardService} reading SQL Server through {@link DashboardDao}.
 * Every figure and section is filtered by the caller's permissions here, so a
 * client never receives data it may not show.
 */
public class DashboardServiceImpl implements DashboardService {

    private static final Set<Permission> LOW_STOCK_LIST = EnumSet.of(INVENTORY, PRODUCTS);
    private static final Set<Permission> SALES_SECTIONS = EnumSet.of(SALES, FINANCIAL_REPORTS);
    private static final Set<Permission> TOP_PRODUCTS = EnumSet.of(SALES, FINANCIAL_REPORTS, INVENTORY);

    private final DashboardDao dashboardDao;
    private final SecurityContext security;

    public DashboardServiceImpl(DashboardDao dashboardDao, SecurityContext security) {
        this.dashboardDao = dashboardDao;
        this.security = security;
    }

    @Override
    public DashboardData load(TrendRange trendRange) {
        security.requirePermission(DASHBOARD);
        DashboardStats stats = dashboardDao.loadStats();
        return new DashboardData(
                stats.serverDate(),
                metrics(stats),
                canSee(LOW_STOCK_LIST) ? dashboardDao.findLowStock(LIST_SIZE) : null,
                canSee(SALES_SECTIONS) ? dashboardDao.findRecentInvoices(LIST_SIZE) : null,
                canSee(TOP_PRODUCTS) ? dashboardDao.findTopProducts(LIST_SIZE, TOP_PRODUCTS_DAYS) : null,
                canSee(SALES_SECTIONS) ? loadTrend(trendRange, stats.serverDate()) : null);
    }

    @Override
    public SalesTrend loadSalesTrend(TrendRange range) {
        security.requirePermission(DASHBOARD);
        if (!canSee(SALES_SECTIONS)) {
            throw new AccessDeniedException("ليس لديك صلاحية عرض رسم المبيعات.");
        }
        return loadTrend(range, dashboardDao.serverDate());
    }

    private SalesTrend loadTrend(TrendRange range, LocalDate today) {
        List<SalesPoint> rows = range.isMonthly()
                ? dashboardDao.salesByMonth(range.getLength())
                : dashboardDao.salesByDay(range.getLength());
        return new SalesTrend(range, fillGaps(range, today, rows));
    }

    /**
     * Returns exactly one point per day (or month) of the range, oldest first,
     * with zero for periods that had no sales.
     */
    static List<SalesPoint> fillGaps(TrendRange range, LocalDate today, List<SalesPoint> rows) {
        Map<LocalDate, SalesPoint> byPeriod = rows.stream()
                .collect(Collectors.toMap(SalesPoint::period, Function.identity()));
        int length = range.getLength();
        List<SalesPoint> points = new ArrayList<>(length);
        LocalDate first = range.isMonthly()
                ? today.withDayOfMonth(1).minusMonths(length - 1L)
                : today.minusDays(length - 1L);
        for (int i = 0; i < length; i++) {
            LocalDate period = range.isMonthly() ? first.plusMonths(i) : first.plusDays(i);
            points.add(byPeriod.getOrDefault(period, new SalesPoint(period, MoneyUtil.ZERO, 0)));
        }
        return points;
    }

    private List<MetricValue> metrics(DashboardStats s) {
        List<MetricValue> values = new ArrayList<>();
        for (Metric m : Metric.values()) {
            if (canSee(m.getPermissions())) {
                values.add(value(m, s));
            }
        }
        return values;
    }

    private static MetricValue value(Metric m, DashboardStats s) {
        return switch (m) {
            case TODAY_SALES -> new MetricValue(m, s.todaySales(), s.yesterdaySales());
            case MONTH_SALES -> new MetricValue(m, s.monthSales(), null);
            case TODAY_INVOICES -> new MetricValue(m, BigDecimal.valueOf(s.todayInvoices()), null);
            case NET_PROFIT -> new MetricValue(m, s.monthNetProfit(), null);
            case EXPENSES -> new MetricValue(m, s.monthExpenses(), null);
            case CASH_BALANCE -> new MetricValue(m, s.cashBalance(), null);
            case RECEIVABLES -> new MetricValue(m, s.receivables(), null);
            case PAYABLES -> new MetricValue(m, s.payables(), null);
            case LOW_STOCK -> new MetricValue(m, BigDecimal.valueOf(s.lowStockProducts()), null);
        };
    }

    private boolean canSee(Set<Permission> anyOf) {
        return anyOf.stream().anyMatch(security::hasPermission);
    }
}
