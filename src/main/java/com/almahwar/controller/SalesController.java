package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.Customer;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.Permission;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleFilter;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.SaleType;
import com.almahwar.model.User;
import com.almahwar.service.SaleService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.controller.PurchasesController.money;

/** Sales module: filterable list of invoices; details, print preview and the POS open in place. */
public class SalesController implements SalePages {

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Button posButton;
    @FXML private Label listMessage;
    @FXML private TextField searchField;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private ComboBox<Customer> customerFilter;
    @FXML private ComboBox<User> cashierFilter;
    @FXML private ComboBox<SaleType> saleTypeFilter;
    @FXML private ComboBox<PaymentStatus> paymentFilter;
    @FXML private ComboBox<SaleStatus> statusFilter;
    @FXML private TableView<Sale> salesTable;
    @FXML private TableColumn<Sale, String> noColumn;
    @FXML private TableColumn<Sale, String> dateColumn;
    @FXML private TableColumn<Sale, String> customerColumn;
    @FXML private TableColumn<Sale, String> typeColumn;
    @FXML private TableColumn<Sale, String> totalColumn;
    @FXML private TableColumn<Sale, String> paidColumn;
    @FXML private TableColumn<Sale, String> remainingColumn;
    @FXML private TableColumn<Sale, String> profitColumn;
    @FXML private TableColumn<Sale, Sale> statusColumn;
    @FXML private TableColumn<Sale, String> cashierColumn;
    @FXML private Label countLabel;
    @FXML private Button viewButton;
    @FXML private Button printButton;

