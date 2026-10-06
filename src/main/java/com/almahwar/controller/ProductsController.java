package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.MasterDataPane.CheckInput;
import com.almahwar.controller.MasterDataPane.Column;
import com.almahwar.controller.MasterDataPane.Spec;
import com.almahwar.controller.MasterDataPane.TextInput;
import com.almahwar.controller.ProductFormController.Mode;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.Unit;
import com.almahwar.service.CatalogService;
import com.almahwar.service.ProductService;
import com.almahwar.service.SecurityContext;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Products module: searchable/filterable product list, and the categories,
 * brands and units tabs. Add/edit/view opens {@code product-form.fxml} in place.
 * Users with only "عرض المنتجات" (e.g. the cashier) get a read-only list of active products.
 */
public class ProductsController {

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Label subtitleLabel;
    @FXML private Label listMessage;
    @FXML private Button addButton;
    @FXML private TabPane tabs;
    @FXML private Tab categoriesTab;
    @FXML private Tab brandsTab;
    @FXML private Tab unitsTab;

    @FXML private TextField searchField;
    @FXML private ComboBox<Category> categoryFilter;
    @FXML private ComboBox<Brand> brandFilter;
    @FXML private ComboBox<Unit> unitFilter;
    @FXML private ComboBox<ActiveStatus> statusFilter;
    @FXML private CheckBox lowStockFilter;

    @FXML private TableView<Product> productsTable;
    @FXML private TableColumn<Product, String> codeColumn;
    @FXML private TableColumn<Product, String> barcodeColumn;
    @FXML private TableColumn<Product, String> nameColumn;
    @FXML private TableColumn<Product, String> categoryColumn;
    @FXML private TableColumn<Product, String> brandColumn;
    @FXML private TableColumn<Product, String> unitColumn;
    @FXML private TableColumn<Product, String> priceColumn;
    @FXML private TableColumn<Product, Product> quantityColumn;
    @FXML private TableColumn<Product, String> minimumColumn;
    @FXML private TableColumn<Product, Product> statusColumn;
    @FXML private Label countLabel;
    @FXML private Button viewButton;
    @FXML private Button editButton;
    @FXML private Button toggleButton;

    private final SecurityContext security = AppContext.get().security();
    private final ProductService products = AppContext.get().products();
    private final CatalogService catalog = AppContext.get().catalog();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    private boolean canManage;
    private boolean loadingFilters;

