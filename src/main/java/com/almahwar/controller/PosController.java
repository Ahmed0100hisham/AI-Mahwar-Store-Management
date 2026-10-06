package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.LeaveGuard;
import com.almahwar.controller.support.Navigator;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleType;
import com.almahwar.service.CreditLimitExceededException;
import com.almahwar.service.InsufficientStockException;
import com.almahwar.service.SaleService;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
import com.almahwar.util.QuantityUtil;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The point of sale. Built for the keyboard and a barcode scanner (a scanner types the code and presses
 * Enter): every scan is queued and handled in order, the product is added (or its quantity increased) and
 * the focus stays in the search field, so several items can be scanned in a row.
 * <p>
 * The screen only prepares the cart: the service validates everything again and posts the sale in one
 * database transaction (stock, customer account, cash). Each cart has one request id, so a double click,
 * a repeated F10 or a retry can never create two invoices.
 * <p>
 * While shown, the POS is the {@link Navigator}'s {@link LeaveGuard}: leaving with an unsaved cart (side menu,
 * logout, closing the window, back to the sales list ...) first asks to complete, hold as a draft, stay or clear.
 */
public class PosController implements SalePages, LeaveGuard {

    private static final int MAX_POPUP = 12;

    /** One cart line: the typed values plus the item they update. */
    public static final class Line {
        final SaleItem item;
        final BigDecimal retailPrice;
        final BigDecimal wholesalePrice;
        BigDecimal available;
        final boolean allowsDecimal;
        final StringProperty quantity = new SimpleStringProperty();
        final StringProperty price = new SimpleStringProperty();
        final StringProperty discount = new SimpleStringProperty();
        final ReadOnlyStringWrapper total = new ReadOnlyStringWrapper();
        final ReadOnlyStringWrapper stockText = new ReadOnlyStringWrapper();
        final BooleanProperty shortage = new SimpleBooleanProperty();
        /** The price differs from the list price of the current sale type (typed by a permitted user). */
        boolean manualPrice;

        Line(SaleItem item, BigDecimal retailPrice, BigDecimal wholesalePrice, BigDecimal available,
             boolean allowsDecimal) {
            this.item = item;
            this.retailPrice = retailPrice;
            this.wholesalePrice = wholesalePrice;
            this.available = available;
            this.allowsDecimal = allowsDecimal;
            quantity.set(NumberInput.text(item.getQuantity()));
            price.set(NumberInput.text(item.getUnitPrice()));
            discount.set(item.getDiscountAmount() == null || item.getDiscountAmount().signum() == 0
                    ? "" : NumberInput.text(item.getDiscountAmount()));
        }

        BigDecimal listPrice(SaleType type) {
            return MoneyUtil.of(type.priceOf(retailPrice, wholesalePrice));
        }

        public SaleItem getItem() {
            return item;
        }
    }

    /** What a scan / search found. */
    private record Lookup(Product exact, List<Product> matches) {
    }

    @FXML private StackPane pageHost;
    @FXML private BorderPane posPage;
    @FXML private Button backButton;
    @FXML private Label subtitleLabel;
    @FXML private ToggleButton retailToggle;
    @FXML private ToggleButton wholesaleToggle;
    @FXML private Label cashierLabel;
    @FXML private TextField searchField;
    @FXML private Label posMessage;
    @FXML private VBox leaveBox;
    @FXML private HBox leaveClearConfirm;
    @FXML private Button leaveStayButton;
    @FXML private Button leaveClearConfirmButton;
    @FXML private HBox lastSaleBar;
    @FXML private Label lastSaleLabel;
    @FXML private TableView<Line> cartTable;
    @FXML private TableColumn<Line, String> lineNoColumn;
    @FXML private TableColumn<Line, Line> productColumn;
    @FXML private TableColumn<Line, String> codeColumn;
    @FXML private TableColumn<Line, String> unitColumn;
    @FXML private TableColumn<Line, Line> quantityColumn;
    @FXML private TableColumn<Line, Line> priceColumn;
    @FXML private TableColumn<Line, Line> discountColumn;
    @FXML private TableColumn<Line, String> lineTotalColumn;
    @FXML private TableColumn<Line, Line> removeColumn;
    @FXML private Label cartCountLabel;
    @FXML private HBox clearConfirmBox;
    @FXML private Button confirmClearButton;
    @FXML private Button clearButton;
    @FXML private Button walkInButton;
    @FXML private Label customerNameLabel;
    @FXML private Label customerInfoLabel;
    @FXML private TextField customerField;
    @FXML private Label subtotalLabel;
    @FXML private TextField discountField;
    @FXML private Label totalLabel;
    @FXML private ComboBox<PaymentType> paymentTypeCombo;
    @FXML private HBox partialBox;
    @FXML private ComboBox<PaymentMethod> partialMethodCombo;
    @FXML private TextField paidField;
    @FXML private HBox cashBox;
    @FXML private TextField receivedField;
    @FXML private Label changeLabel;
    @FXML private Label paymentHint;
    @FXML private Label paidLabel;
    @FXML private Label remainingLabel;
    @FXML private VBox overrideBox;
    @FXML private Label overrideLabel;
    @FXML private Button overrideButton;
    @FXML private Button completeButton;
    @FXML private Button draftButton;

    private final SecurityContext security = AppContext.get().security();
    private final SaleService sales = AppContext.get().sales();
    private final ToggleGroup saleTypeGroup = new ToggleGroup();
    private final ContextMenu productPopup = new ContextMenu();
    private final ContextMenu customerPopup = new ContextMenu();
    private final PauseTransition productDelay = new PauseTransition(Duration.millis(250));
    private final PauseTransition customerDelay = new PauseTransition(Duration.millis(250));
    private final Deque<String> scans = new ArrayDeque<>();

