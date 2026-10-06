package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.LeaveGuard;
import com.almahwar.controller.support.Navigator;
import com.almahwar.model.CompanySettings;
import com.almahwar.model.Permission;
import com.almahwar.model.Settings.About;
import com.almahwar.model.Settings.LogoInfo;
import com.almahwar.model.Settings.Snapshot;
import com.almahwar.model.SystemSettings;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.SettingsService;
import com.almahwar.service.ValidationException;
import com.almahwar.util.PhoneNumbers;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static com.almahwar.service.SettingsService.*;

/**
 * Settings screen: company profile, logo, system settings and "about". Saving writes both tabs in one transaction.
 * While shown it is the {@link Navigator}'s {@link LeaveGuard}: leaving with unsaved changes (side menu, logout,
 * closing the window) asks first — save, discard or stay. No figure or rule lives here: the service validates.
 */
public class SettingsController implements LeaveGuard {

    @FXML private StackPane pageHost;
    @FXML private Button reloadButton;
    @FXML private Button saveButton;
    @FXML private HBox leaveBar;
    @FXML private Label pageMessage;
    @FXML private TabPane tabs;
    @FXML private TextField nameArField;
    @FXML private Label nameArError;
    @FXML private TextField nameEnField;
    @FXML private Label nameEnError;
    @FXML private TextField phoneField;
    @FXML private Label phoneError;
    @FXML private TextField phone2Field;
    @FXML private Label phone2Error;
    @FXML private TextField emailField;
    @FXML private Label emailError;
    @FXML private TextField currencyField;
    @FXML private TextField addressField;
    @FXML private Label addressError;
    @FXML private TextField countryField;
    @FXML private Label countryError;
    @FXML private TextField taxField;
    @FXML private Label taxError;
    @FXML private TextField crField;
    @FXML private Label crError;
    @FXML private ImageView logoPreview;
    @FXML private Label noLogoLabel;
    @FXML private Label logoInfoLabel;
    @FXML private Label logoError;
    @FXML private Button changeLogoButton;
    @FXML private Button removeLogoButton;
    @FXML private TextField validityField;
    @FXML private Label validityError;
    @FXML private TextArea termsArea;
    @FXML private Label termsError;
    @FXML private TextArea invoiceFooterArea;
    @FXML private Label invoiceFooterError;
    @FXML private TextArea reportFooterArea;
    @FXML private Label reportFooterError;
    @FXML private GridPane aboutGrid;

    private final SecurityContext security = AppContext.get().security();
    private final SettingsService settings = AppContext.get().settings();
    private final FormErrors errors = new FormErrors();

    /** The form as last loaded or saved: anything else is an unsaved change. */
    private String savedState;
    private boolean busy;
    /** The navigation waiting for the user's answer in the leave bar. */
    private Runnable pendingLeave;

    @FXML
    private void initialize() {
        boolean canEdit = security.hasPermission(Permission.SETTINGS_EDIT);
        for (TextInputControl f : inputs()) {
            f.setEditable(canEdit);
        }
        ViewSupport.show(saveButton, canEdit);
        ViewSupport.show(reloadButton, canEdit);
        ViewSupport.show(changeLogoButton, canEdit);
        ViewSupport.show(removeLogoButton, canEdit);
        phoneField.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        phone2Field.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);
        emailField.setNodeOrientation(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT);

        errors.register(NAME_AR, nameArField, nameArError)
                .register(NAME_EN, nameEnField, nameEnError)
                .register(PHONE, phoneField, phoneError)
                .register(PHONE2, phone2Field, phone2Error)
                .register(EMAIL, emailField, emailError)
                .register(ADDRESS, addressField, addressError)
                .register(COUNTRY, countryField, countryError)
                .register(TAX_NUMBER, taxField, taxError)
                .register(CR_NUMBER, crField, crError)
                .register(VALIDITY_DAYS, validityField, validityError)
                .register(QUOTATION_TERMS, termsArea, termsError)
                .register(INVOICE_FOOTER, invoiceFooterArea, invoiceFooterError)
                .register(REPORT_FOOTER, reportFooterArea, reportFooterError)
                .register(LOGO, changeLogoButton, logoError);

