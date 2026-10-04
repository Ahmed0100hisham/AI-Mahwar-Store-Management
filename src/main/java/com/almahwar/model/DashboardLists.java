package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Row types for the dashboard lists and chart. */
public final class DashboardLists {

    private DashboardLists() {
    }

    /** Active product at or below its minimum stock. */
    public record LowStockItem(String productCode, String nameAr, BigDecimal quantity,
                               BigDecimal minimumStock, String unitName) {
    }

    /** One of the latest sales invoices. */
    public record RecentInvoice(String invoiceNo, LocalDateTime saleDate, String customerName,
                                BigDecimal totalAmount, BigDecimal remainingAmount,
                                PaymentMethod paymentMethod, String status) {

        public boolean isCancelled() {
            return "CANCELLED".equals(status);
        }
    }

    /** Best-selling product by sales value. */
    public record TopProduct(String productCode, String nameAr, String unitName,
                             BigDecimal quantity, BigDecimal salesAmount) {
    }

    /** Sales for one day, or one month (period = first day of the month). */
    public record SalesPoint(LocalDate period, BigDecimal total, long invoices) {
    }
}
