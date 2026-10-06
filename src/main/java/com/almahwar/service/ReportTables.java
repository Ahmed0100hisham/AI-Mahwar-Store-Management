package com.almahwar.service;

import com.almahwar.model.AccountStatement;
import com.almahwar.model.CashSource;
import com.almahwar.model.MovementType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.PartyType;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.ReportTable;
import com.almahwar.model.ReportTable.Column;
import com.almahwar.model.ReportTable.Item;
import com.almahwar.model.ReportTable.Kind;
import com.almahwar.model.ReportType;
import com.almahwar.model.Reports.AuditRow;
import com.almahwar.model.Reports.CashReportSummary;
import com.almahwar.model.Reports.CashRow;
import com.almahwar.model.Reports.CountSummary;
import com.almahwar.model.Reports.ExpenseRow;
import com.almahwar.model.Reports.ExpenseSummary;
import com.almahwar.model.Reports.InventoryRow;
import com.almahwar.model.Reports.InventoryValuation;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.almahwar.model.ReportTable.Kind.*;

/**
 * Turns typed report data into a {@link ReportTable}. Columns of cost or profit are only added for users allowed to
 * see them (and their values are already {@code null} otherwise). Package-private: built by {@link ReportServiceImpl}.
 */
final class ReportTables {

    private final ReportType type;
    private final ReportFilter filter;
    private final LocalDate today;
    private final String user;
    private final boolean cost;
    private final boolean profit;

    private final List<Item> summary = new ArrayList<>();
    private final List<Column> columns = new ArrayList<>();
    private final List<List<Object>> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    ReportTables(ReportType type, ReportFilter filter, LocalDate today, String user, boolean cost, boolean profit) {
        this.type = type;
        this.filter = filter;
        this.today = today;
        this.user = user;
        this.cost = cost;
        this.profit = profit;
    }

    // ---------- helpers ----------

    private void item(String label, Object value, Kind kind) {
        summary.add(new Item(label, value, kind));
    }

    private void cols(Object... headerKindPairs) {
        for (int i = 0; i < headerKindPairs.length; i += 2) {
            columns.add(new Column((String) headerKindPairs[i], (Kind) headerKindPairs[i + 1]));
        }
    }

    private void row(Object... values) {
        rows.add(new ArrayList<>(Arrays.asList(values)));
    }

    private ReportTable done(long totalRows, int page, int pageSize) {
        boolean dated = type.isDated();
        return new ReportTable(type, type.getTitle(), dated ? filter.getFrom() : null, dated ? filter.getTo() : null,
                LocalDateTime.now(), user, List.copyOf(summary), List.copyOf(columns), rows, page, pageSize,
                totalRows, List.copyOf(notes));
    }

    private ReportTable done(Result<?, ?> r) {
        return done(r.totalRows(), r.page(), r.pageSize());
    }

    /** The Arabic label of an enum value shown in a report. */
    private static String label(Enum<?> e) {
        if (e == null) {
            return null;
        } else if (e instanceof PaymentStatus x) {
            return x.getLabelAr();
        } else if (e instanceof PaymentMethod x) {
            return x.getLabelAr();
        } else if (e instanceof ExpenseCategory x) {
            return x.getLabelAr();
        } else if (e instanceof CashSource x) {
            return x.getLabelAr();
        } else if (e instanceof MovementType x) {
            return x.getLabelAr();
        } else if (e instanceof QuotationStatus x) {
            return x.getLabelAr();
        }
        return e.name();
    }

    // ---------- sales & profit ----------

    ReportTable sales(Result<SalesSummary, SalesRow> r) {
        SalesSummary s = r.summary();
        item("إجمالي المبيعات", s.grossSales(), MONEY);
        item("المرتجعات", s.returns(), MONEY);
        item("صافي المبيعات", s.netSales(), MONEY);
        item("عدد الفواتير", s.invoiceCount(), INTEGER);
        item("متوسط الفاتورة", s.averageInvoice(), MONEY);
        item("عدد المرتجعات", s.returnCount(), INTEGER);
        cols("رقم الفاتورة", TEXT, "التاريخ", DATETIME, "العميل", TEXT, "حالة الدفع", TEXT, "طريقة الدفع", TEXT,
                "الإجمالي", MONEY, "المرتجع", MONEY, "الصافي", MONEY, "المستخدم", TEXT);
        for (SalesRow x : r.rows()) {
            row(x.invoiceNo(), x.date(), x.customer(), label(x.paymentStatus()), label(x.paymentMethod()),
                    x.grossTotal(), x.returned(), x.netTotal(), x.user());
        }
        notes.add("صافي المبيعات = إجمالي المبيعات − المرتجعات. الفواتير حسب تاريخ اعتمادها والمرتجعات حسب تاريخ المرتجع"
                + " (مرتجع فاتورة من فترة سابقة يُخصم من الفترة التي تم فيها).");
        notes.add("عمود \"المرتجع\" = مرتجعات الفاتورة حتى نهاية الفترة.");
        return done(r);
    }

