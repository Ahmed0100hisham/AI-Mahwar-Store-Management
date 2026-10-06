package com.almahwar.controller;

import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.Unit;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.util.StringConverter;

import java.util.function.Function;

/** Small view helpers shared by the products and inventory screens. */
final class ViewSupport {

    private ViewSupport() {
    }

    static <T> StringConverter<T> converter(Function<T, String> text) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : text.apply(value);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }

    static <S> TableCell<S, String> textCell(String styleClass) {
        TableCell<S, String> cell = new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        };
        cell.getStyleClass().add(styleClass);
        return cell;
    }

    static Label badge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", styleClass);
        return label;
    }

    /** Left-to-right mark: keeps Latin text such as "1.0.0-SNAPSHOT" in order inside right-to-left labels. */
    private static final String LRM = String.valueOf((char) 0x200E);

    static String ltr(String text) {
        return LRM + text + LRM;
    }

    static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** A centred "nothing here yet" panel for tabs whose module does not exist yet or has no data. */
    static javafx.scene.layout.VBox emptyState(String icon, String title, String text) {
        javafx.scene.shape.SVGPath svg = com.almahwar.controller.support.Icons.node(icon, "empty-icon");
        javafx.scene.layout.StackPane circle = new javafx.scene.layout.StackPane(svg);
        circle.getStyleClass().add("empty-icon-box");
        Label t = new Label(title);
        t.getStyleClass().add("empty-title");
        Label d = new Label(text);
        d.getStyleClass().add("empty-text");
        d.setWrapText(true);
        d.setMaxWidth(460);
        d.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(10, circle, t, d);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.getStyleClass().addAll("card", "empty-state");
        box.setMinHeight(260);
        box.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        // The tab stretches its content; the wrapper keeps the card at its natural height, at the top
        javafx.scene.layout.VBox wrapper = new javafx.scene.layout.VBox(box);
        wrapper.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        return wrapper;
    }

    // "All" entries for filter combo boxes (id = null means "do not filter")

    static Category allCategories() {
        Category c = new Category();
        c.setNameAr("كل الأقسام");
        return c;
    }

    static Brand allBrands() {
        Brand b = new Brand();
        b.setNameAr("كل الماركات");
        return b;
    }

    static Unit allUnits() {
        Unit u = new Unit();
        u.setNameAr("كل الوحدات");
        return u;
    }
}
