package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.Permission;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.SaleStatus;
import com.almahwar.service.QuotationService;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.controller.QuotationsController.DAY;

/**
 * One quotation with its lines and totals. The actions shown depend on its status and the user's permissions:
 * edit / send / accept / delete a draft, accept / reject / reopen a sent one, convert an accepted one to a sale.
 */
public class QuotationDetailsController {

    @FXML private Label numberLabel;
    @FXML private HBox badgesBox;
    @FXML private Label customerLabel;
    @FXML private Button convertButton;
    @FXML private Button openSaleButton;
    @FXML private Button sendButton;
    @FXML private Button acceptButton;
    @FXML private Button rejectButton;
    @FXML private Button editButton;
    @FXML private Button reopenButton;
    @FXML private Button printButton;
    @FXML private Button deleteButton;
    @FXML private HBox noteBox;
    @FXML private TextField noteField;
    @FXML private Label pageMessage;
    @FXML private GridPane infoGrid;
    @FXML private TableView<QuotationItem> itemsTable;
    @FXML private TableColumn<QuotationItem, String> lineNoColumn;
    @FXML private TableColumn<QuotationItem, String> codeColumn;
    @FXML private TableColumn<QuotationItem, String> productColumn;
    @FXML private TableColumn<QuotationItem, String> quantityColumn;
    @FXML private TableColumn<QuotationItem, String> unitColumn;
    @FXML private TableColumn<QuotationItem, String> priceColumn;
    @FXML private TableColumn<QuotationItem, String> discountColumn;
    @FXML private TableColumn<QuotationItem, String> lineTotalColumn;
    @FXML private TableColumn<QuotationItem, String> availableColumn;
    @FXML private Label termsLabel;
    @FXML private Label subtotalLabel;
    @FXML private Label discountLabel;
    @FXML private Label totalLabel;

    private final SecurityContext security = AppContext.get().security();
    private final QuotationService quotations = AppContext.get().quotations();

    private QuotationPages host;
    private int quotationId;
    private Quotation quotation;
    /** One id per opening of this page: a repeated "convert" click returns the same sale. */
    private final UUID convertRequest = UUID.randomUUID();
    private boolean busy;