    ReportTable profit(ProfitSummary p) {
        item("إيرادات المبيعات", p.salesRevenue(), MONEY);
        item("تكلفة البضاعة المباعة", p.cogs(), MONEY);
        item("مجمل الربح قبل المرتجعات", p.grossProfitBeforeReturns(), MONEY);
        item("قيمة المرتجعات", p.returnRevenue(), MONEY);
        item("تكلفة المرتجعات", p.returnedCogs(), MONEY);
        item("أثر المرتجعات على الربح", p.returnProfitReversal(), MONEY);
        item("مجمل الربح بعد المرتجعات", p.grossProfitAfterReturns(), MONEY);
        item("المصروفات", p.expenses(), MONEY);
        item("صافي الربح", p.netProfit(), MONEY);
        cols("البند", TEXT, "المبلغ", MONEY);
        row("إيرادات المبيعات (فواتير معتمدة)", p.salesRevenue());
        row("− تكلفة البضاعة المباعة (التكلفة التاريخية)", p.cogs());
        row("= مجمل الربح قبل المرتجعات", p.grossProfitBeforeReturns());
        row("قيمة المرتجعات", p.returnRevenue());
        row("− تكلفة المرتجعات (تكلفة البيع الأصلية)", p.returnedCogs());
        row("= أثر المرتجعات على الربح", p.returnProfitReversal());
        row("= مجمل الربح بعد المرتجعات", p.grossProfitAfterReturns());
        row("− المصروفات", p.expenses());
        row("= صافي الربح", p.netProfit());
        notes.add("التكلفة تاريخية: تكلفة كل سطر بيع وقت اعتماده، ولا يُستخدم سعر الشراء الحالي للصنف.");
        notes.add("رصيد الخزنة ليس ربحًا: الربح يُحسب من المبيعات والتكلفة والمصروفات فقط.");
        return done(rows.size(), 0, rows.size());
    }

