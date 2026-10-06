package com.almahwar.service;

import com.almahwar.model.AccountStatement;
import com.almahwar.model.Product;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.ReportPeriod;
import com.almahwar.model.ReportPeriod.DateRange;
import com.almahwar.model.ReportTable;
import com.almahwar.model.ReportType;
import com.almahwar.model.Reports.AuditRow;
import com.almahwar.model.Reports.CashReportSummary;
import com.almahwar.model.Reports.CashRow;
import com.almahwar.model.Reports.CountSummary;
import com.almahwar.model.Reports.ExpenseRow;
import com.almahwar.model.Reports.ExpenseSummary;
import com.almahwar.model.Reports.InventoryRow;
import com.almahwar.model.Reports.InventoryValuation;
import com.almahwar.model.Reports.Lookup;
import com.almahwar.model.Reports.LowStockRow;
import com.almahwar.model.Reports.PartyBalanceRow;
import com.almahwar.model.Reports.PartyBalanceSummary;
import com.almahwar.model.Reports.ProductPerformance;
import com.almahwar.model.Reports.ProfitSummary;
import com.almahwar.model.Reports.PurchaseRow;
import com.almahwar.model.Reports.PurchaseSummary;
import com.almahwar.model.Reports.QuotationRow;
import com.almahwar.model.Reports.QuotationSummary;
import com.almahwar.model.Reports.Result;
import com.almahwar.model.Reports.SalesRow;
import com.almahwar.model.Reports.SalesSummary;
import com.almahwar.model.Reports.SlowMovingRow;
import com.almahwar.model.Reports.StockMovementRow;
import com.almahwar.model.Reports.StockMovementSummary;

import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * Reports & analytics. <b>Read-only</b>: no method writes business data (not even the quotation expiry, which is
 * computed on the fly).
 * <p>
 * Every report needs {@code REPORTS_VIEW} and its own permission ({@link ReportType#getPermissions()}); cost and
 * inventory value need {@code PRODUCT_COST}, profit needs {@code REPORTS_PROFIT}. Unauthorised figures are removed
 * here (returned as {@code null}), so neither the screen nor the export can show them.
 * <p>
 * Dates are inclusive days on the database server's calendar. Sales and purchases count on their posting date,
 * returns on their return date (a September sale returned in October: September gross sales, October returns).
 */
public interface ReportService {

    /** Rows of one export at most (the export notes when the report was cut). */
    int EXPORT_MAX_ROWS = 100_000;
    int MAX_TOP_N = 500;

    LocalDate today();

    /** The inclusive range of a preset, on the server's date ({@code null} for CUSTOM). */
    DateRange range(ReportPeriod period);

    /** The reports the current user may open, in display order. */
    List<ReportType> availableReports();

    boolean canSeeCost();

    boolean canSeeProfit();

    boolean canExport();

    Result<SalesSummary, SalesRow> sales(ReportFilter filter);

    ProfitSummary profit(ReportFilter filter);

    Result<PurchaseSummary, PurchaseRow> purchases(ReportFilter filter);

    Result<ExpenseSummary, ExpenseRow> expenses(ReportFilter filter);

    Result<CashReportSummary, CashRow> cashbox(ReportFilter filter);

    Result<InventoryValuation, InventoryRow> inventory(ReportFilter filter);

    Result<CountSummary, LowStockRow> lowStock(ReportFilter filter);

    Result<StockMovementSummary, StockMovementRow> stockMovements(ReportFilter filter);

    /** Ranked by net quantity sold (sold − returned in the period). */
    List<ProductPerformance> bestSellers(ReportFilter filter);

    /** Products in stock not sold for {@code filter.days} days (or never sold). */
    Result<CountSummary, SlowMovingRow> slowMoving(ReportFilter filter);

    Result<PartyBalanceSummary, PartyBalanceRow> customerDebts(ReportFilter filter);

    Result<PartyBalanceSummary, PartyBalanceRow> supplierBalances(ReportFilter filter);

    /** The ledger statement of {@code filter.customerId} (same logic as the customer's page). */
    AccountStatement customerStatement(ReportFilter filter);

    AccountStatement supplierStatement(ReportFilter filter);

    Result<QuotationSummary, QuotationRow> quotations(ReportFilter filter);

    Result<CountSummary, AuditRow> userActivity(ReportFilter filter);

    /** Any report as a plain table (screen and print), with the same permission checks. */
    ReportTable table(ReportType type, ReportFilter filter);

    /**
     * Writes the report (every matching row, current filters, authorised columns only) as an .xlsx workbook.
     * Needs {@code REPORTS_EXPORT} in addition to the report's own permission.
     */
    void exportXlsx(ReportType type, ReportFilter filter, OutputStream out);

    // ---------- lists for the filters ----------

    List<Lookup> users();

    List<Lookup> customers();

    List<Lookup> suppliers();

    List<Lookup> categories();

    List<Lookup> brands();

    /** Products by code, barcode or name (no cost). */
    List<Product> searchProducts(String text);

    List<String> auditActions();
}
