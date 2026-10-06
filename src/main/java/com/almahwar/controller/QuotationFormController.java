package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.controller.support.ProductPicker;
import com.almahwar.model.Customer;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.SaleType;
import com.almahwar.model.SystemSettings;
import com.almahwar.service.QuotationService;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.almahwar.service.QuotationService.*;

/**
 * New quotation / edit draft. Prices come from the selected price list (retail or wholesale); a different price needs
 * the price-override permission, discounts need the discount permission. Saving never touches stock, accounts or cash.
 * Each opening of the form has one request id, so a double click or a retry can never save twice.
 */
public class QuotationFormController {

    /** One editable line: the typed text plus the item it updates. */
    public static final class Line {
        final QuotationItem item;
        final BigDecimal retailPrice;
        final BigDecimal wholesalePrice;
        final StringProperty quantity = new SimpleStringProperty();
        final StringProperty price = new SimpleStringProperty();
        final StringProperty discount = new SimpleStringProperty();
        final ReadOnlyStringWrapper total = new ReadOnlyStringWrapper();

        Line(QuotationItem item, BigDecimal retailPrice, BigDecimal wholesalePrice) {
            this.item = item;
            this.retailPrice = retailPrice;
            this.wholesalePrice = wholesalePrice;
            quantity.set(NumberInput.text(item.getQuantity()));
            price.set(item.getUnitPrice() == null ? "" : NumberInput.text(item.getUnitPrice()));
            discount.set(item.getDiscountAmount() == null || item.getDiscountAmount().signum() == 0
                    ? "" : NumberInput.text(item.getDiscountAmount()));
        }

        BigDecimal listPrice(SaleType type) {
            return retailPrice == null ? null : MoneyUtil.of(type.priceOf(retailPrice, wholesalePrice));
        }

        public QuotationItem getItem() {
            return item;
        }
    }

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label formAlert;
    @FXML private ComboBox<Customer> customerCombo;
    @FXML private Label customerError;
    @FXML private Label balanceLabel;
    @FXML private ComboBox<SaleType> priceTypeCombo;
    @FXML private Label priceTypeError;
    @FXML private DatePicker validUntilPicker;
    @FXML private Label validUntilError;
    @FXML private VBox prospectNameBox;
    @FXML private TextField prospectNameField;
    @FXML private Label prospectNameError;
    @FXML private VBox prospectPhoneBox;
    @FXML private TextField prospectPhoneField;
    @FXML private Label prospectPhoneError;
    @FXML private TextField notesField;
    @FXML private Label notesError;
    @FXML private Label itemsCountLabel;
    @FXML private TextField productField;
    @FXML private TableView<Line> itemsTable;
    @FXML private TableColumn<Line, String> lineNoColumn;
    @FXML private TableColumn<Line, Line> productColumn;
    @FXML private TableColumn<Line, String> unitColumn;
    @FXML private TableColumn<Line, String> availableColumn;
    @FXML private TableColumn<Line, Line> quantityColumn;
    @FXML private TableColumn<Line, Line> priceColumn;
    @FXML private TableColumn<Line, Line> discountColumn;
    @FXML private TableColumn<Line, String> lineTotalColumn;
    @FXML private TableColumn<Line, Line> removeColumn;
    @FXML private Label itemsError;
    @FXML private TextArea termsArea;
    @FXML private Label termsError;
    @FXML private Label subtotalLabel;
    @FXML private HBox discountRow;
    @FXML private TextField discountField;
    @FXML private Label discountError;
    @FXML private Label totalLabel;
    @FXML private Button saveButton;

    private final SecurityContext security = AppContext.get().security();
    private final QuotationService quotations = AppContext.get().quotations();
    private final FormErrors errors = new FormErrors();

    private QuotationPages host;
    private Quotation quotation;
    private UUID requestId;
    private boolean saving;
    private boolean mayOverridePrice;
    private boolean mayDiscount;

