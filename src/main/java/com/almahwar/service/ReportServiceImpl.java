package com.almahwar.service;

import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.ReportDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.Customer;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.PartyType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.QuotationStatus;
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
import com.almahwar.model.Supplier;
import com.almahwar.util.MoneyUtil;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** {@link ReportService} on SQL Server through {@link ReportDao} (read-only queries). */
public class ReportServiceImpl implements ReportService {

    private final ReportDao reportDao;
    private final AccountLedger accountLedger;
    private final CustomerDao customerDao;
    private final SupplierDao supplierDao;
    private final ProductDao productDao;
    private final SecurityContext security;
    /** The company name for the export header, read when exporting (the central company profile). */
    private final java.util.function.Supplier<String> companyName;

    public ReportServiceImpl(ReportDao reportDao, AccountLedger accountLedger, CustomerDao customerDao,
                             SupplierDao supplierDao, ProductDao productDao, SecurityContext security,
                             String companyName) {
        this(reportDao, accountLedger, customerDao, supplierDao, productDao, security, () -> companyName);
    }

    public ReportServiceImpl(ReportDao reportDao, AccountLedger accountLedger, CustomerDao customerDao,
                             SupplierDao supplierDao, ProductDao productDao, SecurityContext security,
                             java.util.function.Supplier<String> companyName) {
        this.reportDao = reportDao;
        this.accountLedger = accountLedger;
        this.customerDao = customerDao;
        this.supplierDao = supplierDao;
        this.productDao = productDao;
        this.security = security;
        this.companyName = companyName;
    }

    // ======================= Access =======================

    @Override
    public LocalDate today() {
        security.requirePermission(Permission.REPORTS_VIEW);
        return reportDao.serverDate();
    }

    @Override
    public DateRange range(ReportPeriod period) {
        return period.range(today());
    }

    @Override
    public List<ReportType> availableReports() {
        if (!security.hasPermission(Permission.REPORTS_VIEW)) {
            return List.of();
        }
        return List.of(ReportType.values()).stream().filter(this::allowed).toList();
    }

    private boolean allowed(ReportType type) {
        return security.hasPermission(Permission.REPORTS_VIEW)
                && type.getPermissions().stream().anyMatch(security::hasPermission);
    }

    /** Permission, then the filter rules (date range, paging) of this report. */
    private ReportFilter check(ReportType type, ReportFilter filter) {
        return check(type, filter, ReportFilter.MAX_PAGE_SIZE);
    }

    private ReportFilter check(ReportType type, ReportFilter filter, int maxPageSize) {
        security.requirePermission(Permission.REPORTS_VIEW);
        if (!allowed(type)) {
            throw new AccessDeniedException("ليس لديك صلاحية عرض \"" + type.getTitle() + "\".");
        }
        ReportFilter f = filter == null ? ReportFilter.none() : filter.copy();
        validate(type, f, maxPageSize);
        return f;
    }

    /** Package-private for unit tests. */
    static void validate(ReportType type, ReportFilter f, int maxPageSize) {
        Validation v = new Validation();
        if (type.isDated()) {
            if (f.getFrom() == null || f.getTo() == null) {
                v.error("from", "حدد الفترة: من تاريخ وإلى تاريخ.");
            } else if (f.getFrom().isAfter(f.getTo())) {
                v.error("from", "تاريخ البداية يجب ألا يكون بعد تاريخ النهاية.");
            }
        }
        if (f.getPage() < 0) {
            v.error("page", "رقم الصفحة غير صحيح.");
        }
        if (f.getPageSize() < 1 || f.getPageSize() > maxPageSize) {
            v.error("pageSize", "عدد الصفوف في الصفحة يجب أن يكون بين 1 و " + maxPageSize + ".");
        }
        if (type == ReportType.BEST_SELLERS && (f.getTopN() < 1 || f.getTopN() > MAX_TOP_N)) {
            v.error("topN", "عدد الأصناف يجب أن يكون بين 1 و " + MAX_TOP_N + ".");
        }
        if (type == ReportType.SLOW_MOVING && (f.getDays() < 1 || f.getDays() > 3650)) {
            v.error("days", "عدد الأيام يجب أن يكون بين 1 و 3650.");
        }
        if (type == ReportType.CUSTOMER_STATEMENT && f.getCustomerId() == null) {
            v.error("customerId", "اختر العميل.");
        }
        if (type == ReportType.SUPPLIER_STATEMENT && f.getSupplierId() == null) {
            v.error("supplierId", "اختر المورد.");
        }
        v.throwIfAny();
    }

    @Override
    public boolean canSeeCost() {
        return security.hasPermission(Permission.PRODUCT_COST);
    }

    @Override
    public boolean canSeeProfit() {
        return security.hasPermission(Permission.REPORTS_PROFIT);
    }