    @FXML
    private void initialize() {
        canManage = security.hasPermission(Permission.PRODUCTS);
        subtitleLabel.setText(canManage
                ? "إدارة الأصناف والأسعار والأقسام والماركات والوحدات"
                : "البحث في الأصناف المتاحة للبيع وأسعارها");
        show(addButton, canManage);
        show(editButton, canManage);
        show(toggleButton, canManage);

        setUpFilters();
        setUpTable();
        if (canManage) {
            setUpCatalogTabs();
        } else {
            tabs.getTabs().removeAll(categoriesTab, brandsTab, unitsTab);
        }
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, old, index) -> {
            show(addButton, canManage && index.intValue() == 0);
            if (index.intValue() == 0) {
                loadFilterLists();   // pick up categories/brands/units added in the other tabs
            }
        });
        loadFilterLists();
        refresh(null);
    }

    /** Opens the list already filtered to low-stock products (from the dashboard or the inventory page). */
    public void showLowStockOnly() {
        lowStockFilter.setSelected(true);
    }

    // ---------- Filters ----------

    private void setUpFilters() {
        statusFilter.getItems().setAll(ActiveStatus.values());
        statusFilter.setConverter(ViewSupport.converter(ActiveStatus::getLabelAr));
        statusFilter.setValue(canManage ? ActiveStatus.ALL : ActiveStatus.ACTIVE);
        statusFilter.setDisable(!canManage);

        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh(null));
        searchField.setOnAction(e -> refresh(null));
        for (ComboBox<?> combo : List.of(categoryFilter, brandFilter, unitFilter, statusFilter)) {
            combo.valueProperty().addListener((o, a, b) -> {
                if (!loadingFilters) {
                    refresh(null);
                }
            });
        }
        lowStockFilter.selectedProperty().addListener((o, a, b) -> refresh(null));
    }

    private void loadFilterLists() {
        Async.run(() -> new Object[]{catalog.categories(null, false), catalog.brands(null, false),
                catalog.units(null, false)}, lists -> {
            loadingFilters = true;
            @SuppressWarnings("unchecked") List<Category> categories = (List<Category>) lists[0];
            @SuppressWarnings("unchecked") List<Brand> brands = (List<Brand>) lists[1];
            @SuppressWarnings("unchecked") List<Unit> units = (List<Unit>) lists[2];
            fillFilter(categoryFilter, categories, ViewSupport.allCategories(), Category::getCategoryId);
            fillFilter(brandFilter, brands, ViewSupport.allBrands(), Brand::getBrandId);
            fillFilter(unitFilter, units, ViewSupport.allUnits(), Unit::getUnitId);
            loadingFilters = false;
        }, error -> ErrorMessages.show("تحميل القوائم", error));
    }

    /** Fills a filter with "all" first, keeping the current choice if it still exists. */
    private static <T> void fillFilter(ComboBox<T> combo, List<T> items, T all, Function<T, Integer> id) {
        Integer selected = combo.getValue() == null ? null : id.apply(combo.getValue());
        List<T> list = new ArrayList<>();
        list.add(all);
        list.addAll(items);
        combo.getItems().setAll(list);
        combo.setValue(list.stream().filter(x -> selected != null && selected.equals(id.apply(x)))
                .findFirst().orElse(all));
    }

    private ProductFilter currentFilter() {
        return new ProductFilter(searchField.getText(),
                idOf(categoryFilter.getValue(), Category::getCategoryId),
                idOf(brandFilter.getValue(), Brand::getBrandId),
                idOf(unitFilter.getValue(), Unit::getUnitId),
                statusFilter.getValue(), lowStockFilter.isSelected());
    }

    private static <T> Integer idOf(T value, Function<T, Integer> id) {
        return value == null ? null : id.apply(value);
    }

    @FXML
    private void onClearFilters() {
        loadingFilters = true;
        searchField.clear();
        categoryFilter.getSelectionModel().selectFirst();
        brandFilter.getSelectionModel().selectFirst();
        unitFilter.getSelectionModel().selectFirst();
        statusFilter.setValue(canManage ? ActiveStatus.ALL : ActiveStatus.ACTIVE);
        loadingFilters = false;
        lowStockFilter.setSelected(false);
        refresh(null);
    }

    // ---------- Table ----------

    private void setUpTable() {
        productsTable.setPlaceholder(new Label("لا توجد منتجات مطابقة"));
        productsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        codeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getProductCode()));
        barcodeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getBarcode()));
        nameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getNameAr()));
        categoryColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getCategoryName()));
        brandColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getBrandName()));
        unitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUnitName()));
        priceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().getSalePrice())));
        priceColumn.setCellFactory(col -> ViewSupport.textCell("money-cell"));
        quantityColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        quantityColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Product p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().remove("qty-low");
                if (empty || p == null) {
                    setText(null);
                    return;
                }
                setText(QuantityUtil.format(p.getQuantity()));
                if (p.isLowStock()) {
                    getStyleClass().add("qty-low");
                }
            }
        });
        minimumColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().getMinimumStock())));
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Product p, boolean empty) {
                super.updateItem(p, empty);
                setText(null);
                if (empty || p == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(4, statusBadge(p.isActive()));
                if (p.isActive() && p.isLowStock()) {
                    box.getChildren().add(ViewSupport.badge("منخفض", "badge-warning"));
                }
                setGraphic(box);
            }
        });
        codeColumn.setMinWidth(80);
        nameColumn.setMinWidth(170);
        statusColumn.setMinWidth(110);

        productsTable.setRowFactory(tv -> {
            TableRow<Product> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    openForm(canManage ? Mode.EDIT : Mode.VIEW, row.getItem());
                }
            });
            return row;
        });
        productsTable.getSelectionModel().selectedItemProperty().addListener((o, old, p) -> updateButtons(p));
        updateButtons(null);
    }

    private void updateButtons(Product p) {
        viewButton.setDisable(p == null);
        editButton.setDisable(p == null);
        toggleButton.setDisable(p == null);
        toggleButton.setText(p != null && !p.isActive() ? "تفعيل" : "تعطيل");
        toggleButton.getStyleClass().removeAll("btn-danger", "btn-secondary");
        toggleButton.getStyleClass().add(p != null && p.isActive() ? "btn-danger" : "btn-secondary");
    }

    private void refresh(Integer selectId) {
        ProductFilter filter = currentFilter();
        Async.run(() -> products.search(filter), rows -> {
            productsTable.getItems().setAll(rows);
            long low = rows.stream().filter(p -> p.isActive() && p.isLowStock()).count();
            countLabel.setText(rows.size() + " منتج" + (low > 0 ? "  •  " + low + " منخفض المخزون" : ""));
            if (selectId != null) {
                rows.stream().filter(p -> selectId.equals(p.getProductId())).findFirst().ifPresent(p -> {
                    productsTable.getSelectionModel().select(p);
                    productsTable.scrollTo(p);
                });
            }
        }, error -> ErrorMessages.show("تحميل المنتجات", error));
    }

    // ---------- Actions ----------

    @FXML
    private void onAdd() {
        openForm(Mode.ADD, null);
    }

    @FXML
    private void onView() {
        Product p = productsTable.getSelectionModel().getSelectedItem();
        if (p != null) {
            openForm(Mode.VIEW, p);
        }
    }

    @FXML
    private void onEdit() {
        Product p = productsTable.getSelectionModel().getSelectedItem();
        if (p != null) {
            openForm(Mode.EDIT, p);
        }
    }

    @FXML
    private void onToggleActive() {
        Product p = productsTable.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        boolean activate = !p.isActive();
        if (!activate && !AlertUtil.confirm("تعطيل منتج",
                "سيتم إخفاء \"" + p.getNameAr() + "\" من البيع والبحث، مع الاحتفاظ بكل حركاته.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> products.setActive(p.getProductId(), activate), () -> {
                    showMessage((activate ? "تم تفعيل" : "تم تعطيل") + " المنتج " + '"' + p.getNameAr() + '"' + ".");
                    refresh(p.getProductId());
                },
                error -> ErrorMessages.show("تغيير حالة المنتج", error));
    }

    /** Replaces the list with the form; the list comes back when the form closes. */
    private void openForm(Mode mode, Product product) {
        Parent form = ViewLoader.<ProductFormController>load("product-form.fxml", c ->
                c.open(mode, product == null ? null : product.getProductId(), (savedId, message) -> {
                    pageHost.getChildren().removeIf(n -> n != listPage);
                    listPage.setVisible(true);
                    showMessage(message);
                    refresh(savedId);
                }));
        showMessage(null);
        listPage.setVisible(false);
        pageHost.getChildren().add(form);
    }

    // ---------- Categories / brands / units ----------

    private void setUpCatalogTabs() {
        categoriesTab.setContent(new MasterDataPane<>(new Spec<Category>("قسم",
                Category::new, ProductsController::copy, Category::getCategoryId, Category::isActive,
                catalog::categories, catalog::saveCategory,
                (c, active) -> catalog.setCategoryActive(c.getCategoryId(), active),
                List.of(new Column<>("اسم القسم", Category::getNameAr, 180),
                        new Column<>("الاسم بالإنجليزي", Category::getNameEn, 150),
                        new Column<>("الوصف", Category::getDescription, 200)),
                List.of(new TextInput<>(CatalogService.NAME_AR, "اسم القسم بالعربي *", Category::getNameAr, Category::setNameAr),
                        new TextInput<>(CatalogService.NAME_EN, "الاسم بالإنجليزي", Category::getNameEn, Category::setNameEn),
                        new TextInput<>(CatalogService.DESCRIPTION, "الوصف", Category::getDescription, Category::setDescription))),
                true));

        brandsTab.setContent(new MasterDataPane<>(new Spec<Brand>("ماركة",
                Brand::new, ProductsController::copy, Brand::getBrandId, Brand::isActive,
                catalog::brands, catalog::saveBrand,
                (b, active) -> catalog.setBrandActive(b.getBrandId(), active),
                List.of(new Column<>("اسم الماركة", Brand::getNameAr, 180),
                        new Column<>("الاسم بالإنجليزي", Brand::getNameEn, 150),
                        new Column<>("بلد المنشأ", Brand::getCountry, 130)),
                List.of(new TextInput<>(CatalogService.NAME_AR, "اسم الماركة *", Brand::getNameAr, Brand::setNameAr),
                        new TextInput<>(CatalogService.NAME_EN, "الاسم بالإنجليزي", Brand::getNameEn, Brand::setNameEn),
                        new TextInput<>(CatalogService.COUNTRY, "بلد المنشأ", Brand::getCountry, Brand::setCountry))),
                true));

        unitsTab.setContent(new MasterDataPane<>(new Spec<Unit>("وحدة",
                Unit::new, ProductsController::copy, Unit::getUnitId, Unit::isActive,
                catalog::units, catalog::saveUnit,
                (u, active) -> catalog.setUnitActive(u.getUnitId(), active),
                List.of(new Column<>("اسم الوحدة", Unit::getNameAr, 150),
                        new Column<>("الاسم بالإنجليزي", Unit::getNameEn, 130),
                        new Column<>("الرمز", Unit::getSymbol, 80),
                        new Column<>("تقبل الكسور", u -> u.isAllowsDecimal() ? "نعم (مثل المتر)" : "لا", 120)),
                List.of(new TextInput<>(CatalogService.NAME_AR, "اسم الوحدة *", Unit::getNameAr, Unit::setNameAr),
                        new TextInput<>(CatalogService.NAME_EN, "الاسم بالإنجليزي", Unit::getNameEn, Unit::setNameEn),
                        new TextInput<>(CatalogService.SYMBOL, "الرمز", Unit::getSymbol, Unit::setSymbol),
                        new CheckInput<>("تقبل الكسور (مثل المتر والكيلو)", Unit::isAllowsDecimal, Unit::setAllowsDecimal))),
                true));
    }

    private static Category copy(Category c) {
        Category x = new Category();
        x.setCategoryId(c.getCategoryId());
        x.setNameAr(c.getNameAr());
        x.setNameEn(c.getNameEn());
        x.setParentCategoryId(c.getParentCategoryId());
        x.setDescription(c.getDescription());
        x.setActive(c.isActive());
        return x;
    }

    private static Brand copy(Brand b) {
        Brand x = new Brand();
        x.setBrandId(b.getBrandId());
        x.setNameAr(b.getNameAr());
        x.setNameEn(b.getNameEn());
        x.setCountry(b.getCountry());
        x.setActive(b.isActive());
        return x;
    }

    private static Unit copy(Unit u) {
        Unit x = new Unit();
        x.setUnitId(u.getUnitId());
        x.setNameAr(u.getNameAr());
        x.setNameEn(u.getNameEn());
        x.setSymbol(u.getSymbol());
        x.setAllowsDecimal(u.isAllowsDecimal());
        x.setActive(u.isActive());
        return x;
    }

    // ---------- Helpers ----------

    private void showMessage(String text) {
        listMessage.setText(text == null ? "" : text);
        listMessage.getStyleClass().setAll("label", "form-alert", "form-alert-info");
        show(listMessage, text != null);
    }

    static Label statusBadge(boolean active) {
        return ViewSupport.badge(active ? "نشط" : "معطّل", active ? "badge-success" : "badge-danger");
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
