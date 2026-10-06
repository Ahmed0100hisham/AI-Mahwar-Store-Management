package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Figures for the dashboard cards. Amounts are KWD with 3 decimals; "today" and
 * "this month" follow the database server's date ({@link #serverDate}).
 * <p>
 * Sales are <b>gross</b> (posted invoices, by posting date); returns are the sales returns made in the same
 * period (by return date); net sales = gross − returns — the same rules as the sales report.
 *
 * @param todaySales       gross sales today
 * @param monthGrossProfit sales revenue − cost of goods − invoice discounts − returns, this month
 */
public record DashboardStats(
        LocalDate serverDate,
        BigDecimal todaySales,
        BigDecimal todayReturns,
        BigDecimal yesterdaySales,
        BigDecimal yesterdayReturns,
        long todayInvoices,
        BigDecimal monthSales,
        BigDecimal monthReturns,
        BigDecimal monthGrossProfit,
        BigDecimal monthExpenses,
        BigDecimal cashBalance,
        BigDecimal receivables,
        BigDecimal payables,
        long lowStockProducts) {

    public BigDecimal todayNetSales() {
        return todaySales.subtract(todayReturns);
    }

    public BigDecimal yesterdayNetSales() {
        return yesterdaySales.subtract(yesterdayReturns);
    }

    public BigDecimal monthNetSales() {
        return monthSales.subtract(monthReturns);
    }

    /** Gross profit minus expenses for the current month. */
    public BigDecimal monthNetProfit() {
        return monthGrossProfit.subtract(monthExpenses);
    }
}