        // the guard is active exactly while this page is part of the window
        pageHost.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                Navigator.setLeaveGuard(this);
            } else {
                Navigator.clearLeaveGuard(this);
            }
        });
        // test / automation hook: the "save, discard or stay" question without a modal dialog
        pageHost.getProperties().put("dirty", (java.util.function.BooleanSupplier) this::hasUnsavedWork);
        pageHost.getProperties().put("uploadLogo", (Consumer<Path>) this::uploadLogo);
        load(null);
        loadAbout();
    }

    private List<TextInputControl> inputs() {
        return List.of(nameArField, nameEnField, phoneField, phone2Field, emailField, addressField, countryField,
                taxField, crField, validityField, termsArea, invoiceFooterArea, reportFooterArea);
    }

    // ======================= Loading =======================

    private void load(String message) {
        Async.run(settings::load, snap -> {
            fill(snap);
            if (message != null) {
                showMessage(message, false);
            }
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void fill(Snapshot s) {
        CompanySettings c = s.company();
        nameArField.setText(text(c.nameAr()));
        nameEnField.setText(text(c.nameEn()));
        phoneField.setText(c.phone() == null ? "" : PhoneNumbers.format(c.phone()));
        phone2Field.setText(c.phone2() == null ? "" : PhoneNumbers.format(c.phone2()));
        emailField.setText(text(c.email()));
        currencyField.setText(c.currencyCode() + " — دينار كويتي (3 منازل عشرية)");
        addressField.setText(text(c.address()));
        countryField.setText(text(c.country()));
        taxField.setText(text(c.taxNumber()));
        crField.setText(text(c.crNumber()));
        SystemSettings sys = s.system();
        validityField.setText(String.valueOf(sys.quotationValidityDays()));
        termsArea.setText(text(sys.quotationTerms()));
        invoiceFooterArea.setText(text(sys.invoiceFooter()));
        reportFooterArea.setText(text(sys.reportFooter()));
        showLogo(s.logo());
        errors.clear();
        savedState = state();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private void showLogo(LogoInfo info) {
        ViewSupport.show(removeLogoButton, info != null && security.hasPermission(Permission.SETTINGS_EDIT));
        if (info == null) {
            logoPreview.setImage(null);
            noLogoLabel.setVisible(true);
            logoInfoLabel.setText("لم يُحدَّد شعار؛ تُطبع المستندات باسم الشركة فقط.");
            return;
        }
        logoInfoLabel.setText((info.fileName() == null ? "" : info.fileName() + "  •  ") + info.width() + "×" + info.height()
                + " بكسل  •  " + Math.max(1, info.sizeBytes() / 1024) + " ك.ب"
                + (info.updatedBy() == null ? "" : "  •  غيّره " + info.updatedBy()));
        Async.run(() -> settings.logo().orElse(null), bytes -> {
            logoPreview.setImage(bytes == null ? null : new Image(new ByteArrayInputStream(bytes)));
            noLogoLabel.setVisible(bytes == null);
        }, e -> { });
    }

    private void loadAbout() {
        Async.run(settings::about, this::showAbout, error -> showMessage(ErrorMessages.of(error), true));
    }

    private void showAbout(About a) {
        aboutGrid.getChildren().clear();
        int row = 0;
        row = aboutRow(row, "البرنامج", a.appName() + " — " + a.appNameEn(), "aboutAppName");
        row = aboutRow(row, "الإصدار", ViewSupport.ltr(a.appVersion()), "aboutVersion");
        row = aboutRow(row, "قاعدة البيانات", (a.databaseConnected() ? "متصلة" : "غير متصلة") + "  •  "
                + a.databaseDescription(), "aboutDatabase");
        row = aboutRow(row, "إصدار SQL Server", a.sqlServerVersion() == null ? "—" : a.sqlServerVersion(), "aboutServer");
        row = aboutRow(row, "إصدار مخطط قاعدة البيانات", (a.schemaVersion() == null ? "غير معروف" : a.schemaVersion())
                + "  (المطلوب " + a.requiredSchemaVersion() + "، " + (a.schemaCompatible() ? "متوافق" : "غير متوافق — شغّل سكربت الترقية")
                + ")", "aboutSchema");
        aboutRow(row, "Java", a.javaVersion(), "aboutJava");
    }

    private int aboutRow(int row, String label, String value, String id) {
        Label l = new Label(label);
        l.getStyleClass().add("muted");
        Label v = new Label(value);
        v.getStyleClass().add("info-value");
        v.setWrapText(true);
        v.setId(id);
        aboutGrid.add(l, 0, row);
        aboutGrid.add(v, 1, row);
        return row + 1;
    }

    @FXML
    private void onRefreshAbout() {
        loadAbout();
    }

    // ======================= Saving =======================

    /** Everything the user can change, as one string (to tell unsaved changes). */
    private String state() {
        StringBuilder b = new StringBuilder();
        for (TextInputControl f : inputs()) {
            b.append(f.getText() == null ? "" : f.getText().strip()).append('\u0001');
        }
        return b.toString();
    }

    @FXML
    private void onSave() {
        save(null);
    }

    /** Saves both tabs; {@code then} runs after a successful save (a waiting navigation). */
    private void save(Runnable then) {
        if (busy) {
            return;
        }
        errors.clear();
        ViewSupport.show(pageMessage, false);
        int days;
        try {
            days = Integer.parseInt(validityField.getText().strip());
        } catch (NumberFormatException e) {
            errors.set(VALIDITY_DAYS, "أدخل عدد أيام صحيحًا (من " + MIN_VALIDITY_DAYS + " إلى " + MAX_VALIDITY_DAYS + ").");
            tabs.getSelectionModel().select(1);
            showMessage("يرجى تصحيح الأخطاء الموضحة.", true);
            return;
        }
        CompanySettings company = new CompanySettings(nameArField.getText(), nameEnField.getText(), phoneField.getText(),
                phone2Field.getText(), emailField.getText(), addressField.getText(), countryField.getText(),
                CURRENCY_CODE, taxField.getText(), crField.getText());
        SystemSettings system = new SystemSettings(days, termsArea.getText(), invoiceFooterArea.getText(),
                reportFooterArea.getText());
        busy = true;
        saveButton.setDisable(true);
        Async.run(() -> settings.save(company, system), snap -> {
            busy = false;
            saveButton.setDisable(false);
            fill(snap);
            showMessage("تم حفظ الإعدادات. تظهر في المستندات المطبوعة من الآن.", false);
            if (then != null) {
                then.run();
            }
        }, error -> {
            busy = false;
            saveButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                boolean systemError = ve.getErrors().keySet().stream().anyMatch(k -> k.equals(VALIDITY_DAYS)
                        || k.equals(QUOTATION_TERMS) || k.equals(INVOICE_FOOTER) || k.equals(REPORT_FOOTER));
                boolean companyError = ve.getErrors().keySet().stream().anyMatch(k -> !(k.equals(VALIDITY_DAYS)
                        || k.equals(QUOTATION_TERMS) || k.equals(INVOICE_FOOTER) || k.equals(REPORT_FOOTER)));
                tabs.getSelectionModel().select(companyError ? 0 : systemError ? 1 : tabs.getSelectionModel().getSelectedIndex());
                showMessage(other != null ? other : "يرجى تصحيح الأخطاء الموضحة؛ لم يُحفظ أي شيء.", true);
            } else {
                showMessage(ErrorMessages.of(error) + " لم يُحفظ أي شيء.", true);
            }
        });
    }

    @FXML
    private void onReload() {
        load("أُعيد تحميل الإعدادات المحفوظة.");
    }

    // ======================= Logo =======================

    @FXML
    private void onChangeLogo() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("اختيار شعار الشركة");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("صور PNG / JPG", "*.png", "*.jpg", "*.jpeg"));
        java.io.File file = chooser.showOpenDialog(pageHost.getScene() == null ? null : pageHost.getScene().getWindow());
        if (file != null) {
            uploadLogo(file.toPath());
        }
    }

    /** Reads and stores a logo file (also the automation hook used by the UI tests). */
    void uploadLogo(Path file) {
        errors.clear();
        changeLogoButton.setDisable(true);
        Async.run(() -> {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (java.io.IOException e) {
                throw new ValidationException(LOGO, "تعذّرت قراءة الملف.");
            }
            return settings.changeLogo(file.getFileName().toString(), bytes);
        }, info -> {
            changeLogoButton.setDisable(false);
            showLogo(info);
            showMessage("تم تغيير شعار الشركة.", false);
        }, error -> {
            changeLogoButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                errors.show(ve);
                showMessage(ve.getMessage(), true);
            } else {
                showMessage(ErrorMessages.of(error), true);
            }
        });
    }

    @FXML
    private void onRemoveLogo() {
        Async.run(settings::removeLogo, () -> {
            showLogo(null);
            showMessage("تمت إزالة الشعار.", false);
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    // ======================= Leave guard =======================

    @Override
    public boolean hasUnsavedWork() {
        return savedState != null && !savedState.equals(state());
    }

    @Override
    public void askToLeave(Runnable leave) {
        pendingLeave = leave;
        ViewSupport.show(leaveBar, true);
    }

    /** The session ends without the user (inactivity): unsaved settings are not saved half-checked — discarded. */
    @Override
    public void saveBeforeForcedExit(Runnable then) {
        savedState = null;
        then.run();
    }

    @FXML
    private void onLeaveSave() {
        Runnable leave = pendingLeave;
        ViewSupport.show(leaveBar, false);
        save(leave == null ? null : () -> {
            pendingLeave = null;
            leave.run();
        });
    }

    @FXML
    private void onLeaveDiscard() {
        Runnable leave = pendingLeave;
        pendingLeave = null;
        ViewSupport.show(leaveBar, false);
        savedState = state();   // nothing left to protect
        if (leave != null) {
            leave.run();
        }
    }

    @FXML
    private void onLeaveStay() {
        pendingLeave = null;
        ViewSupport.show(leaveBar, false);
    }

    private void showMessage(String text, boolean error) {
        pageMessage.setText(text);
        pageMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(pageMessage, text != null);
    }
}
