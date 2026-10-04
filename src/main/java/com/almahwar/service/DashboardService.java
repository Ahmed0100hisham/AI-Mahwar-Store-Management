package com.almahwar.service;

import com.almahwar.model.DashboardLists.LowStockItem;
import com.almahwar.model.DashboardLists.RecentInvoice;
import com.almahwar.model.DashboardLists.SalesPoint;
import com.almahwar.model.DashboardLists.TopProduct;
import com.almahwar.model.Permission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.almahwar.model.Permission.*;

/**
 * Dashboard data for the current user. Returns <b>raw values</b> (BigDecimal,
 * counts, dates) — formatting, colours and wording belong to each client, so the
 * same contract can later be served as JSON to the Flutter app.
 * <p>
 * Implementations: {@link DashboardServiceImpl} (SQL Server via DAO); a future
 * REST client would call {@code GET /api/dashboard}.
 */
public interface DashboardService {

    int LIST_SIZE = 8;
    int TOP_PRODUCTS_DAYS = 30;

    /** The dashboard figures, in display order, with the permissions that unlock each one. */
    enum Metric {
        TODAY_SALES("مبيعات اليوم", true, EnumSet.of(SALES, FINANCIAL_REPORTS)),
        MONTH_SALES("مبيعات الشهر", true, EnumSet.of(FINANCIAL_REPORTS)),
        TODAY_INVOICES("فواتير اليوم", false, EnumSet.of(SALES, FINANCIAL_REPORTS)),
        NET_PROFIT("صافي الربح", true, EnumSet.of(FINANCIAL_REPORTS)),
        EXPENSES("المصروفات", true, EnumSet.of(Permission.EXPENSES)),
        CASH_BALANCE("رصيد الخزنة", true, EnumSet.of(CASH)),
        RECEIVABLES("ديون العملاء", true, EnumSet.of(CUSTOMER_PAYMENTS)),
        PAYABLES("مستحقات الموردين", true, EnumSet.of(SUPPLIER_PAYMENTS)),
        LOW_STOCK("منتجات ناقصة", false, EnumSet.of(INVENTORY, PRODUCTS));

        private final String title;
        private final boolean money;
        private final Set<Permission> anyOf;

        Metric(String title, boolean money, Set<Permission> anyOf) {
            this.title = title;
            this.money = money;
            this.anyOf = anyOf;
        }

        /** Default Arabic title. */
        public String getTitle() {
            return title;
        }

        /** {@code true}: value is KWD (3 decimals); {@code false}: value is a count. */
        public boolean isMoney() {
            return money;
        }

        /** The metric is visible to users holding any of these. */
        public Set<Permission> getPermissions() {
            return EnumSet.copyOf(anyOf);
        }
    }

    /**
     * One figure.
     *
     * @param value         KWD amount or count (see {@link Metric#isMoney()})
     * @param previousValue comparison figure (e.g. yesterday's sales for TODAY_SALES), or {@code null}
     */
    record MetricValue(Metric metric, BigDecimal value, BigDecimal previousValue) {
    }

    /** Ranges offered by the sales chart. */
    enum TrendRange {
        LAST_7_DAYS("7 أيام", 7, false),
        LAST_30_DAYS("30 يومًا", 30, false),
        LAST_12_MONTHS("12 شهرًا", 12, true);

        private final String labelAr;
        private final int length;
        private final boolean monthly;

        TrendRange(String labelAr, int length, boolean monthly) {
            this.labelAr = labelAr;
            this.length = length;
            this.monthly = monthly;
        }

        public String getLabelAr() {
            return labelAr;
        }

        /** Number of days, or of months when {@link #isMonthly()}. */
        public int getLength() {
            return length;
        }

        public boolean isMonthly() {
            return monthly;
        }
    }

    /** Chart data with one point per day/month, including periods without sales. */
    record SalesTrend(TrendRange range, List<SalesPoint> points) {
    }

    /**
     * Everything the dashboard shows. {@code metrics} holds only the figures the
     * user may see. A list/trend is {@code null} when the user may not see that
     * section (as opposed to empty: allowed, but no data yet).
     */
    record DashboardData(LocalDate serverDate, List<MetricValue> metrics, List<LowStockItem> lowStock,
                         List<RecentInvoice> recentInvoices, List<TopProduct> topProducts,
                         SalesTrend salesTrend) {
    }

    /** Loads the whole dashboard for the current user. May block. */
    DashboardData load(TrendRange trendRange);

    /** Reloads only the chart, e.g. when the user picks another range. May block. */
    SalesTrend loadSalesTrend(TrendRange range);
}