    private final SecurityContext security = AppContext.get().security();
    private final SaleService sales = AppContext.get().sales();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));
    private boolean loadingFilters;

    @FXML
    private void initialize() {
        ViewSupport.show(posButton, canOpenPos());
        profitColumn.setVisible(security.hasPermission(Permission.SALES_PROFIT_VIEW));
        setUpFilters();
        setUpTable();
        refresh(null);
    }

    // ---------- Filters ----------

    private void setUpFilters() {
        customerFilter.setConverter(ViewSupport.converter(c -> c.getCustomerId() == null ? "كل العملاء" : c.getName()));
        Customer allCustomers = new Customer();
        allCustomers.setCustomerId(null);
        customerFilter.getItems().setAll(allCustomers);
        customerFilter.setValue(allCustomers);
        cashierFilter.setConverter(ViewSupport.converter(u -> u.getUserId() == null ? "كل الكاشيرية" : u.getFullName()));
        User allCashiers = new User();
        cashierFilter.getItems().setAll(allCashiers);
        cashierFilter.setValue(allCashiers);
        Async.run(() -> new Object[]{
                security.hasPermission(Permission.CUSTOMERS_VIEW)
                        ? AppContext.get().customers().search(PartyFilter.all()) : List.<Customer>of(),
                sales.cashiers()
        }, data -> {
            loadingFilters = true;
            @SuppressWarnings("unchecked") List<Customer> customers = (List<Customer>) data[0];
            @SuppressWarnings("unchecked") List<User> cashiers = (List<User>) data[1];
            List<Customer> c = new ArrayList<>(List.of(allCustomers));
            c.addAll(customers);
            customerFilter.getItems().setAll(c);
            customerFilter.setValue(allCustomers);
            List<User> u = new ArrayList<>(List.of(allCashiers));
            u.addAll(cashiers);
            cashierFilter.getItems().setAll(u);
            cashierFilter.setValue(allCashiers);
            loadingFilters = false;
        }, e -> { });
        PurchasesController.nullable(saleTypeFilter, SaleType.values(), SaleType::getLabelAr, "كل الأنواع");
        PurchasesController.nullable(paymentFilter, PaymentStatus.values(), PaymentStatus::getLabelAr, "كل حالات الدفع");
        PurchasesController.nullable(statusFilter, SaleStatus.values(), SaleStatus::getLabelAr, "كل الحالات");

        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh(null));
        for (ComboBox<?> c : List.of(customerFilter, cashierFilter, saleTypeFilter, paymentFilter, statusFilter)) {
            c.valueProperty().addListener((o, a, b) -> {
                if (!loadingFilters) {
                    refresh(null);
                }
            });
        }
        fromDate.valueProperty().addListener((o, a, b) -> refresh(null));
        toDate.valueProperty().addListener((o, a, b) -> refresh(null));
    }

    @FXML
    private void onClearFilters() {
        loadingFilters = true;
        searchField.clear();
        fromDate.setValue(null);
        toDate.setValue(null);
        customerFilter.getSelectionModel().selectFirst();
        cashierFilter.getSelectionModel().selectFirst();
        saleTypeFilter.getSelectionModel().selectFirst();
        paymentFilter.getSelectionModel().selectFirst();
        statusFilter.getSelectionModel().selectFirst();
        loadingFilters = false;
        refresh(null);
    }

    // ---------- Table ----------

    private void setUpTable() {
        salesTable.setPlaceholder(new Label("لا توجد فواتير بيع مطابقة"));
        salesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        noColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getSaleNo()));
        dateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getSaleDate() == null ? ""
                : c.getValue().getSaleDate().format(WHEN)));
        customerColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getCustomerName()));
        typeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getSaleType().getLabelAr()));
        totalColumn.setCellValueFactory(c -> money(c.getValue().getTotalAmount()));
        paidColumn.setCellValueFactory(c -> money(c.getValue().getPaidAmount()));
        remainingColumn.setCellValueFactory(c -> money(c.getValue().getRemainingAmount()));
        profitColumn.setCellValueFactory(c -> money(c.getValue().getGrossProfit()));
        for (TableColumn<Sale, String> col : List.of(totalColumn, paidColumn, remainingColumn, profitColumn)) {
            col.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Sale s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                setGraphic(empty || s == null ? null : badges(s));
            }
        });
        cashierColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUserName()));
        noColumn.setMinWidth(130);
        dateColumn.setMinWidth(150);
        statusColumn.setMinWidth(150);

        salesTable.setRowFactory(tv -> {
            TableRow<Sale> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    showSaleDetails(row.getItem().getSaleId(), null);
                }
            });
            return row;
        });
        salesTable.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> {
            viewButton.setDisable(s == null);
            printButton.setDisable(s == null);
        });
        viewButton.setDisable(true);
        printButton.setDisable(true);
    }

    /** Status badge + payment badge (for posted invoices). */
    static HBox badges(Sale s) {
        HBox box = new HBox(4, statusBadge(s.getStatus()));
        if (s.getStatus() == SaleStatus.POSTED && s.getPaymentStatus() != null) {
            box.getChildren().add(PurchasesController.paymentBadge(s.getPaymentStatus()));
        }
        return box;
    }

    static Label statusBadge(SaleStatus s) {
        return ViewSupport.badge(s.getLabelAr(), switch (s) {
            case DRAFT -> "badge-muted";
            case POSTED -> "badge-info";
            case CANCELLED -> "badge-danger";
        });
    }

    private void refresh(Integer selectId) {
        Customer customer = customerFilter.getValue();
        User cashier = cashierFilter.getValue();
        SaleFilter filter = new SaleFilter(searchField.getText(), fromDate.getValue(), toDate.getValue(),
                customer == null ? null : customer.getCustomerId(), cashier == null ? null : cashier.getUserId(),
                paymentFilter.getValue(), saleTypeFilter.getValue(), statusFilter.getValue());
        Async.run(() -> sales.search(filter), rows -> {
            salesTable.getItems().setAll(rows);
            List<Sale> posted = rows.stream().filter(s -> s.getStatus() == SaleStatus.POSTED).toList();
            BigDecimal total = posted.stream().map(Sale::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            StringBuilder text = new StringBuilder(rows.size() + " فاتورة  •  إجمالي المعتمد: "
                    + MoneyUtil.formatWithCurrency(total));
            if (security.hasPermission(Permission.SALES_PROFIT_VIEW)) {
                BigDecimal profit = posted.stream().map(Sale::getGrossProfit).filter(p -> p != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                text.append("  •  الربح: ").append(MoneyUtil.formatWithCurrency(profit));
            }
            if (rows.size() >= SaleService.MAX_LIST_ROWS) {
                text.append("  •  استخدم الفلاتر لعرض فواتير أقدم");
            }
            countLabel.setText(text.toString());
            if (selectId != null) {
                rows.stream().filter(s -> selectId.equals(s.getSaleId())).findFirst().ifPresent(s -> {
                    salesTable.getSelectionModel().select(s);
                    salesTable.scrollTo(s);
                });
            }
        }, error -> ErrorMessages.show("تحميل المبيعات", error));
    }

    // ---------- Actions ----------

    @FXML
    private void onPos() {
        openPos(null);
    }

    @FXML
    private void onView() {
        Sale s = salesTable.getSelectionModel().getSelectedItem();
        if (s != null) {
            showSaleDetails(s.getSaleId(), null);
        }
    }

    @FXML
    private void onPrint() {
        Sale s = salesTable.getSelectionModel().getSelectedItem();
        if (s != null) {
            showSalePrint(s.getSaleId());
        }
    }

    // ---------- SalePages ----------

    @Override
    public void closeSale(String message) {
        Integer selected = salesTable.getSelectionModel().getSelectedItem() == null ? null
                : salesTable.getSelectionModel().getSelectedItem().getSaleId();
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(true);
        listMessage.setText(message == null ? "" : message);
        listMessage.getStyleClass().setAll("label", "form-alert", "form-alert-info");
        ViewSupport.show(listMessage, message != null);
        refresh(selected);
    }

    @Override
    public void showSaleDetails(int saleId, String message) {
        replacePage(ViewLoader.<SaleDetailsController>load("sale-details.fxml", c -> c.open(this, saleId, message)));
    }

    @Override
    public void showSalePrint(int saleId) {
        replacePage(SalePrintPage.create(this, saleId));
    }

    @Override
    public void showPos(int draftSaleId) {
        openPos(draftSaleId);
    }

    @Override
    public boolean canOpenPos() {
        return security.hasPermission(Permission.SALES_CREATE);
    }

    @Override
    public boolean canCreateReturn() {
        return security.hasPermission(Permission.SALE_RETURNS);
    }

    @Override
    public void showReturnForm(int saleId) {
        replacePage(ViewLoader.<ReturnFormController>load("return-form.fxml", c -> c.open(new ReturnFormController.Host() {
            @Override
            public void returnSaved(com.almahwar.model.ReturnDocument saved, String message) {
                showSaleDetails(saleId, message);
            }

            @Override
            public void returnCancelled() {
                showSaleDetails(saleId, null);
            }
        }, com.almahwar.model.ReturnKind.SALE, saleId)));
    }

    private void openPos(Integer draftSaleId) {
        replacePage(ViewLoader.<PosController>load("pos.fxml", c -> c.open(this, draftSaleId)));
    }

    private void replacePage(Parent page) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(false);
        pageHost.getChildren().add(page);
    }
}
