package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.PurchaseStatus;
import com.almahwar.service.PurchaseService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import java.math.BigDecimal;
import java.util.List;

/** One purchase invoice with its lines and totals; drafts can be posted, edited or cancelled here. */
public class PurchaseDetailsController {

    @FXML private Label numberLabel;
    @FXML private HBox badgesBox;
    @FXML private Label supplierLabel;
    @FXML private Button cancelButton;
    @FXML private Button editButton;
    @FXML private Button printButton;
    @FXML private Button returnButton;
    @FXML private Button postButton;
    @FXML private Label pageMessage;
    @FXML private GridPane infoGrid;
    @FXML private TableView<PurchaseItem> itemsTable;
    @FXML private TableColumn<PurchaseItem, String> lineNoColumn;
    @FXML private TableColumn<PurchaseItem, String> codeColumn;
    @FXML private TableColumn<PurchaseItem, String> productColumn;
    @FXML private TableColumn<PurchaseItem, String> quantityColumn;
    @FXML private TableColumn<PurchaseItem, String> unitColumn;
    @FXML private TableColumn<PurchaseItem, String> costColumn;
    @FXML private TableColumn<PurchaseItem, String> discountColumn;
    @FXML private TableColumn<PurchaseItem, String> lineTotalColumn;
    @FXML private HBox totalsBox;
    @FXML private Label subtotalLabel;
    @FXML private Label discountLabel;
    @FXML private Label totalLabel;
    @FXML private Label paidLabel;
    @FXML private Label remainingLabel;

    private final SecurityContext security = AppContext.get().security();
    private final PurchaseService purchases = AppContext.get().purchases();

    private PurchasePages host;
    private int purchaseId;
    private Purchase purchase;
    private boolean busy;

    public void open(PurchasePages host, int purchaseId, String message) {
        this.host = host;
        this.purchaseId = purchaseId;
        showMessage(message, false);
        boolean amounts = security.hasPermission(Permission.PURCHASE_COST_VIEW);
        costColumn.setVisible(amounts);
        discountColumn.setVisible(amounts);
        lineTotalColumn.setVisible(amounts);
        ViewSupport.show(totalsBox, amounts);
        setUpTable();
        load();
    }

    private void setUpTable() {
        itemsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        lineNoColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                String.valueOf(itemsTable.getItems().indexOf(c.getValue()) + 1)));
        codeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getProductCode()));
        productColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getProductName()));
        quantityColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().getQuantity())));
        unitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUnitName()));
        costColumn.setCellValueFactory(c -> PurchasesController.money(c.getValue().getUnitCost()));
        discountColumn.setCellValueFactory(c -> PurchasesController.money(c.getValue().getDiscountAmount()));
        lineTotalColumn.setCellValueFactory(c -> PurchasesController.money(c.getValue().getLineTotal()));
        for (TableColumn<PurchaseItem, String> col : List.of(costColumn, discountColumn, lineTotalColumn)) {
            col.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        productColumn.setMinWidth(160);
    }

    private void load() {
        Async.run(() -> purchases.findById(purchaseId).orElse(null), p -> {
            if (p == null) {
                host.closePurchase("فاتورة المشتريات غير موجودة.");
                return;
            }
            purchase = p;
            show(p);
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void show(Purchase p) {
        numberLabel.setText("فاتورة مشتريات " + p.getPurchaseNo());
        badgesBox.getChildren().setAll(PurchasesController.badges(p).getChildren());
        supplierLabel.setText("المورد: " + p.getSupplierName() + " (" + p.getSupplierCode() + ")");

        boolean draft = p.isDraft();
        boolean canCreate = security.hasPermission(Permission.PURCHASES_CREATE);
        ViewSupport.show(postButton, draft && security.hasPermission(Permission.PURCHASES_POST));
        ViewSupport.show(editButton, draft && canCreate && host.canEditPurchases());
        ViewSupport.show(cancelButton, draft && canCreate);
        ViewSupport.show(returnButton, p.getStatus() == PurchaseStatus.POSTED && host.canCreateReturn());

        infoGrid.getChildren().clear();
        int row = 0;
        PaymentType type = p.getPaymentType();
        row = info(row, "التاريخ", p.getPurchaseDate() == null ? null : p.getPurchaseDate().format(PurchasesController.WHEN),
                "فاتورة المورد", p.getSupplierInvoiceNo());
        row = info(row, "طريقة الدفع", type == null ? null : type.getLabelAr()
                        + (type == PaymentType.PARTIAL ? " (" + p.getPaymentMethod().getLabelAr() + ")" : ""),
                "أنشأها", p.getUserName());
        row = info(row, "الحالة", p.getStatus().getLabelAr(),
                "اعتمدها", p.getPostedAt() == null ? null
                        : p.getPostedByName() + " — " + p.getPostedAt().format(PurchasesController.WHEN));
        info(row, "ملاحظات", p.getNotes(), null, null);

        itemsTable.getItems().setAll(p.getItems());
        subtotalLabel.setText(fmt(p.getSubtotal()));
        discountLabel.setText(fmt(p.getDiscountAmount()));
        totalLabel.setText(MoneyUtil.formatWithCurrency(p.getTotalAmount() == null ? BigDecimal.ZERO : p.getTotalAmount()));
        paidLabel.setText(fmt(p.getPaidAmount()));
        remainingLabel.setText(fmt(p.getRemainingAmount()));
    }

    private static String fmt(BigDecimal amount) {
        return amount == null ? "" : MoneyUtil.format(amount);
    }

    private int info(int row, String l1, String v1, String l2, String v2) {
        add(row, 0, l1, v1);
        if (l2 != null) {
            add(row, 2, l2, v2);
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
        host.closePurchase(null);
    }

    @FXML
    private void onEdit() {
        host.showPurchaseForm(purchaseId);
    }

    @FXML
    private void onReturn() {
        host.showReturnForm(purchaseId);
    }

    @FXML
    private void onPrint() {
        host.showPurchasePrint(purchaseId);
    }

    @FXML
    private void onPost() {
        if (busy || purchase == null) {
            return;
        }
        busy = true;
        postButton.setDisable(true);
        Async.run(() -> purchases.post(purchaseId), p -> {
            busy = false;
            postButton.setDisable(false);
            showMessage("تم اعتماد الفاتورة " + p.getPurchaseNo() + " وتحديث المخزون وحساب المورد"
                    + (p.getPaidAmount() != null && p.getPaidAmount().signum() > 0 ? " والخزنة." : "."), false);
            purchase = p;
            show(p);
        }, error -> {
            busy = false;
            postButton.setDisable(false);
            showMessage(ErrorMessages.of(error), true);
        });
    }

    @FXML
    private void onCancelDraft() {
        if (purchase == null || purchase.getStatus() != PurchaseStatus.DRAFT
                || !AlertUtil.confirm("إلغاء المسودة", "ستُلغى المسودة " + purchase.getPurchaseNo()
                + " وتبقى محفوظة كملغاة.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> purchases.cancelDraft(purchaseId), () -> {
            showMessage("تم إلغاء المسودة.", false);
            load();
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void showMessage(String text, boolean error) {
        pageMessage.setText(text == null ? "" : text);
        pageMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(pageMessage, text != null);
    }
}