    private boolean canOverridePrice;
    private boolean canDiscount;
    private SalePages parent;
    private Customer walkIn;
    private Customer customer;
    private SaleType saleType = SaleType.RETAIL;
    private Integer draftId;
    /** Shown with the loaded draft (e.g. what changed since the quotation it was converted from). */
    private String draftNotice;
    private UUID requestId = UUID.randomUUID();
    private boolean busy;
    private boolean scanning;
    private boolean settingText;
    private Sale lastSale;
    private BigDecimal lastChange;
    /** The navigation waiting for the cart to be saved or cleared ({@code null} = none). */
    private Runnable pendingLeave;
    /** The cart as last saved (a loaded draft); an unchanged draft is not "unsaved work". */
    private String savedSnapshot;

    @FXML
    private void initialize() {
        canOverridePrice = security.hasPermission(Permission.SALES_PRICE_OVERRIDE);
        canDiscount = security.hasPermission(Permission.SALES_DISCOUNT);
        cashierLabel.setText("الكاشير: " + security.currentUser().getFullName());
        ViewSupport.show(backButton, false);
        ViewSupport.show(draftButton, true);
        completeButton.setDisable(!security.hasPermission(Permission.SALES_POST));
        discountField.setDisable(!canDiscount);
        if (!canDiscount) {
            discountField.setPromptText("بدون صلاحية");
        }

        retailToggle.setToggleGroup(saleTypeGroup);
        wholesaleToggle.setToggleGroup(saleTypeGroup);
        retailToggle.setUserData(SaleType.RETAIL);
        wholesaleToggle.setUserData(SaleType.WHOLESALE);
        retailToggle.setSelected(true);
        saleTypeGroup.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) {
                old.setSelected(true);   // one of the two is always selected
                return;
            }
            applySaleType((SaleType) now.getUserData());
        });

        paymentTypeCombo.setConverter(ViewSupport.converter(PaymentType::getLabelAr));
        partialMethodCombo.getItems().setAll(PaymentMethod.CASH, PaymentMethod.KNET, PaymentMethod.BANK_TRANSFER,
                PaymentMethod.CHEQUE);
        partialMethodCombo.setConverter(ViewSupport.converter(PaymentMethod::getLabelAr));
        partialMethodCombo.setValue(PaymentMethod.CASH);
        paymentTypeCombo.valueProperty().addListener((o, a, b) -> {
            updatePaymentFields();
            recalculate();
        });
        for (TextField f : List.of(discountField, paidField, receivedField)) {
            NumberInput.install(f);
            f.textProperty().addListener((o, a, b) -> recalculate());
        }

        setUpTable();
        setUpSearch();
        setUpCustomerSearch();
        posPage.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // the guard is active exactly while the POS is part of the window
        pageHost.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                Navigator.setLeaveGuard(this);
            } else {
                Navigator.clearLeaveGuard(this);
            }
        });
        productPopup.getStyleClass().add("picker-popup");
        customerPopup.getStyleClass().add("picker-popup");
        setPaymentTypes(true);
        updatePaymentFields();
        recalculate();
    }

    /**
     * @param parent      the sales module when opened from there ("back" returns to it), or {@code null}
     *                    when opened from the side menu
     * @param draftSaleId a held draft to continue, or {@code null} for a new sale
     */
    public void open(SalePages parent, Integer draftSaleId) {
        open(parent, draftSaleId, null);
    }

    /** @param notice shown once the draft is loaded (warnings to review before completing the sale) */
    public void open(SalePages parent, Integer draftSaleId, String notice) {
        this.parent = parent;
        this.draftNotice = notice;
        ViewSupport.show(backButton, parent != null);
        Async.run(sales::walkInCustomer, c -> {
            walkIn = c;
            if (customer == null) {
                selectCustomer(c);
            }
            if (draftSaleId != null) {
                loadDraft(draftSaleId);
            } else {
                showSuggestedNumber();
            }
        }, error -> showMessage(ErrorMessages.of(error), true));
        Platform.runLater(() -> searchField.requestFocus());
    }

    private void showSuggestedNumber() {
        Async.run(sales::suggestNumber, no -> subtitleLabel.setText(draftId != null ? subtitleLabel.getText()
                : "فاتورة جديدة — الرقم المتوقع " + no + " (يُثبَّت عند إتمام البيع)"), e -> { });
    }

    // ======================= Keyboard =======================

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case F2 -> {
                focus(searchField);
                e.consume();
            }
            case F4 -> {
                focus(customerField);
                e.consume();
            }
            case F6 -> {
                if (canDiscount) {
                    focus(discountField);
                } else {
                    showMessage("ليس لديك صلاحية منح الخصومات.", true);
                }
                e.consume();
            }
            case F8 -> {
                paymentTypeCombo.requestFocus();
                paymentTypeCombo.show();
                e.consume();
            }
            case F10 -> {
                onComplete();
                e.consume();
            }
            case DELETE -> {
                Node focused = posPage.getScene() == null ? null : posPage.getScene().getFocusOwner();
                if (!(focused instanceof TextInputControl)) {
                    Line selected = cartTable.getSelectionModel().getSelectedItem();
                    if (selected != null) {
                        removeLine(selected);
                        e.consume();
                    }
                }
            }
            case ESCAPE -> {
                // closes the search / dialogs only: the cart is never cleared by Esc
                productPopup.hide();
                customerPopup.hide();
                ViewSupport.show(clearConfirmBox, false);
                ViewSupport.show(overrideBox, false);
                pendingLeave = null;
                hideLeaveBox();
                clearButton.setDisable(false);
                if (!searchField.getText().isEmpty()) {
                    setSearchText("");
                }
                if (!customerField.getText().isEmpty()) {
                    customerField.clear();
                }
                focus(searchField);
                e.consume();
            }
            default -> { }
        }
    }

    private static void focus(TextField field) {
        field.requestFocus();
        field.selectAll();
    }

    // ======================= Product search and barcode scans =======================

    private void setUpSearch() {
        searchField.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        searchField.setOnAction(e -> {
            String text = searchField.getText() == null ? "" : searchField.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            setSearchText("");   // ready for the next scan at once
            productPopup.hide();
            scans.add(text);
            processScans();
        });
        searchField.textProperty().addListener((o, a, b) -> {
            if (!settingText) {
                productDelay.playFromStart();
            }
        });
        productDelay.setOnFinished(e -> suggestProducts());
        searchField.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) {
                productPopup.hide();
            }
        });
    }

    private void setSearchText(String text) {
        settingText = true;
        searchField.setText(text);
        settingText = false;
    }

    /** Handles queued scans one after the other, so fast scans are all added, in order. */
    private void processScans() {
        if (scanning || scans.isEmpty()) {
            return;
        }
        scanning = true;
        String code = scans.poll();
        Async.run(() -> lookup(code), result -> {
            scanning = false;
            applyLookup(code, result);
            processScans();
        }, error -> {
            scanning = false;
            showMessage(ErrorMessages.of(error), true);
            processScans();
        });
    }

    private Lookup lookup(String code) {
        Optional<Product> exact = sales.findByScan(code);
        return exact.map(p -> new Lookup(p, List.of())).orElseGet(() -> new Lookup(null, sales.searchProducts(code)));
    }

    private void applyLookup(String code, Lookup result) {
        if (result.exact() != null) {
            if (!result.exact().isActive()) {
                showMessage("الصنف \"" + result.exact().getNameAr() + "\" معطّل ولا يمكن بيعه.", true);
            } else {
                addProduct(result.exact());
            }
        } else if (result.matches().size() == 1) {
            addProduct(result.matches().get(0));
        } else if (result.matches().isEmpty()) {
            showMessage("لا يوجد صنف بهذا الباركود أو الاسم: \"" + code + "\"", true);
        } else {
            // several matches: let the cashier pick one
            setSearchText(code);
            searchField.positionCaret(code.length());
            showProductPopup(result.matches());
        }
        searchField.requestFocus();
    }

    private void suggestProducts() {
        String text = searchField.getText();
        if (text == null || text.isBlank() || !searchField.isFocused()) {
            productPopup.hide();
            return;
        }
        Async.run(() -> sales.searchProducts(text.trim()), results -> {
            if (text.equals(searchField.getText())) {
                showProductPopup(results);
            }
        }, error -> productPopup.hide());
    }

    private void showProductPopup(List<Product> results) {
        fill(productPopup, results, "لا توجد أصناف مطابقة", p -> {
            Label name = new Label(p.getNameAr());
            name.getStyleClass().add("cell-primary");
            Label info = new Label(p.getProductCode() + (p.getBarcode() == null ? "" : "  •  " + p.getBarcode())
                    + "  •  " + MoneyUtil.format(saleType.priceOf(p)) + "  •  المتوفر: "
                    + QuantityUtil.format(p.getQuantity()) + " " + p.getUnitName());
            info.getStyleClass().add("cell-secondary");
            return new VBox(1, name, info);
        }, p -> {
            setSearchText("");
            addProduct(p);
            searchField.requestFocus();
        }, searchField);
    }

    /** Shows {@code items} under {@code field}; picking one runs {@code onPick}. */
    private <T> void fill(ContextMenu popup, List<T> items, String emptyText, Function<T, Node> view,
                          Consumer<T> onPick, TextField field) {
        if (field.getScene() == null || field.getScene().getWindow() == null
                || !field.getScene().getWindow().isShowing()) {
            return;   // the page was closed while searching
        }
        popup.getItems().clear();
        if (items.isEmpty()) {
            MenuItem none = new MenuItem(emptyText);
            none.setDisable(true);
            popup.getItems().add(none);
        }
        for (T item : items.stream().limit(MAX_POPUP).toList()) {
            Node box = view.apply(item);
            if (box instanceof VBox v) {
                v.setPrefWidth(Math.max(field.getWidth() - 24, 280));
            }
            CustomMenuItem menuItem = new CustomMenuItem(box, true);
            menuItem.setOnAction(e -> onPick.accept(item));
            popup.getItems().add(menuItem);
        }
        if (!popup.isShowing()) {
            popup.show(field, Side.BOTTOM, 0, 2);
        }
        if (popup.getScene() != null) {
            popup.getScene().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        }
    }

    // ======================= Cart =======================

    private void setUpTable() {
        cartTable.setPlaceholder(new Label("السلة فارغة. امسح باركود صنف أو ابحث عنه بالاسم أو الكود."));
        cartTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        lineNoColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                String.valueOf(cartTable.getItems().indexOf(c.getValue()) + 1)));
        productColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        productColumn.setCellFactory(col -> new ProductCell());
        codeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().item.getProductCode()));
        unitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().item.getUnitName()));
        quantityColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        quantityColumn.setCellFactory(col -> new QuantityCell());
        priceColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        priceColumn.setCellFactory(col -> canOverridePrice ? new EditCell(l -> l.price) : new TextCell(l -> l.price));
        discountColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        discountColumn.setCellFactory(col -> canDiscount ? new EditCell(l -> l.discount) : new TextCell(l -> l.discount));
        lineTotalColumn.setCellValueFactory(c -> c.getValue().total.getReadOnlyProperty());
        lineTotalColumn.setCellFactory(col -> ViewSupport.textCell("money-cell"));
        removeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        removeColumn.setCellFactory(col -> new TableCell<>() {
            private final Button remove = new Button(null, com.almahwar.controller.support.Icon.of("delete"));

            {
                remove.getStyleClass().addAll("icon-button", "icon-button-danger");
                remove.setTooltip(new javafx.scene.control.Tooltip("حذف الصنف من السلة (Delete)"));
                remove.setFocusTraversable(false);
                remove.setOnAction(e -> {
                    if (getItem() != null) {
                        removeLine(getItem());
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
        quantityColumn.setMinWidth(140);
        priceColumn.setMinWidth(85);
    }

    /** Product name, with the available stock underneath (red when the cart asks for more). */
    private static final class ProductCell extends TableCell<Line, Line> {
        private final Label name = new Label();
        private final Label stock = new Label();
        private final VBox box = new VBox(1, name, stock);
        private final javafx.beans.value.ChangeListener<Boolean> onShortage = (o, a, b) -> style(b);
        private Line shown;

        ProductCell() {
            name.getStyleClass().add("cell-primary");
            stock.getStyleClass().add("cell-secondary");
        }

        @Override
        protected void updateItem(Line line, boolean empty) {
            super.updateItem(line, empty);
            if (shown != null) {
                shown.shortage.removeListener(onShortage);
                stock.textProperty().unbind();
                shown = null;
            }
            setText(null);
            if (empty || line == null) {
                setGraphic(null);
                return;
            }
            shown = line;
            name.setText(line.item.getProductName());
            stock.textProperty().bind(line.stockText.getReadOnlyProperty());
            line.shortage.addListener(onShortage);
            style(line.shortage.get());
            setGraphic(box);
        }

        private void style(boolean shortage) {
            stock.getStyleClass().remove("stock-short");
            if (shortage) {
                stock.getStyleClass().add("stock-short");
            }
        }
    }

    /** Quantity: − [typed] +. */
    private final class QuantityCell extends TableCell<Line, Line> {
        private final TextField field = new TextField();
        private final Button minus = new Button(null, com.almahwar.controller.support.Icon.of("minus"));
        private final Button plus = new Button(null, com.almahwar.controller.support.Icon.of("plus"));
        private final HBox box = new HBox(4, minus, field, plus);
        private StringProperty bound;

        QuantityCell() {
            NumberInput.install(field);
            field.getStyleClass().addAll("line-input", "qty-input");
            field.setPrefWidth(64);
            for (Button b : List.of(minus, plus)) {
                b.getStyleClass().addAll("qty-button", "icon-button");
                b.setFocusTraversable(false);
                b.setMinSize(30, 30);
                b.setPrefSize(30, 30);
            }
            minus.setTooltip(new javafx.scene.control.Tooltip("إنقاص الكمية"));
            plus.setTooltip(new javafx.scene.control.Tooltip("زيادة الكمية"));
            box.setAlignment(Pos.CENTER);
            box.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
            field.textProperty().addListener((o, a, b) -> recalculate());
            minus.setOnAction(e -> step(-1));
            plus.setOnAction(e -> step(1));
        }

        private void step(int by) {
            Line line = getItem();
            if (line == null) {
                return;
            }
            BigDecimal q = tryParse(line.quantity.get());
            BigDecimal next = (q == null ? BigDecimal.ZERO : q).add(BigDecimal.valueOf(by));
            if (next.signum() <= 0) {
                showMessage("الكمية لا تقل عن 1؛ لإزالة الصنف استخدم زر الحذف أو مفتاح Delete.", true);
                return;
            }
            line.quantity.set(NumberInput.text(next));
            cartTable.getSelectionModel().select(line);
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
            bound = line.quantity;
            field.textProperty().bindBidirectional(bound);
            setGraphic(box);
        }
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

    /** Read-only value (the user may not change it). */
    private static final class TextCell extends TableCell<Line, Line> {
        private final Function<Line, StringProperty> property;

        TextCell(Function<Line, StringProperty> property) {
            this.property = property;
            getStyleClass().add("money-cell");
        }

        @Override
        protected void updateItem(Line line, boolean empty) {
            super.updateItem(line, empty);
            textProperty().unbind();
            setGraphic(null);
            if (empty || line == null) {
                setText(null);
                return;
            }
            textProperty().bind(property.apply(line));
        }
    }

    private void addProduct(Product p) {
        hideDoneBar();
        for (Line line : cartTable.getItems()) {
            if (line.item.getProductId().equals(p.getProductId())) {
                BigDecimal q = tryParse(line.quantity.get());
                line.quantity.set(NumberInput.text((q == null ? BigDecimal.ZERO : q).add(BigDecimal.ONE)));
                line.available = p.getQuantity();
                cartTable.getSelectionModel().select(line);
                cartTable.scrollTo(line);
                recalculate();
                showMessage("تمت زيادة كمية \"" + p.getNameAr() + "\" إلى " + line.quantity.get() + ".", false);
                return;
            }
        }
        SaleItem item = new SaleItem();
        item.setProductId(p.getProductId());
        item.setProductCode(p.getProductCode());
        item.setBarcode(p.getBarcode());
        item.setProductName(p.getNameAr());
        item.setUnitName(p.getUnitName());
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(MoneyUtil.of(saleType.priceOf(p)));
        item.setDiscountAmount(BigDecimal.ZERO);
        Line line = new Line(item, p.getSalePrice(), p.getWholesalePrice(), p.getQuantity(), false);
        cartTable.getItems().add(line);
        cartTable.getSelectionModel().select(line);
        cartTable.scrollTo(line);
        recalculate();
        showMessage("أُضيف \"" + p.getNameAr() + "\" إلى السلة.", false);
    }

    private void removeLine(Line line) {
        cartTable.getItems().remove(line);
        cartTable.refresh();   // renumber
        recalculate();
        showMessage("حُذف \"" + line.item.getProductName() + "\" من السلة.", false);
        searchField.requestFocus();
    }

    /** Retail ↔ wholesale: list prices follow; prices typed by hand are kept, and the cashier is told. */
    private void applySaleType(SaleType type) {
        if (type == saleType) {
            return;
        }
        int kept = 0;
        for (Line line : cartTable.getItems()) {
            if (line.manualPrice) {
                kept++;
            } else {
                line.price.set(NumberInput.text(line.listPrice(type)));
            }
        }
        saleType = type;
        recalculate();
        if (!cartTable.getItems().isEmpty()) {
            showMessage("تم تحديث الأسعار حسب قائمة " + (type == SaleType.WHOLESALE ? "الجملة" : "التجزئة") + "."
                    + (kept > 0 ? " أُبقي على السعر المعدّل يدويًا لـ " + kept + " صنف؛ عدّله إذا لزم." : ""), kept > 0);
        }
    }

    @FXML
    private void onClearCart() {
        if (cartTable.getItems().isEmpty()) {
            return;
        }
        ViewSupport.show(clearConfirmBox, true);
        clearButton.setDisable(true);
        confirmClearButton.requestFocus();
    }

    @FXML
    private void onConfirmClear() {
        ViewSupport.show(clearConfirmBox, false);
        clearButton.setDisable(false);
        resetCart();
        showMessage("تم مسح السلة.", false);
    }

    @FXML
    private void onCancelClear() {
        ViewSupport.show(clearConfirmBox, false);
        clearButton.setDisable(false);
        searchField.requestFocus();
    }

    /** A new, empty sale (new request id, walk-in customer, retail, cash). */
    private void resetCart() {
        cartTable.getItems().clear();
        draftId = null;
        requestId = UUID.randomUUID();
        settingText = true;
        discountField.clear();
        paidField.clear();
        receivedField.clear();
        settingText = false;
        savedSnapshot = null;
        retailToggle.setSelected(true);
        saleType = SaleType.RETAIL;
        if (walkIn != null) {
            selectCustomer(walkIn);
        }
        paymentTypeCombo.setValue(PaymentType.CASH);
        ViewSupport.show(overrideBox, false);
        recalculate();
        showSuggestedNumber();
        searchField.requestFocus();
    }

    // ======================= Customer =======================

    private void setUpCustomerSearch() {
        customerField.textProperty().addListener((o, a, b) -> customerDelay.playFromStart());
        customerDelay.setOnFinished(e -> suggestCustomers(false));
        customerField.setOnAction(e -> suggestCustomers(true));
        customerField.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) {
                customerPopup.hide();
            }
        });
    }

    private void suggestCustomers(boolean pickSingle) {
        String text = customerField.getText();
        if (text == null || text.isBlank()) {
            customerPopup.hide();
            return;
        }
        Async.run(() -> sales.searchCustomers(text.trim()), results -> {
            if (!text.equals(customerField.getText())) {
                return;
            }
            if (pickSingle && results.size() == 1) {
                selectCustomer(results.get(0));
                return;
            }
            fill(customerPopup, results, "لا يوجد عميل نشط مطابق", c -> {
                Label name = new Label(c.getName());
                name.getStyleClass().add("cell-primary");
                Label info = new Label(c.getCustomerCode() + (c.getPhone() == null ? "" : "  •  "
                        + PhoneNumbers.format(c.getPhone())) + "  •  " + c.getCustomerType().getLabelAr());
                info.getStyleClass().add("cell-secondary");
                return new VBox(1, name, info);
            }, this::selectCustomer, customerField);
        }, error -> customerPopup.hide());
    }

    private void selectCustomer(Customer c) {
        customer = c;
        customerPopup.hide();
        customerField.clear();
        customerNameLabel.setText(c.getName());
        boolean cash = c.isCashCustomer();
        customerInfoLabel.setText(cash ? "بيع نقدي: الدفع بالكامل فقط."
                : c.getCustomerCode() + (c.getPhone() == null ? "" : "  •  " + PhoneNumbers.format(c.getPhone())));
        ViewSupport.show(walkInButton, !cash);
        setPaymentTypes(cash);
        ViewSupport.show(overrideBox, false);
        if (!cash && security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW)) {
            int id = c.getCustomerId();
            Async.run(() -> AppContext.get().customers().creditStatus(id), credit -> {
                if (customer != null && customer.getCustomerId() == id) {
                    customerInfoLabel.setText(customerInfoLabel.getText() + "\n" + creditText(credit));
                }
            }, e -> { });
        }
        if (!cash) {
            showMessage("العميل: " + c.getName(), false);
        }
        searchField.requestFocus();
    }

    private static String creditText(CreditStatus credit) {
        if (credit.creditLimit().signum() == 0) {
            return "الرصيد " + MoneyUtil.format(credit.currentDebt()) + "  •  بلا حد ائتمان (نقدي فقط)";
        }
        return "الرصيد " + MoneyUtil.format(credit.currentDebt()) + "  •  الحد " + MoneyUtil.format(credit.creditLimit())
                + "  •  المتاح " + MoneyUtil.format(credit.availableCredit());
    }

    @FXML
    private void onWalkIn() {
        if (walkIn != null) {
            selectCustomer(walkIn);
            showMessage("العميل: عميل نقدي.", false);
        }
    }

    // ======================= Payment and totals =======================

    /** The walk-in customer pays in full: credit and partial are not offered. */
    private void setPaymentTypes(boolean walkInCustomer) {
        PaymentType current = paymentTypeCombo.getValue();
        List<PaymentType> types = new ArrayList<>(List.of(PaymentType.CASH, PaymentType.KNET, PaymentType.BANK_TRANSFER,
                PaymentType.CHEQUE));
        if (!walkInCustomer) {
            types.add(PaymentType.CREDIT);
            types.add(PaymentType.PARTIAL);
        }
        paymentTypeCombo.getItems().setAll(types);
        paymentTypeCombo.setValue(current != null && types.contains(current) ? current : PaymentType.CASH);
    }

    private void updatePaymentFields() {
        PaymentType t = paymentTypeCombo.getValue();
        ViewSupport.show(partialBox, t == PaymentType.PARTIAL);
        ViewSupport.show(cashBox, t == PaymentType.CASH);
        paymentHint.setText(t == null ? "" : switch (t) {
            case CREDIT -> "آجل: يُسجَّل كامل المبلغ على حساب العميل (ضمن حد الائتمان).";
            case PARTIAL -> "دفعة جزئية: يُحصَّل جزء الآن ويُسجَّل الباقي على حساب العميل.";
            case CASH -> "نقدًا: يُحصَّل كامل المبلغ. أدخل المبلغ المستلم لحساب الباقي للعميل.";
            default -> "يُحصَّل كامل المبلغ الآن بـ" + t.getLabelAr() + ".";
        });
    }

    /** Applies the typed values to the items and refreshes line and invoice totals. */
    private void recalculate() {
        if (cartTable == null || settingText) {
            return;
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        int units = 0;
        for (Line line : cartTable.getItems()) {
            BigDecimal q = tryParse(line.quantity.get());
            BigDecimal p = tryParse(line.price.get());
            BigDecimal d = tryParse(line.discount.get());
            line.item.setQuantity(q);
            line.item.setUnitPrice(p);
            line.item.setDiscountAmount(d == null ? BigDecimal.ZERO : d);
            line.manualPrice = p != null && p.compareTo(line.listPrice(saleType)) != 0;
            BigDecimal total = q == null || p == null ? null : line.item.getLineTotal();
            line.total.set(total == null ? "—" : MoneyUtil.format(total));
            if (total != null) {
                subtotal = subtotal.add(total);
            }
            boolean short_ = q != null && line.available != null && q.compareTo(line.available) > 0;
            line.shortage.set(short_);
            line.stockText.set("المتوفر: " + QuantityUtil.format(line.available)
                    + (short_ ? "  —  الكمية المطلوبة غير متوفرة" : "")
                    + (line.manualPrice ? "  •  سعر معدّل (القائمة " + MoneyUtil.format(line.listPrice(saleType)) + ")" : ""));
            units++;
        }
        cartCountLabel.setText(units == 0 ? "السلة فارغة" : units + " صنف في السلة");
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
        totalLabel.setText(MoneyUtil.formatWithCurrency(total));
        totalLabel.getStyleClass().remove("negative");
        if (total.signum() < 0) {
            totalLabel.getStyleClass().add("negative");
        }
        paidLabel.setText(MoneyUtil.format(paid));
        remainingLabel.setText(MoneyUtil.format(total.subtract(paid)));
        BigDecimal received = tryParse(receivedField.getText());
        lastChange = received == null || t != PaymentType.CASH ? null : MoneyUtil.of(received.subtract(total));
        changeLabel.setText(lastChange == null ? "—" : MoneyUtil.format(lastChange));
        changeLabel.getStyleClass().remove("negative");
        if (lastChange != null && lastChange.signum() < 0) {
            changeLabel.getStyleClass().add("negative");
        }
        completeButton.setDisable(busy || units == 0 || !security.hasPermission(Permission.SALES_POST));
        draftButton.setDisable(busy || units == 0);
    }

    private static BigDecimal tryParse(String text) {
        try {
            return NumberInput.parse(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ======================= Completing the sale =======================

    @FXML
    private void onComplete() {
        complete(false);
    }

    @FXML
    private void onOverrideCredit() {
        ViewSupport.show(overrideBox, false);
        complete(true);
    }

    @FXML
    private void onCancelOverride() {
        ViewSupport.show(overrideBox, false);
        showMessage("لم يتم البيع. يمكنك تحصيل مبلغ أكبر أو اختيار طريقة دفع أخرى.", false);
    }

    @FXML
    private void onSaveDraft() {
        if (busy) {
            return;
        }
        Sale s = buildSale();
        if (s == null) {
            return;
        }
        PaymentType type = paymentTypeCombo.getValue();
        setBusy(true);
        Async.run(() -> sales.saveDraft(s, type), saved -> {
            setBusy(false);
            resetCart();
            showMessage("تم تعليق الفاتورة كمسودة " + saved.getSaleNo()
                    + " دون أي تأثير على المخزون أو الحسابات. تابعها من شاشة المبيعات (الحالة: مسودة).", false);
            proceedWithLeave();
        }, this::showError);
    }

    private void complete(boolean overrideCredit) {
        if (busy) {
            return;   // a second F10 / click while the first one is still running
        }
        ViewSupport.show(overrideBox, false);
        Sale s = buildSale();
        if (s == null) {
            return;
        }
        List<String> short_ = cartTable.getItems().stream().filter(l -> l.shortage.get())
                .map(l -> "\"" + l.item.getProductName() + "\": المتوفر " + QuantityUtil.format(l.available)
                        + "، المطلوب " + QuantityUtil.format(l.item.getQuantity())).toList();
        if (!short_.isEmpty()) {
            showMessage(InsufficientStockException.MESSAGE + ":\n• " + String.join("\n• ", short_), true);
            return;
        }
        PaymentType type = paymentTypeCombo.getValue();
        BigDecimal change = lastChange;
        if (change != null && change.signum() < 0) {
            showMessage("المبلغ المستلم أقل من الإجمالي.", true);
            return;
        }
        setBusy(true);
        Async.run(() -> sales.saveAndPost(s, type, overrideCredit), saved -> {
            setBusy(false);
            lastSale = saved;
            resetCart();
            hideMessage();
            lastSaleLabel.setGraphic(com.almahwar.controller.support.Icon.of("check"));
            lastSaleLabel.setText("تم إتمام البيع " + saved.getSaleNo() + "  •  الإجمالي "
                    + MoneyUtil.formatWithCurrency(saved.getTotalAmount()) + "  •  المدفوع " + MoneyUtil.format(saved.getPaidAmount())
                    + (saved.getRemainingAmount().signum() > 0 ? "  •  على الحساب " + MoneyUtil.format(saved.getRemainingAmount()) : "")
                    + (change != null && change.signum() > 0 ? "  •  الباقي للعميل " + MoneyUtil.format(change) : ""));
            ViewSupport.show(lastSaleBar, true);
            proceedWithLeave();
        }, this::showError);
    }

    private void showError(Throwable error) {
        setBusy(false);
        pendingLeave = null;   // nothing was saved: stay here with the cart
        if (error instanceof CreditLimitExceededException ce) {
            if (ce.isOverridable()) {
                overrideLabel.setText(ce.getMessage() + "\nلديك صلاحية تجاوز حد الائتمان؛ سيُسجَّل التجاوز في سجل العمليات.");
                ViewSupport.show(overrideBox, true);
                overrideButton.requestFocus();
                hideMessage();
            } else {
                showMessage(ce.getMessage(), true);
            }
        } else if (error instanceof InsufficientStockException se) {
            for (InsufficientStockException.Shortage s : se.getShortages()) {
                cartTable.getItems().stream().filter(l -> l.item.getProductId() == s.productId())
                        .forEach(l -> l.available = s.available());
            }
            recalculate();
            showMessage(se.getMessage(), true);
        } else if (error instanceof ValidationException ve) {
            showMessage(String.join("\n", ve.getErrors().values()), true);
        } else {
            showMessage(ErrorMessages.of(error) + " لم يُحفظ أي شيء.", true);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        completeButton.setText(value ? "جارٍ الحفظ..." : "إتمام البيع   (F10)");
        recalculate();
    }

    /** The cart as a sale, or {@code null} (with a message) when something typed is not a number. */
    private Sale buildSale() {
        recalculate();
        hideDoneBar();
        if (cartTable.getItems().isEmpty()) {
            showMessage("السلة فارغة؛ أضف صنفًا واحدًا على الأقل.", true);
            return null;
        }
        if (customer == null) {
            showMessage("جارٍ تحميل العميل النقدي؛ حاول بعد لحظة.", true);
            return null;
        }
        for (Line line : cartTable.getItems()) {
            if (line.item.getQuantity() == null || line.item.getUnitPrice() == null
                    || (!line.discount.get().isBlank() && tryParse(line.discount.get()) == null)) {
                showMessage("السطر " + (cartTable.getItems().indexOf(line) + 1) + " (" + line.item.getProductName()
                        + "): أدخل الكمية والسعر والخصم بأرقام صحيحة.", true);
                return null;
            }
        }
        BigDecimal discount = tryParse(discountField.getText());
        if (discount == null && !discountField.getText().isBlank()) {
            showMessage("خصم الفاتورة: أدخل رقمًا صحيحًا.", true);
            return null;
        }
        Sale s = new Sale();
        s.setSaleId(draftId);
        s.setRequestId(requestId);
        s.setCustomerId(customer.getCustomerId());
        s.setSaleType(saleType);
        s.setDiscountAmount(discount == null ? BigDecimal.ZERO : discount);
        s.setItems(cartTable.getItems().stream().map(l -> l.item.copy()).toList());
        if (paymentTypeCombo.getValue() == PaymentType.PARTIAL) {
            BigDecimal paid = tryParse(paidField.getText());
            s.setPaymentMethod(partialMethodCombo.getValue());
            s.setPaidAmount(paid);
        }
        return s;
    }

    // ======================= Held drafts =======================

    private void loadDraft(int saleId) {
        Async.run(() -> {
            Sale s = sales.findById(saleId).orElse(null);
            if (s == null || !s.isDraft()) {
                return new Object[]{s, null, null};
            }
            List<Product> products = new ArrayList<>();
            for (SaleItem i : s.getItems()) {
                products.add(sales.findByScan(i.getProductCode()).orElse(null));
            }
            Customer c = AppContext.get().customers().findById(s.getCustomerId()).orElse(null);
            return new Object[]{s, products, c};
        }, data -> {
            Sale s = (Sale) data[0];
            if (s == null || !s.isDraft()) {
                showMessage("لا يمكن متابعة هذه الفاتورة؛ الفواتير المعتمدة أو الملغاة لا تُعدّل.", true);
                return;
            }
            @SuppressWarnings("unchecked") List<Product> products = (List<Product>) data[1];
            cartTable.getItems().clear();
            draftId = s.getSaleId();
            requestId = s.getRequestId();
            (s.getSaleType() == SaleType.WHOLESALE ? wholesaleToggle : retailToggle).setSelected(true);
            saleType = s.getSaleType();
            Customer c = (Customer) data[2];
            selectCustomer(c != null && c.isActive() ? c : walkIn);
            for (int i = 0; i < s.getItems().size(); i++) {
                SaleItem item = s.getItems().get(i).copy();
                Product p = products.get(i);
                cartTable.getItems().add(new Line(item, p == null ? item.getUnitPrice() : p.getSalePrice(),
                        p == null ? null : p.getWholesalePrice(), p == null ? BigDecimal.ZERO : p.getQuantity(), false));
            }
            settingText = true;
            discountField.setText(s.getDiscountAmount().signum() == 0 ? "" : NumberInput.text(s.getDiscountAmount()));
            settingText = false;
            PaymentType type = s.getPaymentType();
            paymentTypeCombo.setValue(type != null && paymentTypeCombo.getItems().contains(type) ? type : PaymentType.CASH);
            if (type == PaymentType.PARTIAL) {
                partialMethodCombo.setValue(s.getPaymentMethod());
                paidField.setText(NumberInput.text(s.getPaidAmount()));
            }
            subtitleLabel.setText("متابعة المسودة " + s.getSaleNo() + " — لم تؤثر بعد على المخزون أو الحسابات.");
            recalculate();
            savedSnapshot = snapshot();
            String notice = draftNotice;
            draftNotice = null;
            showMessage("تم تحميل المسودة " + s.getSaleNo() + ". راجع السلة ثم أتمم البيع (F10)."
                    + (notice == null ? "" : "\n" + notice), notice != null && notice.contains("⚠"));
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    // ======================= Messages =======================

    private void showMessage(String text, boolean error) {
        posMessage.setText(text);
        posMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(posMessage, true);
    }

    private void hideMessage() {
        ViewSupport.show(posMessage, false);
    }

    private void hideDoneBar() {
        ViewSupport.show(lastSaleBar, false);
    }

    @FXML
    private void onViewLastSale() {
        if (lastSale != null) {
            showSaleDetails(lastSale.getSaleId(), null);
        }
    }

    @FXML
    private void onPrintLastSale() {
        if (lastSale != null) {
            showSalePrint(lastSale.getSaleId());
        }
    }

    @FXML
    private void onBack() {
        if (parent != null) {
            Navigator.leave(() -> parent.closeSale(null));
        }
    }

    // ======================= Leave guard =======================

    /** Everything a completed sale or a draft would contain, to tell an unchanged held draft apart. */
    private String snapshot() {
        StringBuilder b = new StringBuilder();
        b.append(customer == null ? null : customer.getCustomerId()).append('|').append(saleType).append('|')
                .append(discountField.getText()).append('|').append(paymentTypeCombo.getValue()).append('|')
                .append(partialMethodCombo.getValue()).append('|').append(paidField.getText());
        for (Line l : cartTable.getItems()) {
            b.append('|').append(l.item.getProductId()).append(':').append(l.quantity.get()).append(':')
                    .append(l.price.get()).append(':').append(l.discount.get());
        }
        return b.toString();
    }

    @Override
    public boolean hasUnsavedWork() {
        return !cartTable.getItems().isEmpty() && !Objects.equals(snapshot(), savedSnapshot);
    }

    @Override
    public void askToLeave(Runnable leave) {
        if (busy) {
            showMessage("جارٍ حفظ الفاتورة؛ انتظر لحظة ثم أعد المحاولة.", true);
            return;
        }
        closeSale(null);   // details / preview opened over the POS: bring the cart back into view
        pendingLeave = leave;
        ViewSupport.show(leaveClearConfirm, false);
        ViewSupport.show(leaveBox, true);
        leaveStayButton.requestFocus();
    }

    @Override
    public void saveBeforeForcedExit(Runnable then) {
        hideLeaveBox();
        pendingLeave = null;
        Sale s = busy ? null : buildSale();
        if (s == null) {
            then.run();
            return;
        }
        PaymentType type = paymentTypeCombo.getValue();
        busy = true;
        Async.run(() -> sales.saveDraft(s, type), saved -> {
            busy = false;
            resetCart();
            then.run();
        }, error -> {
            busy = false;
            then.run();
        });
    }

    private void proceedWithLeave() {
        Runnable leave = pendingLeave;
        pendingLeave = null;
        hideLeaveBox();
        if (leave != null) {
            leave.run();
        }
    }

    private void hideLeaveBox() {
        ViewSupport.show(leaveBox, false);
        ViewSupport.show(leaveClearConfirm, false);
    }

    @FXML
    private void onLeaveComplete() {
        ViewSupport.show(leaveBox, false);
        complete(false);   // on success the waiting navigation continues; on failure the cart stays here
    }

    @FXML
    private void onLeaveDraft() {
        ViewSupport.show(leaveBox, false);
        onSaveDraft();
    }

    @FXML
    private void onLeaveStay() {
        pendingLeave = null;
        hideLeaveBox();
        searchField.requestFocus();
    }

    @FXML
    private void onLeaveClear() {
        ViewSupport.show(leaveClearConfirm, true);
        leaveClearConfirmButton.requestFocus();
    }

    @FXML
    private void onLeaveClearConfirmed() {
        resetCart();
        proceedWithLeave();
    }

    @FXML
    private void onLeaveClearCancelled() {
        ViewSupport.show(leaveClearConfirm, false);
        leaveStayButton.requestFocus();
    }

    // ======================= SalePages (details / print open over the POS) =======================

    @Override
    public void closeSale(String message) {
        pageHost.getChildren().removeIf(n -> n != posPage);
        posPage.setVisible(true);
        if (message != null) {
            showMessage(message, false);
        }
        searchField.requestFocus();
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
        closeSale(null);
        if (cartTable.getItems().isEmpty()) {
            loadDraft(draftSaleId);
        } else {
            showMessage("السلة الحالية غير فارغة؛ أتمم البيع أو امسح السلة ثم افتح المسودة.", true);
        }
    }

    @Override
    public boolean canOpenPos() {
        return true;
    }

    private void replacePage(Parent page) {
        productPopup.hide();
        customerPopup.hide();
        pageHost.getChildren().removeIf(n -> n != posPage);
        posPage.setVisible(false);
        pageHost.getChildren().add(page);
    }
}
