package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.LeaveGuard;
import com.almahwar.controller.support.Navigator;
import com.almahwar.model.UserAccount;
import com.almahwar.model.UserAccount.Changes;
import com.almahwar.model.UserAccount.NewUser;
import com.almahwar.service.UserService;
import com.almahwar.service.ValidationException;
import com.almahwar.util.PhoneNumbers;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Map;

import static com.almahwar.service.UserService.*;

/**
 * New user, edit user, or an admin's password reset for another user. The service applies every rule (policy,
 * last-admin protection, self-protection); this form only collects the input. Unsaved changes are protected by the
 * existing {@link LeaveGuard} (save, discard or stay) — side menu, logout and window close all ask first.
 */
public class UserFormController implements LeaveGuard {

    public enum Mode { CREATE, EDIT, RESET_PASSWORD }

    @FXML private StackPane formHost;
    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private HBox leaveBar;
    @FXML private Label formAlert;
    @FXML private VBox profileCard;
    @FXML private TextField usernameField;
    @FXML private Label usernameError;
    @FXML private TextField fullNameField;
    @FXML private Label fullNameError;
    @FXML private ComboBox<String> roleCombo;
    @FXML private Label roleError;
    @FXML private VBox activeBox;
    @FXML private CheckBox activeCheck;
    @FXML private Label activeError;
    @FXML private TextField phoneField;
    @FXML private Label phoneError;
    @FXML private TextField emailField;
    @FXML private Label emailError;
    @FXML private Label roleHint;
    @FXML private VBox passwordCard;
    @FXML private Label passwordTitle;
    @FXML private PasswordField passwordField;
    @FXML private Label passwordError;
    @FXML private PasswordField confirmField;
    @FXML private CheckBox mustChangeCheck;
    @FXML private Button saveButton;

    private final UserService users = AppContext.get().users();
    private final FormErrors errors = new FormErrors();

    private UsersController host;
    private Mode mode;
    private Integer userId;
    private Map<String, String> roleNames;
    private String savedState;
    private boolean busy;
    private Runnable pendingLeave;

