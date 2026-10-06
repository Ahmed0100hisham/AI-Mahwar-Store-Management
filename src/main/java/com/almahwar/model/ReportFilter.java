package com.almahwar.model;

import java.time.LocalDate;

/**
 * Filters of a report; unused fields are {@code null}. Dates are inclusive days ({@code from} 00:00:00 to the end of
 * {@code to}). Detail rows are paged ({@code page} from 0); summaries always cover every matching row.
 * Fluent setters return {@code this}.
 */
public class ReportFilter {

    public static final int DEFAULT_PAGE_SIZE = 200;
    public static final int MAX_PAGE_SIZE = 1000;
    public static final int DEFAULT_TOP_N = 20;
    public static final int DEFAULT_DAYS = 30;

    private LocalDate from;
    private LocalDate to;
    private String search;
    private Integer customerId;
    private Integer supplierId;
    private Integer productId;
    private Integer categoryId;
    private Integer brandId;
    private Integer userId;
    private ExpenseCategory expenseCategory;
    /** {@code true}: money in, {@code false}: money out, {@code null}: both. */
    private Boolean cashIn;
    private CashSource cashSource;
    private PaymentMethod paymentMethod;
    private MovementType movementType;
    private QuotationStatus quotationStatus;
    private String action;
    /** {@code true}: active products, {@code false}: inactive, {@code null}: all. */
    private Boolean active;
    private int topN = DEFAULT_TOP_N;
    private int days = DEFAULT_DAYS;
    private int page;
    private int pageSize = DEFAULT_PAGE_SIZE;

    public static ReportFilter between(LocalDate from, LocalDate to) {
        return new ReportFilter().from(from).to(to);
    }

    public static ReportFilter none() {
        return new ReportFilter();
    }

    public ReportFilter copy() {
        ReportFilter f = new ReportFilter();
        f.from = from;
        f.to = to;
        f.search = search;
        f.customerId = customerId;
        f.supplierId = supplierId;
        f.productId = productId;
        f.categoryId = categoryId;
        f.brandId = brandId;
        f.userId = userId;
        f.expenseCategory = expenseCategory;
        f.cashIn = cashIn;
        f.cashSource = cashSource;
        f.paymentMethod = paymentMethod;
        f.movementType = movementType;
        f.quotationStatus = quotationStatus;
        f.action = action;
        f.active = active;
        f.topN = topN;
        f.days = days;
        f.page = page;
        f.pageSize = pageSize;
        return f;
    }

    public LocalDate getFrom() { return from; }
    public ReportFilter from(LocalDate from) { this.from = from; return this; }

    public LocalDate getTo() { return to; }
    public ReportFilter to(LocalDate to) { this.to = to; return this; }

    public String getSearch() { return search; }
    public ReportFilter search(String search) { this.search = search; return this; }

    public Integer getCustomerId() { return customerId; }
    public ReportFilter customerId(Integer customerId) { this.customerId = customerId; return this; }

    public Integer getSupplierId() { return supplierId; }
    public ReportFilter supplierId(Integer supplierId) { this.supplierId = supplierId; return this; }

    public Integer getProductId() { return productId; }
    public ReportFilter productId(Integer productId) { this.productId = productId; return this; }

    public Integer getCategoryId() { return categoryId; }
    public ReportFilter categoryId(Integer categoryId) { this.categoryId = categoryId; return this; }

    public Integer getBrandId() { return brandId; }
    public ReportFilter brandId(Integer brandId) { this.brandId = brandId; return this; }

    public Integer getUserId() { return userId; }
    public ReportFilter userId(Integer userId) { this.userId = userId; return this; }

    public ExpenseCategory getExpenseCategory() { return expenseCategory; }
    public ReportFilter expenseCategory(ExpenseCategory c) { this.expenseCategory = c; return this; }

    public Boolean getCashIn() { return cashIn; }
    public ReportFilter cashIn(Boolean cashIn) { this.cashIn = cashIn; return this; }

    public CashSource getCashSource() { return cashSource; }
    public ReportFilter cashSource(CashSource cashSource) { this.cashSource = cashSource; return this; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public ReportFilter paymentMethod(PaymentMethod m) { this.paymentMethod = m; return this; }

    public MovementType getMovementType() { return movementType; }
    public ReportFilter movementType(MovementType t) { this.movementType = t; return this; }

    public QuotationStatus getQuotationStatus() { return quotationStatus; }
    public ReportFilter quotationStatus(QuotationStatus s) { this.quotationStatus = s; return this; }

    public String getAction() { return action; }
    public ReportFilter action(String action) { this.action = action; return this; }

    public Boolean getActive() { return active; }
    public ReportFilter active(Boolean active) { this.active = active; return this; }

    public int getTopN() { return topN; }
    public ReportFilter topN(int topN) { this.topN = topN; return this; }

    public int getDays() { return days; }
    public ReportFilter days(int days) { this.days = days; return this; }

    public int getPage() { return page; }
    public ReportFilter page(int page) { this.page = page; return this; }

    public int getPageSize() { return pageSize; }
    public ReportFilter pageSize(int pageSize) { this.pageSize = pageSize; return this; }
}