    @Override
    public boolean canExport() {
        return security.hasPermission(Permission.REPORTS_VIEW) && security.hasPermission(Permission.REPORTS_EXPORT);
    }

    private static <S, R> Result<S, R> result(S summary, List<R> rows, ReportFilter f, long total) {
        return new Result<>(summary, rows, f.getPage(), f.getPageSize(), total);
    }

    // ======================= Sales & profit =======================

    @Override
    public Result<SalesSummary, SalesRow> sales(ReportFilter filter) {
        return loadSales(check(ReportType.SALES, filter));
    }

    private Result<SalesSummary, SalesRow> loadSales(ReportFilter f) {
        SalesSummary s = reportDao.salesSummary(f);
        return result(s, reportDao.salesRows(f), f, s.invoiceCount());
    }

    @Override
    public ProfitSummary profit(ReportFilter filter) {
        return loadProfit(check(ReportType.PROFIT, filter));
    }

    private ProfitSummary loadProfit(ReportFilter f) {
        return reportDao.profit(f.getFrom(), f.getTo());
    }

    // ======================= Purchases & expenses =======================

    @Override
    public Result<PurchaseSummary, PurchaseRow> purchases(ReportFilter filter) {
        return loadPurchases(check(ReportType.PURCHASES, filter));
    }

    private Result<PurchaseSummary, PurchaseRow> loadPurchases(ReportFilter f) {
        PurchaseSummary s = reportDao.purchaseSummary(f);
        return result(s, reportDao.purchaseRows(f), f, s.purchaseCount());
    }

    @Override
    public Result<ExpenseSummary, ExpenseRow> expenses(ReportFilter filter) {
        return loadExpenses(check(ReportType.EXPENSES, filter));
    }

    private Result<ExpenseSummary, ExpenseRow> loadExpenses(ReportFilter f) {
        Map<ExpenseCategory, BigDecimal[]> found = reportDao.expensesByCategory(f);
        Map<ExpenseCategory, BigDecimal> byCategory = new EnumMap<>(ExpenseCategory.class);
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        for (ExpenseCategory c : ExpenseCategory.values()) {
            BigDecimal[] v = found.get(c);
            byCategory.put(c, v == null ? MoneyUtil.ZERO : v[0]);
            if (v != null) {
                total = total.add(v[0]);
                count += v[1].longValue();
            }
        }
        return result(new ExpenseSummary(MoneyUtil.of(total), count, byCategory), reportDao.expenseRows(f), f, count);
    }

    // ======================= Cashbox =======================

    @Override
    public Result<CashReportSummary, CashRow> cashbox(ReportFilter filter) {
        return loadCashbox(check(ReportType.CASHBOX, filter));
    }

    private Result<CashReportSummary, CashRow> loadCashbox(ReportFilter f) {
        return result(reportDao.cashSummary(f), reportDao.cashRows(f), f, reportDao.cashCount(f));
    }

    // ======================= Inventory =======================

    @Override
    public Result<InventoryValuation, InventoryRow> inventory(ReportFilter filter) {
        return loadInventory(check(ReportType.INVENTORY, filter));
    }

    private Result<InventoryValuation, InventoryRow> loadInventory(ReportFilter f) {
        boolean cost = canSeeCost();
        BigDecimal[] totals = reportDao.inventoryTotals(f);
        List<InventoryRow> rows = reportDao.inventoryRows(f);
        if (!cost) {
            rows = rows.stream().map(r -> new InventoryRow(r.productCode(), r.barcode(), r.name(), r.category(),
                    r.brand(), r.unit(), r.quantity(), r.minimumQuantity(), null, null, r.location(), r.active())).toList();
        }
        long count = totals[0].longValue();
        return result(new InventoryValuation(count, cost ? totals[1] : null), rows, f, count);
    }

    @Override
    public Result<CountSummary, LowStockRow> lowStock(ReportFilter filter) {
        return loadLowStock(check(ReportType.LOW_STOCK, filter));
    }

    private Result<CountSummary, LowStockRow> loadLowStock(ReportFilter f) {
        long count = reportDao.lowStockCount(f);
        return result(new CountSummary(count), reportDao.lowStockRows(f), f, count);
    }

    @Override
    public Result<StockMovementSummary, StockMovementRow> stockMovements(ReportFilter filter) {
        return loadStockMovements(check(ReportType.STOCK_MOVEMENTS, filter));
    }

    private Result<StockMovementSummary, StockMovementRow> loadStockMovements(ReportFilter f) {
        StockMovementSummary s = reportDao.movementSummary(f);
        List<StockMovementRow> rows = reportDao.movementRows(f);
        if (!canSeeCost()) {
            rows = rows.stream().map(r -> new StockMovementRow(r.date(), r.productCode(), r.productName(), r.unit(),
                    r.type(), r.reference(), r.quantity(), r.before(), r.after(), null, r.user())).toList();
        }
        return result(s, rows, f, s.count());
    }

