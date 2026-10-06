package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Unit;
import com.almahwar.service.CatalogService;
import com.almahwar.service.ProductService;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.almahwar.service.ProductService.*;

/**
 * Add / edit / view one product. Quantity is only entered once, as the opening
 * balance of a new product; afterwards it changes through stock adjustments.
 */
public class ProductFormController {

    public enum Mode { ADD, EDIT, VIEW }

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d/M/yyyy  hh:mm a", Locale.forLanguageTag("ar"));

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label statusBadge;
    @FXML private Label formAlert;

    @FXML private TextField codeField;
    @FXML private Label codeError;
    @FXML private TextField barcodeField;
    @FXML private Label barcodeError;
    @FXML private TextField nameArField;
    @FXML private Label nameArError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;
    @FXML private ComboBox<Category> categoryCombo;
    @FXML private Label categoryError;
    @FXML private ComboBox<Brand> brandCombo;
    @FXML private Label brandError;
    @FXML private ComboBox<Unit> unitCombo;
    @FXML private Label unitError;
    @FXML private TextField sizeField;
    @FXML private Label sizeError;
    @FXML private TextField colorField;
    @FXML private Label colorError;

    @FXML private Label currencyLabel;
    @FXML private VBox purchasePriceBox;
    @FXML private TextField purchasePriceField;
    @FXML private Label purchasePriceError;
    @FXML private TextField salePriceField;
    @FXML private Label salePriceError;
    @FXML private TextField wholesalePriceField;
    @FXML private Label wholesalePriceError;
    @FXML private Label marginLabel;

    @FXML private Label quantityLabel;
    @FXML private TextField quantityField;
    @FXML private Label quantityError;
    @FXML private TextField minimumField;
    @FXML private Label minimumError;
    @FXML private TextField locationField;
    @FXML private Label locationError;
    @FXML private Label quantityHint;

    @FXML private TextArea notesArea;
    @FXML private Label notesError;
    @FXML private CheckBox activeCheck;
    @FXML private HBox recordInfo;
    @FXML private Label createdLabel;
    @FXML private Label updatedLabel;

    @FXML private Button saveButton;
    @FXML private Button editButton;

    private final SecurityContext security = AppContext.get().security();
    private final ProductService products = AppContext.get().products();
    private final CatalogService catalog = AppContext.get().catalog();
    private final FormErrors errors = new FormErrors();

    private Mode mode;
    private Product product;
    private BiConsumer<Integer, String> onClose;
    private boolean canSeeCost;

    @FXML
    private void initialize() {
        canSeeCost = security.hasPermission(Permission.PRODUCT_COST);
        currencyLabel.setText("(" + AppConfig.getInstance().currencyCode() + " - "
                + AppConfig.getInstance().currencySymbol() + ")");
        ViewSupport.show(purchasePriceBox, canSeeCost);

        errors.register(PRODUCT_CODE, codeField, codeError)
                .register(BARCODE, barcodeField, barcodeError)
                .register(NAME_AR, nameArField, nameArError)
                .register(NAME_EN, nameEnField, nameEnError)
                .register(CATEGORY, categoryCombo, categoryError)
                .register(BRAND, brandCombo, brandError)
                .register(UNIT, unitCombo, unitError)
                .register(SIZE, sizeField, sizeError)
                .register(COLOR, colorField, colorError)
                .register(PURCHASE_PRICE, purchasePriceField, purchasePriceError)
                .register(SALE_PRICE, salePriceField, salePriceError)
                .register(WHOLESALE_PRICE, wholesalePriceField, wholesalePriceError)
                .register(OPENING_QUANTITY, quantityField, quantityError)
                .register(MINIMUM_STOCK, minimumField, minimumError)
                .register(LOCATION, locationField, locationError)
                .register(NOTES, notesArea, notesError);

        for (TextField f : List.of(purchasePriceField, salePriceField, wholesalePriceField, quantityField, minimumField)) {
            NumberInput.install(f);
        }
        codeField.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        barcodeField.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        categoryCombo.setConverter(ViewSupport.converter(c -> c.getNameAr() + (c.isActive() ? "" : " (معطّل)")));
        brandCombo.setConverter(ViewSupport.converter(b -> b.getBrandId() == null ? "بدون ماركة"
                : b.getNameAr() + (b.isActive() ? "" : " (معطّلة)")));
        unitCombo.setConverter(ViewSupport.converter(u -> u.getNameAr() + (u.isActive() ? "" : " (معطّلة)")));
        purchasePriceField.textProperty().addListener((o, a, b) -> updateMargin());
        salePriceField.textProperty().addListener((o, a, b) -> updateMargin());
        unitCombo.valueProperty().addListener((o, a, u) -> updateQuantityHint());
    }

