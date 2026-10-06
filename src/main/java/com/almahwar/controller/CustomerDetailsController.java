package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.Icons;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.Permission;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationFilter;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleFilter;
import com.almahwar.service.CustomerService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

/**
 * One customer: basic data, balance / credit limit / available credit, and the account tabs.
 * The invoices tab lists the customer's sales; payments and returns show an empty state until those
 * modules exist; the account statement is live from the ledger.
 */
public class CustomerDetailsController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d/M/yyyy  hh:mm a", Locale.forLanguageTag("ar"));

    @FXML private Label nameLabel;
    @FXML private Label statusBadge;
    @FXML private Label codeLabel;
    @FXML private Button toggleButton;
    @FXML private Button editButton;
    @FXML private Button payButton;
    @FXML private Label pageMessage;
    @FXML private HBox figures;
    @FXML private Label balanceLabel;
    @FXML private Label balanceCaption;
    @FXML private Label limitLabel;
    @FXML private Label limitCaption;
    @FXML private VBox availableCard;
    @FXML private Label availableLabel;
    @FXML private Label availableCaption;
    @FXML private GridPane infoGrid;
    @FXML private TabPane tabs;
    @FXML private Tab invoicesTab;
    @FXML private Tab paymentsTab;
    @FXML private Tab returnsTab;
    @FXML private Tab quotationsTab;
    @FXML private Tab statementTab;

    private final SecurityContext security = AppContext.get().security();
    private final CustomerService customers = AppContext.get().customers();

    private CustomersController host;
    private int customerId;
    private Customer customer;
    private AccountStatementPane statement;

    public void open(CustomersController host, int customerId, String message) {
        this.host = host;
        this.customerId = customerId;
        boolean canEdit = security.hasPermission(Permission.CUSTOMERS_EDIT);
        boolean canSeeBalances = security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW);
        ViewSupport.show(editButton, canEdit);
        ViewSupport.show(toggleButton, canEdit);
        ViewSupport.show(figures, canSeeBalances);
        showMessage(message, false);

        if (security.hasPermission(Permission.SALES_VIEW)) {
            invoicesTab.setContent(salesPane());
        } else {
            invoicesTab.setContent(ViewSupport.emptyState(Icons.INVOICE, "الفواتير غير متاحة",
                    "عرض فواتير البيع يحتاج صلاحية \"عرض المبيعات\"."));
        }
        boolean canPay = security.hasPermission(Permission.CUSTOMER_PAYMENTS);
        ViewSupport.show(payButton, canPay);
        if (canPay || security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW)) {
            paymentsTab.setContent(PaymentHistoryPane.create(com.almahwar.model.PartyType.CUSTOMER, customerId, e -> showMessage(e, true)));
        } else {
            paymentsTab.setContent(ViewSupport.emptyState(Icons.PAYMENT, "المدفوعات غير متاحة",
                    "عرض المدفوعات يحتاج صلاحية المدفوعات أو كشوف الحسابات."));
        }
        if (security.hasPermission(Permission.SALE_RETURNS)) {
            returnsTab.setContent(ReturnHistoryPane.create(com.almahwar.model.ReturnKind.SALE, customerId, e -> showMessage(e, true)));
        } else {
            returnsTab.setContent(ViewSupport.emptyState(Icons.RETURN, "المرتجعات غير متاحة",
                    "عرض مرتجعات المبيعات يحتاج صلاحية \"مرتجعات المبيعات\"."));
        }
        if (security.hasPermission(Permission.QUOTATIONS_VIEW)) {
            quotationsTab.setContent(quotationsPane());
        } else {
            quotationsTab.setContent(ViewSupport.emptyState(Icons.INVOICE, "عروض الأسعار غير متاحة",
                    "عرض عروض الأسعار يحتاج صلاحية \"عرض عروض الأسعار\"."));
        }
        if (canSeeBalances) {
            statement = new AccountStatementPane((from, to, search) -> customers.statement(customerId, from, to, search),
                    "الرصيد الموجب = مبلغ مستحق على العميل، والسالب = رصيد لصالح العميل. المدين يزيد ما على العميل والدائن ينقصه.");
            statementTab.setContent(statement);
            tabs.getSelectionModel().select(statementTab);
        } else {
            statementTab.setContent(ViewSupport.emptyState(Icons.STATEMENT, "كشف الحساب غير متاح",
                    "عرض أرصدة العملاء وكشوف حساباتهم يحتاج صلاحية \"أرصدة وكشوف حسابات العملاء\"."));
        }
        load();
    }

    /** The customer's sales invoices; double click opens the invoice. */
    private javafx.scene.Node salesPane() {
        TableView<Sale> table = new TableView<>();
        table.getStyleClass().add("customer-sales");
        table.setPlaceholder(new Label("لا توجد فواتير بيع لهذا العميل بعد"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.getColumns().add(col("رقم الفاتورة", 110, Sale::getSaleNo));
        table.getColumns().add(col("التاريخ", 135, s -> s.getSaleDate() == null ? "" : s.getSaleDate().format(PurchasesController.WHEN)));
        table.getColumns().add(col("الإجمالي", 100, s -> fmt(s.getTotalAmount())));
        table.getColumns().add(col("المدفوع", 100, s -> fmt(s.getPaidAmount())));
        table.getColumns().add(col("المتبقي", 100, s -> fmt(s.getRemainingAmount())));
        TableColumn<Sale, Sale> status = new TableColumn<>("الحالة");
        status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Sale s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                setGraphic(empty || s == null ? null : SalesController.badges(s));
            }
        });
        status.setPrefWidth(160);
        table.getColumns().add(status);
        SalePages pages = host.salePagesFor(customerId);
        table.setRowFactory(tv -> {
            TableRow<Sale> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    pages.showSaleDetails(row.getItem().getSaleId(), null);
                }
            });
            return row;
        });
        Label hint = new Label("انقر مرتين على الفاتورة لعرض تفاصيلها.");
        hint.getStyleClass().add("muted");
        VBox box = new VBox(10, table, hint);
        box.getStyleClass().addAll("card", "module-card");
        Async.run(() -> AppContext.get().sales().search(SaleFilter.forCustomer(customerId)),
                rows -> table.getItems().setAll(rows), error -> showMessage(ErrorMessages.of(error), true));
        return box;
    }

    /** The customer's quotations (information only: they are not part of the account statement). */
    private javafx.scene.Node quotationsPane() {
        QuotationPages pages = host.quotationPagesFor(customerId);
        TableView<Quotation> table = new TableView<>();
        table.getStyleClass().add("customer-quotations");
        table.setPlaceholder(new Label("لا توجد عروض أسعار لهذا العميل"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.getColumns().add(qcol("رقم العرض", 110, Quotation::getQuotationNo));
        table.getColumns().add(qcol("التاريخ", 135, q -> q.getQuotationDate() == null ? ""
                : q.getQuotationDate().format(PurchasesController.WHEN)));
        table.getColumns().add(qcol("الإجمالي", 100, q -> fmt(q.getTotalAmount())));
        table.getColumns().add(qcol("صالح حتى", 100, q -> q.getValidUntil() == null ? ""
                : q.getValidUntil().format(QuotationsController.DAY)));
        TableColumn<Quotation, Quotation> status = new TableColumn<>("الحالة");
        status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Quotation q, boolean empty) {
                super.updateItem(q, empty);
                setText(null);
                setGraphic(empty || q == null ? null : QuotationsController.statusBadge(q.getStatus()));
            }
        });
        status.setPrefWidth(110);
        table.getColumns().add(status);
        table.getColumns().add(qcol("الفاتورة", 110, Quotation::getConvertedSaleNo));
        table.setRowFactory(tv -> {
            TableRow<Quotation> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    pages.showDetails(row.getItem().getQuotationId(), null);
                }
            });
            return row;
        });
        Label hint = new Label("انقر مرتين على العرض لعرض تفاصيله. عروض الأسعار لا تدخل في كشف الحساب.");
        hint.getStyleClass().add("muted");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.HBox bar = new javafx.scene.layout.HBox(10, hint, spacer);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        if (security.hasPermission(Permission.QUOTATIONS_CREATE)) {
            javafx.scene.control.Button add = new javafx.scene.control.Button("عرض سعر جديد لهذا العميل",
                    com.almahwar.controller.support.Icon.of("plus"));
            add.getStyleClass().addAll("btn", "btn-secondary");
            add.setOnAction(e -> pages.showForm(null, customerId));
            bar.getChildren().add(add);
        }
        VBox box = new VBox(10, table, bar);
        box.getStyleClass().addAll("card", "module-card");
        Async.run(() -> AppContext.get().quotations().search(QuotationFilter.forCustomer(customerId)),
                rows -> table.getItems().setAll(rows), error -> showMessage(ErrorMessages.of(error), true));
        return box;
    }

    private static TableColumn<Quotation, String> qcol(String title, double width, Function<Quotation, String> value) {
        TableColumn<Quotation, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        if (title.equals("الإجمالي")) {
            c.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        return c;
    }

    private static TableColumn<Sale, String> col(String title, double width, Function<Sale, String> value) {
        TableColumn<Sale, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        if (title.equals("الإجمالي") || title.equals("المدفوع") || title.equals("المتبقي")) {
            c.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        return c;
    }

    private static String fmt(BigDecimal amount) {
        return amount == null ? "" : MoneyUtil.format(amount);
    }

    private void load() {
        Async.run(() -> new Object[]{
                customers.findById(customerId).orElse(null),
                security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW) ? customers.creditStatus(customerId) : null
        }, data -> {
            customer = (Customer) data[0];
            if (customer == null) {
                host.showList("العميل غير موجود.", null);
                return;
            }
            show(customer, (CreditStatus) data[1]);
            if (statement != null) {
                statement.reload();
            }
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void show(Customer c, CreditStatus credit) {
        nameLabel.setText(c.getName());
        codeLabel.setText("كود العميل: " + c.getCustomerCode() + "  •  " + c.getCustomerType().getLabelAr());
        statusBadge.setText(c.isActive() ? "نشط" : "معطّل");
        statusBadge.getStyleClass().setAll("label", "badge", c.isActive() ? "badge-success" : "badge-danger");
        toggleButton.setText(c.isActive() ? "تعطيل" : "تفعيل");
        toggleButton.getStyleClass().removeAll("btn-danger", "btn-secondary");
        toggleButton.getStyleClass().add(c.isActive() ? "btn-danger" : "btn-secondary");
        toggleButton.setDisable(c.isCashCustomer() && c.isActive());

        infoGrid.getChildren().clear();
        int row = 0;
        row = info(row, "الهاتف", PhoneNumbers.format(c.getPhone()), "هاتف آخر", PhoneNumbers.format(c.getPhone2()));
        row = info(row, "المنطقة", c.getArea(), "البريد الإلكتروني", c.getEmail());
        row = info(row, "العنوان", c.getAddress(), "نوع العميل", c.getCustomerType().getLabelAr());
        row = info(row, "تاريخ الإضافة", c.getCreatedAt() == null ? null : c.getCreatedAt().format(STAMP),
                "آخر تعديل", c.getUpdatedAt() == null ? null : c.getUpdatedAt().format(STAMP));
        info(row, "ملاحظات", c.getNotes(), null, null);

        if (credit != null) {
            BigDecimal b = credit.currentDebt();
            balanceLabel.setText(MoneyUtil.formatWithCurrency(b.abs()));
            balanceCaption.setText(b.signum() > 0 ? "مستحق على العميل" : b.signum() < 0 ? "رصيد لصالح العميل" : "لا يوجد رصيد");
            balanceLabel.getStyleClass().removeAll("negative");
            if (credit.overLimit()) {
                balanceLabel.getStyleClass().add("negative");
            }
            limitLabel.setText(credit.creditLimit().signum() == 0 ? "نقدي فقط" : MoneyUtil.formatWithCurrency(credit.creditLimit()));
            limitCaption.setText(credit.creditLimit().signum() == 0 ? "لا يُسمح بالبيع الآجل" : "أقصى مبلغ آجل مسموح");
            availableLabel.setText(MoneyUtil.formatWithCurrency(credit.availableCredit()));
            availableCaption.setText(credit.overLimit() ? "تجاوز العميل حد الائتمان" : "يمكن البيع له بالآجل حتى هذا المبلغ");
            availableCard.getStyleClass().removeAll("figure-success", "figure-danger");
            availableCard.getStyleClass().add(credit.overLimit() ? "figure-danger" : "figure-success");
        }
    }

    /** Adds one row of two label/value pairs; empty values show "—". */
    private int info(int row, String label1, String value1, String label2, String value2) {
        add(row, 0, label1, value1);
        if (label2 != null) {
            add(row, 2, label2, value2);
        }
        return row + 1;
    }

    private void add(int row, int col, String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("muted");
        Label v = new Label(value == null || value.isBlank() ? "—" : value);
        v.getStyleClass().add("info-value");
        v.setWrapText(true);
        infoGrid.add(l, col, row);
        infoGrid.add(v, col + 1, row);
    }

    @FXML
    private void onBack() {
        host.showList(null, customerId);
    }

    @FXML
    private void onPay() {
        host.showPaymentForm(customerId, true);
    }

    @FXML
    private void onEdit() {
        host.showForm(customerId, true);
    }

    @FXML
    private void onToggleActive() {
        if (customer == null) {
            return;
        }
        boolean activate = !customer.isActive();
        if (!activate && !AlertUtil.confirm("تعطيل عميل", "لن يظهر \"" + customer.getName()
                + "\" في العمليات الجديدة، ويبقى حسابه وكشف حسابه كما هو.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> customers.setActive(customerId, activate), () -> {
            showMessage(activate ? "تم تفعيل العميل." : "تم تعطيل العميل.", false);
            load();
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void showMessage(String text, boolean error) {
        pageMessage.setText(text == null ? "" : text);
        pageMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(pageMessage, text != null);
    }
}
