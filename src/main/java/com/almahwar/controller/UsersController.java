package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import com.almahwar.model.UserAccount;
import com.almahwar.model.UserAccount.PermissionRow;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.UserService;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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
import java.util.Map;

import static com.almahwar.controller.PurchasesController.WHEN;

/** Users module (admin only): accounts list with filters, quick actions, the permission matrix; the form opens in place. */
public class UsersController {

    @FXML private StackPane pageHost;
    @FXML private VBox listPage;
    @FXML private Button newButton;
    @FXML private Label listMessage;
    @FXML private TabPane tabs;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> roleFilter;
    @FXML private ComboBox<String> statusFilter;
    @FXML private TableView<UserAccount> usersTable;
    @FXML private TableColumn<UserAccount, String> usernameColumn;
    @FXML private TableColumn<UserAccount, String> nameColumn;
    @FXML private TableColumn<UserAccount, String> roleColumn;
    @FXML private TableColumn<UserAccount, UserAccount> statusColumn;
    @FXML private TableColumn<UserAccount, String> lastLoginColumn;
    @FXML private TableColumn<UserAccount, String> createdColumn;
    @FXML private Label countLabel;
    @FXML private Button editButton;
    @FXML private Button toggleButton;
    @FXML private Button unlockButton;
    @FXML private Button resetButton;
    @FXML private TableView<PermissionRow> matrixTable;
    @FXML private TableColumn<PermissionRow, String> groupColumn;
    @FXML private TableColumn<PermissionRow, String> permissionColumn;
    @FXML private TableColumn<PermissionRow, String> adminColumn;
    @FXML private TableColumn<PermissionRow, String> accountantColumn;
    @FXML private TableColumn<PermissionRow, String> cashierColumn;
    @FXML private TableColumn<PermissionRow, String> storekeeperColumn;