    /**
     * @param productId {@code null} when adding
     * @param onClose   called when the form closes, with the saved product id and a success message
     *                  (both {@code null} if nothing was saved)
     */
    public void open(Mode mode, Integer productId, BiConsumer<Integer, String> onClose) {
        this.onClose = onClose;
        setFormDisabled(true);
        Async.run(() -> new Object[]{
                catalog.categories(null, false), catalog.brands(null, false), catalog.units(null, false),
                productId == null ? null : products.findById(productId).orElse(null)
        }, data -> {
            @SuppressWarnings("unchecked") List<Category> categories = (List<Category>) data[0];
            @SuppressWarnings("unchecked") List<Brand> brands = (List<Brand>) data[1];
            @SuppressWarnings("unchecked") List<Unit> units = (List<Unit>) data[2];
            Product loaded = (Product) data[3];
            if (productId != null && loaded == null) {
                AlertUtil.warning("المنتج", "المنتج غير موجود أو لم يعد متاحًا.");
                close(null, null);
                return;
            }
            product = loaded == null ? new Product() : loaded;
            fillLookups(categories, brands, units);
            switchTo(mode);
            fill(product);
        }, error -> {
            ErrorMessages.show("فتح المنتج", error);
            close(null, null);
        });
    }

    // ---------- Display ----------

    /** Active choices, plus the product's current one even if it has been deactivated since. */
    private void fillLookups(List<Category> categories, List<Brand> brands, List<Unit> units) {
        categoryCombo.getItems().setAll(usable(categories, Category::isActive, Category::getCategoryId, product.getCategoryId()));
        List<Brand> brandItems = new ArrayList<>();
        Brand none = new Brand();
        brandItems.add(none);
        brandItems.addAll(usable(brands, Brand::isActive, Brand::getBrandId, product.getBrandId()));
        brandCombo.getItems().setAll(brandItems);
        unitCombo.getItems().setAll(usable(units, Unit::isActive, Unit::getUnitId, product.getUnitId()));
    }

    private static <T> List<T> usable(List<T> all, java.util.function.Predicate<T> active,
                                      Function<T, Integer> id, Integer current) {
        return all.stream().filter(x -> active.test(x) || Objects.equals(id.apply(x), current)).toList();
    }

    private void switchTo(Mode newMode) {
        mode = newMode;
        boolean canManage = security.hasPermission(Permission.PRODUCTS);
        if (mode != Mode.VIEW && !canManage) {
            mode = Mode.VIEW;
        }
        boolean editable = mode != Mode.VIEW;
        titleLabel.setText(switch (mode) {
            case ADD -> "إضافة منتج جديد";
            case EDIT -> "تعديل منتج";
            case VIEW -> "بيانات المنتج";
        });
        subtitleLabel.setText(switch (mode) {
            case ADD -> "الحقول المعلّمة بـ * مطلوبة. الكمية الافتتاحية تُسجَّل كحركة مخزون \"رصيد افتتاحي\".";
            case EDIT -> "الكمية لا تُعدّل من هنا؛ استخدم شاشة تسوية المخزون.";
            case VIEW -> "عرض فقط";
        });
        setFormDisabled(!editable);
        quantityField.setEditable(mode == Mode.ADD);
        quantityField.setDisable(mode != Mode.ADD);
        quantityLabel.setText(mode == Mode.ADD ? "الكمية الافتتاحية" : "الكمية الحالية");
        ViewSupport.show(saveButton, editable);
        ViewSupport.show(editButton, mode == Mode.VIEW && canManage);
        ViewSupport.show(recordInfo, mode != Mode.ADD);
        ViewSupport.show(activeCheck, mode != Mode.ADD);
        saveButton.setText(mode == Mode.ADD ? "إضافة المنتج" : "حفظ التعديلات");
        hideAlert();
        errors.clear();
        updateQuantityHint();
        if (editable) {
            (mode == Mode.ADD ? codeField : nameArField).requestFocus();
        }
    }

