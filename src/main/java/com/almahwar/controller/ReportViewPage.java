package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ProductPicker;
import com.almahwar.model.CashSource;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.MovementType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.ReportPeriod;
import com.almahwar.model.ReportPeriod.DateRange;
import com.almahwar.model.ReportTable;
import com.almahwar.model.ReportTable.Kind;
import com.almahwar.model.ReportType;
import com.almahwar.model.ReportType.Field;
import com.almahwar.model.Reports.Lookup;
import com.almahwar.service.ReportService;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.controller.PurchasesController.nullable;

/**
 * One report: its filters (only those the report supports), Apply / Reset, summary cards, the table (paged), print
 * preview and Excel export. Built in code from {@link ReportType} and the {@link ReportTable} returned by the
 * service, so every report looks and behaves the same. No figure is computed here.
 */
final class ReportViewPage {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d/M/yyyy");

    private final ReportsController host;
    private final ReportType type;
    private final ReportService reports = AppContext.get().reports();

    private final VBox root = new VBox(14);
    private final Label message = new Label();
    private final FlowPane summaryBox = new FlowPane(12, 12);
    private final TableView<List<Object>> table = new TableView<>();
    private final VBox notesBox = new VBox(4);
    private final Label pageLabel = new Label();
    private final Button prevButton = new Button("السابق", com.almahwar.controller.support.Icon.of("previous"));
    private final Button nextButton = new Button("التالي", com.almahwar.controller.support.Icon.of("next"));
    private final Button printButton = new Button("معاينة وطباعة", com.almahwar.controller.support.Icon.of("print"));
    private final Button exportButton = new Button("تصدير Excel", com.almahwar.controller.support.Icon.of("export"));
    private final Button applyButton = new Button("تطبيق");

    // filters
    private final ComboBox<ReportPeriod> periodCombo = new ComboBox<>();
    private final DatePicker fromPicker = new DatePicker();
    private final DatePicker toPicker = new DatePicker();
    private final TextField searchField = new TextField();
    private final ComboBox<Lookup> customerCombo = new ComboBox<>();
    private final ComboBox<Lookup> supplierCombo = new ComboBox<>();
    private final ComboBox<Lookup> userCombo = new ComboBox<>();
    private final ComboBox<Lookup> categoryCombo = new ComboBox<>();
    private final ComboBox<Lookup> brandCombo = new ComboBox<>();
    private final TextField productField = new TextField();
    private Integer productId;
    private final ComboBox<ExpenseCategory> expenseCombo = new ComboBox<>();
    private final ComboBox<String> directionCombo = new ComboBox<>();
    private final ComboBox<CashSource> sourceCombo = new ComboBox<>();
    private final ComboBox<PaymentMethod> methodCombo = new ComboBox<>();
    private final ComboBox<MovementType> movementCombo = new ComboBox<>();
    private final ComboBox<QuotationStatus> statusCombo = new ComboBox<>();
    private final ComboBox<String> actionCombo = new ComboBox<>();
    private final ComboBox<Integer> topCombo = new ComboBox<>();
    private final ComboBox<Integer> daysCombo = new ComboBox<>();
    private final ComboBox<String> activeCombo = new ComboBox<>();

    private LocalDate today = LocalDate.now();
    private boolean settingDates;
    private ReportFilter current;
    private ReportTable shown;
    private boolean busy;
    private ScrollPane page;

    private ReportViewPage(ReportsController host, ReportType type) {
        this.host = host;
        this.type = type;
    }

    static Parent create(ReportsController host, ReportType type) {
        return new ReportViewPage(host, type).build();
    }

    // ======================= Layout =======================