    private final SecurityContext security = AppContext.get().security();
    private final UserService users = AppContext.get().users();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));
    private final List<String> roleCodes = new ArrayList<>();
    private Map<String, String> roleNames = Map.of();

    @FXML
    private void initialize() {
        ViewSupport.show(newButton, security.hasPermission(Permission.USERS_CREATE));
        ViewSupport.show(editButton, security.hasPermission(Permission.USERS_EDIT));
        ViewSupport.show(toggleButton, security.hasPermission(Permission.USERS_EDIT));
        ViewSupport.show(unlockButton, security.hasPermission(Permission.USERS_EDIT));
        ViewSupport.show(resetButton, security.hasPermission(Permission.USERS_RESET_PASSWORD));
        statusFilter.getItems().setAll("كل الحالات", "المفعّلون", "المعطّلون");
        statusFilter.getSelectionModel().selectFirst();
        roleFilter.getItems().setAll("كل الأدوار");
        roleFilter.getSelectionModel().selectFirst();
        setUpTable();
        setUpMatrix();
        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh(null));
        roleFilter.valueProperty().addListener((o, a, b) -> refresh(null));
        statusFilter.valueProperty().addListener((o, a, b) -> refresh(null));
        Async.run(users::roles, roles -> {
            roleNames = roles;
            roleCodes.clear();
            roleCodes.addAll(roles.keySet());
            List<String> items = new ArrayList<>();
            items.add("كل الأدوار");
            items.addAll(roles.values());
            roleFilter.getItems().setAll(items);
            roleFilter.getSelectionModel().selectFirst();
            refresh(null);
        }, error -> ErrorMessages.show("تحميل الأدوار", error));
        Async.run(users::permissionMatrix, rows -> matrixTable.getItems().setAll(rows),
                error -> ErrorMessages.show("مصفوفة الصلاحيات", error));
    }

    // ---------- table ----------

    private void setUpTable() {
        usersTable.setPlaceholder(new Label("لا يوجد مستخدمون مطابقون"));
        usersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        usernameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().username()));
        nameColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().fullName()));
        roleColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().roleName()));
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(UserAccount u, boolean empty) {
                super.updateItem(u, empty);
                setText(null);
                setGraphic(empty || u == null ? null : badges(u));
            }
        });
        lastLoginColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().lastLoginAt() == null ? "لم يدخل بعد"
                : c.getValue().lastLoginAt().format(WHEN)));
        createdColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().createdAt() == null ? ""
                : c.getValue().createdAt().format(ReportViewPage.DAY)));
        usersTable.setRowFactory(tv -> {
            TableRow<UserAccount> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()
                        && security.hasPermission(Permission.USERS_EDIT)) {
                    showForm(UserFormController.Mode.EDIT, row.getItem().userId());
                }
            });
            return row;
        });
        usersTable.getSelectionModel().selectedItemProperty().addListener((o, a, u) -> updateButtons(u));
        updateButtons(null);
    }

    static HBox badges(UserAccount u) {
        HBox box = new HBox(4, ViewSupport.badge(u.active() ? "مفعّل" : "معطّل", u.active() ? "badge-success" : "badge-danger"));
        if (u.locked()) {
            box.getChildren().add(ViewSupport.badge("موقوف مؤقتًا", "badge-warning"));
        }
        if (u.mustChangePassword()) {
            box.getChildren().add(ViewSupport.badge("يلزم تغيير كلمة المرور", "badge-info"));
        }
        return box;
    }

    private void updateButtons(UserAccount u) {
        boolean none = u == null;
        boolean self = !none && u.userId() == security.currentUser().getUserId();
        editButton.setDisable(none);
        toggleButton.setDisable(none || self);
        toggleButton.setText(!none && !u.active() ? "تفعيل" : "تعطيل");
        unlockButton.setDisable(none || !u.locked());
        resetButton.setDisable(none || self);
    }

    private void setUpMatrix() {
        matrixTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        groupColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().group()));
        permissionColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().permission().getLabelAr()));
        adminColumn.setCellValueFactory(c -> mark(c.getValue(), Role.ADMIN));
        accountantColumn.setCellValueFactory(c -> mark(c.getValue(), Role.ACCOUNTANT));
        cashierColumn.setCellValueFactory(c -> mark(c.getValue(), Role.CASHIER));
        storekeeperColumn.setCellValueFactory(c -> mark(c.getValue(), Role.STOREKEEPER));
        for (TableColumn<PermissionRow, String> col : List.of(adminColumn, accountantColumn, cashierColumn, storekeeperColumn)) {
            col.setCellFactory(c -> new TableCell<>() {
                @Override
                protected void updateItem(String value, boolean empty) {
                    super.updateItem(value, empty);
                    boolean granted = !empty && "✓".equals(value);
                    setText(empty || granted ? null : value);
                    setGraphic(granted ? com.almahwar.controller.support.Icon.of("check") : null);
                    getStyleClass().removeAll("matrix-yes", "matrix-no");
                    if (!empty) {
                        getStyleClass().add(granted ? "matrix-yes" : "matrix-no");
                    }
                }
            });
        }
    }

    private static ReadOnlyStringWrapper mark(PermissionRow row, String role) {
        return new ReadOnlyStringWrapper(row.roles().contains(role) ? "✓" : "—");
    }

    private void refresh(Integer selectId) {
        int roleIndex = roleFilter.getSelectionModel().getSelectedIndex();
        String role = roleIndex > 0 && roleIndex - 1 < roleCodes.size() ? roleCodes.get(roleIndex - 1) : null;
        int status = statusFilter.getSelectionModel().getSelectedIndex();
        Boolean active = status == 1 ? Boolean.TRUE : status == 2 ? Boolean.FALSE : null;
        String text = searchField.getText();
        Async.run(() -> users.search(text, role, active), rows -> {
            usersTable.getItems().setAll(rows);
            countLabel.setText(rows.size() + " مستخدم");
            if (selectId != null) {
                rows.stream().filter(u -> u.userId() == selectId).findFirst()
                        .ifPresent(u -> usersTable.getSelectionModel().select(u));
            }
        }, error -> ErrorMessages.show("تحميل المستخدمين", error));
    }

    // ---------- actions ----------

    private UserAccount selected() {
        return usersTable.getSelectionModel().getSelectedItem();
    }

    @FXML
    private void onNew() {
        showForm(UserFormController.Mode.CREATE, null);
    }

    @FXML
    private void onEdit() {
        if (selected() != null) {
            showForm(UserFormController.Mode.EDIT, selected().userId());
        }
    }

    @FXML
    private void onReset() {
        if (selected() != null) {
            showForm(UserFormController.Mode.RESET_PASSWORD, selected().userId());
        }
    }

    @FXML
    private void onToggle() {
        UserAccount u = selected();
        if (u == null) {
            return;
        }
        boolean enable = !u.active();
        if (!enable && !AlertUtil.confirm("تعطيل المستخدم", "سيُمنع " + u.fullName() + " (" + u.username()
                + ") من الدخول، وتبقى سجلاته ومستنداته كما هي.\nهل تريد المتابعة؟")) {
            return;
        }
        Async.run(() -> users.setActive(u.userId(), enable), saved -> {
            showMessage((enable ? "تم تفعيل " : "تم تعطيل ") + saved.username() + ".", false);
            refresh(saved.userId());
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    @FXML
    private void onUnlock() {
        UserAccount u = selected();
        if (u == null) {
            return;
        }
        Async.run(() -> users.unlock(u.userId()), saved -> {
            showMessage("تم إلغاء الإيقاف المؤقت لـ " + saved.username() + ".", false);
            refresh(saved.userId());
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    // ---------- pages ----------

    void showForm(UserFormController.Mode mode, Integer userId) {
        replacePage(ViewLoader.<UserFormController>load("user-form.fxml", c -> c.open(this, mode, userId, roleNames)));
    }

    /** Back to the list (after saving, cancelling or discarding). */
    void closeForm(String message, Integer selectId) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(true);
        showMessage(message, false);
        refresh(selectId);
    }

    private void replacePage(Parent page) {
        pageHost.getChildren().removeIf(n -> n != listPage);
        listPage.setVisible(false);
        pageHost.getChildren().add(page);
    }

    private void showMessage(String text, boolean error) {
        listMessage.setText(text == null ? "" : text);
        listMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(listMessage, text != null);
    }
}
