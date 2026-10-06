package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only report data (KWD amounts with 3 decimals, quantities with 3 decimals). Cost and profit components are
 * {@code null} when the caller may not see them: the service removes them, so no client can show or export them.
 */
public final class Reports {

    private Reports() {
    }

    /**
     * A report: its summary over <b>every</b> matching row, and one page of detail rows.
     *
     * @param totalRows number of matching detail rows (all pages)
     */
    public record Result<S, R>(S summary, List<R> rows, int page, int pageSize, long totalRows) {

        public int pageCount() {
            return pageSize <= 0 ? 1 : (int) Math.max(1, (totalRows + pageSize - 1) / pageSize);
        }
    }

    /** An id and a display name, for filter lists. */
    public record Lookup(int id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Count only (reports whose rows are their own summary). */
    public record CountSummary(long count) {
    }

    // ---------- sales ----------

    /**
     * Sales of a period: invoices by their posting date, returns by the return date (a September sale returned in
     * October is in September's gross sales and October's returns).
     *
     * @param averageInvoice gross sales / invoice count (0 without invoices)
     */
    public record SalesSummary(BigDecimal grossSales, BigDecimal returns, BigDecimal netSales, long invoiceCount,
                               BigDecimal averageInvoice, long returnCount) {
    }

    /**
     * One posted invoice of the period.
     *
     * @param returned value of this invoice's returns up to the end of the period
     */
    public record SalesRow(String invoiceNo, LocalDateTime date, String customer, PaymentStatus paymentStatus,
                           PaymentMethod paymentMethod, BigDecimal grossTotal, BigDecimal returned, BigDecimal netTotal,
                           String user) {
    }

    /**
     * Profit with historical cost: the cost stored on each sale line when it was posted, and the same cost on the
     * returned lines; the product's current purchase price is never used.
     */
    public record ProfitSummary(BigDecimal salesRevenue, BigDecimal cogs, BigDecimal grossProfitBeforeReturns,
                                BigDecimal returnRevenue, BigDecimal returnedCogs, BigDecimal returnProfitReversal,
                                BigDecimal grossProfitAfterReturns, BigDecimal expenses, BigDecimal netProfit) {

        /** Net sales = sales revenue − return revenue. */
        public BigDecimal netSales() {
            return salesRevenue.subtract(returnRevenue);
        }
    }

    // ---------- purchases & expenses ----------

    public record PurchaseSummary(BigDecimal grossPurchases, BigDecimal returns, BigDecimal netPurchases,
                                  long purchaseCount, long returnCount) {
    }

    /** @param returned value of this purchase's returns up to the end of the period */
    public record PurchaseRow(String purchaseNo, String supplierInvoiceNo, LocalDateTime date, String supplier,
                              PaymentStatus paymentStatus, PaymentMethod paymentMethod, BigDecimal grossTotal,
                              BigDecimal returned, BigDecimal netTotal, String user) {
    }

    /** @param byCategory every category, in enum order, with 0 for categories without expenses */
    public record ExpenseSummary(BigDecimal total, long count, Map<ExpenseCategory, BigDecimal> byCategory) {
    }

    public record ExpenseRow(String expenseNo, LocalDateTime date, ExpenseCategory category, BigDecimal amount,
                             PaymentMethod paymentMethod, String description, String user) {
    }

    // ---------- cashbox ----------

    /**
     * Cash book of a period: opening = every movement before the period (never assumed 0); closing = opening + in −
     * out. With direction / source / method / search filters, all four figures cover the filtered movements only.
     */
    public record CashReportSummary(BigDecimal openingBalance, BigDecimal totalIn, BigDecimal totalOut,
                                    BigDecimal netMovement, BigDecimal closingBalance) {
    }

    public record CashRow(LocalDateTime date, boolean in, CashSource source, String reference,
                          PaymentMethod paymentMethod, BigDecimal amount, String user, String notes) {
    }

    // ---------- inventory ----------

    /**
     * Current inventory valuation: quantity × current purchase cost (not the historical cost of goods sold).
     *
     * @param totalValue {@code null} without the cost permission
     */
    public record InventoryValuation(long productCount, BigDecimal totalValue) {
    }

    /** @param purchaseCost and {@code value} are {@code null} without the cost permission */
    public record InventoryRow(String productCode, String barcode, String name, String category, String brand,
                               String unit, BigDecimal quantity, BigDecimal minimumQuantity, BigDecimal purchaseCost,
                               BigDecimal value, String location, boolean active) {
    }

    /** @param shortage max(minimum − quantity, 0) */
    public record LowStockRow(String productCode, String name, String category, String brand, String unit,
                              BigDecimal quantity, BigDecimal minimumQuantity, BigDecimal shortage) {
    }

    /** @param quantityIn and {@code quantityOut} are the signed sums of the movements (out is negative) */
    public record StockMovementSummary(long count, BigDecimal quantityIn, BigDecimal quantityOut) {
    }

    /** @param quantity signed (incoming +, outgoing −); {@code unitCost} {@code null} without the cost permission */
    public record StockMovementRow(LocalDateTime date, String productCode, String productName, String unit,
                                   MovementType type, String reference, BigDecimal quantity, BigDecimal before,
                                   BigDecimal after, BigDecimal unitCost, String user) {
    }

    /**
     * A product's sales in the period, net of the period's returns.
     *
     * @param netRevenue  line totals less their share of the invoice discount, less the returned value
     * @param grossProfit net revenue − historical cost of the net quantity; {@code null} without the profit permission
     */
    public record ProductPerformance(String productCode, String name, String category, String unit,
                                     BigDecimal grossQuantity, BigDecimal returnedQuantity, BigDecimal netQuantity,
                                     BigDecimal netRevenue, BigDecimal grossProfit) {
    }

    /**
     * @param lastSale         {@code null}: never sold
     * @param daysSinceLastSale {@code null}: never sold
     * @param value            {@code null} without the cost permission
     */
    public record SlowMovingRow(String productCode, String name, String unit, BigDecimal quantity, LocalDate lastSale,
                                Long daysSinceLastSale, BigDecimal value) {
    }

    // ---------- customers & suppliers ----------

    /** Balances from the account ledger (customers: debit − credit; suppliers: credit − debit). */
    public record PartyBalanceSummary(long count, BigDecimal total) {
    }

    /** @param lastDocument last posted sale (customers) or purchase (suppliers) */
    public record PartyBalanceRow(int partyId, String code, String name, String phone, String area, BigDecimal balance,
                                  LocalDateTime lastDocument, LocalDateTime lastPayment) {
    }

    // ---------- quotations ----------

    /**
     * Quotations dated in the period, by their effective status (an open quotation past its validity counts as
     * expired). Quotation values are never revenue.
     *
     * @param eligible       quotations offered to the customer: all but drafts (the conversion rate's denominator)
     * @param conversionRate converted / eligible × 100, or {@code null} when nothing is eligible
     */
    public record QuotationSummary(long total, long draft, long sent, long accepted, long rejected, long expired,
                                   long converted, long eligible, BigDecimal conversionRate, BigDecimal totalValue,
                                   BigDecimal convertedValue) {
    }

    public record QuotationRow(String quotationNo, LocalDateTime date, String customer, QuotationStatus status,
                               BigDecimal total, LocalDate validUntil, String saleNo, String user) {
    }

    // ---------- audit ----------

    /** One audit entry; old / new values are never exposed here. */
    public record AuditRow(LocalDateTime time, String user, String action, String entity, String reference,
                           String details) {
    }
}