    @FXML
    private void initialize() {
        errors.register(USERNAME, usernameField, usernameError)
                .register(FULL_NAME, fullNameField, fullNameError)
                .register(ROLE, roleCombo, roleError)
                .register(ACTIVE, activeCheck, activeError)
                .register(PHONE, phoneField, phoneError)
                .register(EMAIL, emailField, emailError)
                .register(PASSWORD, passwordField, passwordError);
        usernameField.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        phoneField.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        emailField.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        formHost.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                Navigator.setLeaveGuard(this);
            } else {
                Navigator.clearLeaveGuard(this);
            }
        });
        formHost.getProperties().put("dirty", (java.util.function.BooleanSupplier) this::hasUnsavedWork);
    }

    public void open(UsersController host, Mode mode, Integer userId, Map<String, String> roleNames) {
        this.host = host;
        this.mode = mode;
        this.userId = userId;
        this.roleNames = roleNames;
        roleCombo.getItems().setAll(roleNames.keySet());
        roleCombo.setConverter(ViewSupport.converter(code -> roleNames.getOrDefault(code, code)));
        boolean create = mode == Mode.CREATE;
        boolean reset = mode == Mode.RESET_PASSWORD;
        titleLabel.setText(create ? "مستخدم جديد" : reset ? "إعادة تعيين كلمة المرور" : "تعديل المستخدم");
        ViewSupport.show(profileCard, !reset);
        ViewSupport.show(passwordCard, create || reset);
        ViewSupport.show(activeBox, !create);
        ViewSupport.show(roleHint, !create);
        usernameField.setEditable(create);
        passwordTitle.setText(reset ? "كلمة المرور المؤقتة الجديدة" : "كلمة المرور");
        if (create) {
            subtitleLabel.setText("اختر الدور بعناية: الصلاحيات تأتي من الدور.");
            roleCombo.setValue(null);
            activeCheck.setSelected(true);
            savedState = state();
            return;
        }
        Async.run(() -> users.findById(userId), u -> {
            usernameField.setText(u.username());
            fullNameField.setText(u.fullName());
            phoneField.setText(u.phone() == null ? "" : PhoneNumbers.format(u.phone()));
            emailField.setText(u.email() == null ? "" : u.email());
            roleCombo.setValue(u.roleCode());
            activeCheck.setSelected(u.active());
            subtitleLabel.setText(u.fullName() + " (" + u.username() + ")" + (reset
                    ? " — لن تُعرض كلمة المرور القديمة؛ أدخل كلمة مؤقتة وأبلغها للمستخدم بنفسك." : ""));
            savedState = state();
        }, error -> host.closeForm(ErrorMessages.of(error), null));
    }

    private String state() {
        return String.join("\u0001", usernameField.getText(), fullNameField.getText(), phoneField.getText(),
                emailField.getText(), String.valueOf(roleCombo.getValue()), String.valueOf(activeCheck.isSelected()),
                String.valueOf(!passwordField.getText().isEmpty() || !confirmField.getText().isEmpty()),
                String.valueOf(mustChangeCheck.isSelected()));
    }

    // ---------- saving ----------

    @FXML
    private void onSave() {
        save(null);
    }

    private void save(Runnable then) {
        if (busy) {
            return;
        }
        errors.clear();
        ViewSupport.show(formAlert, false);
        char[] password = passwordField.getText().toCharArray();
        char[] confirm = confirmField.getText().toCharArray();
        String role = roleCombo.getValue();
        boolean mustChange = mustChangeCheck.isSelected();
        busy = true;
        saveButton.setDisable(true);
        Async.run(() -> switch (mode) {
            case CREATE -> users.create(new NewUser(usernameField.getText(), fullNameField.getText(), phoneField.getText(),
                    emailField.getText(), role, mustChange), password, confirm);
            case EDIT -> users.update(new Changes(userId, fullNameField.getText(), phoneField.getText(),
                    emailField.getText(), role, activeCheck.isSelected()));
            case RESET_PASSWORD -> users.resetPassword(userId, password, confirm, mustChange);
        }, saved -> {
            busy = false;
            passwordField.clear();
            confirmField.clear();
            savedState = state();
            String message = switch (mode) {
                case CREATE -> "تم إنشاء المستخدم " + saved.username() + (mustChange
                        ? "؛ سيُطلب منه تغيير كلمة المرور عند أول دخول." : ".");
                case EDIT -> "تم حفظ بيانات المستخدم " + saved.username() + ".";
                case RESET_PASSWORD -> "تمت إعادة تعيين كلمة مرور " + saved.username() + (mustChange
                        ? "؛ سيُطلب منه تغييرها عند الدخول التالي." : ".");
            };
            if (then != null) {
                then.run();
            } else {
                host.closeForm(message, saved.userId());
            }
        }, error -> {
            busy = false;
            saveButton.setDisable(false);
            passwordField.clear();
            confirmField.clear();
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                showAlert(other != null ? other : "يرجى تصحيح الأخطاء الموضحة؛ لم يُحفظ أي شيء.");
            } else {
                showAlert(ErrorMessages.of(error));
            }
        });
    }

    @FXML
    private void onBack() {
        Navigator.leave(() -> host.closeForm(null, userId));
    }

    private void showAlert(String text) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-error");
        ViewSupport.show(formAlert, true);
    }

    // ---------- leave guard ----------

    @Override
    public boolean hasUnsavedWork() {
        return savedState != null && !savedState.equals(state());
    }

    @Override
    public void askToLeave(Runnable leave) {
        pendingLeave = leave;
        ViewSupport.show(leaveBar, true);
    }

    /** Inactivity logout: a half-filled account (or password) is never saved silently — discarded. */
    @Override
    public void saveBeforeForcedExit(Runnable then) {
        passwordField.clear();
        confirmField.clear();
        savedState = null;
        then.run();
    }

    @FXML
    private void onLeaveSave() {
        Runnable leave = pendingLeave;
        pendingLeave = null;
        ViewSupport.show(leaveBar, false);
        save(leave);
    }

    @FXML
    private void onLeaveDiscard() {
        Runnable leave = pendingLeave;
        pendingLeave = null;
        ViewSupport.show(leaveBar, false);
        passwordField.clear();
        confirmField.clear();
        savedState = state();
        if (leave != null) {
            leave.run();
        }
    }

    @FXML
    private void onLeaveStay() {
        pendingLeave = null;
        ViewSupport.show(leaveBar, false);
    }
}