    @FXML
    private void initialize() {
        mayOverridePrice = security.hasPermission(Permission.QUOTATIONS_PRICE_OVERRIDE);
        mayDiscount = security.hasPermission(Permission.QUOTATIONS_DISCOUNT);
        discountColumn.setVisible(mayDiscount);
        ViewSupport.show(discountRow, mayDiscount);

        customerCombo.setConverter(ViewSupport.converter(c -> c.isCashCustomer() ? c.getName() + " (عرض لعميل غير مسجل)"
                : c.getName() + "  (" + c.getCustomerCode() + ")"));
        customerCombo.valueProperty().addListener((o, a, c) -> customerChanged(c));
        priceTypeCombo.getItems().setAll(SaleType.values());
        priceTypeCombo.setConverter(ViewSupport.converter(SaleType::getLabelAr));
        priceTypeCombo.setValue(SaleType.RETAIL);
        priceTypeCombo.valueProperty().addListener((o, a, b) -> repriceLines(a, b));
        NumberInput.install(discountField);
        discountField.textProperty().addListener((o, a, b) -> recalculate());
        prospectPhoneField.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);

        errors.register(CUSTOMER, customerCombo, customerError)
                .register("priceType", priceTypeCombo, priceTypeError)
                .register(VALID_UNTIL, validUntilPicker, validUntilError)
                .register(PROSPECT_NAME, prospectNameField, prospectNameError)
                .register(PROSPECT_PHONE, prospectPhoneField, prospectPhoneError)
                .register(NOTES, notesField, notesError)
                .register(TERMS, termsArea, termsError)
                .register(ITEMS, itemsTable, itemsError)
                .register(DISCOUNT, discountField, discountError);

