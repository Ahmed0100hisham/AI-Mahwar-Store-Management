package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.model.DatabaseHealth;
import com.almahwar.model.DatabaseHealth.Status;
import com.almahwar.service.HealthCheckService;
import com.almahwar.service.AuthService;
import com.almahwar.service.AuthenticationException;
import com.almahwar.service.CredentialPolicy;
import com.almahwar.controller.support.Navigator;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Login screen, plus the one-time "create administrator" form shown while the
 * Users table is empty. All database and hashing work runs on background threads.
 */
public class LoginController {

    private static final Logger LOG = Logger.getLogger(LoginController.class.getName());

    // Material Design "visibility" / "visibility_off" icons
    private static final String EYE = "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z";
    private static final String EYE_OFF = "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z";

    private static final String DB_ERROR_HINT =
            "تأكد من تشغيل SQL Server ومن صحة إعدادات الاتصال (config/application.properties بجانب البرنامج، أو متغيرات"
                    + " البيئة ALMAHWAR_DB_...)، ثم أعد المحاولة.";
    private static final String DB_NOT_CONFIGURED =
            "اضبط الإعدادات في الملف config/application.properties بجانب البرنامج، أو في متغيرات البيئة"
                    + " (ALMAHWAR_DB_HOST، ALMAHWAR_DB_USER، ALMAHWAR_DB_PASSWORD …)، ثم أعد المحاولة.";

    @FXML private Label brandTitleLabel;
    @FXML private Label brandSubtitleLabel;
    @FXML private Label versionLabel;

    @FXML private VBox loadingPane;
    @FXML private VBox dbErrorPane;
    @FXML private Label dbErrorTitle;
    @FXML private Label dbErrorLabel;
    @FXML private VBox loginPane;
    @FXML private VBox setupPane;

    @FXML private Label messageLabel;
    @FXML private javafx.scene.layout.HBox loginStatusBox;
    @FXML private Label loginStatusLabel;
    @FXML private TextField usernameField;
    @FXML private Label usernameError;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordTextField;
    @FXML private ToggleButton togglePasswordButton;
    @FXML private Tooltip togglePasswordTooltip;
    @FXML private SVGPath eyeIcon;
    @FXML private Node passwordGroup;
    @FXML private Label passwordError;
    @FXML private Label capsLockLabel;
    @FXML private Button loginButton;

    @FXML private Label setupMessageLabel;
    @FXML private TextField setupFullNameField;
    @FXML private TextField setupUsernameField;
    @FXML private PasswordField setupPasswordField;
    @FXML private PasswordField setupConfirmField;
    @FXML private Button setupButton;

    private final AuthService authService = AppContext.get().auth();
    private final HealthCheckService health = AppContext.get().health();

    /** Message passed by {@link Navigator} (logout, session timeout), shown once the form appears. */
    private String pendingInfo;

    @FXML
    private void initialize() {
        AppConfig cfg = AppConfig.getInstance();
        brandTitleLabel.setText(cfg.appName());
        brandSubtitleLabel.setText(cfg.appNameEn());
        versionLabel.setText("الإصدار " + ViewSupport.ltr(cfg.appVersion()) + "  •  الكويت");

        // Both forms have a default button; only the visible one may react to Enter
        loginButton.defaultButtonProperty().bind(loginPane.visibleProperty());
        setupButton.defaultButtonProperty().bind(setupPane.visibleProperty());

        // Show/hide password: both fields share the same text
        passwordTextField.textProperty().bindBidirectional(passwordField.textProperty());
        eyeIcon.setContent(EYE);

        // Clear a field's error as soon as the user edits it
        usernameField.textProperty().addListener((o, a, b) -> clearFieldError(usernameField, usernameError));
        passwordField.textProperty().addListener((o, a, b) -> clearFieldError(passwordGroup, passwordError));

        passwordField.addEventHandler(KeyEvent.KEY_RELEASED, this::updateCapsLock);
        passwordTextField.addEventHandler(KeyEvent.KEY_RELEASED, this::updateCapsLock);

        checkUsers();
    }

