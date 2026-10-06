package com.almahwar.controller.support;

import com.almahwar.model.Product;
import com.almahwar.util.QuantityUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Function;

/**
 * Turns a {@link TextField} into a product search box: typing a name, code or
 * barcode shows matching products under the field; picking one selects it.
 * A scanned barcode followed by Enter selects the product directly.
 */
public final class ProductPicker {

    private static final int MAX_RESULTS = 10;

    private final TextField field;
    private final Function<String, List<Product>> search;
    private final ContextMenu popup = new ContextMenu();
    private final ObjectProperty<Product> selected = new SimpleObjectProperty<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private List<Product> lastResults = List.of();
    private boolean settingText;

    /** @param search runs in the background; receives the typed text */
    public ProductPicker(TextField field, Function<String, List<Product>> search) {
        this.field = field;
        this.search = search;
        popup.getStyleClass().add("picker-popup");

        field.textProperty().addListener((obs, old, text) -> {
            if (settingText) {
                return;
            }
            selected.set(null);
            debounce.playFromStart();
        });
        debounce.setOnFinished(e -> runSearch(false));
        field.setOnAction(e -> runSearch(true));   // Enter / barcode scanner
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                popup.hide();
            }
        });
    }

    public ObjectProperty<Product> selectedProperty() {
        return selected;
    }

    public Product getSelected() {
        return selected.get();
    }

    public void select(Product product) {
        settingText = true;
        field.setText(product == null ? "" : product.getNameAr() + " - " + product.getProductCode());
        settingText = false;
        selected.set(product);
        popup.hide();
    }

    public void clear() {
        select(null);
    }

    private void runSearch(boolean pickSingle) {
        String text = field.getText();
        if (text == null || text.isBlank()) {
            popup.hide();
            return;
        }
        if (pickSingle && selected.get() != null) {
            return;
        }
        Async.run(() -> search.apply(text.trim()), results -> {
            if (!text.equals(field.getText())) {
                return;   // the user kept typing; a newer search is on its way
            }
            lastResults = results;
            Product exact = results.stream()
                    .filter(p -> text.trim().equalsIgnoreCase(p.getBarcode())
                            || text.trim().equalsIgnoreCase(p.getProductCode()))
                    .findFirst().orElse(null);
            if (pickSingle && (exact != null || results.size() == 1)) {
                select(exact != null ? exact : results.get(0));
            } else {
                showResults();
            }
        }, error -> popup.hide());
    }

    private void showResults() {
        if (field.getScene() == null || field.getScene().getWindow() == null
                || !field.getScene().getWindow().isShowing()) {
            return;   // the page was closed while searching
        }
        popup.getItems().clear();
        if (lastResults.isEmpty()) {
            MenuItem none = new MenuItem("لا توجد منتجات مطابقة");
            none.setDisable(true);
            popup.getItems().add(none);
        }
        for (Product p : lastResults.stream().limit(MAX_RESULTS).toList()) {
            Label name = new Label(p.getNameAr());
            name.getStyleClass().add("cell-primary");
            Label info = new Label(p.getProductCode()
                    + (p.getBarcode() == null ? "" : "  •  " + p.getBarcode())
                    + "  •  المتوفر: " + QuantityUtil.format(p.getQuantity()) + " " + p.getUnitName());
            info.getStyleClass().add("cell-secondary");
            VBox box = new VBox(1, name, info);
            box.setPrefWidth(Math.max(field.getWidth() - 24, 260));
            CustomMenuItem item = new CustomMenuItem(box, true);
            item.setOnAction(e -> select(p));
            popup.getItems().add(item);
        }
        if (!popup.isShowing()) {
            popup.show(field, Side.BOTTOM, 0, 2);
        }
        if (popup.getScene() != null) {
            popup.getScene().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        }
    }
}
