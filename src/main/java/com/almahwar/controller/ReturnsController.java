package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.Permission;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnFilter;
import com.almahwar.model.ReturnKind;
import com.almahwar.service.ReturnService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.List;

import static com.almahwar.controller.PurchasesController.WHEN;

/** Returns module: the sales returns and purchase returns lists; the return form and details open in place. */
public class ReturnsController {

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Button newButton;
    @FXML private Label listMessage;
    @FXML private TabPane tabs;
    @FXML private Tab salesTab;
    @FXML private Tab purchasesTab;
    @FXML private TextField searchField;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private TableView<ReturnDocument> returnsTable;
    @FXML private TableColumn<ReturnDocument, String> noColumn;
    @FXML private TableColumn<ReturnDocument, String> dateColumn;
    @FXML private TableColumn<ReturnDocument, String> originalColumn;
    @FXML private TableColumn<ReturnDocument, String> partyColumn;
    @FXML private TableColumn<ReturnDocument, String> valueColumn;
    @FXML private TableColumn<ReturnDocument, String> refundColumn;
    @FXML private TableColumn<ReturnDocument, String> reasonColumn;
    @FXML private TableColumn<ReturnDocument, String> userColumn;
    @FXML private Label countLabel;
    @FXML private Button viewButton;

    private final SecurityContext security = AppContext.get().security();
    private final ReturnService returns = AppContext.get().returns();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    @FXML
    private void initialize() {
        boolean salesReturns = security.hasPermission(Permission.SALE_RETURNS);
        boolean purchaseReturns = security.hasPermission(Permission.PURCHASE_RETURNS);
        if (!salesReturns) {
            tabs.getTabs().remove(salesTab);
        }
        if (!purchaseReturns) {
            tabs.getTabs().remove(purchasesTab);
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refresh());
        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh());
        fromDate.valueProperty().addListener((o, a, b) -> refresh());
        toDate.valueProperty().addListener((o, a, b) -> refresh());

        returnsTable.setPlaceholder(new Label("لا توجد مرتجعات مطابقة"));
        returnsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        noColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getReturnNo()));
        dateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getReturnDate() == null ? ""
                : c.getValue().getReturnDate().format(WHEN)));
        originalColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getOriginalNo()));
        partyColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getPartyName()));
        valueColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().getTotalAmount())));
        refundColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().getRefundAmount())));
        for (TableColumn<ReturnDocument, String> col : List.of(valueColumn, refundColumn)) {
            col.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        reasonColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getReason().getLabelAr()));
        userColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUserName()));
        noColumn.setMinWidth(105);
        dateColumn.setMinWidth(150);
        originalColumn.setMinWidth(120);
        returnsTable.setRowFactory(tv -> {
            TableRow<ReturnDocument> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    showDetails(row.getItem().getKind(), row.getItem().getReturnId(), null);
                }
            });
            return row;
        });
        returnsTable.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> viewButton.setDisable(r == null));
        viewButton.setDisable(true);
        refresh();
    }

    /** The kind of the selected tab (or {@code null} when the user has neither permission). */
    private ReturnKind kind() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        return t == null ? null : t == salesTab ? ReturnKind.SALE : ReturnKind.PURCHASE;
    }

    private void refresh() {
        ReturnKind kind = kind();
        ViewSupport.show(newButton, kind != null);
        if (kind == null) {
            returnsTable.getItems().clear();
            return;
        }
        newButton.setText(kind == ReturnKind.SALE ? "مرتجع مبيعات" : "مرتجع مشتريات");
        partyColumn.setText(kind == ReturnKind.SALE ? "العميل" : "المورد");
        refundColumn.setText(kind == ReturnKind.SALE ? "المردود للعميل" : "المسترد من المورد");
        ReturnFilter filter = new ReturnFilter(searchField.getText(), fromDate.getValue(), toDate.getValue(), null, null);
        Async.run(() -> returns.search(kind, filter), rows -> {
            if (kind != kind()) {
                return;   // the tab changed meanwhile
            }
            returnsTable.getItems().setAll(rows);
            BigDecimal total = rows.stream().map(ReturnDocument::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal refunded = rows.stream().map(ReturnDocument::getRefundAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            countLabel.setText(rows.size() + " مرتجع  •  القيمة " + MoneyUtil.formatWithCurrency(total) + "  •  نقدًا "
                    + MoneyUtil.format(refunded));
        }, error -> ErrorMessages.show("تحميل المرتجعات", error));
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        fromDate.setValue(null);
        toDate.setValue(null);
        refresh();
    }

    @FXML
    private void onNew() {
        ReturnKind kind = kind();
        if (kind != null) {
            showForm(kind, null);
        }
    }

    @FXML
    private void onView() {
        ReturnDocument r = returnsTable.getSelectionModel().getSelectedItem();
        if (r != null) {
            showDetails(r.getKind(), r.getReturnId(), null);
        }
    }

    // ---------- pages ----------

    void showList(String message) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(true);
        listMessage.setText(message == null ? "" : message);
        listMessage.getStyleClass().setAll("label", "form-alert", "form-alert-info");
        ViewSupport.show(listMessage, message != null);
        refresh();
    }

    void showForm(ReturnKind kind, Integer originalId) {
        replacePage(ViewLoader.<ReturnFormController>load("return-form.fxml", c -> c.open(new ReturnFormController.Host() {
            @Override
            public void returnSaved(ReturnDocument saved, String message) {
                showDetails(saved.getKind(), saved.getReturnId(), message);
            }

            @Override
            public void returnCancelled() {
                showList(null);
            }
        }, kind, originalId)));
    }

    void showDetails(ReturnKind kind, int returnId, String message) {
        replacePage(ReturnDetailsPage.create(kind, returnId, message, () -> showList(null)));
    }

    private void replacePage(Parent page) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(false);
        pageHost.getChildren().add(page);
    }
}
