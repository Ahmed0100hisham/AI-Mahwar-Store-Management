package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleStatus;
import com.almahwar.service.SaleService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
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

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.controller.PurchasesController.money;

/** One sales invoice with its lines and totals; cost and profit for permitted users; drafts can be continued. */
public class SaleDetailsController {

    @FXML private Label numberLabel;
    @FXML private HBox badgesBox;
    @FXML private Label customerLabel;
    @FXML private Button cancelButton;
    @FXML private Button continueButton;
    @FXML private Button printButton;
    @FXML private Button returnButton;
    @FXML private Label pageMessage;
    @FXML private GridPane infoGrid;
    @FXML private TableView<SaleItem> itemsTable;
    @FXML private TableColumn<SaleItem, String> lineNoColumn;
    @FXML private TableColumn<SaleItem, String> codeColumn;
    @FXML private TableColumn<SaleItem, String> productColumn;
    @FXML private TableColumn<SaleItem, String> quantityColumn;
    @FXML private TableColumn<SaleItem, String> unitColumn;
    @FXML private TableColumn<SaleItem, String> priceColumn;
    @FXML private TableColumn<SaleItem, String> discountColumn;
    @FXML private TableColumn<SaleItem, String> lineTotalColumn;
    @FXML private TableColumn<SaleItem, String> costColumn;
    @FXML private TableColumn<SaleItem, String> lineProfitColumn;
    @FXML private Label profitNote;
    @FXML private Label subtotalLabel;
    @FXML private Label discountLabel;
    @FXML private Label totalLabel;
    @FXML private Label paidLabel;
    @FXML private Label remainingLabel;
    @FXML private HBox costRow;
    @FXML private Label costLabel;
    @FXML private HBox profitRow;
    @FXML private Label profitLabel;

    private final SecurityContext security = AppContext.get().security();
    private final SaleService sales = AppContext.get().sales();

    private SalePages host;
    private int saleId;
    private Sale sale;

    public void open(SalePages host, int saleId, String message) {
        this.host = host;
        this.saleId = saleId;
        showMessage(message, false);
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
        priceColumn.setCellValueFactory(c -> money(c.getValue().getUnitPrice()));
        discountColumn.setCellValueFactory(c -> money(c.getValue().getDiscountAmount()));
        lineTotalColumn.setCellValueFactory(c -> money(c.getValue().getLineTotal()));
        costColumn.setCellValueFactory(c -> money(sale != null && sale.isDraft() ? null : c.getValue().getUnitCost()));
        lineProfitColumn.setCellValueFactory(c -> money(sale != null && sale.isDraft() ? null : c.getValue().getLineProfit()));
        for (TableColumn<SaleItem, String> col : List.of(priceColumn, discountColumn, lineTotalColumn, costColumn,
                lineProfitColumn)) {
            col.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        productColumn.setMinWidth(160);
        boolean cost = security.hasPermission(Permission.SALES_COST_VIEW);
        boolean profit = security.hasPermission(Permission.SALES_PROFIT_VIEW);
        costColumn.setVisible(cost);
        lineProfitColumn.setVisible(cost && profit);
        ViewSupport.show(costRow, cost);
        ViewSupport.show(profitRow, profit);
        ViewSupport.show(profitNote, profit);
        profitNote.setText("الربح = إجمالي الفاتورة بعد خصم الأسطر وخصم الفاتورة − تكلفة البضاعة المباعة. "
                + "التكلفة هي تكلفة الصنف لحظة البيع ولا تتغير إذا تغيّر سعر الشراء لاحقًا. "
                + "ربح السطر لا يشمل خصم الفاتورة.");
    }

    private void load() {
        Async.run(() -> sales.findById(saleId).orElse(null), s -> {
            if (s == null) {
                host.closeSale("فاتورة البيع غير موجودة.");
                return;
            }
            sale = s;
            show(s);
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void show(Sale s) {
        numberLabel.setText("فاتورة مبيعات " + s.getSaleNo());
        badgesBox.getChildren().setAll(SalesController.badges(s).getChildren());
        customerLabel.setText("العميل: " + s.getCustomerName() + " (" + s.getCustomerCode() + ")"
                + (s.getCustomerPhone() == null ? "" : "  •  " + PhoneNumbers.format(s.getCustomerPhone())));

        boolean draft = s.isDraft();
        boolean canCreate = security.hasPermission(Permission.SALES_CREATE);
        ViewSupport.show(continueButton, draft && canCreate && host.canOpenPos());
        ViewSupport.show(cancelButton, draft && canCreate);
        ViewSupport.show(returnButton, s.getStatus() == SaleStatus.POSTED && host.canCreateReturn());

        infoGrid.getChildren().clear();
        int row = 0;
        PaymentType type = s.getPaymentType();
        row = info(row, "التاريخ والوقت", s.getSaleDate() == null ? null : s.getSaleDate().format(WHEN),
                "نوع البيع", s.getSaleType().getLabelAr());
        row = info(row, "طريقة الدفع", type == null ? null : type.getLabelAr()
                        + (type == PaymentType.PARTIAL ? " (" + s.getPaymentMethod().getLabelAr() + ")" : ""),
                "الكاشير", s.getUserName());
        row = info(row, "الحالة", s.getStatus().getLabelAr()
                        + (s.getStatus() == SaleStatus.POSTED && s.getPaymentStatus() != null
                        ? " — " + s.getPaymentStatus().getLabelAr() : ""),
                "اعتمدها", s.getPostedAt() == null ? null
                        : s.getPostedByName() + " — " + s.getPostedAt().format(WHEN));
        info(row, "ملاحظات", s.getNotes(), "عرض السعر", s.getQuotationNo());

        itemsTable.getItems().setAll(s.getItems());
        itemsTable.refresh();
        subtotalLabel.setText(fmt(s.getSubtotal()));
        discountLabel.setText(fmt(s.getDiscountAmount()));
        totalLabel.setText(MoneyUtil.formatWithCurrency(s.getTotalAmount()));
        paidLabel.setText(fmt(s.getPaidAmount()));
        remainingLabel.setText(fmt(s.getRemainingAmount()));
        costLabel.setText(draft ? "تُحسب عند الاعتماد" : fmt(s.getCostTotal()));
        profitLabel.setText(draft ? "—" : fmt(s.getGrossProfit()));
        profitLabel.getStyleClass().remove("negative");
        if (s.getGrossProfit() != null && s.getGrossProfit().signum() < 0) {
            profitLabel.getStyleClass().add("negative");
        }
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
        host.closeSale(null);
    }

    @FXML
    private void onReturn() {
        host.showReturnForm(saleId);
    }

    @FXML
    private void onPrint() {
        host.showSalePrint(saleId);
    }

    @FXML
    private void onContinue() {
        host.showPos(saleId);
    }

    @FXML
    private void onCancelDraft() {
        if (sale == null || sale.getStatus() != SaleStatus.DRAFT
                || !AlertUtil.confirm("إلغاء المسودة", "ستُلغى المسودة " + sale.getSaleNo()
                + " وتبقى محفوظة كملغاة.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> sales.cancelDraft(saleId), () -> {
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
