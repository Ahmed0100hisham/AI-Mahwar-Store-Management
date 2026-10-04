package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Figures for the dashboard cards. Amounts are KWD with 3 decimals; "today" and
 * "this month" follow the database server's date ({@link #serverDate}).
 *
 * @param monthGrossProfit sales revenue − cost of goods − invoice discounts − returns, this month
 */
public record DashboardStats(
        LocalDate serverDate,
        BigDecimal todaySales,
        BigDecimal yesterdaySales,
        long todayInvoices,
        BigDecimal monthSales,
        BigDecimal monthGrossProfit,
        BigDecimal monthExpenses,
        BigDecimal cashBalance,
        BigDecimal receivables,
        BigDecimal payables,
        long lowStockProducts) {

    /** Gross profit minus expenses for the current month. */
    public BigDecimal monthNetProfit() {
        return monthGrossProfit.subtract(monthExpenses);
    }
}
