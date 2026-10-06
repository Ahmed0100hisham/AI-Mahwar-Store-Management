package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.Permission;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.Supplier;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.SupplierService;
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

/** Suppliers module: list with filters; details (with account statement) and the form open in place. */
public class SuppliersController {

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Label subtitleLabel;
    @FXML private HBox payablesChip;
    @FXML private Label payablesLabel;
    @FXML private Button addButton;
    @FXML private Button payButton;
    @FXML private Label listMessage;

    @FXML private TextField searchField;
    @FXML private ComboBox<ActiveStatus> statusFilter;
    @FXML private CheckBox withBalanceFilter;

    @FXML private TableView<Supplier> suppliersTable;
    @FXML private TableColumn<Supplier, String> codeColumn;
    @FXML private TableColumn<Supplier, String> nameColumn;
    @FXML private TableColumn<Supplier, String> contactColumn;
    @FXML private TableColumn<Supplier, String> phoneColumn;
    @FXML private TableColumn<Supplier, String> areaColumn;
    @FXML private TableColumn<Supplier, Supplier> balanceColumn;
    @FXML private TableColumn<Supplier, Supplier> statusColumn;
    @FXML private Label countLabel;
    @FXML private Button viewButton;
    @FXML private Button editButton;
    @FXML private Button toggleButton;

    private final SecurityContext security = AppContext.get().security();
    private final SupplierService suppliers = AppContext.get().suppliers();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    private boolean canSeeBalances;

    @FXML
    private void initialize() {
        boolean canEdit = security.hasPermission(Permission.SUPPLIERS_EDIT);
        canSeeBalances = security.hasPermission(Permission.SUPPLIER_BALANCE_VIEW);
        subtitleLabel.setText(canSeeBalances
                ? "بيانات الموردين وأرصدتهم وكشوف حساباتهم"
                : "بيانات الموردين وأرقام التواصل");
        ViewSupport.show(addButton, canEdit);
        ViewSupport.show(payButton, security.hasPermission(Permission.SUPPLIER_PAYMENTS));
        ViewSupport.show(editButton, canEdit);
        ViewSupport.show(toggleButton, canEdit);
        ViewSupport.show(payablesChip, canSeeBalances);
        ViewSupport.show(withBalanceFilter, canSeeBalances);
        balanceColumn.setVisible(canSeeBalances);

        statusFilter.getItems().setAll(ActiveStatus.values());
        statusFilter.setConverter(ViewSupport.converter(ActiveStatus::getLabelAr));
        statusFilter.setValue(ActiveStatus.ALL);
        statusFilter.valueProperty().addListener((o, a, b) -> refresh(null));
        withBalanceFilter.selectedProperty().addListener((o, a, b) -> refresh(null));
        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh(null));

