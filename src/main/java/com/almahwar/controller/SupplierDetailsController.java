package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.Icons;
import com.almahwar.model.Permission;
import com.almahwar.model.Supplier;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.SupplierService;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One supplier: basic data, current balance and the account tabs (statement live from the ledger). */
public class SupplierDetailsController {

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
    @FXML private Label openingLabel;
    @FXML private Label openingCaption;
    @FXML private GridPane infoGrid;
    @FXML private TabPane tabs;
    @FXML private Tab purchasesTab;
    @FXML private Tab paymentsTab;
    @FXML private Tab returnsTab;
    @FXML private Tab statementTab;

    private final SecurityContext security = AppContext.get().security();
    private final SupplierService suppliers = AppContext.get().suppliers();

    private SuppliersController host;
    private int supplierId;
    private Supplier supplier;
    private AccountStatementPane statement;

    public void open(SuppliersController host, int supplierId, String message) {
        this.host = host;
        this.supplierId = supplierId;
        boolean canEdit = security.hasPermission(Permission.SUPPLIERS_EDIT);
        boolean canSeeBalances = security.hasPermission(Permission.SUPPLIER_BALANCE_VIEW);
        ViewSupport.show(editButton, canEdit);
        ViewSupport.show(toggleButton, canEdit);
        ViewSupport.show(figures, canSeeBalances);
        showMessage(message, false);

        if (security.hasPermission(Permission.PURCHASES_VIEW)) {
            purchasesTab.setContent(purchasesPane());
        } else {
            purchasesTab.setContent(ViewSupport.emptyState(Icons.PURCHASE, "المشتريات غير متاحة",
                    "عرض فواتير المشتريات يحتاج صلاحية \"عرض المشتريات\"."));
        }
        boolean canPay = security.hasPermission(Permission.SUPPLIER_PAYMENTS);
        ViewSupport.show(payButton, canPay);
        if (canPay || security.hasPermission(Permission.SUPPLIER_BALANCE_VIEW)) {
            paymentsTab.setContent(PaymentHistoryPane.create(com.almahwar.model.PartyType.SUPPLIER, supplierId, e -> showMessage(e, true)));
        } else {
            paymentsTab.setContent(ViewSupport.emptyState(Icons.PAYMENT, "المدفوعات غير متاحة",
                    "عرض المدفوعات يحتاج صلاحية المدفوعات أو كشوف الحسابات."));
        }
        if (security.hasPermission(Permission.PURCHASE_RETURNS)) {
            returnsTab.setContent(ReturnHistoryPane.create(com.almahwar.model.ReturnKind.PURCHASE, supplierId, e -> showMessage(e, true)));
        } else {
            returnsTab.setContent(ViewSupport.emptyState(Icons.RETURN, "المرتجعات غير متاحة",
                    "عرض مرتجعات المشتريات يحتاج صلاحية \"مرتجعات المشتريات\"."));
        }
        if (canSeeBalances) {
            statement = new AccountStatementPane((from, to, search) -> suppliers.statement(supplierId, from, to, search),
                    "الرصيد الموجب = مبلغ مستحق للمورد علينا، والسالب = رصيد لصالحنا. الدائن يزيد ما علينا والمدين ينقصه.");
            statementTab.setContent(statement);
            tabs.getSelectionModel().select(statementTab);
        } else {
            statementTab.setContent(ViewSupport.emptyState(Icons.STATEMENT, "كشف الحساب غير متاح",
                    "عرض أرصدة الموردين وكشوف حساباتهم يحتاج صلاحية \"أرصدة وكشوف حسابات الموردين\"."));
        }
        load();
    }