    @Override
    public List<ProductPerformance> bestSellers(ReportFilter filter) {
        return loadBestSellers(check(ReportType.BEST_SELLERS, filter));
    }

    private List<ProductPerformance> loadBestSellers(ReportFilter f) {
        List<ProductPerformance> rows = reportDao.bestSellers(f);
        if (!canSeeProfit()) {
            rows = rows.stream().map(r -> new ProductPerformance(r.productCode(), r.name(), r.category(), r.unit(),
                    r.grossQuantity(), r.returnedQuantity(), r.netQuantity(), r.netRevenue(), null)).toList();
        }
        return rows;
    }

    @Override
    public Result<CountSummary, SlowMovingRow> slowMoving(ReportFilter filter) {
        return loadSlowMoving(check(ReportType.SLOW_MOVING, filter));
    }

    private Result<CountSummary, SlowMovingRow> loadSlowMoving(ReportFilter f) {
        long count = reportDao.slowMovingCount(f);
        List<SlowMovingRow> rows = reportDao.slowMovingRows(f);
        if (!canSeeCost()) {
            rows = rows.stream().map(r -> new SlowMovingRow(r.productCode(), r.name(), r.unit(), r.quantity(),
                    r.lastSale(), r.daysSinceLastSale(), null)).toList();
        }
        return result(new CountSummary(count), rows, f, count);
    }

    // ======================= Customers & suppliers =======================

    @Override
    public Result<PartyBalanceSummary, PartyBalanceRow> customerDebts(ReportFilter filter) {
        return loadCustomerDebts(check(ReportType.CUSTOMER_DEBTS, filter));
    }

    private Result<PartyBalanceSummary, PartyBalanceRow> loadCustomerDebts(ReportFilter f) {
        PartyBalanceSummary s = reportDao.balanceSummary(f, true);
        return result(s, reportDao.balanceRows(f, true), f, s.count());
    }

    @Override
    public Result<PartyBalanceSummary, PartyBalanceRow> supplierBalances(ReportFilter filter) {
        return loadSupplierBalances(check(ReportType.SUPPLIER_BALANCES, filter));
    }

    private Result<PartyBalanceSummary, PartyBalanceRow> loadSupplierBalances(ReportFilter f) {
        PartyBalanceSummary s = reportDao.balanceSummary(f, false);
        return result(s, reportDao.balanceRows(f, false), f, s.count());
    }

    @Override
    public AccountStatement customerStatement(ReportFilter filter) {
        return loadCustomerStatement(check(ReportType.CUSTOMER_STATEMENT, filter));
    }

    private AccountStatement loadCustomerStatement(ReportFilter f) {
        Customer c = customerDao.findById(f.getCustomerId())
                .orElseThrow(() -> new ValidationException("customerId", "العميل غير موجود."));
        return accountLedger.statement(PartyType.CUSTOMER, c.getCustomerId(), c.getCustomerCode(), c.getName(),
                f.getFrom(), f.getTo(), null);
    }

    @Override
    public AccountStatement supplierStatement(ReportFilter filter) {
        return loadSupplierStatement(check(ReportType.SUPPLIER_STATEMENT, filter));
    }

    private AccountStatement loadSupplierStatement(ReportFilter f) {
        Supplier s = supplierDao.findById(f.getSupplierId())
                .orElseThrow(() -> new ValidationException("supplierId", "المورد غير موجود."));
        return accountLedger.statement(PartyType.SUPPLIER, s.getSupplierId(), s.getSupplierCode(), s.getName(),
                f.getFrom(), f.getTo(), null);
    }

    // ======================= Quotations & audit =======================

    @Override
    public Result<QuotationSummary, QuotationRow> quotations(ReportFilter filter) {
        return loadQuotations(check(ReportType.QUOTATIONS, filter));
    }