    ReportTable bestSellers(List<ProductPerformance> list) {
        item("عدد الأصناف", (long) list.size(), INTEGER);
        item("صافي الإيراد", sum(list.stream().map(ProductPerformance::netRevenue).toList()), MONEY);
        if (profit) {
            item("مجمل الربح", sum(list.stream().map(ProductPerformance::grossProfit).toList()), MONEY);
        }
        cols("#", INTEGER, "الكود", TEXT, "الصنف", TEXT, "القسم", TEXT, "الوحدة", TEXT, "الكمية المباعة", QUANTITY,
                "المرتجع", QUANTITY, "صافي الكمية", QUANTITY, "صافي الإيراد", MONEY);
        if (profit) {
            cols("مجمل الربح", MONEY);
        }
        long n = 0;
        for (ProductPerformance x : list) {
            List<Object> values = new ArrayList<>(Arrays.asList(++n, x.productCode(), x.name(), x.category(), x.unit(),
                    x.grossQuantity(), x.returnedQuantity(), x.netQuantity(), x.netRevenue()));
            if (profit) {
                values.add(x.grossProfit());
            }
            rows.add(values);
        }
        notes.add("الترتيب حسب صافي الكمية = المباع − المرتجع في الفترة. الإيراد بعد حصة السطر من خصم الفاتورة"
                + " (مقربة لثلاث منازل) وبعد قيمة المرتجع.");
        return done(list.size(), 0, Math.max(1, list.size()));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------- purchases & expenses ----------

    ReportTable purchases(Result<PurchaseSummary, PurchaseRow> r) {
        PurchaseSummary s = r.summary();
        item("إجمالي المشتريات", s.grossPurchases(), MONEY);
        item("مرتجعات المشتريات", s.returns(), MONEY);
        item("صافي المشتريات", s.netPurchases(), MONEY);
        item("عدد الفواتير", s.purchaseCount(), INTEGER);
        item("عدد المرتجعات", s.returnCount(), INTEGER);
        cols("رقم الفاتورة", TEXT, "فاتورة المورد", TEXT, "التاريخ", DATETIME, "المورد", TEXT, "حالة الدفع", TEXT,
                "طريقة الدفع", TEXT, "الإجمالي", MONEY, "المرتجع", MONEY, "الصافي", MONEY, "المستخدم", TEXT);
        for (PurchaseRow x : r.rows()) {
            row(x.purchaseNo(), x.supplierInvoiceNo(), x.date(), x.supplier(), label(x.paymentStatus()),
                    label(x.paymentMethod()), x.grossTotal(), x.returned(), x.netTotal(), x.user());
        }
        notes.add("صافي المشتريات = المشتريات المعتمدة − مرتجعات المشتريات؛ المشتريات حسب تاريخ اعتمادها والمرتجعات حسب"
                + " تاريخ المرتجع.");
        return done(r);
    }

    ReportTable expenses(Result<ExpenseSummary, ExpenseRow> r) {
        ExpenseSummary s = r.summary();
        item("إجمالي المصروفات", s.total(), MONEY);
        item("عدد المصروفات", s.count(), INTEGER);
        for (ExpenseCategory c : ExpenseCategory.values()) {
            item(c.getLabelAr(), s.byCategory().get(c), MONEY);
        }
        cols("رقم المصروف", TEXT, "التاريخ", DATETIME, "الفئة", TEXT, "المبلغ", MONEY, "طريقة الدفع", TEXT,
                "الوصف", TEXT, "المستخدم", TEXT);
        for (ExpenseRow x : r.rows()) {
            row(x.expenseNo(), x.date(), label(x.category()), x.amount(), label(x.paymentMethod()), x.description(),
                    x.user());
        }
        return done(r);
    }

    // ---------- cashbox ----------

    ReportTable cashbox(Result<CashReportSummary, CashRow> r) {
        CashReportSummary s = r.summary();
        item("رصيد أول المدة", s.openingBalance(), MONEY);
        item("إجمالي الوارد", s.totalIn(), MONEY);
        item("إجمالي الصادر", s.totalOut(), MONEY);
        item("صافي الحركة", s.netMovement(), MONEY);
        item("رصيد آخر المدة", s.closingBalance(), MONEY);
        cols("التاريخ", DATETIME, "الاتجاه", TEXT, "المصدر", TEXT, "المرجع", TEXT, "طريقة الدفع", TEXT, "المبلغ", MONEY,
                "المستخدم", TEXT, "ملاحظات", TEXT);
        for (CashRow x : r.rows()) {
            row(x.date(), x.in() ? "وارد" : "صادر", label(x.source()), x.reference(), label(x.paymentMethod()),
                    x.amount(), x.user(), x.notes());
        }
        notes.add("رصيد آخر المدة = رصيد أول المدة + الوارد − الصادر. رصيد أول المدة = كل الحركات قبل بداية الفترة."
                + (filter.getCashIn() != null || filter.getCashSource() != null || filter.getPaymentMethod() != null
                || (filter.getSearch() != null && !filter.getSearch().isBlank())
                ? " مع الفلاتر الحالية تشمل الأرصدة الحركات المطابقة فقط." : ""));
        return done(r);
    }

    // ---------- inventory ----------

    ReportTable inventory(Result<InventoryValuation, InventoryRow> r) {
        item("عدد الأصناف", r.summary().productCount(), INTEGER);
        if (cost) {
            item("إجمالي قيمة المخزون", r.summary().totalValue(), MONEY);
        }
        cols("الكود", TEXT, "الباركود", TEXT, "الصنف", TEXT, "القسم", TEXT, "الماركة", TEXT, "الوحدة", TEXT,
                "الكمية", QUANTITY, "الحد الأدنى", QUANTITY);
        if (cost) {
            cols("سعر الشراء", MONEY, "القيمة", MONEY);
        }
        cols("الموقع", TEXT, "الحالة", TEXT);
        for (InventoryRow x : r.rows()) {
            List<Object> values = new ArrayList<>(Arrays.asList(x.productCode(), x.barcode(), x.name(), x.category(),
                    x.brand(), x.unit(), x.quantity(), x.minimumQuantity()));
            if (cost) {
                values.add(x.purchaseCost());
                values.add(x.value());
            }
            values.add(x.location());
            values.add(x.active() ? "نشط" : "غير نشط");
            rows.add(values);
        }
        if (cost) {
            notes.add("قيمة المخزون = الكمية الحالية × سعر الشراء الحالي (تقييم حالي، وليس تكلفة البضاعة المباعة التاريخية).");
        }
        return done(r);
    }

    ReportTable lowStock(Result<CountSummary, LowStockRow> r) {
        item("أصناف عند الحد الأدنى أو أقل", r.summary().count(), INTEGER);
        cols("الكود", TEXT, "الصنف", TEXT, "القسم", TEXT, "الماركة", TEXT, "الوحدة", TEXT, "الكمية الحالية", QUANTITY,
                "الحد الأدنى", QUANTITY, "النقص", QUANTITY);
        for (LowStockRow x : r.rows()) {
            row(x.productCode(), x.name(), x.category(), x.brand(), x.unit(), x.quantity(), x.minimumQuantity(),
                    x.shortage());
        }
        notes.add("النقص = الحد الأدنى − الكمية الحالية (ولا يقل عن صفر). الأصناف النشطة فقط.");
        return done(r);
    }

    ReportTable movements(Result<StockMovementSummary, StockMovementRow> r) {
        StockMovementSummary s = r.summary();
        item("عدد الحركات", s.count(), INTEGER);
        item("كميات واردة", s.quantityIn(), QUANTITY);
        item("كميات صادرة", s.quantityOut(), QUANTITY);
        cols("التاريخ", DATETIME, "الكود", TEXT, "الصنف", TEXT, "نوع الحركة", TEXT, "المرجع", TEXT, "الكمية (±)", QUANTITY,
                "قبل", QUANTITY, "بعد", QUANTITY);
        if (cost) {
            cols("تكلفة الوحدة", MONEY);
        }
        cols("المستخدم", TEXT);
        for (StockMovementRow x : r.rows()) {
            List<Object> values = new ArrayList<>(Arrays.asList(x.date(), x.productCode(), x.productName(),
                    label(x.type()), x.reference(), x.quantity(), x.before(), x.after()));
            if (cost) {
                values.add(x.unitCost());
            }
            values.add(x.user());
            rows.add(values);
        }
        notes.add("الكمية بإشارتها: الوارد موجب والصادر سالب، وبعد = قبل + الكمية.");
        return done(r);
    }

    ReportTable slowMoving(Result<CountSummary, SlowMovingRow> r) {
        item("أصناف راكدة", r.summary().count(), INTEGER);
        if (cost) {
            item("قيمة الصفحة المعروضة", sum(r.rows().stream().map(SlowMovingRow::value).toList()), MONEY);
        }
        cols("الكود", TEXT, "الصنف", TEXT, "الوحدة", TEXT, "الكمية", QUANTITY, "آخر بيع", DATE, "أيام منذ آخر بيع", TEXT);
        if (cost) {
            cols("قيمة المخزون", MONEY);
        }
        for (SlowMovingRow x : r.rows()) {
            List<Object> values = new ArrayList<>(Arrays.asList(x.productCode(), x.name(), x.unit(), x.quantity(),
                    x.lastSale(), x.daysSinceLastSale() == null ? "لم يُبع مطلقًا" : String.valueOf(x.daysSinceLastSale())));
            if (cost) {
                values.add(x.value());
            }
            rows.add(values);
        }
        notes.add("أصناف نشطة رصيدها أكبر من صفر ولم تُبع خلال آخر " + filter.getDays() + " يومًا (أو لم تُبع مطلقًا).");
        return done(r);
    }

    // ---------- customers & suppliers ----------

    ReportTable balances(Result<PartyBalanceSummary, PartyBalanceRow> r, boolean customers) {
        item(customers ? "إجمالي ديون العملاء" : "إجمالي مستحقات الموردين", r.summary().total(), MONEY);
        item(customers ? "عدد العملاء" : "عدد الموردين", r.summary().count(), INTEGER);
        cols("الكود", TEXT, "الاسم", TEXT, "الهاتف", TEXT, "المنطقة", TEXT, "الرصيد", MONEY,
                customers ? "آخر فاتورة بيع" : "آخر فاتورة شراء", DATETIME, "آخر دفعة", DATETIME);
        for (PartyBalanceRow x : r.rows()) {
            row(x.code(), x.name(), x.phone(), x.area(), x.balance(), x.lastDocument(), x.lastPayment());
        }
        notes.add("الأرصدة من دفتر الحسابات (Account_Ledger): " + (customers
                ? "مدين − دائن، والمعروض فقط من عليهم رصيد مستحق."
                : "دائن − مدين، والمعروض فقط من لهم رصيد مستحق."));
        return done(r);
    }

    ReportTable statement(AccountStatement st) {
        boolean customer = st.partyType() == PartyType.CUSTOMER;
        item(customer ? "العميل" : "المورد", st.partyName() + " (" + st.partyCode() + ")", TEXT);
        item("رصيد أول المدة", st.openingBalance(), MONEY);
        item("إجمالي المدين", st.totalDebit(), MONEY);
        item("إجمالي الدائن", st.totalCredit(), MONEY);
        item("رصيد آخر المدة", st.closingBalance(), MONEY);
        cols("التاريخ", DATETIME, "النوع", TEXT, "المرجع", TEXT, "البيان", TEXT, "مدين", MONEY, "دائن", MONEY,
                "الرصيد", MONEY);
        row(null, "رصيد أول المدة", null, null, null, null, st.openingBalance());
        for (LedgerEntry e : st.entries()) {
            row(e.getEntryDate(), e.getEntryType().getLabelAr(), e.getReferenceNo(), e.getDescription(), e.getDebit(),
                    e.getCredit(), e.getRunningBalance());
        }
        notes.add(customer
                ? "الرصيد الموجب = مستحق على العميل. عروض الأسعار لا تدخل في كشف الحساب."
                : "الرصيد الموجب = مستحق للمورد.");
        return done(rows.size(), 0, Math.max(1, rows.size()));
    }

    // ---------- quotations & audit ----------

    ReportTable quotations(Result<QuotationSummary, QuotationRow> r) {
        QuotationSummary s = r.summary();
        item("إجمالي العروض", s.total(), INTEGER);
        item("مسودة", s.draft(), INTEGER);
        item("مُرسل", s.sent(), INTEGER);
        item("مقبول", s.accepted(), INTEGER);
        item("مرفوض", s.rejected(), INTEGER);
        item("منتهي الصلاحية", s.expired(), INTEGER);
        item("محوّل لفاتورة", s.converted(), INTEGER);
        item("نسبة التحويل %", s.conversionRate(), PERCENT);
        item("قيمة العروض", s.totalValue(), MONEY);
        item("قيمة المحوّل", s.convertedValue(), MONEY);
        cols("رقم العرض", TEXT, "التاريخ", DATETIME, "العميل", TEXT, "الحالة", TEXT, "الإجمالي", MONEY, "صالح حتى", DATE,
                "الفاتورة", TEXT, "أنشأه", TEXT);
        for (QuotationRow x : r.rows()) {
            row(x.quotationNo(), x.date(), x.customer(), label(x.status()), x.total(), x.validUntil(), x.saleNo(),
                    x.user());
        }
        notes.add("نسبة التحويل = المحوّل ÷ العروض المؤهلة × 100، والمؤهلة = كل العروض عدا المسودات (التي لم تُعرض على العميل).");
        notes.add("العرض المفتوح الذي انتهت صلاحيته يظهر \"منتهي الصلاحية\". قيم العروض لا تدخل في إيرادات المبيعات.");
        return done(r);
    }

    ReportTable audit(Result<CountSummary, AuditRow> r) {
        item("عدد العمليات", r.summary().count(), INTEGER);
        cols("التاريخ والوقت", DATETIME, "المستخدم", TEXT, "الإجراء", TEXT, "الجدول", TEXT, "المرجع", TEXT,
                "التفاصيل", TEXT);
        for (AuditRow x : r.rows()) {
            row(x.time(), x.user(), x.action(), x.entity(), x.reference(), x.details());
        }
        return done(r);
    }

    LocalDate today() {
        return today;
    }
}
