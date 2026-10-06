package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.controller.support.ProductPicker;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.Supplier;
import com.almahwar.service.PurchaseService;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.almahwar.service.PurchaseService.*;

/**
 * New purchase / edit draft. Lines are edited directly in the table; totals update as you type.
 * "اعتماد" saves and posts in one transaction; "حفظ كمسودة" saves without touching stock or accounts.
 * Each opening of the form has one request id, so a double click or a retry can never save twice.
 */
public class PurchaseFormController {

    /** One editable line: the typed text plus the item it updates. */
    public static final class Line {
        final PurchaseItem item;
        final StringProperty quantity = new SimpleStringProperty();
        final StringProperty cost = new SimpleStringProperty();
        final StringProperty discount = new SimpleStringProperty();
        final ReadOnlyStringWrapper total = new ReadOnlyStringWrapper();
        boolean invalid;

        Line(PurchaseItem item) {
            this.item = item;
            quantity.set(NumberInput.text(item.getQuantity()));
            cost.set(item.getUnitCost() == null ? "" : NumberInput.text(item.getUnitCost()));
            discount.set(item.getDiscountAmount() == null || item.getDiscountAmount().signum() == 0
                    ? "" : NumberInput.text(item.getDiscountAmount()));
        }

        public PurchaseItem getItem() {
            return item;
        }
    }

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label formAlert;
    @FXML private ComboBox<Supplier> supplierCombo;
    @FXML private Label supplierError;
    @FXML private TextField supplierInvoiceField;
    @FXML private Label supplierInvoiceError;
    @FXML private TextField notesField;
    @FXML private Label notesError;
    @FXML private Label itemsCountLabel;
    @FXML private TextField productField;
    @FXML private TableView<Line> itemsTable;
    @FXML private TableColumn<Line, String> lineNoColumn;
    @FXML private TableColumn<Line, Line> productColumn;
    @FXML private TableColumn<Line, String> unitColumn;
    @FXML private TableColumn<Line, Line> quantityColumn;
    @FXML private TableColumn<Line, Line> costColumn;
    @FXML private TableColumn<Line, Line> discountColumn;
    @FXML private TableColumn<Line, String> lineTotalColumn;
    @FXML private TableColumn<Line, Line> removeColumn;
    @FXML private Label itemsError;
    @FXML private ComboBox<PaymentType> paymentTypeCombo;
    @FXML private Label paymentTypeError;
    @FXML private VBox partialMethodBox;
    @FXML private ComboBox<PaymentMethod> partialMethodCombo;
    @FXML private Label partialMethodError;
    @FXML private VBox paidBox;
    @FXML private TextField paidField;
    @FXML private Label paidError;
    @FXML private Label paymentHint;
    @FXML private Label subtotalLabel;
    @FXML private TextField discountField;
    @FXML private Label discountError;
    @FXML private Label totalLabel;
    @FXML private Label paidLabel;
    @FXML private Label remainingLabel;
    @FXML private Button postButton;
    @FXML private Button draftButton;
    @FXML private Label postHint;

    private final SecurityContext security = AppContext.get().security();
    private final PurchaseService purchases = AppContext.get().purchases();
    private final FormErrors errors = new FormErrors();

    private PurchasePages host;
    private Purchase purchase;
    private UUID requestId;
    private boolean saving;

    @FXML
    private void initialize() {
        boolean canPost = security.hasPermission(Permission.PURCHASES_POST);
        ViewSupport.show(postButton, canPost);
        postHint.setText(canPost
                ? "عند الاعتماد: تزيد الكميات في المخزون، ويُسجَّل المبلغ في حساب المورد، ويُسجَّل المدفوع في الخزنة — في عملية واحدة."
                : "ستُحفظ الفاتورة كمسودة، ويعتمدها مستخدم لديه صلاحية الاعتماد.");

        supplierCombo.setConverter(ViewSupport.converter(s -> s.getName() + "  (" + s.getSupplierCode() + ")"));
        paymentTypeCombo.getItems().setAll(PaymentType.values());
        paymentTypeCombo.setConverter(ViewSupport.converter(PaymentType::getLabelAr));
        paymentTypeCombo.setValue(PaymentType.CASH);
        partialMethodCombo.getItems().setAll(PaymentMethod.CASH, PaymentMethod.KNET, PaymentMethod.BANK_TRANSFER,
                PaymentMethod.CHEQUE);
        partialMethodCombo.setConverter(ViewSupport.converter(PaymentMethod::getLabelAr));
        partialMethodCombo.setValue(PaymentMethod.CASH);
        paymentTypeCombo.valueProperty().addListener((o, a, b) -> {
            updatePaymentFields();
            recalculate();
        });
        NumberInput.install(paidField);
        NumberInput.install(discountField);
        paidField.textProperty().addListener((o, a, b) -> recalculate());
        discountField.textProperty().addListener((o, a, b) -> recalculate());
        supplierInvoiceField.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);