        setUpTable();
        refresh(null);
    }

    private void setUpTable() {
        suppliersTable.setPlaceholder(new Label("لا يوجد موردون مطابقون"));
        suppliersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        codeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getSupplierCode()));
        nameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getName()));
        contactColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getContactPerson()));
        phoneColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(PhoneNumbers.format(c.getValue().getPhone())));
        phoneColumn.setCellFactory(col -> ViewSupport.textCell("ltr-cell"));
        areaColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(join(c.getValue().getArea(), c.getValue().getCountry())));
        balanceColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        balanceColumn.setCellFactory(col -> CustomersController.balanceCell(Supplier::getBalance));
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Supplier s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                setGraphic(empty || s == null ? null : ProductsController.statusBadge(s.isActive()));
            }
        });
        codeColumn.setMinWidth(80);
        nameColumn.setMinWidth(160);

        suppliersTable.setRowFactory(tv -> {
            TableRow<Supplier> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    showDetails(row.getItem().getSupplierId(), null);
                }
            });
            return row;
        });
        suppliersTable.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> updateButtons(s));
        updateButtons(null);
    }

    static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b;
        }
        return b == null || b.isBlank() ? a : a + " - " + b;
    }

    private void updateButtons(Supplier s) {
        viewButton.setDisable(s == null);
        editButton.setDisable(s == null);
        toggleButton.setDisable(s == null);
        toggleButton.setText(s != null && !s.isActive() ? "تفعيل" : "تعطيل");
        toggleButton.getStyleClass().removeAll("btn-danger", "btn-secondary");
        toggleButton.getStyleClass().add(s != null && s.isActive() ? "btn-danger" : "btn-secondary");
    }

    private void refresh(Integer selectId) {
        PartyFilter filter = new PartyFilter(searchField.getText(), statusFilter.getValue(),
                withBalanceFilter.isSelected(), false);
        Async.run(() -> suppliers.search(filter), rows -> {
            suppliersTable.getItems().setAll(rows);
            countLabel.setText(rows.size() + " مورد");
            if (selectId != null) {
                rows.stream().filter(s -> selectId.equals(s.getSupplierId())).findFirst().ifPresent(s -> {
                    suppliersTable.getSelectionModel().select(s);
                    suppliersTable.scrollTo(s);
                });
            }
        }, error -> ErrorMessages.show("تحميل الموردين", error));
        if (canSeeBalances) {
            Async.run(() -> suppliers.search(new PartyFilter(null, ActiveStatus.ALL, true, false)).stream()
                            .map(Supplier::getBalance).filter(b -> b.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add),
                    total -> payablesLabel.setText(MoneyUtil.formatWithCurrency(total)), error -> { });
        }
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        statusFilter.setValue(ActiveStatus.ALL);
        withBalanceFilter.setSelected(false);
        refresh(null);
    }

    @FXML
    private void onAdd() {
        showForm(null, false);
    }

    @FXML
    private void onView() {
        Supplier s = suppliersTable.getSelectionModel().getSelectedItem();
        if (s != null) {
            showDetails(s.getSupplierId(), null);
        }
    }

    @FXML
    private void onEdit() {
        Supplier s = suppliersTable.getSelectionModel().getSelectedItem();
        if (s != null) {
            showForm(s.getSupplierId(), false);
        }
    }

    @FXML
    private void onToggleActive() {
        Supplier s = suppliersTable.getSelectionModel().getSelectedItem();
        if (s == null) {
            return;
        }
        boolean activate = !s.isActive();
        if (!activate && !AlertUtil.confirm("تعطيل مورد", "لن يظهر \"" + s.getName()
                + "\" في المشتريات الجديدة، ويبقى حسابه وكشف حسابه كما هو.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> suppliers.setActive(s.getSupplierId(), activate), () -> {
            showMessage((activate ? "تم تفعيل" : "تم تعطيل") + " المورد \"" + s.getName() + "\".", false);
            refresh(s.getSupplierId());
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    void showList(String message, Integer selectId) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(true);
        showMessage(message, false);
        refresh(selectId);
    }

    void showDetails(int supplierId, String message) {
        replacePage(ViewLoader.<SupplierDetailsController>load("supplier-details.fxml",
                c -> c.open(this, supplierId, message)));
    }

    void showForm(Integer supplierId, boolean fromDetails) {
        replacePage(ViewLoader.<SupplierFormController>load("supplier-form.fxml",
                c -> c.open(this, supplierId, fromDetails)));
    }

    /** Purchase pages opened from a supplier's purchases tab; "back" returns to that supplier. */
    PurchasePages purchasePagesFor(int supplierId) {
        SuppliersController self = this;
        return new PurchasePages() {
            @Override
            public void closePurchase(String message) {
                self.showDetails(supplierId, message);
            }

            @Override
            public void showPurchaseDetails(int purchaseId, String message) {
                replacePage(ViewLoader.<PurchaseDetailsController>load("purchase-details.fxml",
                        c -> c.open(this, purchaseId, message)));
            }

            @Override
            public void showPurchaseForm(Integer purchaseId) {
                throw new UnsupportedOperationException("Drafts are edited from the purchases module");
            }

            @Override
            public void showPurchasePrint(int purchaseId) {
                replacePage(PurchasePrintPage.create(this, purchaseId));
            }

            @Override
            public boolean canEditPurchases() {
                return false;
            }
        };
    }

    @FXML
    private void onPay() {
        showPaymentForm(null, false);
    }

    /** The payment form (سند صرف); after saving it opens the supplier with the message. */
    void showPaymentForm(Integer supplierId, boolean fromDetails) {
        replacePage(ViewLoader.<PartyPaymentController>load("party-payment.fxml", c -> c.open(
                new PartyPaymentController.Host() {
                    @Override
                    public void paymentSaved(int partyId, String message) {
                        showDetails(partyId, message);
                    }

                    @Override
                    public void paymentCancelled() {
                        if (fromDetails && supplierId != null) {
                            showDetails(supplierId, null);
                        } else {
                            showList(null, null);
                        }
                    }
                }, com.almahwar.model.PartyType.SUPPLIER, supplierId)));
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
