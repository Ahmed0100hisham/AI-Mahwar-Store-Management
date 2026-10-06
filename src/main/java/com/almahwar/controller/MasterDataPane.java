package com.almahwar.controller;

import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.service.ValidationException;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * List + side form used for categories, brands and units: search, add, edit,
 * activate/deactivate. What differs per entity is described by a {@link Spec}.
 */
public class MasterDataPane<T> extends HBox {

    public record Column<T>(String title, Function<T, String> value, double prefWidth) {
    }

    public sealed interface Field<T> permits TextInput, CheckInput {
    }

    /** A text input bound to a property; {@code key} matches the service's validation field name. */
    public record TextInput<T>(String key, String label, Function<T, String> get, BiConsumer<T, String> set)
            implements Field<T> {
    }

    public record CheckInput<T>(String label, Predicate<T> get, BiConsumer<T, Boolean> set) implements Field<T> {
    }

    /**
     * @param noun      e.g. "قسم" — used in titles ("إضافة قسم")
     * @param load      (search text, active only) → rows, called in the background
     * @param save      inserts or updates, returns the saved row
     * @param copy      a detached copy for editing, so failed saves never change the list
     */
    public record Spec<T>(String noun, Supplier<T> create, UnaryOperator<T> copy, Function<T, Integer> id,
                          Predicate<T> isActive, BiFunction<String, Boolean, List<T>> load, UnaryOperator<T> save,
                          BiConsumer<T, Boolean> setActive, List<Column<T>> columns, List<Field<T>> fields) {
    }

    private final Spec<T> spec;
    private final boolean canEdit;

    private final TextField searchField = new TextField();
    private final CheckBox showInactive = new CheckBox("إظهار المعطّل");
    private final Label countLabel = new Label();
    private final TableView<T> table = new TableView<>();

    private final Label formTitle = new Label();
    private final Label formMessage = new Label();
    private final Button saveButton = new Button("حفظ");
    private final Button newButton = new Button("جديد");
    private final Button toggleButton = new Button();
    private final FormErrors errors = new FormErrors();
    private final Map<Field<T>, Node> inputs = new HashMap<>();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    private T editing;

    public MasterDataPane(Spec<T> spec, boolean canEdit) {
        this.spec = spec;
        this.canEdit = canEdit;
        setSpacing(16);
        getStyleClass().add("master-data");

        getChildren().add(buildList());
        if (canEdit) {
            getChildren().add(buildForm());
            startNew();
        }
        reload(null, null);
    }

    // ---------- List ----------