    public void open(QuotationPages host, int quotationId, String message) {
        this.host = host;
        this.quotationId = quotationId;
        showMessage(message, false);
        setUpTable();
        for (Button b : List.of(convertButton, openSaleButton, sendButton, acceptButton, rejectButton, editButton,
                reopenButton, deleteButton)) {
            ViewSupport.show(b, false);
        }
        ViewSupport.show(noteBox, false);
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
        priceColumn.setCellValueFactory(c -> PurchasesController.money(c.getValue().getUnitPrice()));
        discountColumn.setCellValueFactory(c -> PurchasesController.money(c.getValue().getDiscountAmount()));
        lineTotalColumn.setCellValueFactory(c -> PurchasesController.money(c.getValue().getLineTotal()));
        for (TableColumn<QuotationItem, String> col : List.of(priceColumn, discountColumn, lineTotalColumn)) {
            col.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        availableColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getAvailable() == null ? ""
                : QuantityUtil.format(c.getValue().getAvailable())
                + (c.getValue().getAvailable().compareTo(c.getValue().getQuantity()) < 0 ? "  (أقل من المطلوب)" : "")));
        productColumn.setMinWidth(160);
    }

    private void load() {
        Async.run(() -> quotations.findById(quotationId).orElse(null), q -> {
            if (q == null) {
                host.closeQuotation("عرض السعر غير موجود.");
                return;
            }
            quotation = q;
            show(q);
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void show(Quotation q) {
        numberLabel.setText("عرض سعر " + q.getQuotationNo());
        badgesBox.getChildren().setAll(QuotationsController.statusBadge(q.getStatus()));
        customerLabel.setText("العميل: " + q.getDisplayName() + (q.isWalkIn() ? " (غير مسجل)" : " (" + q.getCustomerCode() + ")"));

        QuotationStatus s = q.getStatus();
        boolean draft = s == QuotationStatus.DRAFT;
        boolean sent = s == QuotationStatus.SENT;
        boolean canEdit = security.hasPermission(Permission.QUOTATIONS_EDIT);
        boolean canDecide = security.hasPermission(Permission.QUOTATIONS_ACCEPT);
        SaleStatus saleStatus = q.getConvertedSaleId() == null ? null : q.getConvertedSaleStatus();
        boolean liveSale = saleStatus == SaleStatus.DRAFT || saleStatus == SaleStatus.POSTED;
        ViewSupport.show(editButton, draft && canEdit);
        ViewSupport.show(deleteButton, draft && canEdit);
        ViewSupport.show(sendButton, draft && security.hasPermission(Permission.QUOTATIONS_SEND));
        ViewSupport.show(acceptButton, (draft || sent) && canDecide);
        ViewSupport.show(rejectButton, sent && canDecide);
        ViewSupport.show(noteBox, (draft || sent) && canDecide);
        ViewSupport.show(reopenButton, sent && canEdit);
        ViewSupport.show(convertButton, s == QuotationStatus.ACCEPTED && !liveSale
                && security.hasPermission(Permission.QUOTATIONS_CONVERT) && security.hasPermission(Permission.SALES_CREATE));
        boolean continueDraft = saleStatus == SaleStatus.DRAFT && security.hasPermission(Permission.SALES_CREATE);
        boolean viewSale = liveSale && security.hasPermission(Permission.SALES_VIEW);
        openSaleButton.setText(continueDraft ? "متابعة الفاتورة " + q.getConvertedSaleNo() + " في نقطة البيع"
                : "عرض الفاتورة " + q.getConvertedSaleNo());
        ViewSupport.show(openSaleButton, continueDraft || viewSale);

        infoGrid.getChildren().clear();
        int row = 0;
        row = info(row, "التاريخ", q.getQuotationDate() == null ? null : q.getQuotationDate().format(WHEN),
                "صالح حتى", q.getValidUntil() == null ? null : q.getValidUntil().format(DAY)
                        + (q.isPastValidity(LocalDate.now()) ? "  (منتهي)" : ""));
        row = info(row, "العميل", q.getDisplayName(), "الهاتف", PhoneNumbers.format(q.getDisplayPhone()));
        row = info(row, "نوع التسعير", q.getPriceType().getLabelAr(), "أنشأه", q.getUserName());
        row = info(row, "الحالة", s.getLabelAr(), "أُرسل في", q.getSentAt() == null ? null : q.getSentAt().format(WHEN));
        if (q.getDecidedAt() != null) {
            row = info(row, s == QuotationStatus.REJECTED ? "رفضه" : "قبله", q.getDecidedByName() + " — "
                    + q.getDecidedAt().format(WHEN), "ملاحظة القرار", q.getStatusNote());
        }
        row = info(row, "الفاتورة", q.getConvertedSaleNo() == null ? null : q.getConvertedSaleNo()
                        + (saleStatus == null ? "" : " (" + saleStatus.getLabelAr() + ")"),
                "ملاحظات", q.getNotes());

        itemsTable.getItems().setAll(q.getItems());
        termsLabel.setText(q.getTerms() == null ? "" : "الشروط: " + q.getTerms());
        subtotalLabel.setText(fmt(q.getSubtotal()));
        discountLabel.setText(fmt(q.getDiscountAmount()));
        totalLabel.setText(MoneyUtil.formatWithCurrency(q.getTotalAmount() == null ? BigDecimal.ZERO : q.getTotalAmount()));
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

    // ---------- Actions ----------

    @FXML
    private void onBack() {
        host.closeQuotation(null);
    }

    @FXML
    private void onEdit() {
        host.showForm(quotationId, null);
    }

    @FXML
    private void onPrint() {
        host.showPrint(quotationId);
    }

    @FXML
    private void onSend() {
        change(() -> quotations.send(quotationId), "تم تعليم عرض السعر كمُرسَل للعميل.");
    }

    @FXML
    private void onAccept() {
        change(() -> quotations.accept(quotationId, noteField.getText()),
                "تم قبول عرض السعر. يمكن الآن تحويله إلى فاتورة بيع قبل انتهاء صلاحيته.");
    }

    @FXML
    private void onReject() {
        change(() -> quotations.reject(quotationId, noteField.getText()), "تم رفض عرض السعر.");
    }

    @FXML
    private void onReopen() {
        change(() -> quotations.reopen(quotationId), "أُعيد عرض السعر إلى مسودة؛ عدّله ثم أرسله من جديد.");
    }

    private void change(Supplier<Quotation> action, String done) {
        if (busy) {
            return;
        }
        busy = true;
        Async.run(action::get, q -> {
            busy = false;
            noteField.clear();
            quotation = q;
            show(q);
            showMessage(done, false);
        }, error -> {
            busy = false;
            showMessage(ErrorMessages.of(error), true);
            load();   // the status may have changed meanwhile (e.g. expired)
        });
    }

    @FXML
    private void onDelete() {
        if (quotation == null || quotation.getStatus() != QuotationStatus.DRAFT
                || !AlertUtil.confirm("حذف المسودة", "ستُحذف مسودة عرض السعر " + quotation.getQuotationNo()
                + " نهائيًا.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> quotations.deleteDraft(quotationId),
                () -> host.closeQuotation("تم حذف مسودة عرض السعر " + quotation.getQuotationNo() + "."),
                error -> showMessage(ErrorMessages.of(error), true));
    }

    @FXML
    private void onConvert() {
        if (busy) {
            return;
        }
        busy = true;
        convertButton.setDisable(true);
        Async.run(() -> quotations.convert(quotationId, convertRequest), result -> {
            busy = false;
            convertButton.setDisable(false);
            if (result.alreadyMade()) {
                // a sale was already made from this quotation (e.g. another user): show it instead of a second one
                showMessage(String.join("\n", result.warnings()), false);
                load();
                return;
            }
            String text = "تم إنشاء مسودة الفاتورة " + result.saleNo() + " من عرض السعر " + quotation.getQuotationNo()
                    + ". يصبح العرض \"محوّل\" عند إتمام البيع."
                    + (result.warnings().isEmpty() ? "" : "\n⚠ " + String.join("\n⚠ ", result.warnings()));
            host.showPos(quotationId, result.saleId(), text);
        }, error -> {
            busy = false;
            convertButton.setDisable(false);
            showMessage(ErrorMessages.of(error), true);
            load();
        });
    }

    @FXML
    private void onOpenSale() {
        if (quotation == null || quotation.getConvertedSaleId() == null) {
            return;
        }
        if (quotation.getConvertedSaleStatus() == SaleStatus.DRAFT && security.hasPermission(Permission.SALES_CREATE)) {
            host.showPos(quotationId, quotation.getConvertedSaleId(), null);
        } else {
            host.showSale(quotationId, quotation.getConvertedSaleId());
        }
    }

    private void showMessage(String text, boolean error) {
        pageMessage.setText(text == null ? "" : text);
        pageMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(pageMessage, text != null);
    }
}
