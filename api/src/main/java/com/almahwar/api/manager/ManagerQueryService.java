package com.almahwar.api.manager;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.web.PageResponse;
import com.almahwar.model.PartyType;
import com.almahwar.model.ReportFilter;
import com.almahwar.service.DashboardService.Metric;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.almahwar.api.manager.ManagerResponses.*;

/** Authorizes before parsing or accessing business data, including calls made without an HTTP controller. */
@Service
public class ManagerQueryService {
    private final ManagerAccess access;
    private final SpringSecurityContext security;
    private final ManagerAnalyticsRepository analytics;
    private final ManagerCatalogRepository catalog;
    private final ManagerPartyRepository parties;
    public ManagerQueryService(ManagerAccess access,SpringSecurityContext security,ManagerAnalyticsRepository analytics,
                               ManagerCatalogRepository catalog,ManagerPartyRepository parties) {
        this.access=access;this.security=security;this.analytics=analytics;this.catalog=catalog;this.parties=parties;
    }
    private ManagerDateRange range(ManagerDateRange.Input input,int max) { return ManagerDateRange.resolve(input,max,analytics::today); }
    public Dashboard dashboard() {
        access.require("dashboard");
        var stats=analytics.dashboard();var metrics=new ArrayList<ManagerResponses.Metric>();
        for(Metric metric:Metric.values()) {
            if(metric.getPermissions().stream().noneMatch(security::hasPermission)) continue;
            if(metric==Metric.RECEIVABLES && !access.allowed("customerAccounts")) continue;
            if(metric==Metric.PAYABLES && !access.allowed("supplierAccounts")) continue;
            if(metric==Metric.NET_PROFIT && !access.profit()) continue;
            BigDecimal value=switch(metric) {
                case TODAY_SALES -> stats.todayNetSales();case MONTH_SALES -> stats.monthNetSales();
                case TODAY_INVOICES -> BigDecimal.valueOf(stats.todayInvoices());case NET_PROFIT -> stats.monthNetProfit();
                case EXPENSES -> stats.monthExpenses();case CASH_BALANCE -> stats.cashBalance();
                case RECEIVABLES -> stats.receivables();case PAYABLES -> stats.payables();case LOW_STOCK -> BigDecimal.valueOf(stats.lowStockProducts());
            };
            metrics.add(new ManagerResponses.Metric(metric.name(),metric.isMoney()?money(value):value.toPlainString(),
                    metric==Metric.TODAY_SALES?money(stats.todaySales()):metric==Metric.MONTH_SALES?money(stats.monthSales()):null,
                    metric==Metric.TODAY_SALES?money(stats.todayReturns()):metric==Metric.MONTH_SALES?money(stats.monthReturns()):null));
        }
        return new Dashboard(stats.serverDate(),List.copyOf(metrics));
    }
    public Sales sales(ManagerDateRange.Input input) {
        access.require("sales");var range=range(input,366);var sales=analytics.sales(range);
        Profit profit=null;
        if(access.profit()) {
            var p=analytics.profit(range);
            profit=new Profit(money(p.cogs()),money(p.returnedCogs()),money(p.grossProfitAfterReturns()),money(p.expenses()),money(p.netProfit()));
        }
        return new Sales(range,money(sales.grossSales()),money(sales.returns()),money(sales.netSales()),sales.invoiceCount(),
                sales.returnCount(),money(sales.averageInvoice()),profit);
    }
    public Trend trend(ManagerDateRange.Input input,String group) {
        access.require("sales");
        ManagerAnalyticsRepository.Grouping grouping;
        try { grouping=ManagerAnalyticsRepository.Grouping.valueOf(group); }
        catch(RuntimeException e) { throw new FieldValidationException("grouping","Use daily, weekly or monthly."); }
        var range=range(input,grouping==ManagerAnalyticsRepository.Grouping.daily?366:1096);
        var rows=analytics.trend(range,grouping).stream().collect(Collectors.toMap(TrendPoint::bucket,Function.identity()));
        List<TrendPoint> points=new ArrayList<>();
        for(var day=grouping.bucket(range.from());!day.isAfter(range.to());day=grouping.next(day))
            points.add(rows.getOrDefault(day,new TrendPoint(day,"0.000","0.000","0.000",0)));
        return new Trend(range,grouping.name(),points);
    }
    public TopProducts top(ManagerDateRange.Input input,int limit) {
        access.require("sales");
        if(limit<1 || limit>100) throw new FieldValidationException("limit","Use 1..100.");
        var range=range(input,366);
        return new TopProducts(range,limit,analytics.top(range,limit).stream().map(p -> new Performance(p.productCode(),p.name(),p.unit(),
                quantity(p.grossQuantity()),quantity(p.returnedQuantity()),quantity(p.netQuantity()),money(p.netRevenue()),
                access.profit()?money(p.grossProfit()):null)).toList());
    }
    public PageResponse<SlowProduct> slow(int days,ManagerPage input) {
        access.require("inventory");var page=input.paging();
        if(days<1 || days>3650) throw new FieldValidationException("days","Use 1..3650.");
        if(input.sort()!=null && !input.sort().equals("lastSale,asc")) throw new FieldValidationException("sort","Use lastSale,asc.");
        var filter=ReportFilter.none().days(days).search(input.search()).page(page.page()).pageSize(page.size());
        long count=analytics.slowCount(filter);
        var rows=analytics.slow(filter).stream().map(p -> new SlowProduct(p.productCode(),p.name(),p.unit(),quantity(p.quantity()),
                p.lastSale(),p.daysSinceLastSale(),access.cost()?money(p.value()):null)).toList();
        return PageResponse.of(rows,page,count);
    }
    public Expenses expenses(ManagerDateRange.Input input) {
        access.require("expenses");var range=range(input,366);var found=analytics.expenses(range);
        List<CategoryAmount> categories=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;long count=0;
        for(var category:com.almahwar.model.ExpenseCategory.values()) {
            var value=found.get(category);var amount=value==null?BigDecimal.ZERO:value[0];long n=value==null?0:value[1].longValueExact();
            total=total.add(amount);count+=n;categories.add(new CategoryAmount(category.name(),money(amount),n));
        }
        return new Expenses(range,money(total),count,List.copyOf(categories));
    }
    public Cashbox cashbox(ManagerDateRange.Input input) {
        access.require("cashbox");var range=range(input,366);var cash=analytics.cashbox(range);
        return new Cashbox(range,money(cash.openingBalance()),money(cash.totalIn()),money(cash.totalOut()),money(cash.netMovement()),money(cash.closingBalance()));
    }
    public Inventory inventory() { access.require("inventory");return catalog.inventory(access.cost()); }
    public PageResponse<StockProduct> lowStock(ManagerPage page) {
        access.require("inventory");page.validate("name","code","quantity","minimumStock");return catalog.lowStock(page,access.cost());
    }
    public ProductDetail product(int id) {
        access.require("products");positive(id);var p=catalog.product(id);
        return new ProductDetail(p.getProductId(),p.getProductCode(),p.getBarcode(),p.getNameAr(),p.getNameEn(),p.getCategoryName(),p.getBrandName(),
                p.getUnitName(),money(p.getSalePrice()),quantity(p.getQuantity()),quantity(p.getMinimumStock()),
                p.isActive() && p.getQuantity().compareTo(p.getMinimumStock())<=0,p.isActive(),access.cost()?money(p.getPurchasePrice()):null);
    }
    public PageResponse<Movement> movements(int id,ManagerDateRange.Input input,ManagerPage paging) {
        access.require("inventory");positive(id);catalog.product(id);var page=paging.paging();
        if(paging.search()!=null || (paging.sort()!=null && !paging.sort().equals("date,desc")))
            throw new FieldValidationException("sort","Movements use date,desc and do not support text filters.");
        var filter=range(input,366).filter().productId(id).page(page.page()).pageSize(page.size());
        var count=analytics.movementSummary(filter).count();
        var rows=analytics.movements(filter).stream().map(m -> new Movement(m.date(),m.type().name(),m.type().getLabelAr(),quantity(m.quantity()),
                quantity(m.before()),quantity(m.after()),access.cost()?money(m.unitCost()):null)).toList();
        return PageResponse.of(rows,page,count);
    }
    public PartyList parties(PartyType type,ManagerPage input,boolean outstanding) {
        access.require(view(type,outstanding));boolean balances=access.allowed(view(type,true));
        input.validate(balances?new String[]{"name","code","balance"}:new String[]{"name","code"});
        return parties.list(type,input,balances,outstanding);
    }
    public Party party(PartyType type,int id) { access.require(view(type,false));positive(id);return parties.detail(type,id); }
    public Account account(PartyType type,int id,ManagerDateRange.Input input,ManagerPage page) {
        access.require(view(type,true));positive(id);parties.detail(type,id);return parties.account(type,id,range(input,366),page);
    }
    private static String view(PartyType type,boolean balances) {
        return type==PartyType.CUSTOMER?(balances?"customerAccounts":"customers"):(balances?"supplierAccounts":"suppliers");
    }
    private static void positive(int id) { if(id<1) throw new FieldValidationException("id","Use a positive identifier."); }
}