    /** The supplier's purchase invoices; double click opens the invoice. */
    private javafx.scene.Node purchasesPane() {
        javafx.scene.control.TableView<com.almahwar.model.Purchase> table = new javafx.scene.control.TableView<>();
        table.getStyleClass().add("supplier-purchases");
        table.setPlaceholder(new Label("لا توجد فواتير مشتريات لهذا المورد بعد"));
        table.setColumnResizePolicy(javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.getColumns().add(col("رقم الفاتورة", 110, com.almahwar.model.Purchase::getPurchaseNo));
        table.getColumns().add(col("فاتورة المورد", 100, com.almahwar.model.Purchase::getSupplierInvoiceNo));
        table.getColumns().add(col("التاريخ", 130, p -> p.getPurchaseDate() == null ? ""
                : p.getPurchaseDate().format(PurchasesController.WHEN)));
        table.getColumns().add(col("الإجمالي", 100, p -> money(p.getTotalAmount())));
        table.getColumns().add(col("المدفوع", 100, p -> money(p.getPaidAmount())));
        table.getColumns().add(col("المتبقي", 100, p -> money(p.getRemainingAmount())));
        javafx.scene.control.TableColumn<com.almahwar.model.Purchase, com.almahwar.model.Purchase> status =
                new javafx.scene.control.TableColumn<>("الحالة");
        status.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue()));
        status.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(com.almahwar.model.Purchase p, boolean empty) {
                super.updateItem(p, empty);
                setText(null);
                setGraphic(empty || p == null ? null : PurchasesController.badges(p));
            }
        });
        status.setPrefWidth(160);
        table.getColumns().add(status);
        PurchasePages pages = host.purchasePagesFor(supplierId);
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<com.almahwar.model.Purchase> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    pages.showPurchaseDetails(row.getItem().getPurchaseId(), null);
                }
            });
            return row;
        });
        Label hint = new Label("انقر مرتين على الفاتورة لعرض تفاصيلها.");
        hint.getStyleClass().add("muted");
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(10, table, hint);
        box.getStyleClass().addAll("card", "module-card");
        Async.run(() -> AppContext.get().purchases().search(com.almahwar.model.PurchaseFilter.forSupplier(supplierId)),
                rows -> table.getItems().setAll(rows), error -> showMessage(ErrorMessages.of(error), true));
        return box;
    }

    private static javafx.scene.control.TableColumn<com.almahwar.model.Purchase, String> col(
            String title, double width, java.util.function.Function<com.almahwar.model.Purchase, String> value) {
        javafx.scene.control.TableColumn<com.almahwar.model.Purchase, String> c = new javafx.scene.control.TableColumn<>(title);
        c.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    private static String money(java.math.BigDecimal amount) {
        return amount == null ? "" : MoneyUtil.format(amount);
    }

    private void load() {
        Async.run(() -> suppliers.findById(supplierId).orElse(null), s -> {
            supplier = s;
            if (s == null) {
                host.showList("المورد غير موجود.", null);
                return;
            }
            show(s);
            if (statement != null) {
                statement.reload();
            }
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void show(Supplier s) {
        nameLabel.setText(s.getName());
        codeLabel.setText("كود المورد: " + s.getSupplierCode()
                + (s.getContactPerson() == null ? "" : "  •  المسؤول: " + s.getContactPerson()));
        statusBadge.setText(s.isActive() ? "نشط" : "معطّل");
        statusBadge.getStyleClass().setAll("label", "badge", s.isActive() ? "badge-success" : "badge-danger");
        toggleButton.setText(s.isActive() ? "تعطيل" : "تفعيل");
        toggleButton.getStyleClass().removeAll("btn-danger", "btn-secondary");
        toggleButton.getStyleClass().add(s.isActive() ? "btn-danger" : "btn-secondary");

        infoGrid.getChildren().clear();
        int row = 0;
        row = info(row, "الهاتف", PhoneNumbers.format(s.getPhone()), "هاتف آخر", PhoneNumbers.format(s.getPhone2()));
        row = info(row, "المنطقة", s.getArea(), "الدولة", s.getCountry());
        row = info(row, "العنوان", s.getAddress(), "البريد الإلكتروني", s.getEmail());
        row = info(row, "تاريخ الإضافة", s.getCreatedAt() == null ? null : s.getCreatedAt().format(STAMP),
                "آخر تعديل", s.getUpdatedAt() == null ? null : s.getUpdatedAt().format(STAMP));
        info(row, "ملاحظات", s.getNotes(), null, null);

        if (s.getBalance() != null) {
            BigDecimal b = s.getBalance();
            balanceLabel.setText(MoneyUtil.formatWithCurrency(b.abs()));
            balanceCaption.setText(b.signum() > 0 ? "مستحق للمورد" : b.signum() < 0 ? "رصيد لصالحنا عند المورد" : "لا يوجد رصيد");
            BigDecimal o = s.getOpeningBalance();
            openingLabel.setText(MoneyUtil.formatWithCurrency(o.abs()));
            openingCaption.setText(o.signum() > 0 ? "دائن - مستحق للمورد" : o.signum() < 0 ? "مدين - لصالحنا" : "بدون رصيد افتتاحي");
        }
    }

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
        host.showList(null, supplierId);
    }

    @FXML
    private void onPay() {
        host.showPaymentForm(supplierId, true);
    }

    @FXML
    private void onEdit() {
        host.showForm(supplierId, true);
    }

    @FXML
    private void onToggleActive() {
        if (supplier == null) {
            return;
        }
        boolean activate = !supplier.isActive();
        if (!activate && !AlertUtil.confirm("تعطيل مورد", "لن يظهر \"" + supplier.getName()
                + "\" في المشتريات الجديدة، ويبقى حسابه وكشف حسابه كما هو.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> suppliers.setActive(supplierId, activate), () -> {
            showMessage(activate ? "تم تفعيل المورد." : "تم تعطيل المورد.", false);
            load();
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void showMessage(String text, boolean error) {
        pageMessage.setText(text == null ? "" : text);
        pageMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(pageMessage, text != null);
    }
}
