package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.Permission;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseFilter;
import com.almahwar.model.PurchaseStatus;
import com.almahwar.model.Supplier;
import com.almahwar.service.PurchaseService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.animation.PauseTransition;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Purchases module: filterable list; new purchase, details and print preview open in place. */
public class PurchasesController implements PurchasePages {

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d/M/yyyy  hh:mm a", Locale.forLanguageTag("ar"));

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Button newButton;
    @FXML private Label listMessage;
    @FXML private TextField searchField;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private ComboBox<Supplier> supplierFilter;
    @FXML private ComboBox<PaymentStatus> paymentFilter;
    @FXML private ComboBox<PurchaseStatus> statusFilter;
    @FXML private TableView<Purchase> purchasesTable;
    @FXML private TableColumn<Purchase, String> noColumn;
    @FXML private TableColumn<Purchase, String> supplierInvoiceColumn;
    @FXML private TableColumn<Purchase, String> dateColumn;
    @FXML private TableColumn<Purchase, String> supplierColumn;
    @FXML private TableColumn<Purchase, String> totalColumn;
    @FXML private TableColumn<Purchase, String> paidColumn;
    @FXML private TableColumn<Purchase, String> remainingColumn;
    @FXML private TableColumn<Purchase, Purchase> statusColumn;
    @FXML private TableColumn<Purchase, String> userColumn;
    @FXML private Label countLabel;
    @FXML private Button viewButton;
    @FXML private Button printButton;

