package com.almahwar.controller.support;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * One icon of the program's icon system: a Material Design glyph (24×24 artboard, the same family as the side
 * menu) drawn at a fixed size, so every icon has the same visual weight and alignment. Its colour comes from CSS
 * ({@code .icon-glyph}, set per button type in components.css).
 * <p>
 * Sizes: 14 (small, inside compact / table buttons), 16 (normal, buttons — the default), 18 (navigation).
 * Usable from FXML: {@code <Icon name="plus"/>} or {@code <Icon name="back" size="14"/>}. In right-to-left layouts
 * JavaFX mirrors the directional icons ("back", "previous", "next") so they point the right way; all others keep
 * their drawn orientation.
 */
public class Icon extends StackPane {

    public static final int SMALL = 14;
    public static final int NORMAL = 16;
    public static final int NAVIGATION = 18;

    private final SVGPath glyph = new SVGPath();
    private String name;
    private int size = NORMAL;

    public Icon() {
        getStyleClass().add("icon");
        glyph.getStyleClass().add("icon-glyph");
        getChildren().add(glyph);
        setMouseTransparent(true);
        applySize();
    }

    public Icon(String name) {
        this();
        setName(name);
    }

    public Icon(String name, int size) {
        this(name);
        setSize(size);
    }

    /** A new icon (normal size). */
    public static Icon of(String name) {
        return new Icon(name);
    }

    /** A new icon of the given size (14, 16 or 18). */
    public static Icon of(String name, int size) {
        return new Icon(name, size);
    }

    public String getName() {
        return name;
    }

    /** @throws IllegalArgumentException for an unknown icon name (a typo is found at once, not shown as a blank) */
    public void setName(String name) {
        this.name = name;
        glyph.setContent(Icons.path(name));
        // Only directional icons follow the right-to-left layout (JavaFX mirrors them: "back" then points right);
        // every other glyph keeps its drawn orientation (a mirrored check mark or printer would look wrong)
        setNodeOrientation(DIRECTIONAL.contains(name) ? javafx.geometry.NodeOrientation.INHERIT
                : javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
    }

    private static final java.util.Set<String> DIRECTIONAL = java.util.Set.of("back", "previous", "next");

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
        applySize();
    }

    private void applySize() {
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        double scale = size / 24.0;   // the artboard is 24×24
        glyph.setScaleX(scale);
        glyph.setScaleY(scale);
    }
}