    /** Called by {@link Navigator} after loading, e.g. "تم تسجيل الخروج بنجاح". */
    public void showInfo(String message) {
        pendingInfo = message;
        if (loginPane.isVisible()) {
            showMessage(messageLabel, message, false);
            pendingInfo = null;
        }
    }

    // ---------- Startup: the database health check (read only), then login or first-run setup ----------

    private void checkUsers() {
        showPane(loadingPane);
        Task<DatabaseHealth> task = new Task<>() {
            @Override
            protected DatabaseHealth call() {
                return health.check();
            }
        };
        task.setOnSucceeded(e -> {
            DatabaseHealth h = task.getValue();
            if (!h.canStart()) {
                showDatabaseProblem(h);
            } else if (h.usersExist()) {
                showLoginPane();
                // a small, quiet confirmation that the database answered (schema version, no server details)
                loginStatusLabel.setText("قاعدة البيانات متصلة  •  المخطط " + ViewSupport.ltr(h.schemaVersion()));
                setShown(loginStatusBox, true);
                if (h.status() == Status.NO_ACTIVE_ADMIN) {
                    showMessage(messageLabel, h.message(), true);
                }
            } else {
                showPane(setupPane);
                Platform.runLater(setupFullNameField::requestFocus);
            }
        });
        task.setOnFailed(e -> {
            LOG.log(Level.WARNING, "Database check failed", task.getException());
            dbErrorTitle.setText("تعذّر الاتصال بقاعدة البيانات");
            dbErrorLabel.setText(DB_ERROR_HINT);
            showPane(dbErrorPane);
        });
        runInBackground(task, "login-db-check");
    }

    /** The program cannot be used with this database: say why (never a user name, password or SQL detail). */
    private void showDatabaseProblem(DatabaseHealth h) {
        boolean connection = h.status() == Status.SERVER_UNREACHABLE || h.status() == Status.LOGIN_FAILED;
        dbErrorTitle.setText(connection || h.status() == Status.CONFIGURATION_ERROR
                ? "تعذّر الاتصال بقاعدة البيانات" : "قاعدة البيانات غير جاهزة للبرنامج");
        StringBuilder text = new StringBuilder(h.message());
        for (String d : h.details()) {
            text.append("\n• ").append(d);
        }
        if (h.status() == Status.CONFIGURATION_ERROR && !h.details().isEmpty()) {
            text.append("\n\n").append(DB_NOT_CONFIGURED);
        } else if (connection || h.status() == Status.CONFIGURATION_ERROR) {
            text.append("\n\n").append(DB_ERROR_HINT);
        }
        dbErrorLabel.setText(text.toString());
        showPane(dbErrorPane);
    }

    @FXML
    private void onRetryConnection() {
        checkUsers();
    }

    private void showLoginPane() {
        showPane(loginPane);
        if (pendingInfo != null) {
            showMessage(messageLabel, pendingInfo, false);
            pendingInfo = null;
        }
        Platform.runLater(() -> (usernameField.getText().isBlank() ? usernameField : activePasswordField()).requestFocus());
    }

    private void showPane(VBox pane) {
        for (VBox p : new VBox[]{loadingPane, dbErrorPane, loginPane, setupPane}) {
            setShown(p, p == pane);
        }
    }

    // ---------- Login ----------