    private Parent build() {
        Button back = new Button("التقارير", com.almahwar.controller.support.Icon.of("back"));
        back.getStyleClass().addAll("btn", "btn-secondary", "btn-back");
        back.setOnAction(e -> host.closeReport());
        Label title = new Label(type.getTitle());
        title.getStyleClass().add("page-title");
        title.setId("reportTitle");
        Label description = new Label(type.getDescription());
        description.getStyleClass().add("page-subtitle");
        VBox titles = new VBox(4, title, description);
        HBox.setHgrow(titles, Priority.ALWAYS);
        printButton.getStyleClass().addAll("btn", "btn-secondary");
        printButton.setId("printButton");
        printButton.setOnAction(e -> print());
        exportButton.getStyleClass().addAll("btn", "btn-secondary");
        exportButton.setId("exportButton");
        exportButton.setOnAction(e -> chooseExportFile());
        ViewSupport.show(exportButton, reports.canExport());
        HBox toolbar = new HBox(12, back, titles, printButton, exportButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        FlowPane filters = new FlowPane(10, 10);
        filters.getStyleClass().add("filter-bar");
        filters.setAlignment(Pos.CENTER_LEFT);
        buildFilters(filters);
        applyButton.getStyleClass().addAll("btn", "btn-primary");
        applyButton.setId("applyButton");
        applyButton.setOnAction(e -> apply());
        applyButton.setDefaultButton(true);
        Button reset = new Button("إعادة ضبط");
        reset.getStyleClass().addAll("btn", "btn-secondary");
        reset.setId("resetButton");
        reset.setOnAction(e -> reset());
        filters.getChildren().addAll(applyButton, reset);

        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        message.setId("reportMessage");
        ViewSupport.show(message, false);

        summaryBox.setId("summaryBox");
        table.setId("reportTable");
        table.setPlaceholder(new Label("لا توجد بيانات مطابقة"));
        table.setPrefHeight(460);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        prevButton.getStyleClass().addAll("btn", "btn-secondary");
        nextButton.getStyleClass().addAll("btn", "btn-secondary");
        prevButton.setOnAction(e -> page(-1));
        nextButton.setOnAction(e -> page(1));
        pageLabel.setId("pageLabel");
        pageLabel.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox paging = new HBox(10, pageLabel, spacer, prevButton, nextButton);
        paging.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(12, summaryBox, table, paging, notesBox);
        card.getStyleClass().addAll("card", "module-card");
        VBox.setVgrow(card, Priority.ALWAYS);

        root.getChildren().addAll(toolbar, filters, message, card);
        root.getStyleClass().add("page-content");
        root.setPadding(new Insets(0));
        root.getProperties().put("reportType", type);
        // test / automation hooks: the table on screen and a file export without the save dialog
        root.getProperties().put("exportTo", (Consumer<Path>) this::exportTo);
        root.getProperties().put("table", (Supplier<ReportTable>) () -> shown);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        scroll.setId("reportPage");
        scroll.getProperties().putAll(root.getProperties());
        page = scroll;

        Async.run(reports::today, d -> {
            today = d;
            reset();
        }, error -> showMessage(ErrorMessages.of(error), true));
        return scroll;
    }

    private void buildFilters(FlowPane box) {
        if (type.has(Field.DATE)) {
            periodCombo.getItems().setAll(ReportPeriod.values());
            periodCombo.setConverter(ViewSupport.converter(ReportPeriod::getLabelAr));
            periodCombo.setId("periodCombo");
            periodCombo.valueProperty().addListener((o, a, p) -> applyPeriod(p));
            fromPicker.setId("fromDate");
            toPicker.setId("toDate");
            fromPicker.setPrefWidth(135);
            toPicker.setPrefWidth(135);
            fromPicker.valueProperty().addListener((o, a, b) -> datesEdited());
            toPicker.valueProperty().addListener((o, a, b) -> datesEdited());
            box.getChildren().addAll(periodCombo, label("من"), fromPicker, label("إلى"), toPicker);
        }
        if (type.has(Field.SEARCH)) {
            searchField.setPromptText("بحث");
            searchField.setId("searchField");
            searchField.setPrefWidth(200);
            box.getChildren().add(searchField);
        }
        lookup(box, Field.CUSTOMER, customerCombo, "customerCombo",
                type == ReportType.CUSTOMER_STATEMENT ? "اختر العميل" : "كل العملاء", reports::customers);
        lookup(box, Field.SUPPLIER, supplierCombo, "supplierCombo",
                type == ReportType.SUPPLIER_STATEMENT ? "اختر المورد" : "كل الموردين", reports::suppliers);
        lookup(box, Field.USER, userCombo, "userCombo", "كل المستخدمين", reports::users);
        lookup(box, Field.CATEGORY, categoryCombo, "categoryCombo", "كل الأقسام", reports::categories);
        lookup(box, Field.BRAND, brandCombo, "brandCombo", "كل الماركات", reports::brands);
        if (type.has(Field.PRODUCT)) {
            productField.setPromptText("كل الأصناف — اكتب الكود أو الاسم");
            productField.setId("productField");
            productField.setPrefWidth(230);
            new ProductPicker(productField, reports::searchProducts).selectedProperty()
                    .addListener((o, a, p) -> productId = p == null ? null : p.getProductId());
            productField.textProperty().addListener((o, a, b) -> {
                if (b == null || b.isBlank()) {
                    productId = null;
                }
            });
            box.getChildren().add(productField);
        }
        enumCombo(box, Field.EXPENSE_CATEGORY, expenseCombo, ExpenseCategory.values(), ExpenseCategory::getLabelAr, "كل الفئات");
        if (type.has(Field.CASH_DIRECTION)) {
            directionCombo.getItems().setAll("الوارد والصادر", "الوارد فقط", "الصادر فقط");
            directionCombo.setId("directionCombo");
            box.getChildren().add(directionCombo);
        }
        enumCombo(box, Field.CASH_SOURCE, sourceCombo, CashSource.values(), CashSource::getLabelAr, "كل المصادر");
        enumCombo(box, Field.PAYMENT_METHOD, methodCombo, PaymentMethod.values(), PaymentMethod::getLabelAr, "كل طرق الدفع");
        enumCombo(box, Field.MOVEMENT_TYPE, movementCombo, MovementType.values(), MovementType::getLabelAr, "كل الحركات");
        enumCombo(box, Field.QUOTATION_STATUS, statusCombo, QuotationStatus.values(), QuotationStatus::getLabelAr, "كل الحالات");
        if (type.has(Field.ACTION)) {
            actionCombo.setId("actionCombo");
            actionCombo.setPrefWidth(200);
            Async.run(reports::auditActions, list -> {
                List<String> items = new ArrayList<>();
                items.add("كل الإجراءات");
                items.addAll(list);
                actionCombo.getItems().setAll(items);
                actionCombo.getSelectionModel().selectFirst();
            }, e -> { });
            box.getChildren().add(actionCombo);
        }
        if (type.has(Field.TOP_N)) {
            topCombo.getItems().setAll(10, 20, 50, 100);
            topCombo.setConverter(ViewSupport.converter(n -> "أعلى " + n));
            topCombo.setId("topCombo");
            box.getChildren().add(topCombo);
        }
        if (type.has(Field.DAYS)) {
            daysCombo.getItems().setAll(30, 60, 90);
            daysCombo.setConverter(ViewSupport.converter(n -> "لم يُبع منذ " + n + " يومًا"));
            daysCombo.setId("daysCombo");
            box.getChildren().add(daysCombo);
        }
        if (type.has(Field.ACTIVE)) {
            activeCombo.getItems().setAll("كل الأصناف", "النشطة فقط", "غير النشطة فقط");
            activeCombo.setId("activeCombo");
            box.getChildren().add(activeCombo);
        }
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private void lookup(FlowPane box, Field field, ComboBox<Lookup> combo, String id, String allText,
                        Supplier<List<Lookup>> source) {
        if (!type.has(field)) {
            return;
        }
        combo.setId(id);
        combo.setPrefWidth(200);
        Lookup all = new Lookup(0, allText);
        combo.getItems().setAll(all);
        combo.setValue(all);
        Async.run(source, list -> {
            List<Lookup> items = new ArrayList<>();
            items.add(all);
            items.addAll(list);
            Lookup selected = combo.getValue();
            combo.getItems().setAll(items);
            combo.setValue(selected == null ? all : selected);
        }, e -> { });
        box.getChildren().add(combo);
    }

    private <E> void enumCombo(FlowPane box, Field field, ComboBox<E> combo, E[] values,
                               java.util.function.Function<E, String> label, String allText) {
        if (!type.has(field)) {
            return;
        }
        nullable(combo, values, label, allText);
        combo.setId(field.name().toLowerCase().replace('_', '-') + "-combo");
        box.getChildren().add(combo);
    }

    // ======================= Filters =======================

    private void applyPeriod(ReportPeriod p) {
        DateRange r = p == null ? null : p.range(today);
        if (r == null) {
            return;
        }
        settingDates = true;
        fromPicker.setValue(r.from());
        toPicker.setValue(r.to());
        settingDates = false;
    }

    private void datesEdited() {
        if (!settingDates && periodCombo.getValue() != ReportPeriod.CUSTOM) {
            periodCombo.setValue(ReportPeriod.CUSTOM);
        }
    }

    /** Back to the default filters, then reload. */
    private void reset() {
        if (type.has(Field.DATE)) {
            boolean statement = type == ReportType.CUSTOMER_STATEMENT || type == ReportType.SUPPLIER_STATEMENT;
            periodCombo.setValue(null);
            periodCombo.setValue(statement ? ReportPeriod.THIS_YEAR : ReportPeriod.THIS_MONTH);
        }
        searchField.clear();
        productField.clear();
        productId = null;
        for (ComboBox<?> c : List.of(customerCombo, supplierCombo, userCombo, categoryCombo, brandCombo, expenseCombo,
                directionCombo, sourceCombo, methodCombo, movementCombo, statusCombo, actionCombo, activeCombo)) {
            c.getSelectionModel().selectFirst();
        }
        topCombo.setValue(ReportFilter.DEFAULT_TOP_N);
        daysCombo.setValue(ReportFilter.DEFAULT_DAYS);
        boolean needsParty = type == ReportType.CUSTOMER_STATEMENT || type == ReportType.SUPPLIER_STATEMENT;
        if (needsParty) {
            current = null;
            showEmpty(type == ReportType.CUSTOMER_STATEMENT ? "اختر العميل ثم اضغط \"تطبيق\"." : "اختر المورد ثم اضغط \"تطبيق\".");
            return;
        }
        apply();
    }

    private ReportFilter readFilter() {
        ReportFilter f = new ReportFilter();
        if (type.has(Field.DATE)) {
            f.from(fromPicker.getValue()).to(toPicker.getValue());
        }
        if (!searchField.getText().isBlank()) {
            f.search(searchField.getText().trim());
        }
        f.customerId(id(customerCombo)).supplierId(id(supplierCombo)).userId(id(userCombo))
                .categoryId(id(categoryCombo)).brandId(id(brandCombo)).productId(productId)
                .expenseCategory(expenseCombo.getValue()).cashSource(sourceCombo.getValue())
                .paymentMethod(methodCombo.getValue()).movementType(movementCombo.getValue())
                .quotationStatus(statusCombo.getValue());
        int direction = directionCombo.getSelectionModel().getSelectedIndex();
        f.cashIn(direction == 1 ? Boolean.TRUE : direction == 2 ? Boolean.FALSE : null);
        int active = activeCombo.getSelectionModel().getSelectedIndex();
        f.active(active == 1 ? Boolean.TRUE : active == 2 ? Boolean.FALSE : null);
        int action = actionCombo.getSelectionModel().getSelectedIndex();
        f.action(action > 0 ? actionCombo.getValue() : null);
        if (topCombo.getValue() != null) {
            f.topN(topCombo.getValue());
        }
        if (daysCombo.getValue() != null) {
            f.days(daysCombo.getValue());
        }
        return f;
    }

    private static Integer id(ComboBox<Lookup> combo) {
        Lookup l = combo.getValue();
        return l == null || l.id() == 0 ? null : l.id();
    }

    // ======================= Loading =======================

    private void apply() {
        current = readFilter();
        load();
    }

    private void page(int delta) {
        if (current == null || shown == null) {
            return;
        }
        int target = current.getPage() + delta;
        if (target < 0 || target >= shown.pageCount()) {
            return;
        }
        current.page(target);
        load();
    }

    private void load() {
        if (busy || current == null) {
            return;
        }
        busy = true;
        applyButton.setDisable(true);
        ReportFilter f = current.copy();
        Async.run(() -> reports.table(type, f), t -> {
            busy = false;
            applyButton.setDisable(false);
            ViewSupport.show(message, false);
            show(t);
        }, error -> {
            busy = false;
            applyButton.setDisable(false);
            showMessage(ErrorMessages.of(error), true);
        });
    }

    private void show(ReportTable t) {
        shown = t;
        summaryBox.getChildren().clear();
        for (ReportTable.Item item : t.summary()) {
            Label l = new Label(item.label());
            l.getStyleClass().add("kpi-title");
            Label v = new Label(format(item.value(), item.kind()));
            v.getStyleClass().addAll("report-figure", item.kind() == Kind.MONEY ? "money" : "count");
            if (item.value() instanceof BigDecimal bd && bd.signum() < 0) {
                v.getStyleClass().add("negative");
            }
            VBox box = new VBox(3, l, v);
            box.getStyleClass().addAll("card", "report-summary");
            box.setUserData(item.label());
            summaryBox.getChildren().add(box);
        }

        table.getColumns().clear();
        for (int c = 0; c < t.columns().size(); c++) {
            ReportTable.Column col = t.columns().get(c);
            int index = c;
            TableColumn<List<Object>, String> tc = new TableColumn<>(col.header());
            tc.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                    index < cd.getValue().size() ? format(cd.getValue().get(index), col.kind()) : ""));
            tc.setSortable(false);
            tc.setPrefWidth(width(col));
            // dates and amounts are never cut short; text columns may shrink a little
            tc.setMinWidth(col.kind() == Kind.TEXT ? Math.min(90, width(col)) : width(col));
            if (col.kind() == Kind.MONEY || col.kind() == Kind.QUANTITY) {
                tc.setCellFactory(x -> numberCell());
            }
            table.getColumns().add(tc);
        }
        table.getItems().setAll(t.rows());

        boolean paged = t.totalRows() > t.rows().size() || t.page() > 0;
        pageLabel.setText(t.totalRows() + " صف" + (paged ? "  •  صفحة " + (t.page() + 1) + " من " + t.pageCount() : ""));
        prevButton.setDisable(t.page() == 0);
        nextButton.setDisable(t.page() + 1 >= t.pageCount());
        ViewSupport.show(prevButton, paged);
        ViewSupport.show(nextButton, paged);

        notesBox.getChildren().clear();
        for (String note : t.notes()) {
            Label n = new Label("• " + note);
            n.getStyleClass().add("muted");
            n.setWrapText(true);
            notesBox.getChildren().add(n);
        }
    }

