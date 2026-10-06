package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.Customer;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.Permission;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationFilter;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.SaleType;
import com.almahwar.service.QuotationService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.controller.PurchasesController.nullable;

/** Quotations module: filterable list; new quotation, details, print preview and the converted sale open in place. */
public class QuotationsController {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d/M/yyyy");

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Button newButton;
    @FXML private Label listMessage;
    @FXML private TextField searchField;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private ComboBox<Customer> customerFilter;
    @FXML private ComboBox<QuotationStatus> statusFilter;
    @FXML private ComboBox<SaleType> priceTypeFilter;
    @FXML private TableView<Quotation> quotationsTable;
    @FXML private TableColumn<Quotation, String> noColumn;
    @FXML private TableColumn<Quotation, String> dateColumn;
    @FXML private TableColumn<Quotation, String> customerColumn;
    @FXML private TableColumn<Quotation, String> phoneColumn;
    @FXML private TableColumn<Quotation, String> typeColumn;
    @FXML private TableColumn<Quotation, String> totalColumn;
    @FXML private TableColumn<Quotation, String> validColumn;
    @FXML private TableColumn<Quotation, Quotation> statusColumn;
    @FXML private TableColumn<Quotation, String> saleColumn;
    @FXML private TableColumn<Quotation, String> userColumn;
    @FXML private Label countLabel;
    @FXML private Button viewButton;
    @FXML private Button printButton;

    private final SecurityContext security = AppContext.get().security();
    private final QuotationService quotations = AppContext.get().quotations();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));
    private final QuotationPages pages = new QuotationPages(this::replacePage, this::closeQuotation);
    private boolean loadingFilters;

    @FXML
    private void initialize() {
        ViewSupport.show(newButton, security.hasPermission(Permission.QUOTATIONS_CREATE));
        setUpFilters();
        setUpTable();
        refresh(null);
    }

    // ---------- Filters ----------

    private void setUpFilters() {
        customerFilter.setConverter(ViewSupport.converter(c -> c.getCustomerId() == null ? "كل العملاء" : c.getName()));
        Customer all = new Customer();
        customerFilter.getItems().setAll(all);
        customerFilter.setValue(all);
        if (security.hasPermission(Permission.CUSTOMERS_VIEW)) {
            Async.run(() -> AppContext.get().customers().search(PartyFilter.all()), list -> {
                loadingFilters = true;
                List<Customer> items = new ArrayList<>();
                items.add(all);
                items.addAll(list);
                customerFilter.getItems().setAll(items);
                customerFilter.setValue(all);
                loadingFilters = false;
            }, e -> { });
        }
        nullable(statusFilter, QuotationStatus.values(), QuotationStatus::getLabelAr, "كل الحالات");
        nullable(priceTypeFilter, SaleType.values(), SaleType::getLabelAr, "كل الأنواع");

        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh(null));
        for (ComboBox<?> c : List.of(customerFilter, statusFilter, priceTypeFilter)) {
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
        statusFilter.getSelectionModel().selectFirst();
        priceTypeFilter.getSelectionModel().selectFirst();
        loadingFilters = false;
        refresh(null);
    }

    // ---------- Table ----------

    private void setUpTable() {
        quotationsTable.setPlaceholder(new Label("لا توجد عروض أسعار مطابقة"));
        quotationsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        noColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getQuotationNo()));
        dateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getQuotationDate() == null ? ""
                : c.getValue().getQuotationDate().format(WHEN)));
        customerColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getDisplayName()));
        phoneColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(PhoneNumbers.format(c.getValue().getDisplayPhone())));
        typeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getPriceType().getLabelAr()));
        totalColumn.setCellValueFactory(c -> PurchasesController.money(c.getValue().getTotalAmount()));
        totalColumn.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        validColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getValidUntil() == null ? ""
                : c.getValue().getValidUntil().format(DAY)));
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Quotation q, boolean empty) {
                super.updateItem(q, empty);
                setText(null);
                setGraphic(empty || q == null ? null : statusBadge(q.getStatus()));
            }
        });
        saleColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getConvertedSaleNo()));
        userColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUserName()));
        noColumn.setMinWidth(100);
        dateColumn.setMinWidth(150);
        customerColumn.setMinWidth(140);

        quotationsTable.setRowFactory(tv -> {
            TableRow<Quotation> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    pages.showDetails(row.getItem().getQuotationId(), null);
                }
            });
            return row;
        });
        quotationsTable.getSelectionModel().selectedItemProperty().addListener((o, a, q) -> {
            viewButton.setDisable(q == null);
            printButton.setDisable(q == null);
        });
        viewButton.setDisable(true);
        printButton.setDisable(true);
    }

    static Label statusBadge(QuotationStatus s) {
        return ViewSupport.badge(s.getLabelAr(), switch (s) {
            case DRAFT -> "badge-muted";
            case SENT -> "badge-info";
            case ACCEPTED -> "badge-success";
            case REJECTED -> "badge-danger";
            case EXPIRED -> "badge-warning";
            case CONVERTED -> "badge-info";
        });
    }

    private void refresh(Integer selectId) {
        Customer customer = customerFilter.getValue();
        QuotationFilter filter = new QuotationFilter(searchField.getText(), fromDate.getValue(), toDate.getValue(),
                statusFilter.getValue(), customer == null ? null : customer.getCustomerId(), priceTypeFilter.getValue());
        Async.run(() -> quotations.search(filter), rows -> {
            quotationsTable.getItems().setAll(rows);
            BigDecimal open = rows.stream()
                    .filter(q -> q.getStatus() == QuotationStatus.SENT || q.getStatus() == QuotationStatus.ACCEPTED)
                    .map(Quotation::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            countLabel.setText(rows.size() + " عرض سعر  •  إجمالي المرسلة والمقبولة: " + MoneyUtil.formatWithCurrency(open)
                    + (rows.size() >= QuotationService.MAX_LIST_ROWS ? "  •  استخدم الفلاتر لعرض عروض أقدم" : ""));
            if (selectId != null) {
                rows.stream().filter(q -> selectId.equals(q.getQuotationId())).findFirst().ifPresent(q -> {
                    quotationsTable.getSelectionModel().select(q);
                    quotationsTable.scrollTo(q);
                });
            }
        }, error -> ErrorMessages.show("تحميل عروض الأسعار", error));
    }

    // ---------- Actions ----------

    @FXML
    private void onNew() {
        pages.showForm(null, null);
    }

    @FXML
    private void onView() {
        Quotation q = quotationsTable.getSelectionModel().getSelectedItem();
        if (q != null) {
            pages.showDetails(q.getQuotationId(), null);
        }
    }

    @FXML
    private void onPrint() {
        Quotation q = quotationsTable.getSelectionModel().getSelectedItem();
        if (q != null) {
            pages.showPrint(q.getQuotationId());
        }
    }

    // ---------- Pages ----------

    private void closeQuotation(String message) {
        Integer selected = quotationsTable.getSelectionModel().getSelectedItem() == null ? null
                : quotationsTable.getSelectionModel().getSelectedItem().getQuotationId();
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(true);
        listMessage.setText(message == null ? "" : message);
        listMessage.getStyleClass().setAll("label", "form-alert", "form-alert-info");
        ViewSupport.show(listMessage, message != null);
        refresh(selected);
    }

    private void replacePage(Parent page) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(false);
        pageHost.getChildren().add(page);
    }
}