    private VBox buildList() {
        searchField.setPromptText("بحث...");
        searchField.setPrefWidth(260);
        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> reload(null, null));
        showInactive.setSelected(true);
        showInactive.selectedProperty().addListener((o, a, b) -> reload(null, null));
        countLabel.getStyleClass().add("muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12, searchField, showInactive, spacer, countLabel);
        bar.getStyleClass().add("filter-bar");

        for (Column<T> c : spec.columns()) {
            TableColumn<T, String> col = new TableColumn<>(c.title());
            col.setCellValueFactory(cd -> new ReadOnlyStringWrapper(c.value().apply(cd.getValue())));
            col.setPrefWidth(c.prefWidth());
            table.getColumns().add(col);
        }
        TableColumn<T, T> status = new TableColumn<>("الحالة");
        status.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : ProductsController.statusBadge(spec.isActive().test(item)));
            }
        });
        status.setPrefWidth(90);
        table.getColumns().add(status);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("لا توجد بيانات"));
        VBox.setVgrow(table, Priority.ALWAYS);
        if (canEdit) {
            table.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
                if (item != null) {
                    edit(item);
                }
            });
        }

        VBox box = new VBox(12, bar, table);
        box.getStyleClass().addAll("card", "module-card");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** @param after runs once the rows are shown (and {@code selectId} selected), e.g. to show a message */
    private void reload(Integer selectId, Runnable after) {
        String text = searchField.getText();
        boolean activeOnly = !showInactive.isSelected();
        Async.run(() -> spec.load().apply(text, activeOnly), rows -> {
            table.getItems().setAll(rows);
            countLabel.setText(rows.size() + " سجل");
            if (selectId != null) {
                rows.stream().filter(r -> selectId.equals(spec.id().apply(r))).findFirst()
                        .ifPresent(r -> table.getSelectionModel().select(r));
            }
            if (after != null) {
                after.run();
            }
        }, error -> ErrorMessages.show("تحميل البيانات", error));
    }

    // ---------- Form ----------

    private VBox buildForm() {
        formTitle.getStyleClass().add("card-title");
        VBox fields = new VBox(12);
        for (Field<T> f : spec.fields()) {
            if (f instanceof TextInput<T> t) {
                Label label = new Label(t.label());
                label.getStyleClass().add("field-label");
                TextField input = new TextField();
                input.setId(t.key());
                input.setMaxWidth(Double.MAX_VALUE);
                Label error = new Label();
                errors.register(t.key(), input, error);
                inputs.put(f, input);
                fields.getChildren().add(new VBox(5, label, input, error));
            } else if (f instanceof CheckInput<T> c) {
                CheckBox box = new CheckBox(c.label());
                inputs.put(f, box);
                fields.getChildren().add(box);
            }
        }

        formMessage.setWrapText(true);
        formMessage.setMaxWidth(Double.MAX_VALUE);
        hideMessage();

        saveButton.setId("masterSaveButton");
        formMessage.setId("masterFormMessage");
        saveButton.getStyleClass().addAll("btn", "btn-primary");
        saveButton.setDefaultButton(false);
        saveButton.setOnAction(e -> save());
        newButton.getStyleClass().addAll("btn", "btn-secondary");
        newButton.setOnAction(e -> {
            table.getSelectionModel().clearSelection();
            startNew();
        });
        toggleButton.getStyleClass().addAll("btn", "btn-secondary");
        toggleButton.setOnAction(e -> toggleActive());
        HBox buttons = new HBox(8, saveButton, newButton, toggleButton);

        VBox form = new VBox(16, formTitle, fields, formMessage, buttons);
        form.getStyleClass().addAll("card", "side-form");
        form.setMinWidth(320);
        form.setPrefWidth(340);
        form.setMaxWidth(360);
        return form;
    }

    private void startNew() {
        editing = spec.create().get();
        formTitle.setText("إضافة " + spec.noun());
        fill(editing);
        toggleButton.setVisible(false);
        toggleButton.setManaged(false);
        hideMessage();
    }

    private void edit(T item) {
        editing = spec.copy().apply(item);
        formTitle.setText("تعديل " + spec.noun());
        fill(editing);
        boolean active = spec.isActive().test(item);
        toggleButton.setText(active ? "تعطيل" : "تفعيل");
        toggleButton.getStyleClass().removeAll("btn-danger", "btn-secondary");
        toggleButton.getStyleClass().add(active ? "btn-danger" : "btn-secondary");
        toggleButton.setVisible(true);
        toggleButton.setManaged(true);
        hideMessage();
    }

    private void fill(T item) {
        errors.clear();
        for (Field<T> f : spec.fields()) {
            if (f instanceof TextInput<T> t) {
                String v = t.get().apply(item);
                ((TextField) inputs.get(f)).setText(v == null ? "" : v);
            } else if (f instanceof CheckInput<T> c) {
                ((CheckBox) inputs.get(f)).setSelected(c.get().test(item));
            }
        }
    }

    private void save() {
        T item = editing;
        for (Field<T> f : spec.fields()) {
            if (f instanceof TextInput<T> t) {
                t.set().accept(item, ((TextField) inputs.get(f)).getText());
            } else if (f instanceof CheckInput<T> c) {
                c.set().accept(item, ((CheckBox) inputs.get(f)).isSelected());
            }
        }
        boolean isNew = spec.id().apply(item) == null;
        saveButton.setDisable(true);
        errors.clear();
        hideMessage();
        Async.run(() -> spec.save().apply(item), saved -> {
            saveButton.setDisable(false);
            editing = spec.copy().apply(saved);
            reload(spec.id().apply(saved),
                    () -> showMessage((isNew ? "تمت إضافة " : "تم حفظ ") + spec.noun() + " بنجاح.", false));
        }, error -> {
            saveButton.setDisable(false);
            if (isNew) {
                // keep the form as typed, but forget any id a failed insert might have set
                editing = spec.create().get();
            }
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                if (other != null) {
                    showMessage(other, true);
                }
            } else {
                showMessage(ErrorMessages.of(error), true);
            }
        });
    }

    private void toggleActive() {
        T item = table.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        boolean activate = !spec.isActive().test(item);
        if (!activate && !AlertUtil.confirm("تعطيل " + spec.noun(),
                "سيختفي هذا " + spec.noun() + " من قوائم الاختيار للمنتجات الجديدة، وتبقى المنتجات الحالية كما هي.\nهل تريد المتابعة؟")) {
            return;
        }
        Integer id = spec.id().apply(item);
        Async.run(() -> spec.setActive().accept(item, activate),
                () -> reload(id, () -> showMessage(activate ? "تم التفعيل." : "تم التعطيل.", false)), error -> showMessage(ErrorMessages.of(error), true));
    }

    private void showMessage(String text, boolean error) {
        formMessage.setText(text);
        formMessage.getStyleClass().removeAll("form-alert", "form-alert-error", "form-alert-info");
        formMessage.getStyleClass().addAll("form-alert", error ? "form-alert-error" : "form-alert-info");
        formMessage.setVisible(true);
        formMessage.setManaged(true);
    }

    private void hideMessage() {
        formMessage.setVisible(false);
        formMessage.setManaged(false);
    }
}
