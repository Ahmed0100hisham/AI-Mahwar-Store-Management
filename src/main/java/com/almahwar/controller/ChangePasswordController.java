package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.Navigator;
import com.almahwar.service.AuthService;
import com.almahwar.service.ValidationException;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

/**
 * The logged-in user changes their own password. Forced after an admin reset (only "change" or "logout" — the
 * session has no permissions until then); voluntary from the top bar ("back" returns to the program). The
 * service checks the current password and the policy; afterwards the session ends and the login screen opens.
 */
public class ChangePasswordController {

    @FXML private Label userLabel;
    @FXML private Label infoLabel;
    @FXML private PasswordField currentField;
    @FXML private Label currentError;
    @FXML private PasswordField newField;
    @FXML private Label newError;
    @FXML private PasswordField confirmField;
    @FXML private Label messageLabel;
    @FXML private Button changeButton;
    @FXML private Button backButton;
    @FXML private Button logoutButton;

    private final AuthService auth = AppContext.get().auth();
    private final FormErrors errors = new FormErrors();
    private boolean busy;

    @FXML
    private void initialize() {
        errors.register("currentPassword", currentField, currentError)
                .register("newPassword", newField, newError);
        Platform.runLater(currentField::requestFocus);
    }

    public void open(boolean forced) {
        AppContext.get().security().getSession().ifPresent(s ->
                userLabel.setText(s.getUser().getFullName() + " (" + s.getUser().getUsername() + ")"));
        infoLabel.setText(forced
                ? "يجب تغيير كلمة المرور المؤقتة التي عيّنها مدير النظام قبل استخدام البرنامج."
                : "أدخل كلمة المرور الحالية ثم الجديدة.");
        infoLabel.getStyleClass().setAll("label", "form-alert", forced ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(backButton, !forced);
        ViewSupport.show(logoutButton, forced);
    }

    @FXML
    private void onChange() {
        if (busy) {
            return;
        }
        errors.clear();
        ViewSupport.show(messageLabel, false);
        // char arrays: the service wipes them after use; the fields are cleared here
        char[] current = currentField.getText().toCharArray();
        char[] next = newField.getText().toCharArray();
        char[] confirm = confirmField.getText().toCharArray();
        busy = true;
        changeButton.setDisable(true);
        Async.run(() -> auth.changePassword(current, next, confirm), () -> {
            busy = false;
            clearFields();
            Navigator.passwordChanged();
        }, error -> {
            busy = false;
            changeButton.setDisable(false);
            newField.clear();
            confirmField.clear();
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                showMessage(other != null ? other : ve.getMessage());
            } else {
                showMessage(ErrorMessages.of(error));
            }
        });
    }

    private void clearFields() {
        currentField.clear();
        newField.clear();
        confirmField.clear();
    }

    @FXML
    private void onBack() {
        clearFields();
        Navigator.showMain();
    }

    @FXML
    private void onLogout() {
        clearFields();
        Navigator.logout();
    }

    private void showMessage(String text) {
        messageLabel.setText(text);
        messageLabel.getStyleClass().setAll("label", "form-alert", "form-alert-error");
        ViewSupport.show(messageLabel, true);
    }
}