    private final SecurityContext security = AppContext.get().security();
    private final PurchaseService purchases = AppContext.get().purchases();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));
    private boolean loadingFilters;

    @FXML
    private void initialize() {
        ViewSupport.show(newButton, security.hasPermission(Permission.PURCHASES_CREATE));
        boolean amounts = security.hasPermission(Permission.PURCHASE_COST_VIEW);
        totalColumn.setVisible(amounts);
        paidColumn.setVisible(amounts);
        remainingColumn.setVisible(amounts);

        setUpFilters();
        setUpTable();
        refresh(null);
    }

    // ---------- Filters ----------

    private void setUpFilters() {
        supplierFilter.setConverter(ViewSupport.converter(s -> s.getSupplierId() == null ? "كل الموردين" : s.getName()));
        Supplier all = new Supplier();
        supplierFilter.getItems().setAll(all);
        supplierFilter.setValue(all);
        if (security.hasPermission(Permission.SUPPLIERS_VIEW)) {
            Async.run(() -> AppContext.get().suppliers().search(PartyFilter.all()), list -> {
                loadingFilters = true;
                List<Supplier> items = new ArrayList<>();
                items.add(all);
                items.addAll(list);
                supplierFilter.getItems().setAll(items);
                supplierFilter.setValue(all);
                loadingFilters = false;
            }, e -> { });
        }
        nullable(paymentFilter, PaymentStatus.values(), PaymentStatus::getLabelAr, "كل حالات الدفع");
        nullable(statusFilter, PurchaseStatus.values(), PurchaseStatus::getLabelAr, "كل الحالات");

        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh(null));
        for (ComboBox<?> c : List.of(supplierFilter, paymentFilter, statusFilter)) {
            c.valueProperty().addListener((o, a, b) -> {
                if (!loadingFilters) {
                    refresh(null);
                }
            });
        }
        fromDate.valueProperty().addListener((o, a, b) -> refresh(null));
        toDate.valueProperty().addListener((o, a, b) -> refresh(null));
    }

    /** A combo with a leading "all" (null) entry. */
    static <T> void nullable(ComboBox<T> combo, T[] values, Function<T, String> label, String allText) {
        List<T> items = new ArrayList<>();
        items.add(null);
        items.addAll(List.of(values));
        combo.getItems().setAll(items);
        combo.setButtonCell(cell(label, allText));
        combo.setCellFactory(lv -> cell(label, allText));
        combo.getSelectionModel().selectFirst();
    }

    private static <T> ListCell<T> cell(Function<T, String> label, String allText) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item == null ? allText : label.apply(item));
            }
        };
    }

    @FXML
    private void onClearFilters() {
        loadingFilters = true;
        searchField.clear();
        fromDate.setValue(null);
        toDate.setValue(null);
        supplierFilter.getSelectionModel().selectFirst();
        paymentFilter.getSelectionModel().selectFirst();
        statusFilter.getSelectionModel().selectFirst();
        loadingFilters = false;
        refresh(null);
    }

    // ---------- Table ----------

    private void setUpTable() {
        purchasesTable.setPlaceholder(new Label("لا توجد فواتير مشتريات مطابقة"));
        purchasesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        noColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getPurchaseNo()));
        supplierInvoiceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getSupplierInvoiceNo()));
        dateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getPurchaseDate() == null ? ""
                : c.getValue().getPurchaseDate().format(WHEN)));
        supplierColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getSupplierName()));
        totalColumn.setCellValueFactory(c -> money(c.getValue().getTotalAmount()));
        paidColumn.setCellValueFactory(c -> money(c.getValue().getPaidAmount()));
        remainingColumn.setCellValueFactory(c -> money(c.getValue().getRemainingAmount()));
        for (TableColumn<Purchase, String> col : List.of(totalColumn, paidColumn, remainingColumn)) {
            col.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Purchase p, boolean empty) {
                super.updateItem(p, empty);
                setText(null);
                setGraphic(empty || p == null ? null : badges(p));
            }
        });
        userColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUserName()));
        noColumn.setMinWidth(100);
        dateColumn.setMinWidth(150);
        statusColumn.setMinWidth(150);

        purchasesTable.setRowFactory(tv -> {
            TableRow<Purchase> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    showPurchaseDetails(row.getItem().getPurchaseId(), null);
                }
            });
            return row;
        });
        purchasesTable.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            viewButton.setDisable(p == null);
            printButton.setDisable(p == null);
        });
        viewButton.setDisable(true);
        printButton.setDisable(true);
    }

    static ReadOnlyStringWrapper money(BigDecimal amount) {
        return new ReadOnlyStringWrapper(amount == null ? "" : MoneyUtil.format(amount));
    }

    /** Status badge + payment badge (for posted invoices). */
    static HBox badges(Purchase p) {
        HBox box = new HBox(4, statusBadge(p.getStatus()));
        if (p.getStatus() == PurchaseStatus.POSTED && p.getPaymentStatus() != null) {
            box.getChildren().add(paymentBadge(p.getPaymentStatus()));
        }
        return box;
    }

    static Label statusBadge(PurchaseStatus s) {
        return ViewSupport.badge(s.getLabelAr(), switch (s) {
            case DRAFT -> "badge-muted";
            case POSTED -> "badge-info";
            case CANCELLED -> "badge-danger";
        });
    }

    static Label paymentBadge(PaymentStatus s) {
        return ViewSupport.badge(s.getLabelAr(), switch (s) {
            case PAID -> "badge-success";
            case PARTIAL -> "badge-warning";
            case UNPAID -> "badge-danger";
        });
    }

    private void refresh(Integer selectId) {
        Supplier supplier = supplierFilter.getValue();
        PurchaseFilter filter = new PurchaseFilter(searchField.getText(), fromDate.getValue(), toDate.getValue(),
                supplier == null ? null : supplier.getSupplierId(), paymentFilter.getValue(), statusFilter.getValue());
        Async.run(() -> purchases.search(filter), rows -> {
            purchasesTable.getItems().setAll(rows);
            BigDecimal total = rows.stream().filter(p -> p.getStatus() == PurchaseStatus.POSTED && p.getTotalAmount() != null)
                    .map(Purchase::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            countLabel.setText(rows.size() + " فاتورة"
                    + (security.hasPermission(Permission.PURCHASE_COST_VIEW)
                    ? "  •  إجمالي المعتمد: " + MoneyUtil.formatWithCurrency(total) : "")
                    + (rows.size() >= PurchaseService.MAX_LIST_ROWS ? "  •  استخدم الفلاتر لعرض فواتير أقدم" : ""));
            if (selectId != null) {
                rows.stream().filter(p -> selectId.equals(p.getPurchaseId())).findFirst().ifPresent(p -> {
                    purchasesTable.getSelectionModel().select(p);
                    purchasesTable.scrollTo(p);
                });
            }
        }, error -> ErrorMessages.show("تحميل المشتريات", error));
    }

    // ---------- Actions ----------

    @FXML
    private void onNew() {
        showPurchaseForm(null);
    }

    @FXML
    private void onView() {
        Purchase p = purchasesTable.getSelectionModel().getSelectedItem();
        if (p != null) {
            showPurchaseDetails(p.getPurchaseId(), null);
        }
    }

    @FXML
    private void onPrint() {
        Purchase p = purchasesTable.getSelectionModel().getSelectedItem();
        if (p != null) {
            showPurchasePrint(p.getPurchaseId());
        }
    }

    // ---------- PurchasePages ----------

    @Override
    public void closePurchase(String message) {
        Integer selected = purchasesTable.getSelectionModel().getSelectedItem() == null ? null
                : purchasesTable.getSelectionModel().getSelectedItem().getPurchaseId();
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(true);
        listMessage.setText(message == null ? "" : message);
        listMessage.getStyleClass().setAll("label", "form-alert", "form-alert-info");
        ViewSupport.show(listMessage, message != null);
        refresh(selected);
    }

    @Override
    public void showPurchaseDetails(int purchaseId, String message) {
        replacePage(ViewLoader.<PurchaseDetailsController>load("purchase-details.fxml",
                c -> c.open(this, purchaseId, message)));
    }

    @Override
    public void showPurchaseForm(Integer purchaseId) {
        replacePage(ViewLoader.<PurchaseFormController>load("purchase-form.fxml", c -> c.open(this, purchaseId)));
    }

    @Override
    public void showPurchasePrint(int purchaseId) {
        replacePage(PurchasePrintPage.create(this, purchaseId));
    }

    @Override
    public boolean canCreateReturn() {
        return security.hasPermission(Permission.PURCHASE_RETURNS);
    }

    @Override
    public void showReturnForm(int purchaseId) {
        replacePage(ViewLoader.<ReturnFormController>load("return-form.fxml", c -> c.open(new ReturnFormController.Host() {
            @Override
            public void returnSaved(com.almahwar.model.ReturnDocument saved, String message) {
                showPurchaseDetails(purchaseId, message);
            }

            @Override
            public void returnCancelled() {
                showPurchaseDetails(purchaseId, null);
            }
        }, com.almahwar.model.ReturnKind.PURCHASE, purchaseId)));
    }

    private void replacePage(Parent page) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(false);
        pageHost.getChildren().add(page);
    }
}
