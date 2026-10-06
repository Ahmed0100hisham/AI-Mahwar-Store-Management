package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.LeaveGuard;
import com.almahwar.controller.support.Navigator;
import com.almahwar.model.BackupInfo;
import com.almahwar.model.BackupInfo.Status;
import com.almahwar.model.BackupInfo.Verification;
import com.almahwar.model.BackupResult;
import com.almahwar.model.BackupSettings;
import com.almahwar.model.Permission;
import com.almahwar.model.RestoreResult;
import com.almahwar.service.BackupRestoreService;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.ValidationException;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.almahwar.controller.PurchasesController.WHEN;

/**
 * Backup &amp; restore (admin): create a backup, the history, verify, restore. Every operation runs in the
 * background with a busy state; the service enforces the permissions and allows one operation at a time.
 * Restore opens an in-page warning that needs the database name typed in; while it runs the page cannot be left.
 */
public class BackupController {

    @FXML private VBox page;
    @FXML private Button createButton;
    @FXML private HBox busyBox;
    @FXML private Label busyLabel;
    @FXML private Label messageLabel;
    @FXML private Label databaseLabel;
    @FXML private Label directoryLabel;
    @FXML private Label settingsProblemLabel;
    @FXML private VBox restorePane;
    @FXML private Label restoreDetailsLabel;
    @FXML private Label confirmHintLabel;
    @FXML private TextField confirmField;
    @FXML private Button cancelRestoreButton;
    @FXML private Button confirmRestoreButton;
    @FXML private Label confirmError;
    @FXML private Label countLabel;
    @FXML private TableView<BackupInfo> historyTable;
    @FXML private TableColumn<BackupInfo, String> createdColumn;
    @FXML private TableColumn<BackupInfo, String> fileColumn;
    @FXML private TableColumn<BackupInfo, String> kindColumn;
    @FXML private TableColumn<BackupInfo, String> userColumn;
    @FXML private TableColumn<BackupInfo, BackupInfo> statusColumn;
    @FXML private TableColumn<BackupInfo, BackupInfo> verifyColumn;
    @FXML private TableColumn<BackupInfo, String> verifiedAtColumn;
    @FXML private TableColumn<BackupInfo, String> sizeColumn;
    @FXML private TableColumn<BackupInfo, String> noteColumn;
    @FXML private Button refreshButton;
    @FXML private Button verifyButton;
    @FXML private Button restoreButton;

    private final SecurityContext security = AppContext.get().security();
    private final BackupRestoreService backups = AppContext.get().backups();
    private boolean busy;
    private boolean ready;
    private BackupInfo restoreTarget;

    private boolean restoring;
    /** An automatic logout (inactivity) that arrived during an operation: run once the operation is over. */
    private Runnable pendingExit;

    /**
     * While a backup / verify / restore runs the page must not be left, the window not closed (closing would cut
     * the connection SQL Server is working on) and the inactivity logout waits until the operation is over.
     */
    private final LeaveGuard restoreGuard = new LeaveGuard() {
        @Override
        public boolean hasUnsavedWork() {
            return true;
        }

        @Override
        public void askToLeave(Runnable leave) {
            AlertUtil.info(restoring ? "الاستعادة جارية" : "عملية جارية", restoring
                    ? "استعادة قاعدة البيانات جارية الآن. انتظر حتى تنتهي؛ ستعود بعدها إلى شاشة الدخول."
                    : "عملية النسخ الاحتياطي أو التحقق جارية الآن على خادم SQL Server. انتظر حتى تنتهي.");
        }

        @Override
        public void saveBeforeForcedExit(Runnable then) {
            pendingExit = then;   // never end the session in the middle of the operation
        }
    };

    @FXML
    private void initialize() {
        ViewSupport.show(createButton, security.hasPermission(Permission.BACKUP_CREATE));
        ViewSupport.show(verifyButton, security.hasPermission(Permission.BACKUP_VERIFY));
        ViewSupport.show(restoreButton, security.hasPermission(Permission.BACKUP_RESTORE));
        databaseLabel.setText(ViewSupport.ltr(backups.databaseName()));
        confirmField.setPromptText(backups.databaseName());
        confirmField.textProperty().addListener((o, a, b) -> {
            confirmRestoreButton.setDisable(busy || b == null || !b.strip().equals(backups.databaseName()));
            ViewSupport.show(confirmError, false);
        });
        setUpTable();
        updateButtons();
        Async.run(backups::settings, this::showSettings, error -> {
            showSettings(null);
            showMessage(ErrorMessages.of(error), true);
        });
        refresh(null);
    }