    @FXML
    private void onLogin() {
        hideMessage(messageLabel);
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();

        boolean valid = true;
        if (username.isEmpty()) {
            showFieldError(usernameField, usernameError, "يرجى إدخال اسم المستخدم.");
            valid = false;
        }
        if (password.isEmpty()) {
            showFieldError(passwordGroup, passwordError, "يرجى إدخال كلمة المرور.");
            valid = false;
        }
        if (!valid) {
            (username.isEmpty() ? usernameField : activePasswordField()).requestFocus();
            return;
        }

        char[] chars = password.toCharArray();
        setBusy(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                authService.login(username, chars);
                return null;
            }
        };
        task.setOnSucceeded(e -> Navigator.showMain());
        task.setOnFailed(e -> {
            setBusy(false);
            passwordField.clear();
            Throwable error = task.getException();
            if (error instanceof AuthenticationException auth) {
                showMessage(messageLabel, auth.getMessage(), true);
            } else {
                LOG.log(Level.SEVERE, "Login failed unexpectedly", error);
                showMessage(messageLabel, "تعذّر تسجيل الدخول بسبب خطأ في الاتصال بقاعدة البيانات.", true);
            }
            activePasswordField().requestFocus();
        });
        runInBackground(task, "login");
    }

    private void setBusy(boolean busy) {
        loginButton.setDisable(busy);
        usernameField.setDisable(busy);
        passwordGroup.setDisable(busy);
        if (busy) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.getStyleClass().add("btn-spinner");
            loginButton.setGraphic(spinner);
            loginButton.setText("جارٍ التحقق...");
        } else {
            loginButton.setGraphic(null);
            loginButton.setText("تسجيل الدخول");
        }
    }

    @FXML
    private void onTogglePassword() {
        boolean show = togglePasswordButton.isSelected();
        TextInputControl from = show ? passwordField : passwordTextField;
        TextInputControl to = show ? passwordTextField : passwordField;
        int caret = from.getCaretPosition();

        setShown(passwordTextField, show);
        setShown(passwordField, !show);
        eyeIcon.setContent(show ? EYE_OFF : EYE);
        togglePasswordTooltip.setText(show ? "إخفاء كلمة المرور" : "إظهار كلمة المرور");

        to.requestFocus();
        to.positionCaret(caret);
    }

    private TextInputControl activePasswordField() {
        return togglePasswordButton.isSelected() ? passwordTextField : passwordField;
    }

    private void updateCapsLock(KeyEvent event) {
        boolean on = Platform.isKeyLocked(KeyCode.CAPS).orElse(false);
        setShown(capsLockLabel, on);
    }

    // ---------- First-run administrator ----------

    @FXML
    private void onCreateAdmin() {
        hideMessage(setupMessageLabel);
        String fullName = setupFullNameField.getText();
        String username = setupUsernameField.getText();
        String password = setupPasswordField.getText();

        try {
            if (fullName == null || fullName.isBlank()) {
                throw new IllegalArgumentException("أدخل الاسم الكامل.");
            }
            CredentialPolicy.validateUsername(username);
            CredentialPolicy.validatePassword(password.toCharArray());
            if (!password.equals(setupConfirmField.getText())) {
                throw new IllegalArgumentException("كلمتا المرور غير متطابقتين.");
            }
        } catch (IllegalArgumentException ex) {
            showMessage(setupMessageLabel, ex.getMessage(), true);
            return;
        }

        char[] chars = password.toCharArray();
        setupButton.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                authService.createInitialAdmin(fullName, username, chars);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setupPasswordField.clear();
            setupConfirmField.clear();
            usernameField.setText(username.trim());
            pendingInfo = null;
            showLoginPane();
            showMessage(messageLabel, "تم إنشاء حساب مدير النظام بنجاح. سجّل الدخول الآن.", false);
        });
        task.setOnFailed(e -> {
            setupButton.setDisable(false);
            Throwable error = task.getException();
            LOG.log(Level.WARNING, "Creating the administrator failed", error);
            String msg = error instanceof IllegalArgumentException || error instanceof IllegalStateException
                    ? error.getMessage()
                    : "تعذّر إنشاء الحساب بسبب خطأ في قاعدة البيانات.";
            showMessage(setupMessageLabel, msg, true);
        });
        runInBackground(task, "create-admin");
    }

    // ---------- Helpers ----------

    private static void showFieldError(Node field, Label errorLabel, String message) {
        if (!field.getStyleClass().contains("field-invalid")) {
            field.getStyleClass().add("field-invalid");
        }
        errorLabel.setText(message);
        setShown(errorLabel, true);
    }

    private static void clearFieldError(Node field, Label errorLabel) {
        field.getStyleClass().remove("field-invalid");
        setShown(errorLabel, false);
    }

    private static void showMessage(Label label, String message, boolean error) {
        if (message == null || message.isBlank()) {
            hideMessage(label);
            return;
        }
        label.setText(message);
        label.getStyleClass().removeAll("form-alert-error", "form-alert-info");
        label.getStyleClass().add(error ? "form-alert-error" : "form-alert-info");
        setShown(label, true);
    }

    private static void hideMessage(Label label) {
        setShown(label, false);
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static void runInBackground(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