    private void fill(Product p) {
        codeField.setText(nz(p.getProductCode()));
        barcodeField.setText(nz(p.getBarcode()));
        nameArField.setText(nz(p.getNameAr()));
        nameEnField.setText(nz(p.getNameEn()));
        select(categoryCombo, Category::getCategoryId, p.getCategoryId());
        select(brandCombo, Brand::getBrandId, p.getBrandId());
        select(unitCombo, Unit::getUnitId, p.getUnitId());
        sizeField.setText(nz(p.getSize()));
        colorField.setText(nz(p.getColor()));
        purchasePriceField.setText(p.getProductId() == null ? "" : NumberInput.text(p.getPurchasePrice()));
        salePriceField.setText(p.getProductId() == null ? "" : NumberInput.text(p.getSalePrice()));
        wholesalePriceField.setText(p.getProductId() == null ? "" : NumberInput.text(p.getWholesalePrice()));
        quantityField.setText(p.getProductId() == null ? "" : NumberInput.text(p.getQuantity()));
        minimumField.setText(p.getProductId() == null ? "" : NumberInput.text(p.getMinimumStock()));
        locationField.setText(nz(p.getLocation()));
        notesArea.setText(nz(p.getNotes()));
        activeCheck.setSelected(p.isActive());

        statusBadge.getStyleClass().setAll("label");
        if (p.getProductId() != null) {
            statusBadge.setText(p.isActive() ? "نشط" : "معطّل");
            statusBadge.getStyleClass().addAll("badge", p.isActive() ? "badge-success" : "badge-danger");
            createdLabel.setText("تاريخ الإنشاء: " + (p.getCreatedAt() == null ? "-" : p.getCreatedAt().format(STAMP)));
            updatedLabel.setText("آخر تعديل: " + (p.getUpdatedAt() == null ? "-" : p.getUpdatedAt().format(STAMP)));
        } else {
            statusBadge.setText("");
        }
        updateMargin();
        updateQuantityHint();
    }

    private static <T> void select(ComboBox<T> combo, Function<T, Integer> id, Integer value) {
        // a null value matches the "بدون ماركة" entry, whose id is null
        combo.setValue(combo.getItems().stream().filter(x -> Objects.equals(id.apply(x), value)).findFirst().orElse(null));
    }

    private void updateMargin() {
        BigDecimal cost = tryParse(purchasePriceField.getText());
        BigDecimal price = tryParse(salePriceField.getText());
        if (!canSeeCost || cost == null || price == null || cost.signum() == 0) {
            marginLabel.setText("");
            return;
        }
        BigDecimal margin = price.subtract(cost);
        BigDecimal percent = margin.multiply(BigDecimal.valueOf(100)).divide(cost, 1, RoundingMode.HALF_UP);
        marginLabel.setText("هامش الربح للقطعة: " + MoneyUtil.formatWithCurrency(margin) + " (" + percent + "%)"
                + (margin.signum() < 0 ? "  —  تنبيه: سعر البيع أقل من سعر الشراء" : ""));
    }

    private void updateQuantityHint() {
        Unit unit = unitCombo.getValue();
        String unitText = unit == null ? "" : (unit.isAllowsDecimal()
                ? "الوحدة \"" + unit.getNameAr() + "\" تقبل الكسور (مثل 2.5)."
                : "الوحدة \"" + unit.getNameAr() + "\" تقبل أرقامًا صحيحة فقط.");
        String stockText = mode == Mode.ADD
                ? "إذا أدخلت كمية افتتاحية سيتم تسجيل حركة مخزون من نوع \"رصيد افتتاحي\" باسمك وبتاريخ اليوم."
                : "يُعتبر المنتج منخفض المخزون عندما تكون الكمية أقل من أو تساوي الحد الأدنى.";
        quantityHint.setText((stockText + " " + unitText).trim());
    }

    // ---------- Actions ----------

    @FXML
    private void onSwitchToEdit() {
        switchTo(Mode.EDIT);
    }

    @FXML
    private void onBack() {
        close(null, null);
    }