        setUpTable();
        new ProductPicker(productField, quotations::searchProducts)
                .selectedProperty().addListener((o, a, product) -> {
                    if (product != null) {
                        addProduct(product);
                        Platform.runLater(() -> {
                            productField.clear();
                            productField.requestFocus();
                        });
                    }
                });
        customerChanged(null);
    }

    /**
     * @param quotationId {@code null} for a new quotation, else a DRAFT to edit
     * @param customerId  the customer to preselect for a new quotation, or {@code null}
     */
    public void open(QuotationPages host, Integer quotationId, Integer customerId) {
        this.host = host;
        boolean adding = quotationId == null;
        requestId = adding ? UUID.randomUUID() : null;
        titleLabel.setText(adding ? "عرض سعر جديد" : "تعديل مسودة عرض السعر");

        Async.run(() -> {
            Quotation found = adding ? null : quotations.findById(quotationId).orElse(null);
            return new Object[]{quotations.activeCustomers(), adding ? quotations.suggestNumber() : null, found,
                    prices(found), adding ? AppContext.get().settings().system() : null};
        }, data -> {
            @SuppressWarnings("unchecked") List<Customer> customers = (List<Customer>) data[0];
            customerCombo.getItems().setAll(customers);
            quotation = adding ? new Quotation() : (Quotation) data[2];
            if (quotation == null || (!adding && !quotation.getStatus().isEditable())) {
                host.closeQuotation("لا يمكن تعديل عرض السعر هذا؛ التعديل للمسودات فقط.");
                return;
            }
            subtitleLabel.setText(adding
                    ? "الرقم المتوقع: " + data[1] + " (يُثبَّت عند الحفظ). العميل غير النشط لا يظهر في القائمة."
                    : "مسودة رقم " + quotation.getQuotationNo() + " — لا أثر لها على المخزون أو الحسابات.");
            if (adding) {
                // defaults of a new quotation come from the settings; a saved quotation keeps its own terms
                SystemSettings defaults = (SystemSettings) data[4];
                validUntilPicker.setValue(LocalDate.now().plusDays(defaults.quotationValidityDays()));
                termsArea.setText(defaults.quotationTerms() == null ? "" : defaults.quotationTerms());
                Customer preset = customers.stream()
                        .filter(c -> customerId != null ? c.getCustomerId().equals(customerId) : c.isCashCustomer())
                        .findFirst().orElse(null);
                customerCombo.setValue(preset);
            } else {
                @SuppressWarnings("unchecked") List<Product> products = (List<Product>) data[3];
                fill(quotation, customers, products);
            }
            customerCombo.requestFocus();
        }, error -> host.closeQuotation(ErrorMessages.of(error)));
    }

    /** The current list prices of a draft's products (to re-price when the price type changes). */
    private List<Product> prices(Quotation q) {
        if (q == null) {
            return List.of();
        }
        return q.getItems().stream().map(i -> quotations.searchProducts(i.getProductCode()).stream()
                .filter(p -> p.getProductId().equals(i.getProductId())).findFirst().orElse(null)).toList();
    }

    private void fill(Quotation q, List<Customer> activeCustomers, List<Product> products) {
        customerCombo.setValue(activeCustomers.stream().filter(c -> c.getCustomerId().equals(q.getCustomerId()))
                .findFirst().orElse(null));   // an inactive customer is not offered: saving would be refused
        priceTypeCombo.setValue(q.getPriceType());
        validUntilPicker.setValue(q.getValidUntil());
        prospectNameField.setText(q.getProspectName() == null ? "" : q.getProspectName());
        prospectPhoneField.setText(q.getProspectPhone() == null ? "" : q.getProspectPhone());
        notesField.setText(q.getNotes() == null ? "" : q.getNotes());
        termsArea.setText(q.getTerms() == null ? "" : q.getTerms());
        discountField.setText(q.getDiscountAmount() == null || q.getDiscountAmount().signum() == 0
                ? "" : NumberInput.text(q.getDiscountAmount()));
        for (int i = 0; i < q.getItems().size(); i++) {
            Product p = i < products.size() ? products.get(i) : null;
            addLine(new Line(q.getItems().get(i).copy(), p == null ? null : p.getSalePrice(),
                    p == null ? null : p.getWholesalePrice()));
        }
        recalculate();
    }

    private void customerChanged(Customer c) {
        boolean walkIn = c != null && c.isCashCustomer();
        ViewSupport.show(prospectNameBox, walkIn);
        ViewSupport.show(prospectPhoneBox, walkIn);
        if (c == null || walkIn || c.getBalance() == null) {
            balanceLabel.setText(walkIn ? "عميل غير مسجل: اكتب اسمه وهاتفه ليظهرا على العرض." : "");
            return;
        }
        balanceLabel.setText("رصيد العميل الحالي (للعلم فقط): " + MoneyUtil.formatWithCurrency(c.getBalance())
                + (c.getCreditLimit() != null && c.getCreditLimit().signum() > 0
                ? "  •  حد الائتمان: " + MoneyUtil.format(c.getCreditLimit()) : ""));
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
        availableColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().item.getAvailable() == null ? "—"
                : QuantityUtil.format(c.getValue().item.getAvailable())));
        quantityColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        quantityColumn.setCellFactory(col -> new EditCell(l -> l.quantity, true));
        priceColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        priceColumn.setCellFactory(col -> new EditCell(l -> l.price, mayOverridePrice));
        discountColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        discountColumn.setCellFactory(col -> new EditCell(l -> l.discount, mayDiscount));
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
        productColumn.setMinWidth(170);
        quantityColumn.setMinWidth(90);
        priceColumn.setMinWidth(100);
    }

    /** A cell that is always a text box (read-only without the needed permission), bound to a typed value. */
    private final class EditCell extends TableCell<Line, Line> {
        private final TextField field = new TextField();
        private final Function<Line, StringProperty> property;
        private StringProperty bound;

        EditCell(Function<Line, StringProperty> property, boolean editable) {
            this.property = property;
            NumberInput.install(field);
            field.getStyleClass().add("line-input");
            field.setEditable(editable);
            field.setFocusTraversable(editable);
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
        QuotationItem item = new QuotationItem();
        item.setProductId(p.getProductId());
        item.setProductCode(p.getProductCode());
        item.setProductName(p.getNameAr());
        item.setBarcode(p.getBarcode());
        item.setUnitName(p.getUnitName());
        item.setAvailable(p.getQuantity());
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(MoneyUtil.of(priceTypeCombo.getValue().priceOf(p)));
        item.setDiscountAmount(BigDecimal.ZERO);
        addLine(new Line(item, p.getSalePrice(), p.getWholesalePrice()));
        recalculate();
    }

    private void addLine(Line line) {
        itemsTable.getItems().add(line);
        itemsTable.scrollTo(line);
    }

    /** Switching retail / wholesale updates every line still at the old list price; typed prices are kept. */
    private void repriceLines(SaleType from, SaleType to) {
        if (from == null || to == null || from == to) {
            return;
        }
        int kept = 0;
        for (Line line : itemsTable.getItems()) {
            BigDecimal p = tryParse(line.price.get());
            BigDecimal oldList = line.listPrice(from);
            if (oldList == null) {
                continue;
            }
            if (p == null || p.compareTo(oldList) == 0) {
                line.price.set(NumberInput.text(line.listPrice(to)));
            } else {
                kept++;
            }
        }
        recalculate();
        if (!itemsTable.getItems().isEmpty()) {
            showInfo("تم تحديث الأسعار حسب قائمة " + to.getLabelAr() + "."
                    + (kept > 0 ? " بقي " + kept + " سطر بسعر معدّل يدويًا كما هو." : ""));
        }
    }

    // ---------- Totals ----------

    /** Applies the typed values to the items and refreshes line and quotation totals. */
    private void recalculate() {
        if (itemsTable == null) {
            return;
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Line line : itemsTable.getItems()) {
            BigDecimal q = tryParse(line.quantity.get());
            BigDecimal p = tryParse(line.price.get());
            BigDecimal d = tryParse(line.discount.get());
            line.item.setQuantity(q);
            line.item.setUnitPrice(p);
            line.item.setDiscountAmount(d == null ? BigDecimal.ZERO : d);
            BigDecimal total = q == null || p == null ? null : line.item.getLineTotal();
            line.total.set(total == null ? "—" : MoneyUtil.format(total));
            if (total != null) {
                subtotal = subtotal.add(total);
            }
        }
        itemsCountLabel.setText(String.valueOf(itemsTable.getItems().size()));
        BigDecimal discount = tryParse(discountField.getText());
        BigDecimal total = MoneyUtil.of(subtotal.subtract(discount == null ? BigDecimal.ZERO : discount));
        subtotalLabel.setText(MoneyUtil.format(subtotal));
        totalLabel.setText(MoneyUtil.format(total));
        totalLabel.getStyleClass().remove("negative");
        if (total.signum() < 0) {
            totalLabel.getStyleClass().add("negative");
        }
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
    private void onSave() {
        if (saving || quotation == null) {
            return;   // a second click / Enter while the first save is still running
        }
        errors.clear();
        ViewSupport.show(formAlert, false);
        recalculate();

        Quotation q = quotation;
        Customer customer = customerCombo.getValue();
        q.setCustomerId(customer == null ? null : customer.getCustomerId());
        q.setPriceType(priceTypeCombo.getValue());
        q.setValidUntil(validUntilPicker.getValue());
        q.setProspectName(prospectNameField.getText());
        q.setProspectPhone(prospectPhoneField.getText());
        q.setNotes(notesField.getText());
        q.setTerms(termsArea.getText());
        BigDecimal discount = tryParse(discountField.getText());
        if (discount == null && !discountField.getText().isBlank()) {
            errors.set(DISCOUNT, "أدخل رقمًا صحيحًا.");
            return;
        }
        q.setDiscountAmount(discount == null ? BigDecimal.ZERO : discount);
        for (Line line : itemsTable.getItems()) {
            if (line.item.getQuantity() == null || line.item.getUnitPrice() == null) {
                errors.set(ITEMS, "السطر " + (itemsTable.getItems().indexOf(line) + 1) + " (" + line.item.getProductName()
                        + "): أدخل الكمية والسعر بأرقام صحيحة.");
                showAlert("يرجى تصحيح الأخطاء الموضحة.");
                return;
            }
        }
        q.setItems(itemsTable.getItems().stream().map(l -> l.item.copy()).toList());
        q.setRequestId(requestId);

        saving = true;
        saveButton.setDisable(true);
        Async.run(() -> quotations.save(q), saved -> {
            saving = false;
            host.showDetails(saved.getQuotationId(), "تم حفظ عرض السعر " + saved.getQuotationNo()
                    + " كمسودة. لا أثر له على المخزون أو الحسابات.");
        }, error -> {
            saving = false;
            saveButton.setDisable(false);
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
        if (quotation != null && quotation.getQuotationId() != null) {
            host.showDetails(quotation.getQuotationId(), null);
        } else {
            host.closeQuotation(null);
        }
    }

    private void showAlert(String text) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-error");
        ViewSupport.show(formAlert, true);
    }

    private void showInfo(String text) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-info");
        ViewSupport.show(formAlert, true);
    }
}
