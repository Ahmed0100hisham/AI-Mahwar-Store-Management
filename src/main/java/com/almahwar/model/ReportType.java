package com.almahwar.model;

import java.util.EnumSet;
import java.util.Set;

import static com.almahwar.model.ReportType.Field.*;

/**
 * The available reports: title, group (tab), the permission each one needs (besides {@link Permission#REPORTS_VIEW})
 * and the filters it supports. The same list drives the screen, the export and a future REST API.
 */
public enum ReportType {

    SALES("تقرير المبيعات", Category.SALES_PROFIT, Permission.REPORTS_SALES, true, EnumSet.of(DATE, SEARCH, CUSTOMER, USER),
            "إجمالي المبيعات والمرتجعات وصافي المبيعات حسب تاريخ الاعتماد"),
    PROFIT("تقرير الأرباح", Category.SALES_PROFIT, Permission.REPORTS_PROFIT, true, EnumSet.of(DATE),
            "الربح بالتكلفة التاريخية بعد المرتجعات والمصروفات"),
    BEST_SELLERS("الأصناف الأكثر مبيعًا", Category.SALES_PROFIT, EnumSet.of(Permission.REPORTS_SALES,
            Permission.REPORTS_INVENTORY), true,
            EnumSet.of(DATE, CATEGORY, TOP_N), "حسب صافي الكمية المباعة بعد المرتجعات"),
    PURCHASES("تقرير المشتريات", Category.PURCHASES_EXPENSES, Permission.REPORTS_PURCHASES, true,
            EnumSet.of(DATE, SEARCH, SUPPLIER, USER), "إجمالي المشتريات والمرتجعات وصافي المشتريات"),
    EXPENSES("تقرير المصروفات", Category.PURCHASES_EXPENSES, Permission.REPORTS_EXPENSES, true,
            EnumSet.of(DATE, SEARCH, EXPENSE_CATEGORY, USER), "المصروفات حسب الفئة"),
    CASHBOX("تقرير الخزنة", Category.CASH, Permission.REPORTS_CASHBOX, true,
            EnumSet.of(DATE, SEARCH, CASH_DIRECTION, CASH_SOURCE, PAYMENT_METHOD),
            "رصيد أول المدة والوارد والصادر ورصيد آخر المدة"),
    INVENTORY("تقييم المخزون", Category.INVENTORY, Permission.REPORTS_INVENTORY, false,
            EnumSet.of(SEARCH, CATEGORY, BRAND, ACTIVE), "الكميات الحالية وقيمتها بسعر الشراء الحالي"),
    LOW_STOCK("الأصناف الناقصة", Category.INVENTORY, Permission.REPORTS_INVENTORY, false,
            EnumSet.of(SEARCH, CATEGORY, BRAND), "الكمية عند الحد الأدنى أو أقل"),
    STOCK_MOVEMENTS("حركات المخزون", Category.INVENTORY, Permission.REPORTS_INVENTORY, true,
            EnumSet.of(DATE, SEARCH, PRODUCT, MOVEMENT_TYPE), "كل حركة بالكمية قبل وبعد"),
    SLOW_MOVING("الأصناف الراكدة", Category.INVENTORY, Permission.REPORTS_INVENTORY, false,
            EnumSet.of(SEARCH, CATEGORY, DAYS), "أصناف في المخزون لم تُبع منذ مدة"),
    CUSTOMER_DEBTS("ديون العملاء", Category.PARTIES, Permission.REPORTS_PARTIES, false, EnumSet.of(SEARCH),
            "العملاء الذين عليهم رصيد مستحق (من دفتر الحسابات)"),
    SUPPLIER_BALANCES("مستحقات الموردين", Category.PARTIES, Permission.REPORTS_PARTIES, false, EnumSet.of(SEARCH),
            "الموردون الذين لهم رصيد مستحق (من دفتر الحسابات)"),
    CUSTOMER_STATEMENT("كشف حساب عميل", Category.PARTIES, Permission.REPORTS_PARTIES, true,
            EnumSet.of(DATE, CUSTOMER), "رصيد أول المدة والحركات والرصيد الجاري"),
    SUPPLIER_STATEMENT("كشف حساب مورد", Category.PARTIES, Permission.REPORTS_PARTIES, true,
            EnumSet.of(DATE, SUPPLIER), "رصيد أول المدة والحركات والرصيد الجاري"),
    QUOTATIONS("تقرير عروض الأسعار", Category.QUOTATIONS, Permission.REPORTS_QUOTATIONS, true,
            EnumSet.of(DATE, SEARCH, CUSTOMER, QUOTATION_STATUS), "الحالات ونسبة التحويل (لا تدخل في المبيعات)"),
    USER_ACTIVITY("نشاط المستخدمين", Category.AUDIT, Permission.REPORTS_AUDIT, true,
            EnumSet.of(DATE, SEARCH, USER, ACTION), "سجل العمليات حسب المستخدم والإجراء");

    /** Report groups (the tabs of the reports screen). */
    public enum Category {
        SALES_PROFIT("المبيعات والأرباح"),
        PURCHASES_EXPENSES("المشتريات والمصروفات"),
        CASH("الخزنة"),
        INVENTORY("المخزون"),
        PARTIES("العملاء والموردون"),
        QUOTATIONS("عروض الأسعار"),
        AUDIT("نشاط المستخدمين");

        private final String labelAr;

        Category(String labelAr) {
            this.labelAr = labelAr;
        }

        public String getLabelAr() {
            return labelAr;
        }
    }

    /** Filters a report may offer. */
    public enum Field {
        DATE, SEARCH, CUSTOMER, SUPPLIER, PRODUCT, CATEGORY, BRAND, USER, EXPENSE_CATEGORY, CASH_DIRECTION,
        CASH_SOURCE, PAYMENT_METHOD, MOVEMENT_TYPE, QUOTATION_STATUS, ACTION, TOP_N, DAYS, ACTIVE
    }

    private final String title;
    private final Category category;
    private final Set<Permission> anyOf;
    private final boolean dated;
    private final Set<Field> fields;
    private final String description;

    ReportType(String title, Category category, Permission permission, boolean dated, Set<Field> fields,
               String description) {
        this(title, category, EnumSet.of(permission), dated, fields, description);
    }

    ReportType(String title, Category category, Set<Permission> anyOf, boolean dated, Set<Field> fields,
               String description) {
        this.title = title;
        this.category = category;
        this.anyOf = anyOf;
        this.dated = dated;
        this.fields = fields;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public Category getCategory() {
        return category;
    }

    /** Any one of these is needed, in addition to {@link Permission#REPORTS_VIEW}. */
    public Set<Permission> getPermissions() {
        return EnumSet.copyOf(anyOf);
    }

    /** The report covers a date range (required). */
    public boolean isDated() {
        return dated;
    }

    public Set<Field> getFields() {
        return EnumSet.copyOf(fields);
    }

    public boolean has(Field field) {
        return fields.contains(field);
    }

    public String getDescription() {
        return description;
    }
}