    private Result<QuotationSummary, QuotationRow> loadQuotations(ReportFilter f) {
        Map<QuotationStatus, BigDecimal[]> by = reportDao.quotationsByStatus(f);
        long[] n = new long[QuotationStatus.values().length];
        long total = 0;
        BigDecimal value = BigDecimal.ZERO;
        for (Map.Entry<QuotationStatus, BigDecimal[]> e : by.entrySet()) {
            n[e.getKey().ordinal()] = e.getValue()[0].longValue();
            total += n[e.getKey().ordinal()];
            value = value.add(e.getValue()[1]);
        }
        long converted = n[QuotationStatus.CONVERTED.ordinal()];
        long eligible = total - n[QuotationStatus.DRAFT.ordinal()];
        BigDecimal rate = eligible == 0 ? null : BigDecimal.valueOf(converted * 100)
                .divide(BigDecimal.valueOf(eligible), 2, RoundingMode.HALF_UP);
        BigDecimal[] convertedValue = by.get(QuotationStatus.CONVERTED);
        QuotationSummary s = new QuotationSummary(total, n[QuotationStatus.DRAFT.ordinal()],
                n[QuotationStatus.SENT.ordinal()], n[QuotationStatus.ACCEPTED.ordinal()],
                n[QuotationStatus.REJECTED.ordinal()], n[QuotationStatus.EXPIRED.ordinal()], converted, eligible, rate,
                MoneyUtil.of(value), convertedValue == null ? MoneyUtil.ZERO : convertedValue[1]);
        return result(s, reportDao.quotationRows(f), f, total);
    }

    @Override
    public Result<CountSummary, AuditRow> userActivity(ReportFilter filter) {
        return loadUserActivity(check(ReportType.USER_ACTIVITY, filter));
    }

    private Result<CountSummary, AuditRow> loadUserActivity(ReportFilter f) {
        long count = reportDao.auditCount(f);
        return result(new CountSummary(count), reportDao.auditRows(f), f, count);
    }

    // ======================= Tables, print and export =======================

    @Override
    public ReportTable table(ReportType type, ReportFilter filter) {
        return build(type, check(type, filter));
    }

    /** Builds the table from the typed report data (already checked: same queries, same hidden figures). */
    private ReportTable build(ReportType type, ReportFilter filter) {
        String user = security.currentUser().getFullName();
        ReportTables t = new ReportTables(type, filter, reportDao.serverDate(), user, canSeeCost(), canSeeProfit());
        return switch (type) {
            case SALES -> t.sales(loadSales(filter));
            case PROFIT -> t.profit(loadProfit(filter));
            case BEST_SELLERS -> t.bestSellers(loadBestSellers(filter));
            case PURCHASES -> t.purchases(loadPurchases(filter));
            case EXPENSES -> t.expenses(loadExpenses(filter));
            case CASHBOX -> t.cashbox(loadCashbox(filter));
            case INVENTORY -> t.inventory(loadInventory(filter));
            case LOW_STOCK -> t.lowStock(loadLowStock(filter));
            case STOCK_MOVEMENTS -> t.movements(loadStockMovements(filter));
            case SLOW_MOVING -> t.slowMoving(loadSlowMoving(filter));
            case CUSTOMER_DEBTS -> t.balances(loadCustomerDebts(filter), true);
            case SUPPLIER_BALANCES -> t.balances(loadSupplierBalances(filter), false);
            case CUSTOMER_STATEMENT -> t.statement(loadCustomerStatement(filter));
            case SUPPLIER_STATEMENT -> t.statement(loadSupplierStatement(filter));
            case QUOTATIONS -> t.quotations(loadQuotations(filter));
            case USER_ACTIVITY -> t.audit(loadUserActivity(filter));
        };
    }

    @Override
    public void exportXlsx(ReportType type, ReportFilter filter, OutputStream out) {
        security.requirePermission(Permission.REPORTS_EXPORT);
        // every matching row (not just the page on screen), within the export limit
        ReportFilter all = (filter == null ? ReportFilter.none() : filter).copy().page(0).pageSize(EXPORT_MAX_ROWS);
        XlsxReportWriter.write(build(type, check(type, all, EXPORT_MAX_ROWS)), companyName.get(), out);
    }

    // ======================= Lookups =======================

    @Override
    public List<Lookup> users() {
        security.requirePermission(Permission.REPORTS_VIEW);
        return reportDao.users();
    }

    @Override
    public List<Lookup> customers() {
        security.requirePermission(Permission.REPORTS_VIEW);
        return reportDao.customers();
    }

    @Override
    public List<Lookup> suppliers() {
        security.requirePermission(Permission.REPORTS_VIEW);
        return reportDao.suppliers();
    }

    @Override
    public List<Lookup> categories() {
        security.requirePermission(Permission.REPORTS_VIEW);
        return reportDao.categories();
    }

    @Override
    public List<Lookup> brands() {
        security.requirePermission(Permission.REPORTS_VIEW);
        return reportDao.brands();
    }

    @Override
    public List<Product> searchProducts(String text) {
        security.requirePermission(Permission.REPORTS_VIEW);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Product> found = productDao.search(text.trim(), false);
        List<Product> rows = new ArrayList<>(found.subList(0, Math.min(found.size(), 30)));
        if (!canSeeCost()) {
            rows.forEach(p -> p.setPurchasePrice(null));
        }
        return rows;
    }

    @Override
    public List<String> auditActions() {
        security.requirePermission(Permission.REPORTS_AUDIT);
        return reportDao.auditActions();
    }
}
