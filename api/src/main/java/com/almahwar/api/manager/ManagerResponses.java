package com.almahwar.api.manager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.almahwar.api.web.PageResponse;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Purpose-built transport projections. All KWD and quantity values are exact three-decimal strings. */
public final class ManagerResponses {
    private ManagerResponses() { }
    public static String money(BigDecimal value) { return value==null?null:MoneyUtil.of(value).toPlainString(); }
    public static String quantity(BigDecimal value) { return value==null?null:QuantityUtil.of(value).toPlainString(); }
    public record Metric(String name, String value, String gross, String returns) { }
    public record Dashboard(LocalDate businessDate, List<Metric> metrics) { }
    public record Profit(String historicalCost, String returnedHistoricalCost, String grossProfitAfterReturns,
                         String expenses, String netProfit) { }
    public record Sales(ManagerDateRange range, String grossSales, String returns, String netSales, long invoiceCount,
                        long returnCount, String averageInvoice,
                        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description="Only with REPORTS_PROFIT") Profit profit) { }
    public record TrendPoint(LocalDate bucket, String grossSales, String returns, String netSales, long invoiceCount) { }
    public record Trend(ManagerDateRange range, String grouping, List<TrendPoint> buckets) { }
    public record Performance(String code, String name, String unit, String grossQuantity, String returnedQuantity,
                              String netQuantity, String netRevenue,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String grossProfit) { }
    public record TopProducts(ManagerDateRange range, int limit, List<Performance> items) { }
    public record SlowProduct(String code, String name, String unit, String quantity, LocalDate lastSale,
                              Long daysSinceLastSale, @JsonInclude(JsonInclude.Include.NON_NULL) String inventoryValue) { }
    public record CategoryAmount(String category, String amount, long count) { }
    public record Expenses(ManagerDateRange range, String total, long count, List<CategoryAmount> categories) { }
    public record Cashbox(ManagerDateRange range, String openingBalance, String totalIn, String totalOut,
                          String netMovement, String closingBalance) { }
    public record Inventory(long activeProducts, long lowStockProducts, long outOfStockProducts,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String inventoryValue) { }
    public record StockProduct(int id, String code, String name, String unit, String quantity, String minimumStock,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String purchaseCost) { }
    public record ProductDetail(int id, String code, String barcode, String nameAr, String nameEn, String category,
                                String brand, String unit, String salePrice, String quantity, String minimumStock,
                                boolean lowStock, boolean active,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String purchaseCost) { }
    public record Movement(LocalDateTime date, String type, String typeName, String quantity, String before,
                           String after, @JsonInclude(JsonInclude.Include.NON_NULL) String unitCost) { }
    public record Party(int id, String code, String name, String phone, String area, boolean active,
                        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description="Only with the corresponding BALANCE_VIEW permission") String balance) { }
    public record PartyList(PageResponse<Party> page, @JsonInclude(JsonInclude.Include.NON_NULL) String totalOutstanding) { }
    public record AccountEntry(LocalDateTime date, String type, String reference, String debit, String credit,
                               String runningBalance) { }
    public record Account(int partyId, ManagerDateRange range, String openingBalance, String totalDebit,
                          String totalCredit, String closingBalance, PageResponse<AccountEntry> entries) { }
}