        errors.register(SUPPLIER, supplierCombo, supplierError)
                .register(SUPPLIER_INVOICE_NO, supplierInvoiceField, supplierInvoiceError)
                .register(NOTES, notesField, notesError)
                .register(ITEMS, itemsTable, itemsError)
                .register(DISCOUNT, discountField, discountError)
                .register(PAYMENT_TYPE, paymentTypeCombo, paymentTypeError)
                .register(PAYMENT_METHOD, partialMethodCombo, partialMethodError)
                .register(PAID, paidField, paidError);

        setUpTable();
        new ProductPicker(productField, text -> AppContext.get().products()
                .search(ProductFilter.search(text).withStatus(ActiveStatus.ACTIVE)))
                .selectedProperty().addListener((o, a, product) -> {
                    if (product != null) {
                        addProduct(product);
                        javafx.application.Platform.runLater(() -> {
                            productField.clear();
                            productField.requestFocus();
                        });
                    }
                });
        updatePaymentFields();
    }

    /** @param purchaseId {@code null} for a new purchase, else a DRAFT to edit */
    public void open(PurchasePages host, Integer purchaseId) {
        this.host = host;
        boolean adding = purchaseId == null;
        requestId = adding ? UUID.randomUUID() : null;
        titleLabel.setText(adding ? "فاتورة شراء جديدة" : "تعديل مسودة مشتريات");

        Async.run(() -> new Object[]{
                AppContext.get().suppliers().search(new PartyFilter(null, ActiveStatus.ACTIVE, false, false)),
                adding ? purchases.suggestNumber() : null,
                adding ? null : purchases.findById(purchaseId).orElse(null)
        }, data -> {
            @SuppressWarnings("unchecked") List<Supplier> suppliers = (List<Supplier>) data[0];
            supplierCombo.getItems().setAll(suppliers);
            purchase = adding ? new Purchase() : (Purchase) data[2];
            if (purchase == null || (!adding && !purchase.isDraft())) {
                host.closePurchase("لا يمكن تعديل هذه الفاتورة؛ الفواتير المعتمدة لا تُعدّل.");
                return;
            }
            subtitleLabel.setText(adding
                    ? "الرقم المتوقع: " + data[1] + " (يُثبَّت عند الحفظ). المورد غير النشط لا يظهر في القائمة."
                    : "مسودة رقم " + purchase.getPurchaseNo() + " — لم تؤثر بعد على المخزون أو الحسابات.");
            if (!adding) {
                fill(purchase, suppliers);
            }
            supplierCombo.requestFocus();
        }, error -> host.closePurchase(ErrorMessages.of(error)));
    }

    private void fill(Purchase p, List<Supplier> activeSuppliers) {
        Supplier selected = activeSuppliers.stream().filter(s -> s.getSupplierId().equals(p.getSupplierId()))
                .findFirst().orElse(null);
        supplierCombo.setValue(selected);   // an inactive supplier is not offered: posting would be refused
        supplierInvoiceField.setText(p.getSupplierInvoiceNo() == null ? "" : p.getSupplierInvoiceNo());
        notesField.setText(p.getNotes() == null ? "" : p.getNotes());
        discountField.setText(p.getDiscountAmount() == null || p.getDiscountAmount().signum() == 0
                ? "" : NumberInput.text(p.getDiscountAmount()));
        for (PurchaseItem i : p.getItems()) {
            addLine(i.copy());
        }
        PaymentType type = p.getPaymentType();
        paymentTypeCombo.setValue(type == null ? PaymentType.CASH : type);
        if (type == PaymentType.PARTIAL) {
            partialMethodCombo.setValue(p.getPaymentMethod());
            paidField.setText(NumberInput.text(p.getPaidAmount()));
        }
        recalculate();
    }

    // ---------- Lines ----------

    private void setUpTable() {
        itemsTable.setPlaceholder(new Label("لم تُضف أصناف بعد. ابحث عن صنف في الخانة أعلاه."));
        itemsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        lineNoColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                String.valueOf(itemsTable.getItems().indexOf(c.getValue()) + 1)));
        productColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        productColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Line line, boolean empty) {
                super.updateItem(line, empty);
                setText(null);
                if (empty || line == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(line.item.getProductName());
                name.getStyleClass().add("cell-primary");
                Label code = new Label(line.item.getProductCode());
                code.getStyleClass().add("cell-secondary");
                setGraphic(new VBox(1, name, code));
            }
        });
        unitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().item.getUnitName()));
        quantityColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        quantityColumn.setCellFactory(col -> new EditCell(l -> l.quantity));
        costColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        costColumn.setCellFactory(col -> new EditCell(l -> l.cost));
        discountColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        discountColumn.setCellFactory(col -> new EditCell(l -> l.discount));
        lineTotalColumn.setCellValueFactory(c -> c.getValue().total.getReadOnlyProperty());
        lineTotalColumn.setCellFactory(col -> ViewSupport.textCell("money-cell"));
        removeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        removeColumn.setCellFactory(col -> new TableCell<>() {
            private final Button remove = new Button("حذف");

            {
                remove.getStyleClass().addAll("btn", "btn-link-danger");
                remove.setOnAction(e -> {
                    Line line = getItem();
                    if (line != null) {
                        itemsTable.getItems().remove(line);
                        itemsTable.refresh();   // renumber
                        recalculate();
                    }
                });
            }

            @Override
            protected void updateItem(Line line, boolean empty) {
                super.updateItem(line, empty);
                setText(null);
                setGraphic(empty || line == null ? null : remove);
            }
        });
        productColumn.setMinWidth(180);
        quantityColumn.setMinWidth(90);
        costColumn.setMinWidth(100);
    }

    /** A cell that is always a text box, bound to one of the line's typed values. */
    private final class EditCell extends TableCell<Line, Line> {
        private final TextField field = new TextField();
        private final Function<Line, StringProperty> property;
        private StringProperty bound;

        EditCell(Function<Line, StringProperty> property) {
            this.property = property;
            NumberInput.install(field);
            field.getStyleClass().add("line-input");
            field.textProperty().addListener((o, a, b) -> recalculate());
        }

        @Override
        protected void updateItem(Line line, boolean empty) {
            super.updateItem(line, empty);
            if (bound != null) {
                field.textProperty().unbindBidirectional(bound);
                bound = null;
            }
            setText(null);
            if (empty || line == null) {
                setGraphic(null);
                return;
            }
            bound = property.apply(line);
            field.textProperty().bindBidirectional(bound);
            setGraphic(field);
        }
    }

    private void addProduct(Product p) {
        for (Line line : itemsTable.getItems()) {
            if (line.item.getProductId().equals(p.getProductId())) {
                BigDecimal q = tryParse(line.quantity.get());
                line.quantity.set(NumberInput.text((q == null ? BigDecimal.ZERO : q).add(BigDecimal.ONE)));
                itemsTable.getSelectionModel().select(line);
                recalculate();
                return;
            }
        }
        PurchaseItem item = new PurchaseItem();
        item.setProductId(p.getProductId());
        item.setProductCode(p.getProductCode());
        item.setProductName(p.getNameAr());
        item.setUnitName(p.getUnitName());
        item.setQuantity(BigDecimal.ONE);
        item.setUnitCost(p.getPurchasePrice());   // last cost as a starting point; edit to the invoice price
        item.setDiscountAmount(BigDecimal.ZERO);
        addLine(item);
        recalculate();
    }

    private void addLine(PurchaseItem item) {
        Line line = new Line(item);
        itemsTable.getItems().add(line);
        itemsTable.scrollTo(line);
    }

    // ---------- Totals ----------

    private void updatePaymentFields() {
        PaymentType t = paymentTypeCombo.getValue();
        boolean partial = t == PaymentType.PARTIAL;
        ViewSupport.show(partialMethodBox, partial);
        ViewSupport.show(paidBox, partial);
        paymentHint.setText(t == null ? "" : switch (t) {
            case CREDIT -> "آجل: لا يُدفع شيء الآن، ويُسجَّل كامل المبلغ في حساب المورد.";
            case PARTIAL -> "دفعة جزئية: يُدفع جزء الآن ويُسجَّل الباقي في حساب المورد.";
            default -> "يُدفع كامل المبلغ الآن ويُسجَّل خروجه من الخزنة.";
        });
    }

    /** Applies the typed values to the items and refreshes line and invoice totals. */
    private void recalculate() {
        if (itemsTable == null) {
            return;
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Line line : itemsTable.getItems()) {
            BigDecimal q = tryParse(line.quantity.get());
            BigDecimal c = tryParse(line.cost.get());
            BigDecimal d = tryParse(line.discount.get());
            line.item.setQuantity(q);
            line.item.setUnitCost(c);
            line.item.setDiscountAmount(d == null ? BigDecimal.ZERO : d);
            BigDecimal total = q == null || c == null ? null : line.item.getLineTotal();
            line.total.set(total == null ? "—" : MoneyUtil.format(total));
            if (total != null) {
                subtotal = subtotal.add(total);
            }
        }
        itemsCountLabel.setText(String.valueOf(itemsTable.getItems().size()));
        BigDecimal discount = tryParse(discountField.getText());
        BigDecimal total = MoneyUtil.of(subtotal.subtract(discount == null ? BigDecimal.ZERO : discount));
        PaymentType t = paymentTypeCombo.getValue();
        BigDecimal paid = t == null ? BigDecimal.ZERO : switch (t) {
            case CREDIT -> BigDecimal.ZERO;
            case PARTIAL -> {
                BigDecimal p = tryParse(paidField.getText());
                yield p == null ? BigDecimal.ZERO : p;
            }
            default -> total;
        };
        subtotalLabel.setText(MoneyUtil.format(subtotal));
        totalLabel.setText(MoneyUtil.format(total));
        totalLabel.getStyleClass().remove("negative");
        if (total.signum() < 0) {
            totalLabel.getStyleClass().add("negative");
        }
        paidLabel.setText(MoneyUtil.format(paid));
        remainingLabel.setText(MoneyUtil.format(total.subtract(paid)));
    }

    private static BigDecimal tryParse(String text) {
        try {
            return NumberInput.parse(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- Saving ----------

    @FXML
    private void onPost() {
        save(true);
    }

    @FXML
    private void onSaveDraft() {
        save(false);
    }

    private void save(boolean post) {
        if (saving || purchase == null) {
            return;   // a second click / Enter while the first save is still running
        }
        errors.clear();
        ViewSupport.show(formAlert, false);
        recalculate();

        Purchase p = purchase;
        p.setSupplierId(supplierCombo.getValue() == null ? null : supplierCombo.getValue().getSupplierId());
        p.setSupplierInvoiceNo(supplierInvoiceField.getText());
        p.setNotes(notesField.getText());
        BigDecimal discount = tryParse(discountField.getText());
        if (discount == null && !discountField.getText().isBlank()) {
            errors.set(DISCOUNT, "أدخل رقمًا صحيحًا.");
            return;
        }
        p.setDiscountAmount(discount == null ? BigDecimal.ZERO : discount);
        for (Line line : itemsTable.getItems()) {
            if (line.item.getQuantity() == null || line.item.getUnitCost() == null) {
                errors.set(ITEMS, "السطر " + (itemsTable.getItems().indexOf(line) + 1) + " (" + line.item.getProductName()
                        + "): أدخل الكمية وسعر الشراء بأرقام صحيحة.");
                showAlert("يرجى تصحيح الأخطاء الموضحة.");
                return;
            }
        }
        p.setItems(itemsTable.getItems().stream().map(l -> l.item.copy()).toList());
        PaymentType type = paymentTypeCombo.getValue();
        if (type == PaymentType.PARTIAL) {
            p.setPaymentMethod(partialMethodCombo.getValue());
            p.setPaidAmount(tryParse(paidField.getText()));
        }
        p.setRequestId(requestId);

        saving = true;
        postButton.setDisable(true);
        draftButton.setDisable(true);
        Async.run(() -> post ? purchases.saveAndPost(p, type) : purchases.saveDraft(p, type), saved -> {
            saving = false;
            String message = post
                    ? "تم اعتماد فاتورة المشتريات " + saved.getPurchaseNo() + " وتحديث المخزون وحساب المورد"
                    + (saved.getPaidAmount() != null && saved.getPaidAmount().signum() > 0 ? " والخزنة." : ".")
                    : "تم حفظ المسودة " + saved.getPurchaseNo() + ". لن تؤثر على المخزون أو الحسابات حتى اعتمادها.";
            host.showPurchaseDetails(saved.getPurchaseId(), message);
        }, error -> {
            saving = false;
            postButton.setDisable(false);
            draftButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                showAlert(other != null ? other : "يرجى تصحيح الأخطاء الموضحة.");
            } else {
                showAlert(ErrorMessages.of(error) + " لم يُحفظ أي شيء.");
            }
        });
    }

    @FXML
    private void onBack() {
        if (purchase != null && purchase.getPurchaseId() != null) {
            host.showPurchaseDetails(purchase.getPurchaseId(), null);
        } else {
            host.closePurchase(null);
        }
    }

    private void showAlert(String text) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-error");
        ViewSupport.show(formAlert, true);
    }
}