    @FXML
    private void onSave() {
        errors.clear();
        hideAlert();
        Product p = mode == Mode.ADD ? new Product() : product;
        p.setProductCode(codeField.getText());
        p.setBarcode(barcodeField.getText());
        p.setNameAr(nameArField.getText());
        p.setNameEn(nameEnField.getText());
        p.setCategoryId(categoryCombo.getValue() == null ? null : categoryCombo.getValue().getCategoryId());
        p.setBrandId(brandCombo.getValue() == null ? null : brandCombo.getValue().getBrandId());
        p.setUnitId(unitCombo.getValue() == null ? null : unitCombo.getValue().getUnitId());
        p.setSize(sizeField.getText());
        p.setColor(colorField.getText());
        p.setLocation(locationField.getText());
        p.setNotes(notesArea.getText());
        if (mode == Mode.EDIT) {
            p.setActive(activeCheck.isSelected());
        }

        // Numbers: blank optional fields mean 0; text that is not a number is reported here
        BigDecimal purchase = canSeeCost ? number(purchasePriceField, PURCHASE_PRICE, true) : null;
        BigDecimal sale = number(salePriceField, SALE_PRICE, false);
        if (sale == null && salePriceField.getText().isBlank()) {
            errors.set(SALE_PRICE, "سعر البيع مطلوب.");
        }
        BigDecimal wholesale = number(wholesalePriceField, WHOLESALE_PRICE, true);
        BigDecimal minimum = number(minimumField, MINIMUM_STOCK, true);
        BigDecimal opening = mode == Mode.ADD ? number(quantityField, OPENING_QUANTITY, true) : null;
        boolean ok = true;
        for (TextField f : List.of(purchasePriceField, salePriceField, wholesalePriceField, minimumField, quantityField)) {
            ok &= !f.getStyleClass().contains("field-invalid");
        }
        if (!ok) {
            // The service is not called, so show its required-field messages here too
            if (codeField.getText().isBlank()) {
                errors.set(PRODUCT_CODE, "كود المنتج مطلوب.");
            }
            if (nameArField.getText().isBlank()) {
                errors.set(NAME_AR, "اسم المنتج بالعربي مطلوب.");
            }
            if (categoryCombo.getValue() == null) {
                errors.set(CATEGORY, "اختر القسم.");
            }
            if (unitCombo.getValue() == null) {
                errors.set(UNIT, "اختر الوحدة.");
            }
            showAlert("يرجى تصحيح الحقول المحددة باللون الأحمر.", true);
            return;
        }
        // Without the cost permission the service keeps the stored purchase price
        p.setPurchasePrice(purchase);
        p.setSalePrice(sale);
        p.setWholesalePrice(wholesale);
        p.setMinimumStock(minimum);
        Product toSave = p;

        saveButton.setDisable(true);
        Mode current = mode;
        Async.run(() -> current == Mode.ADD ? products.create(toSave, opening) : products.update(toSave), saved -> {
            saveButton.setDisable(false);
            close(saved.getProductId(), current == Mode.ADD
                    ? "تمت إضافة المنتج \"" + saved.getNameAr() + "\" بنجاح."
                    + (saved.getQuantity().signum() > 0
                    ? " الرصيد الافتتاحي: " + QuantityUtil.format(saved.getQuantity()) + " " + saved.getUnitName()
                    + " (سُجّل كحركة مخزون)." : "")
                    : "تم حفظ تعديلات المنتج \"" + saved.getNameAr() + "\".");
        }, error -> {
            saveButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                showAlert(other != null ? other : "يرجى تصحيح الحقول المحددة باللون الأحمر.", true);
            } else {
                showAlert(ErrorMessages.of(error), true);
            }
        });
    }

    private BigDecimal number(TextField field, String key, boolean blankIsZero) {
        try {
            BigDecimal value = NumberInput.parse(field.getText());
            if (value != null && value.stripTrailingZeros().scale() > 3) {
                errors.set(key, "الحد الأقصى 3 منازل عشرية (الفلس).");
                return null;
            }
            return value == null && blankIsZero ? BigDecimal.ZERO : value;
        } catch (NumberFormatException e) {
            errors.set(key, "أدخل رقمًا صحيحًا (مثال: 12.500).");
            return null;
        }
    }

    private static BigDecimal tryParse(String text) {
        try {
            return NumberInput.parse(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void close(Integer savedId, String message) {
        if (onClose != null) {
            onClose.accept(savedId, message);
        }
    }

    // ---------- Helpers ----------

    private void setFormDisabled(boolean disabled) {
        for (Control c : List.<Control>of(codeField, barcodeField, nameArField, nameEnField, sizeField, colorField,
                purchasePriceField, salePriceField, wholesalePriceField, minimumField, locationField, notesArea)) {
            if (c instanceof TextInputControl t) {
                t.setEditable(!disabled);
                t.getStyleClass().remove("read-only");
                if (disabled) {
                    t.getStyleClass().add("read-only");
                }
            }
        }
        categoryCombo.setDisable(disabled);
        brandCombo.setDisable(disabled);
        unitCombo.setDisable(disabled);
        activeCheck.setDisable(disabled);
    }

    private void showAlert(String text, boolean error) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(formAlert, true);
    }

    private void hideAlert() {
        ViewSupport.show(formAlert, false);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