    // ---------- table ----------

    private void setUpTable() {
        historyTable.setPlaceholder(new Label("لا توجد نسخ احتياطية بعد"));
        createdColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().createdAt() == null ? ""
                : c.getValue().createdAt().format(WHEN)));
        fileColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(ViewSupport.ltr(c.getValue().fileName())));
        kindColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().kind().getLabelAr()));
        userColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().createdByName()));
        statusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        statusColumn.setCellFactory(col -> badgeCell(b -> ViewSupport.badge(b.status().getLabelAr(),
                b.status() == Status.COMPLETED ? "badge-success" : b.status() == Status.FAILED ? "badge-danger" : "badge-warning")));
        verifyColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        verifyColumn.setCellFactory(col -> badgeCell(b -> ViewSupport.badge(b.verification().getLabelAr(),
                b.verification() == Verification.VERIFIED ? "badge-success"
                        : b.verification() == Verification.VERIFY_FAILED ? "badge-danger" : "badge-muted")));
        verifiedAtColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().verifiedAt() == null ? ""
                : c.getValue().verifiedAt().format(WHEN)));
        sizeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(size(c.getValue().sizeBytes())));
        noteColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().note()));
        historyTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
    }

    private static TableCell<BackupInfo, BackupInfo> badgeCell(java.util.function.Function<BackupInfo, Label> badge) {
        return new TableCell<>() {
            @Override
            protected void updateItem(BackupInfo b, boolean empty) {
                super.updateItem(b, empty);
                setText(null);
                setGraphic(empty || b == null ? null : badge.apply(b));
            }
        };
    }

    static String size(Long bytes) {
        if (bytes == null) {
            return "";
        }
        if (bytes < 1024 * 1024) {
            return ViewSupport.ltr(String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0));
        }
        return ViewSupport.ltr(String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024)));
    }

    private void showSettings(BackupSettings s) {
        ready = s != null && s.ready();
        if (s != null) {
            String dir = s.serverDirectory() == null ? "غير محدد" : ViewSupport.ltr(s.serverDirectory());
            directoryLabel.setText(dir + (s.fromServerDefault() && s.serverDirectory() != null
                    ? "  (المجلد الافتراضي لخادم SQL Server)" : ""));
        }
        settingsProblemLabel.setText(s == null ? "" : s.problem());
        ViewSupport.show(settingsProblemLabel, s != null && s.problem() != null);
        updateButtons();
    }

    private void refresh(Integer selectId) {
        Async.run(backups::history, rows -> {
            historyTable.getItems().setAll(rows);
            countLabel.setText(rows.size() + " نسخة");
            if (selectId != null) {
                rows.stream().filter(b -> b.backupId() == selectId).findFirst()
                        .ifPresent(b -> historyTable.getSelectionModel().select(b));
            }
            updateButtons();
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void updateButtons() {
        BackupInfo b = historyTable.getSelectionModel().getSelectedItem();
        boolean usable = b != null && b.usable();
        boolean restoring = restoreTarget != null;
        createButton.setDisable(busy || !ready || restoring);
        refreshButton.setDisable(busy);
        verifyButton.setDisable(busy || !usable || restoring);
        restoreButton.setDisable(busy || !ready || !usable || restoring);
        historyTable.setDisable(busy || restoring);
        cancelRestoreButton.setDisable(busy);
        confirmField.setDisable(busy);
        confirmRestoreButton.setDisable(busy || confirmField.getText() == null
                || !confirmField.getText().strip().equals(backups.databaseName()));
    }

    // ---------- actions ----------

    @FXML
    private void onRefresh() {
        Async.run(backups::settings, this::showSettings, error -> showMessage(ErrorMessages.of(error), true));
        refresh(selectedId());
    }

    @FXML
    private void onCreate() {
        run("جارٍ إنشاء النسخة الاحتياطية على خادم SQL Server ثم التحقق منها…", backups::createBackup,
                (BackupResult r) -> {
                    showMessage("تم إنشاء النسخة الاحتياطية " + ViewSupport.ltr(r.backup().fileName()) + " ("
                            + size(r.backup().sizeBytes()) + ")"
                            + (r.verification() == null ? "." : r.verified() ? " وتم التحقق منها بنجاح."
                            : "، لكن التحقق منها فشل: " + r.verification().message()), r.verification() != null && !r.verified());
                    refresh(r.backup().backupId());
                });
    }

    @FXML
    private void onVerify() {
        BackupInfo b = historyTable.getSelectionModel().getSelectedItem();
        if (b == null) {
            return;
        }
        run("جارٍ التحقق من النسخة " + ViewSupport.ltr(b.fileName()) + " بواسطة خادم SQL Server…",
                () -> backups.verify(b.backupId()), v -> {
                    showMessage((v.valid() ? "التحقق ناجح: " : "فشل التحقق: ") + v.message(), !v.valid());
                    refresh(b.backupId());
                });
    }

    @FXML
    private void onRestore() {
        BackupInfo b = historyTable.getSelectionModel().getSelectedItem();
        if (b == null || !b.usable()) {
            return;
        }
        restoreTarget = b;
        restoreDetailsLabel.setText("النسخة: " + ViewSupport.ltr(b.fileName()) + "\nتاريخها: "
                + (b.createdAt() == null ? "" : b.createdAt().format(WHEN)) + "   •   أنشأها: " + b.createdByName()
                + "   •   التحقق: " + b.verification().getLabelAr());
        confirmHintLabel.setText("للتأكيد اكتب اسم قاعدة البيانات كما هو تمامًا: " + ViewSupport.ltr(backups.databaseName()));
        confirmField.clear();
        ViewSupport.show(confirmError, false);
        ViewSupport.show(restorePane, true);
        updateButtons();
        confirmField.requestFocus();
    }

    @FXML
    private void onCancelRestore() {
        closeRestorePane();
    }

    private void closeRestorePane() {
        restoreTarget = null;
        confirmField.clear();
        ViewSupport.show(restorePane, false);
        updateButtons();
    }

    @FXML
    private void onConfirmRestore() {
        BackupInfo target = restoreTarget;
        String typed = confirmField.getText();
        if (target == null || busy) {
            return;
        }
        restoring = true;
        run("جارٍ استعادة قاعدة البيانات: التحقق من النسخة، ثم نسخة وقائية، ثم الاستعادة والفحص. لا تغلق البرنامج…",
                () -> backups.restore(target.backupId(), typed), (RestoreResult r) -> {
                    Navigator.databaseRestored("تمت استعادة قاعدة البيانات من النسخة " + ViewSupport.ltr(r.restoredFile())
                            + ". النسخة الوقائية: " + ViewSupport.ltr(r.safetyBackupFile()) + "."
                            + (r.warnings().isEmpty() ? "" : "\nملاحظات: " + String.join("، ", r.warnings()))
                            + "\nسجّل الدخول من جديد.");
                });
    }

    /** Runs an operation in the background with the busy state; failures are shown on the page. */
    private <T> void run(String busyText, Supplier<T> work, Consumer<T> done) {
        busy = true;
        Navigator.setLeaveGuard(restoreGuard);
        busyLabel.setText(busyText);
        ViewSupport.show(busyBox, true);
        showMessage(null, false);
        updateButtons();
        Async.run(work, result -> {
            finish();
            done.accept(result);
            runPendingExit();
        }, error -> {
            finish();
            if (!security.isLoggedIn()) {
                // the database was replaced (and possibly put back): this session is over
                Navigator.databaseRestored(ErrorMessages.of(error));
                return;
            }
            if (error instanceof ValidationException v && v.errorFor(BackupRestoreService.CONFIRMATION) != null) {
                confirmError.setText(v.errorFor(BackupRestoreService.CONFIRMATION));
                confirmError.getStyleClass().setAll("label", "field-error");
                ViewSupport.show(confirmError, true);
                return;
            }
            if (restoreTarget != null) {
                closeRestorePane();
            }
            showMessage(ErrorMessages.of(error), true);
            refresh(selectedId());
            runPendingExit();
        });
    }

    private void finish() {
        busy = false;
        restoring = false;
        ViewSupport.show(busyBox, false);
        Navigator.clearLeaveGuard(restoreGuard);
        updateButtons();
    }

    /** The inactivity logout that waited for the operation (no-op if the session already ended). */
    private void runPendingExit() {
        Runnable exit = pendingExit;
        pendingExit = null;
        if (exit != null) {
            exit.run();
        }
    }

    private Integer selectedId() {
        BackupInfo b = historyTable.getSelectionModel().getSelectedItem();
        return b == null ? null : b.backupId();
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setText(text == null ? "" : text);
        messageLabel.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(messageLabel, text != null);
    }
}
