package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.Permission;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.service.CustomerService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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

/**
 * Customers module: searchable list with filters; customer details (with account statement)
 * and the add/edit form open in place of the list.
 */
public class CustomersController {

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Label subtitleLabel;
    @FXML private HBox receivablesChip;
    @FXML private Label receivablesLabel;
    @FXML private Button addButton;
    @FXML private Button payButton;
    @FXML private Label listMessage;

    @FXML private TextField searchField;
    @FXML private ComboBox<ActiveStatus> statusFilter;
    @FXML private CheckBox withBalanceFilter;
    @FXML private CheckBox overLimitFilter;

    @FXML private TableView<Customer> customersTable;
    @FXML private TableColumn<Customer, String> codeColumn;
    @FXML private TableColumn<Customer, String> nameColumn;
    @FXML private TableColumn<Customer, String> typeColumn;
    @FXML private TableColumn<Customer, String> phoneColumn;
    @FXML private TableColumn<Customer, String> areaColumn;
    @FXML private TableColumn<Customer, Customer> balanceColumn;
    @FXML private TableColumn<Customer, String> limitColumn;
    @FXML private TableColumn<Customer, Customer> statusColumn;
    @FXML private Label countLabel;
    @FXML private Button viewButton;
    @FXML private Button editButton;
    @FXML private Button toggleButton;

    private final SecurityContext security = AppContext.get().security();
    private final CustomerService customers = AppContext.get().customers();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    private boolean canEdit;
    private boolean canSeeBalances;

    @FXML
    private void initialize() {
        canEdit = security.hasPermission(Permission.CUSTOMERS_EDIT);
        canSeeBalances = security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW);
        subtitleLabel.setText(canSeeBalances
                ? "بيانات العملاء وأرصدتهم وحدود الائتمان وكشوف الحسابات"
                : "بيانات العملاء وأرقام التواصل");
        ViewSupport.show(addButton, canEdit);
        ViewSupport.show(payButton, security.hasPermission(Permission.CUSTOMER_PAYMENTS));
        ViewSupport.show(editButton, canEdit);
        ViewSupport.show(toggleButton, canEdit);
        ViewSupport.show(receivablesChip, canSeeBalances);
        ViewSupport.show(withBalanceFilter, canSeeBalances);
        ViewSupport.show(overLimitFilter, canSeeBalances);
        balanceColumn.setVisible(canSeeBalances);
        limitColumn.setVisible(canSeeBalances);

