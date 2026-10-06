package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.controller.support.ProductPicker;
import com.almahwar.model.Category;
import com.almahwar.model.MovementFilter;
import com.almahwar.model.MovementType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.StockAdjustment;
import com.almahwar.model.StockMovement;
import com.almahwar.service.CatalogService;
import com.almahwar.service.InventoryService;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.ValidationException;
import com.almahwar.util.QuantityUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inventory module, kept apart from product management: stock balances,
 * manual adjustments (ADJUSTMENT_IN / ADJUSTMENT_OUT with a required reason)
 * and the stock movement history.
 */
public class InventoryController {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d/M/yyyy  hh:mm a", Locale.forLanguageTag("ar"));

    @FXML private Label activeCountLabel;
    @FXML private Label lowCountLabel;
    @FXML private TabPane tabs;
    @FXML private Tab stockTab;
    @FXML private Tab adjustTab;
    @FXML private Tab historyTab;

    // Stock balances
    @FXML private TextField stockSearchField;
    @FXML private ComboBox<Category> stockCategoryFilter;
    @FXML private CheckBox stockLowOnly;
    @FXML private CheckBox stockShowInactive;
    @FXML private TableView<Product> stockTable;
    @FXML private TableColumn<Product, String> stCodeColumn;
    @FXML private TableColumn<Product, String> stNameColumn;
    @FXML private TableColumn<Product, String> stCategoryColumn;
    @FXML private TableColumn<Product, String> stLocationColumn;
    @FXML private TableColumn<Product, Product> stQuantityColumn;
    @FXML private TableColumn<Product, String> stUnitColumn;
    @FXML private TableColumn<Product, String> stMinimumColumn;
    @FXML private TableColumn<Product, Product> stStatusColumn;
    @FXML private Label stockCountLabel;
    @FXML private Button adjustSelectedButton;
    @FXML private Button historySelectedButton;

    // Adjustment
    @FXML private TextField adjProductField;
    @FXML private Label adjProductError;
    @FXML private GridPane adjProductInfo;
    @FXML private Label adjInfoName;
    @FXML private Label adjInfoQuantity;
    @FXML private Label adjInfoMinimum;
    @FXML private HBox adjTypeBox;
    @FXML private ToggleButton adjInToggle;
    @FXML private ToggleButton adjOutToggle;
    @FXML private Label adjTypeError;
    @FXML private TextField adjQuantityField;
    @FXML private Label adjQuantityError;
    @FXML private ComboBox<String> adjReasonCombo;
    @FXML private Label adjReasonError;
    @FXML private Label adjPreviewLabel;
    @FXML private Label adjAlert;
    @FXML private Button adjSaveButton;

    // History
    @FXML private TextField hisProductField;
    @FXML private ComboBox<MovementType> hisTypeFilter;
    @FXML private DatePicker hisFromDate;
    @FXML private DatePicker hisToDate;
    @FXML private Label hisAlert;
    @FXML private TableView<StockMovement> historyTable;
    @FXML private TableColumn<StockMovement, String> hDateColumn;
    @FXML private TableColumn<StockMovement, StockMovement> hProductColumn;
    @FXML private TableColumn<StockMovement, StockMovement> hTypeColumn;
    @FXML private TableColumn<StockMovement, StockMovement> hQuantityColumn;
    @FXML private TableColumn<StockMovement, String> hBeforeColumn;
    @FXML private TableColumn<StockMovement, String> hAfterColumn;
    @FXML private TableColumn<StockMovement, String> hReferenceColumn;
    @FXML private TableColumn<StockMovement, String> hReasonColumn;
    @FXML private TableColumn<StockMovement, String> hUserColumn;
    @FXML private Label historyCountLabel;

    private final SecurityContext security = AppContext.get().security();
    private final InventoryService inventory = AppContext.get().inventory();
    private final CatalogService catalog = AppContext.get().catalog();
    private final FormErrors adjErrors = new FormErrors();
    private final ToggleGroup adjTypeGroup = new ToggleGroup();
    private final PauseTransition stockSearchDelay = new PauseTransition(Duration.millis(300));

