package com.almahwar.api.security;

/**
 * Fine-grained operations a user may perform: the desktop's {@code com.almahwar.model.Permission}, same names, same
 * meaning ({@code PermissionMatrixParityTest} keeps the two identical). Which roles get which permissions is defined
 * in {@link RolePermissions}. In Spring Security each one is the authority {@code PERM_<NAME>}.
 */
public enum Permission {

    DASHBOARD("الرئيسية"),

    /** Search and open sales invoices. */
    SALES_VIEW("عرض المبيعات"),
    /** Use the point of sale: build carts and save sale drafts. */
    SALES_CREATE("نقطة البيع وإنشاء فواتير البيع"),
    /** Complete (post) a sale: moves stock, the customer account and cash. */
    SALES_POST("اعتماد فواتير البيع"),
    /** See the historical unit cost and cost total of sales. */
    SALES_COST_VIEW("تكلفة المبيعات"),
    /** See the gross profit of sales. */
    SALES_PROFIT_VIEW("أرباح المبيعات"),
    /** Change a product's unit price on a sale (every change is written to the audit log). */
    SALES_PRICE_OVERRIDE("تعديل سعر البيع يدويًا"),
    /** Give line and invoice discounts. */
    SALES_DISCOUNT("خصومات البيع"),
    SALE_RETURNS("مرتجعات المبيعات"),
    /** Search and open quotations. */
    QUOTATIONS_VIEW("عرض عروض الأسعار"),
    /** Create quotations (drafts). */
    QUOTATIONS_CREATE("إنشاء عروض الأسعار"),
    /** Edit / delete drafts, reopen a sent quotation for editing. */
    QUOTATIONS_EDIT("تعديل عروض الأسعار"),
    /** Mark a quotation as sent to the customer. */
    QUOTATIONS_SEND("إرسال عروض الأسعار"),
    /** Record the customer's decision: accepted / rejected. */
    QUOTATIONS_ACCEPT("قبول ورفض عروض الأسعار"),
    /** Turn an accepted quotation into a sale draft (also needs the sales permissions). */
    QUOTATIONS_CONVERT("تحويل عرض السعر إلى فاتورة"),
    /** Give line / quotation discounts. */
    QUOTATIONS_DISCOUNT("خصومات عروض الأسعار"),
    /** Quote a price other than the list price (written to the audit log). */
    QUOTATIONS_PRICE_OVERRIDE("تعديل أسعار عروض الأسعار يدويًا"),
    /** Search and view customers. */
    CUSTOMERS_VIEW("عرض العملاء"),
    /** Add / edit / activate customers. */
    CUSTOMERS_EDIT("إضافة وتعديل العملاء"),
    /** Balances, credit limits, opening balances and account statements of customers. */
    CUSTOMER_BALANCE_VIEW("أرصدة وكشوف حسابات العملاء"),
    CUSTOMER_PAYMENTS("تحصيل العملاء"),
    /** Sell on credit beyond a customer's credit limit (confirmed, and written to the audit log). */
    CUSTOMER_CREDIT_OVERRIDE("تجاوز حد ائتمان العميل"),

    /** Search and view products (needed to sell); no editing. */
    PRODUCTS_VIEW("عرض المنتجات"),
    /** Add/edit products, categories, brands and units. */
    PRODUCTS("إدارة المنتجات والأقسام"),
    /** See purchase prices (cost) of products. */
    PRODUCT_COST("سعر الشراء والتكلفة"),
    /** View stock balances and the stock movement history. */
    INVENTORY("المخزون"),
    /** Manual stock adjustments (ADJUSTMENT_IN / ADJUSTMENT_OUT). */
    INVENTORY_ADJUST("تسوية المخزون"),
    /** Search and open purchase invoices. */
    PURCHASES_VIEW("عرض المشتريات"),
    /** Create and edit purchase drafts. */
    PURCHASES_CREATE("إنشاء فواتير المشتريات"),
    /** Post (approve) a purchase: moves stock, the supplier account and cash. */
    PURCHASES_POST("اعتماد فواتير المشتريات"),
    /** See unit costs and amounts of purchase invoices. */
    PURCHASE_COST_VIEW("تكاليف ومبالغ المشتريات"),
    PURCHASE_RETURNS("مرتجعات المشتريات"),
    SUPPLIERS_VIEW("عرض الموردين"),
    SUPPLIERS_EDIT("إضافة وتعديل الموردين"),
    /** Balances, opening balances and account statements of suppliers. */
    SUPPLIER_BALANCE_VIEW("أرصدة وكشوف حسابات الموردين"),

    /** View the cashbox: balance, today's figures and every movement. */
    CASH("الخزنة"),
    /** Manual cash deposits and withdrawals. */
    CASH_ADJUST("إيداع وسحب يدوي من الخزنة"),
    /** Record and view expenses. */
    EXPENSES("المصروفات"),
    /** Pay suppliers (سند صرف) and see their payment history. */
    SUPPLIER_PAYMENTS("مدفوعات الموردين"),
    FINANCIAL_REPORTS("التقارير المالية"),

    // Reports & analytics (read-only). Every report needs REPORTS_VIEW plus its own permission; cost and profit
    // figures inside a report also need PRODUCT_COST (purchase cost, inventory value) or REPORTS_PROFIT (profit).
    REPORTS_VIEW("التقارير"),
    REPORTS_SALES("تقارير المبيعات"),
    REPORTS_PROFIT("تقارير الأرباح والتكلفة"),
    REPORTS_PURCHASES("تقارير المشتريات"),
    REPORTS_EXPENSES("تقارير المصروفات"),
    REPORTS_CASHBOX("تقارير الخزنة"),
    REPORTS_INVENTORY("تقارير المخزون"),
    REPORTS_PARTIES("تقارير العملاء والموردين"),
    REPORTS_QUOTATIONS("تقارير عروض الأسعار"),
    REPORTS_AUDIT("تقرير نشاط المستخدمين"),
    REPORTS_EXPORT("تصدير التقارير إلى Excel"),

    // User management (admin only): see, create, edit (profile, role, enable / disable, unlock), reset passwords.
    // Changing one's own password is not a permission: every logged-in user may do it.
    USERS_VIEW("عرض المستخدمين"),
    USERS_CREATE("إنشاء المستخدمين"),
    USERS_EDIT("تعديل المستخدمين وأدوارهم وتفعيلهم"),
    USERS_RESET_PASSWORD("إعادة تعيين كلمات المرور"),
    AUDIT_LOG("سجل العمليات"),
    /** Opens the settings screen (company profile, system settings, about). */
    SETTINGS_VIEW("عرض الإعدادات"),
    /** Changes the company profile, system settings and logo. */
    SETTINGS_EDIT("تعديل الإعدادات"),

    // Database backup / restore (admin only). Restore replaces the whole database: the strictest of all.
    /** Opens the backup screen: settings and the backup history. */
    BACKUP_VIEW("عرض النسخ الاحتياطية"),
    /** Creates a full database backup (written by SQL Server on the server machine). */
    BACKUP_CREATE("إنشاء نسخة احتياطية"),
    /** Asks SQL Server to verify that a backup file is readable and complete. */
    BACKUP_VERIFY("التحقق من النسخ الاحتياطية"),
    /** Restores the database from a backup (after verification, a safety backup and a typed confirmation). */
    BACKUP_RESTORE("استعادة قاعدة البيانات من نسخة احتياطية");

    private final String labelAr;

    Permission(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }
}