        statusFilter.getItems().setAll(ActiveStatus.values());
        statusFilter.setConverter(ViewSupport.converter(ActiveStatus::getLabelAr));
        statusFilter.setValue(ActiveStatus.ALL);
        statusFilter.valueProperty().addListener((o, a, b) -> refresh(null));
        withBalanceFilter.selectedProperty().addListener((o, a, b) -> refresh(null));
        overLimitFilter.selectedProperty().addListener((o, a, b) -> refresh(null));
        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh(null));

        setUpTable();
        refresh(null);
    }

    // ---------- Table ----------

    private void setUpTable() {
        customersTable.setPlaceholder(new Label("لا يوجد عملاء مطابقون"));
        customersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        codeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getCustomerCode()));
        nameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getName()));
        typeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getCustomerType().getLabelAr()));
        phoneColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(PhoneNumbers.format(c.getValue().getPhone())));
        phoneColumn.setCellFactory(col -> ViewSupport.textCell("ltr-cell"));
        areaColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getArea()));
        balanceColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        balanceColumn.setCellFactory(col -> balanceCell(Customer::getBalance));
        limitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getCreditLimit() == null ? ""
                : c.getValue().getCreditLimit().signum() == 0 ? "نقدي فقط" : MoneyUtil.format(c.getValue().getCreditLimit())));
        limitColumn.setCellFactory(col -> ViewSupport.textCell("money-cell"));
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Customer c, boolean empty) {
                super.updateItem(c, empty);
                setText(null);
                if (empty || c == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(4, ProductsController.statusBadge(c.isActive()));
                if (c.getBalance() != null && c.getCreditLimit() != null
                        && CreditStatus.of(c.getCreditLimit(), c.getBalance()).overLimit()) {
                    box.getChildren().add(ViewSupport.badge("تجاوز الحد", "badge-danger"));
                }
                setGraphic(box);
            }
        });
        codeColumn.setMinWidth(80);
        nameColumn.setMinWidth(160);
        statusColumn.setMinWidth(120);

        customersTable.setRowFactory(tv -> {
            TableRow<Customer> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    showDetails(row.getItem().getCustomerId(), null);
                }
            });
            return row;
        });
        customersTable.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> updateButtons(c));
        updateButtons(null);
    }

    /** Positive balance = owed to us (red); negative = the party is in credit (green). */
    static <S> TableCell<S, S> balanceCell(java.util.function.Function<S, BigDecimal> balance) {
        return new TableCell<>() {
            @Override
            protected void updateItem(S item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("balance-due", "balance-credit", "money-cell");
                BigDecimal b = empty || item == null ? null : balance.apply(item);
                if (b == null) {
                    setText(null);
                    return;
                }
                setText(MoneyUtil.format(b));
                getStyleClass().add("money-cell");
                if (b.signum() > 0) {
                    getStyleClass().add("balance-due");
                } else if (b.signum() < 0) {
                    getStyleClass().add("balance-credit");
                }
            }
        };
    }

    private void updateButtons(Customer c) {
        viewButton.setDisable(c == null);
        editButton.setDisable(c == null);
        toggleButton.setDisable(c == null || (c.isCashCustomer() && c.isActive()));
        toggleButton.setText(c != null && !c.isActive() ? "تفعيل" : "تعطيل");
        toggleButton.getStyleClass().removeAll("btn-danger", "btn-secondary");
        toggleButton.getStyleClass().add(c != null && c.isActive() ? "btn-danger" : "btn-secondary");
    }

    private void refresh(Integer selectId) {
        PartyFilter filter = new PartyFilter(searchField.getText(), statusFilter.getValue(),
                withBalanceFilter.isSelected(), overLimitFilter.isSelected());
        Async.run(() -> customers.search(filter), rows -> {
            customersTable.getItems().setAll(rows);
            countLabel.setText(rows.size() + " عميل");
            if (selectId != null) {
                rows.stream().filter(c -> selectId.equals(c.getCustomerId())).findFirst().ifPresent(c -> {
                    customersTable.getSelectionModel().select(c);
                    customersTable.scrollTo(c);
                });
            }
        }, error -> ErrorMessages.show("تحميل العملاء", error));
        if (canSeeBalances) {
            Async.run(() -> customers.search(new PartyFilter(null, ActiveStatus.ALL, true, false)).stream()
                            .map(Customer::getBalance).filter(b -> b.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add),
                    total -> receivablesLabel.setText(MoneyUtil.formatWithCurrency(total)), error -> { });
        }
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        statusFilter.setValue(ActiveStatus.ALL);
        withBalanceFilter.setSelected(false);
        overLimitFilter.setSelected(false);
        refresh(null);
    }

    // ---------- Actions ----------

    @FXML
    private void onAdd() {
        showForm(null, false);
    }

    @FXML
    private void onView() {
        Customer c = customersTable.getSelectionModel().getSelectedItem();
        if (c != null) {
            showDetails(c.getCustomerId(), null);
        }
    }

    @FXML
    private void onEdit() {
        Customer c = customersTable.getSelectionModel().getSelectedItem();
        if (c != null) {
            showForm(c.getCustomerId(), false);
        }
    }

    @FXML
    private void onToggleActive() {
        Customer c = customersTable.getSelectionModel().getSelectedItem();
        if (c == null) {
            return;
        }
        boolean activate = !c.isActive();
        if (!activate && !AlertUtil.confirm("تعطيل عميل", "لن يظهر \"" + c.getName()
                + "\" في العمليات الجديدة، ويبقى حسابه وكشف حسابه كما هو.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> customers.setActive(c.getCustomerId(), activate), () -> {
            showMessage((activate ? "تم تفعيل" : "تم تعطيل") + " العميل \"" + c.getName() + "\".", false);
            refresh(c.getCustomerId());
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    // ---------- Pages (list / details / form share this module's area) ----------

    void showList(String message, Integer selectId) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(true);
        showMessage(message, false);
        refresh(selectId);
    }

    void showDetails(int customerId, String message) {
        Parent page = ViewLoader.<CustomerDetailsController>load("customer-details.fxml",
                c -> c.open(this, customerId, message));
        replacePage(page);
    }

    /** @param customerId {@code null} to add; {@code fromDetails} returns to the details page after saving */
    void showForm(Integer customerId, boolean fromDetails) {
        Parent page = ViewLoader.<CustomerFormController>load("customer-form.fxml",
                c -> c.open(this, customerId, fromDetails));
        replacePage(page);
    }

    /** Quotation pages opened from a customer's quotations tab; "back" returns to that customer. */
    QuotationPages quotationPagesFor(int customerId) {
        return new QuotationPages(this::replacePage, message -> showDetails(customerId, message));
    }

    /** Sale pages opened from a customer's invoices tab; "back" returns to that customer. */
    SalePages salePagesFor(int customerId) {
        CustomersController self = this;
        return new SalePages() {
            @Override
            public void closeSale(String message) {
                self.showDetails(customerId, message);
            }

            @Override
            public void showSaleDetails(int saleId, String message) {
                replacePage(ViewLoader.<SaleDetailsController>load("sale-details.fxml",
                        c -> c.open(this, saleId, message)));
            }

            @Override
            public void showSalePrint(int saleId) {
                replacePage(SalePrintPage.create(this, saleId));
            }

            @Override
            public void showPos(int draftSaleId) {
                throw new UnsupportedOperationException("Held sales are continued from the sales module");
            }
        };
    }

    @FXML
    private void onPay() {
        showPaymentForm(null, false);
    }

    /** The payment form (سند قبض); after saving it opens the customer with the message. */
    void showPaymentForm(Integer customerId, boolean fromDetails) {
        replacePage(ViewLoader.<PartyPaymentController>load("party-payment.fxml", c -> c.open(
                new PartyPaymentController.Host() {
                    @Override
                    public void paymentSaved(int partyId, String message) {
                        showDetails(partyId, message);
                    }

                    @Override
                    public void paymentCancelled() {
                        if (fromDetails && customerId != null) {
                            showDetails(customerId, null);
                        } else {
                            showList(null, null);
                        }
                    }
                }, com.almahwar.model.PartyType.CUSTOMER, customerId)));
    }

    private void replacePage(Parent page) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(false);
        pageHost.getChildren().add(page);
    }

    private void showMessage(String text, boolean error) {
        listMessage.setText(text == null ? "" : text);
        listMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(listMessage, text != null);
    }
}