    private ProductPicker adjPicker;
    private ProductPicker historyPicker;
    private boolean canAdjust;

    @FXML
    private void initialize() {
        canAdjust = security.hasPermission(Permission.INVENTORY_ADJUST);
        if (!canAdjust) {
            tabs.getTabs().remove(adjustTab);
        }
        ViewSupport.show(adjustSelectedButton, canAdjust);

        setUpStock();
        if (canAdjust) {
            setUpAdjustment();
        }
        setUpHistory();
        refreshStock();
    }

    /** Opens on the stock list filtered to low-stock products. */
    public void showLowStockOnly() {
        tabs.getSelectionModel().select(stockTab);
        stockLowOnly.setSelected(true);
    }

    // ======================= Stock balances =======================

    private void setUpStock() {
        stockCategoryFilter.setConverter(ViewSupport.converter(Category::getNameAr));
        Async.run(() -> catalog.categories(null, false), categories -> {
            List<Category> items = new ArrayList<>();
            items.add(ViewSupport.allCategories());
            items.addAll(categories);
            stockCategoryFilter.getItems().setAll(items);
            stockCategoryFilter.getSelectionModel().selectFirst();
            stockCategoryFilter.valueProperty().addListener((o, a, b) -> refreshStock());
        }, error -> ErrorMessages.show("تحميل الأقسام", error));

        stockSearchField.textProperty().addListener((o, a, b) -> stockSearchDelay.playFromStart());
        stockSearchDelay.setOnFinished(e -> refreshStock());
        stockLowOnly.selectedProperty().addListener((o, a, b) -> refreshStock());
        stockShowInactive.selectedProperty().addListener((o, a, b) -> refreshStock());

        stockTable.setPlaceholder(new Label("لا توجد أصناف مطابقة"));
        stockTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        stCodeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getProductCode()));
        stNameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getNameAr()));
        stCategoryColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getCategoryName()));
        stLocationColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getLocation()));
        stUnitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUnitName()));
        stMinimumColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().getMinimumStock())));
        stQuantityColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        stQuantityColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Product p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().removeAll("qty-low", "qty-cell");
                if (empty || p == null) {
                    setText(null);
                    return;
                }
                setText(QuantityUtil.format(p.getQuantity()));
                getStyleClass().add(p.isLowStock() ? "qty-low" : "qty-cell");
            }
        });
        stStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        stStatusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Product p, boolean empty) {
                super.updateItem(p, empty);
                setText(null);
                setGraphic(empty || p == null ? null : stockBadge(p));
            }
        });
        stNameColumn.setMinWidth(170);
        stStatusColumn.setMinWidth(110);

        stockTable.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            adjustSelectedButton.setDisable(p == null || !p.isActive());
            historySelectedButton.setDisable(p == null);
        });
        adjustSelectedButton.setDisable(true);
        historySelectedButton.setDisable(true);
    }

    private static Label stockBadge(Product p) {
        if (!p.isActive()) {
            return ViewSupport.badge("معطّل", "badge-muted");
        }
        if (p.getQuantity().signum() == 0) {
            return ViewSupport.badge("نفد المخزون", "badge-danger");
        }
        if (p.isLowStock()) {
            return ViewSupport.badge("منخفض", "badge-warning");
        }
        return ViewSupport.badge("متوفر", "badge-success");
    }

    @FXML
    private void onRefreshStock() {
        refreshStock();
    }

    private void refreshStock() {
        Category category = stockCategoryFilter.getValue();
        ProductFilter filter = new ProductFilter(stockSearchField.getText(),
                category == null ? null : category.getCategoryId(), null, null,
                stockShowInactive.isSelected() ? ActiveStatus.ALL : ActiveStatus.ACTIVE, stockLowOnly.isSelected());
        Integer selectedId = stockTable.getSelectionModel().getSelectedItem() == null ? null
                : stockTable.getSelectionModel().getSelectedItem().getProductId();
        Async.run(() -> inventory.stockList(filter), rows -> {
            stockTable.getItems().setAll(rows);
            stockCountLabel.setText(rows.size() + " صنف");
            if (selectedId != null) {
                rows.stream().filter(p -> selectedId.equals(p.getProductId())).findFirst()
                        .ifPresent(p -> stockTable.getSelectionModel().select(p));
            }
        }, error -> ErrorMessages.show("تحميل المخزون", error));
        refreshCounts();
    }

    /** Same definition as the dashboard card: active products with quantity ≤ minimum stock. */
    private void refreshCounts() {
        Async.run(() -> new long[]{
                inventory.stockList(new ProductFilter(null, null, null, null, ActiveStatus.ACTIVE, false)).size(),
                inventory.countLowStock()
        }, counts -> {
            activeCountLabel.setText(String.valueOf(counts[0]));
            lowCountLabel.setText(String.valueOf(counts[1]));
        }, error -> { });
    }

    @FXML
    private void onAdjustSelected() {
        Product p = stockTable.getSelectionModel().getSelectedItem();
        if (p != null && canAdjust) {
            tabs.getSelectionModel().select(adjustTab);
            adjPicker.select(p);
            adjQuantityField.requestFocus();
        }
    }

    @FXML
    private void onHistorySelected() {
        Product p = stockTable.getSelectionModel().getSelectedItem();
        if (p != null) {
            tabs.getSelectionModel().select(historyTab);
            historyPicker.select(p);
            hisTypeFilter.getSelectionModel().selectFirst();
            hisFromDate.setValue(null);
            hisToDate.setValue(null);
            searchHistory();
        }
    }

    // ======================= Adjustment =======================

    private void setUpAdjustment() {
        adjPicker = new ProductPicker(adjProductField,
                text -> inventory.stockList(ProductFilter.search(text).withStatus(ActiveStatus.ACTIVE)));
        adjPicker.selectedProperty().addListener((o, a, p) -> showAdjustProduct(p));
        showAdjustProduct(null);

        adjInToggle.setToggleGroup(adjTypeGroup);
        adjOutToggle.setToggleGroup(adjTypeGroup);
        adjInToggle.setUserData(MovementType.ADJUSTMENT_IN);
        adjOutToggle.setUserData(MovementType.ADJUSTMENT_OUT);
        adjTypeGroup.selectedToggleProperty().addListener((o, a, b) -> updatePreview());

        NumberInput.install(adjQuantityField);
        adjQuantityField.textProperty().addListener((o, a, b) -> updatePreview());
        adjReasonCombo.getItems().setAll(inventory.suggestedReasons());

        adjErrors.register(InventoryService.PRODUCT, adjProductField, adjProductError)
                .register(InventoryService.TYPE, adjTypeBox, adjTypeError)
                .register(InventoryService.QUANTITY, adjQuantityField, adjQuantityError)
                .register(InventoryService.REASON, adjReasonCombo, adjReasonError);
    }

    private void showAdjustProduct(Product p) {
        ViewSupport.show(adjProductInfo, p != null);
        if (p != null) {
            adjInfoName.setText(p.getNameAr() + "  (" + p.getProductCode() + ")");
            adjInfoQuantity.setText(QuantityUtil.format(p.getQuantity()) + " " + p.getUnitName());
            adjInfoMinimum.setText(QuantityUtil.format(p.getMinimumStock()) + " " + p.getUnitName());
        }
        updatePreview();
    }

    private void updatePreview() {
        Product p = adjPicker == null ? null : adjPicker.getSelected();
        MovementType type = adjTypeGroup.getSelectedToggle() == null ? null
                : (MovementType) adjTypeGroup.getSelectedToggle().getUserData();
        BigDecimal qty;
        try {
            qty = NumberInput.parse(adjQuantityField.getText());
        } catch (NumberFormatException e) {
            qty = null;
        }
        adjPreviewLabel.getStyleClass().remove("preview-negative");
        if (p == null || type == null || qty == null || qty.signum() <= 0) {
            adjPreviewLabel.setText("");
            return;
        }
        BigDecimal after = type.isIncoming() ? p.getQuantity().add(qty) : p.getQuantity().subtract(qty);
        adjPreviewLabel.setText("الرصيد بعد التسوية: " + QuantityUtil.format(p.getQuantity()) + " → "
                + QuantityUtil.format(after) + " " + p.getUnitName());
        if (after.signum() < 0) {
            adjPreviewLabel.setText(adjPreviewLabel.getText() + "  —  لا يمكن أن يصبح المخزون بالسالب");
            adjPreviewLabel.getStyleClass().add("preview-negative");
        }
    }

    @FXML
    private void onSaveAdjustment() {
        adjErrors.clear();
        hideAlert(adjAlert);
        Product p = adjPicker.getSelected();
        MovementType type = adjTypeGroup.getSelectedToggle() == null ? null
                : (MovementType) adjTypeGroup.getSelectedToggle().getUserData();
        BigDecimal qty;
        try {
            qty = NumberInput.parse(adjQuantityField.getText());
        } catch (NumberFormatException e) {
            adjErrors.set(InventoryService.QUANTITY, "أدخل رقمًا صحيحًا.");
            return;
        }
        String reason = adjReasonCombo.getEditor().getText();
        StockAdjustment request = new StockAdjustment(p == null ? null : p.getProductId(), type, qty, reason);

        adjSaveButton.setDisable(true);
        Async.run(() -> inventory.adjust(request), movement -> {
            adjSaveButton.setDisable(false);
            p.setQuantity(movement.getQuantityAfter());
            showAdjustProduct(p);
            showAlert(adjAlert, "تم تنفيذ " + movement.getMovementType().getLabelAr() + " للصنف \""
                    + p.getNameAr() + "\": " + QuantityUtil.format(movement.getQuantityBefore()) + " → "
                    + QuantityUtil.format(movement.getQuantityAfter()) + " " + p.getUnitName()
                    + "  (رقم الحركة " + movement.getMovementId() + ")", false);
            adjQuantityField.clear();
            adjReasonCombo.getEditor().clear();
            adjReasonCombo.setValue(null);
            adjTypeGroup.selectToggle(null);
            refreshStock();
        }, error -> {
            adjSaveButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                String other = adjErrors.show(ve);
                if (other != null) {
                    showAlert(adjAlert, other, true);
                }
            } else {
                showAlert(adjAlert, ErrorMessages.of(error), true);
            }
        });
    }

    @FXML
    private void onClearAdjustment() {
        adjPicker.clear();
        adjTypeGroup.selectToggle(null);
        adjQuantityField.clear();
        adjReasonCombo.getEditor().clear();
        adjReasonCombo.setValue(null);
        adjErrors.clear();
        hideAlert(adjAlert);
    }

    // ======================= History =======================

    private void setUpHistory() {
        historyPicker = new ProductPicker(hisProductField,
                text -> inventory.stockList(ProductFilter.search(text)));
        List<MovementType> types = new ArrayList<>();
        types.add(null);
        types.addAll(List.of(MovementType.values()));
        hisTypeFilter.getItems().setAll(types);
        hisTypeFilter.setConverter(ViewSupport.converter(MovementType::getLabelAr));
        hisTypeFilter.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MovementType t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? "كل أنواع الحركات" : t.getLabelAr());
            }
        });
        hisTypeFilter.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MovementType t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty ? null : t == null ? "كل أنواع الحركات" : t.getLabelAr());
            }
        });
        hisTypeFilter.getSelectionModel().selectFirst();

        historyTable.setPlaceholder(new Label("لا توجد حركات مطابقة"));
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        hDateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().getMovementDate() == null ? "" : c.getValue().getMovementDate().format(WHEN)));
        hProductColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        hProductColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(StockMovement m, boolean empty) {
                super.updateItem(m, empty);
                setText(null);
                if (empty || m == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(m.getProductName());
                name.getStyleClass().add("cell-primary");
                Label code = new Label(m.getProductCode());
                code.getStyleClass().add("cell-secondary");
                setGraphic(new javafx.scene.layout.VBox(1, name, code));
            }
        });
        hTypeColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        hTypeColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(StockMovement m, boolean empty) {
                super.updateItem(m, empty);
                setText(null);
                setGraphic(empty || m == null ? null : typeBadge(m.getMovementType()));
            }
        });
        hQuantityColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        hQuantityColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(StockMovement m, boolean empty) {
                super.updateItem(m, empty);
                getStyleClass().removeAll("qty-in", "qty-out");
                if (empty || m == null) {
                    setText(null);
                    return;
                }
                boolean in = m.getQuantity().signum() > 0;
                setText((in ? "+" : "−") + QuantityUtil.format(m.getQuantity().abs()));
                getStyleClass().add(in ? "qty-in" : "qty-out");
            }
        });
        hBeforeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().getQuantityBefore())));
        hAfterColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().getQuantityAfter())));
        hReferenceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(reference(c.getValue())));
        hReasonColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getReason()));
        hUserColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUserName()));
        hDateColumn.setMinWidth(150);
        hProductColumn.setMinWidth(160);
        hTypeColumn.setMinWidth(115);

        searchHistory();
    }

    private static Label typeBadge(MovementType type) {
        String style = switch (type) {
            case OPENING_BALANCE -> "badge-info";
            case ADJUSTMENT_IN, ADJUSTMENT_OUT, DAMAGED -> "badge-warning";
            default -> type.isIncoming() ? "badge-success" : "badge-danger";
        };
        return ViewSupport.badge(type.getLabelAr(), style);
    }

    private static String reference(StockMovement m) {
        if (m.getReferenceType() == null) {
            return m.getMovementType().isManualAdjustment() ? "يدوي" : "";
        }
        String name = switch (m.getReferenceType()) {
            case "SALE" -> "فاتورة بيع";
            case "PURCHASE" -> "فاتورة شراء";
            case "SALE_RETURN" -> "مرتجع بيع";
            case "PURCHASE_RETURN" -> "مرتجع شراء";
            case "PRODUCT" -> "بطاقة الصنف";
            default -> m.getReferenceType();
        };
        return m.getReferenceId() == null ? name : name + " #" + m.getReferenceId();
    }

    @FXML
    private void onSearchHistory() {
        searchHistory();
    }

    @FXML
    private void onClearHistory() {
        historyPicker.clear();
        hisTypeFilter.getSelectionModel().selectFirst();
        hisFromDate.setValue(null);
        hisToDate.setValue(null);
        searchHistory();
    }

    private void searchHistory() {
        hideAlert(hisAlert);
        if (!hisProductField.getText().isBlank() && historyPicker.getSelected() == null) {
            showAlert(hisAlert, "اختر المنتج من نتائج البحث، أو امسح خانة المنتج لعرض كل المنتجات.", true);
            return;
        }
        Product p = historyPicker.getSelected();
        MovementFilter filter = new MovementFilter(p == null ? null : p.getProductId(),
                hisTypeFilter.getValue(), hisFromDate.getValue(), hisToDate.getValue());
        Async.run(() -> inventory.history(filter), rows -> {
            historyTable.getItems().setAll(rows);
            historyCountLabel.setText(rows.size() >= InventoryService.MAX_HISTORY_ROWS
                    ? "يعرض آخر " + rows.size() + " حركة؛ استخدم الفلاتر لتضييق النتائج."
                    : rows.size() + " حركة");
        }, error -> showAlert(hisAlert, ErrorMessages.of(error), true));
    }

    // ======================= Helpers =======================

    private static void showAlert(Label label, String text, boolean error) {
        label.setText(text);
        label.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(label, true);
    }

    private static void hideAlert(Label label) {
        ViewSupport.show(label, false);
    }
}