    private void showEmpty(String text) {
        shown = null;
        summaryBox.getChildren().clear();
        table.getColumns().clear();
        table.getItems().clear();
        notesBox.getChildren().clear();
        pageLabel.setText("");
        ViewSupport.show(prevButton, false);
        ViewSupport.show(nextButton, false);
        showMessage(text, false);
    }

    private static TableCell<List<Object>, String> numberCell() {
        TableCell<List<Object>, String> cell = ViewSupport.textCell("money-cell");
        cell.setAlignment(Pos.CENTER_LEFT);
        return cell;
    }

    private static double width(ReportTable.Column c) {
        return switch (c.kind()) {
            case DATETIME -> 150;
            case DATE -> 95;
            case MONEY -> 110;
            case QUANTITY, INTEGER, PERCENT -> 90;
            case TEXT -> Math.max(110, Math.min(260, c.header().length() * 14 + 60));
        };
    }

    /** Display text of a raw report value (KWD with 3 decimals, quantities without trailing zeros). */
    static String format(Object value, Kind kind) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal bd) {
            return switch (kind) {
                case QUANTITY -> QuantityUtil.format(bd);
                case PERCENT -> bd.toPlainString() + "%";
                default -> MoneyUtil.format(bd);
            };
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(WHEN);
        }
        if (value instanceof LocalDate d) {
            return d.format(DAY);
        }
        return value.toString();
    }

    private void showMessage(String text, boolean error) {
        message.setText(text);
        message.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(message, text != null);
    }

    // ======================= Print & export =======================

    private void print() {
        if (shown == null) {
            showMessage("طبّق التقرير أولًا.", true);
            return;
        }
        host.replacePage(ReportPrintPage.create(shown, () -> host.replacePage(page)));
    }

    private void chooseExportFile() {
        if (current == null) {
            showMessage("طبّق التقرير أولًا.", true);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("تصدير إلى Excel");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        chooser.setInitialFileName(fileName());
        java.io.File file = chooser.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file != null) {
            exportTo(file.toPath());
        }
    }

    private String fileName() {
        String period = current.getFrom() == null ? today.toString() : current.getFrom() + "_" + current.getTo();
        return type.getTitle().replace(' ', '-') + "_" + period + ".xlsx";
    }

    /** Exports the current filters (all rows, authorised columns) to {@code path}. */
    private void exportTo(Path path) {
        if (current == null) {
            return;
        }
        ReportFilter f = current.copy();
        exportButton.setDisable(true);
        Async.run(() -> {
            try (OutputStream out = Files.newOutputStream(path)) {
                reports.exportXlsx(type, f, out);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, () -> {
            exportButton.setDisable(false);
            showMessage("تم التصدير إلى: " + path, false);
        }, error -> {
            exportButton.setDisable(false);
            try {
                Files.deleteIfExists(path);
            } catch (java.io.IOException ignored) {
                // nothing more to clean up
            }
            showMessage("تعذّر التصدير: " + ErrorMessages.of(error), true);
        });
    }
}
