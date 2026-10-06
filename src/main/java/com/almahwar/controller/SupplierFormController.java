package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.model.Permission;
import com.almahwar.model.Supplier;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.SupplierService;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static com.almahwar.service.SupplierService.*;

/** Add / edit a supplier. The opening balance is entered once, when the supplier is added. */
public class SupplierFormController {

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label formAlert;
    @FXML private TextField codeField;
    @FXML private Label codeError;
    @FXML private TextField contactField;
    @FXML private Label contactError;
    @FXML private TextField nameField;
    @FXML private Label nameError;
    @FXML private TextField phoneField;
    @FXML private Label phoneError;
    @FXML private Label phoneWarning;
    @FXML private TextField phone2Field;
    @FXML private Label phone2Error;
    @FXML private Label phone2Warning;
    @FXML private TextField areaField;
    @FXML private Label areaError;
    @FXML private TextField countryField;
    @FXML private Label countryError;
    @FXML private TextField addressField;
    @FXML private Label addressError;
    @FXML private TextField emailField;
    @FXML private Label emailError;
    @FXML private VBox openingCard;
    @FXML private Label currencyLabel;
    @FXML private TextField openingField;
    @FXML private ComboBox<String> openingSideCombo;
    @FXML private Label openingError;
    @FXML private TextArea notesArea;
    @FXML private Label notesError;
    @FXML private CheckBox activeCheck;
    @FXML private Button saveButton;

    static final String WE_OWE = "دائن (مستحق للمورد)";
    static final String OWES_US = "مدين (رصيد لصالحنا عند المورد)";

    private final SecurityContext security = AppContext.get().security();
    private final SupplierService suppliers = AppContext.get().suppliers();
    private final FormErrors errors = new FormErrors();

    private SuppliersController host;
    private Integer supplierId;
    private boolean fromDetails;
    private Supplier supplier;

    @FXML
    private void initialize() {
        currencyLabel.setText("(" + AppConfig.getInstance().currencyCode() + ")");
        openingSideCombo.getItems().setAll(WE_OWE, OWES_US);
        openingSideCombo.setValue(WE_OWE);
        NumberInput.install(openingField);
        for (TextField f : List.of(codeField, phoneField, phone2Field)) {
            f.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        }
        errors.register(CODE, codeField, codeError)
                .register(CONTACT_PERSON, contactField, contactError)
                .register(NAME, nameField, nameError)
                .register(PHONE, phoneField, phoneError)
                .register(PHONE2, phone2Field, phone2Error)
                .register(AREA, areaField, areaError)
                .register(COUNTRY, countryField, countryError)
                .register(ADDRESS, addressField, addressError)
                .register(EMAIL, emailField, emailError)
                .register(OPENING_BALANCE, openingField, openingError)
                .register(NOTES, notesArea, notesError);
        phoneField.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) {
                warnSamePhone(phoneField, phoneWarning);
            }
        });
        phone2Field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) {
                warnSamePhone(phone2Field, phone2Warning);
            }
        });
    }

    public void open(SuppliersController host, Integer supplierId, boolean fromDetails) {
        this.host = host;
        this.supplierId = supplierId;
        this.fromDetails = fromDetails;
        boolean adding = supplierId == null;
        titleLabel.setText(adding ? "إضافة مورد جديد" : "تعديل بيانات المورد");
        subtitleLabel.setText(adding
                ? "اترك الكود فارغًا ليُولَّد تلقائيًا."
                : "الرصيد لا يُعدَّل من هنا؛ كل تغيير في الرصيد يكون بقيد في كشف الحساب.");
        saveButton.setText(adding ? "إضافة المورد" : "حفظ التعديلات");
        ViewSupport.show(openingCard, adding && security.hasPermission(Permission.SUPPLIER_BALANCE_VIEW));
        ViewSupport.show(activeCheck, !adding);

        Async.run(() -> adding ? null : suppliers.findById(supplierId).orElse(null), loaded -> {
            if (!adding && loaded == null) {
                host.showList("المورد غير موجود.", null);
                return;
            }
            supplier = loaded == null ? new Supplier() : loaded;
            fill(supplier);
            if (adding) {
                countryField.setText("الكويت");
                Async.run(suppliers::suggestCode, code -> codeField.setPromptText("تلقائي: " + code), e -> { });
            }
            nameField.requestFocus();
        }, error -> host.showList(ErrorMessages.of(error), null));
    }

    private void fill(Supplier s) {
        codeField.setText(nz(s.getSupplierCode()));
        contactField.setText(nz(s.getContactPerson()));
        nameField.setText(nz(s.getName()));
        phoneField.setText(nz(s.getPhone()));
        phone2Field.setText(nz(s.getPhone2()));
        areaField.setText(nz(s.getArea()));
        countryField.setText(nz(s.getCountry()));
        addressField.setText(nz(s.getAddress()));
        emailField.setText(nz(s.getEmail()));
        notesArea.setText(nz(s.getNotes()));
        activeCheck.setSelected(s.isActive());
    }

    private void warnSamePhone(TextField field, Label warning) {
        String text = field.getText();
        ViewSupport.show(warning, false);
        if (text == null || text.isBlank()) {
            return;
        }
        Async.run(() -> suppliers.findSamePhone(text, supplierId), same -> {
            if (!same.isEmpty() && text.equals(field.getText())) {
                warning.setText("تنبيه: الرقم " + PhoneNumbers.format(PhoneNumbers.normalize(text)) + " مسجل أيضًا للمورد: "
                        + same.stream().limit(3).map(s -> s.getName() + " (" + s.getSupplierCode() + ")")
                        .collect(Collectors.joining("، ")) + ". يمكنك المتابعة إذا كان ذلك صحيحًا.");
                ViewSupport.show(warning, true);
            }
        }, e -> { });
    }

    @FXML
    private void onBack() {
        if (fromDetails && supplierId != null) {
            host.showDetails(supplierId, null);
        } else {
            host.showList(null, supplierId);
        }
    }

    @FXML
    private void onSave() {
        errors.clear();
        ViewSupport.show(formAlert, false);
        Supplier s = supplier;
        s.setSupplierCode(codeField.getText());
        s.setContactPerson(contactField.getText());
        s.setName(nameField.getText());
        s.setPhone(phoneField.getText());
        s.setPhone2(phone2Field.getText());
        s.setArea(areaField.getText());
        s.setCountry(countryField.getText());
        s.setAddress(addressField.getText());
        s.setEmail(emailField.getText());
        s.setNotes(notesArea.getText());
        if (supplierId != null) {
            s.setActive(activeCheck.isSelected());
        }

        BigDecimal opening = BigDecimal.ZERO;
        if (supplierId == null && openingCard.isVisible()) {
            try {
                BigDecimal value = NumberInput.parse(openingField.getText());
                if (value != null && value.stripTrailingZeros().scale() > 3) {
                    errors.set(OPENING_BALANCE, "الحد الأقصى 3 منازل عشرية (الفلس).");
                    return;
                }
                opening = value == null ? BigDecimal.ZERO : (OWES_US.equals(openingSideCombo.getValue()) ? value.negate() : value);
            } catch (NumberFormatException e) {
                errors.set(OPENING_BALANCE, "أدخل رقمًا صحيحًا (مثال: 1500.000).");
                return;
            }
        }

        boolean adding = supplierId == null;
        BigDecimal openingBalance = opening;
        saveButton.setDisable(true);
        Async.run(() -> adding ? suppliers.create(s, openingBalance) : suppliers.update(s), saved -> {
            saveButton.setDisable(false);
            String message = adding
                    ? "تمت إضافة المورد \"" + saved.getName() + "\" بالكود " + saved.getSupplierCode() + "."
                    + (openingBalance.signum() != 0
                    ? " الرصيد الافتتاحي " + MoneyUtil.formatWithCurrency(openingBalance.abs()) + " مسجل في كشف الحساب." : "")
                    : "تم حفظ بيانات المورد \"" + saved.getName() + "\".";
            host.showDetails(saved.getSupplierId(), message);
        }, error -> {
            saveButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                showAlert(other != null ? other : "يرجى تصحيح الحقول المحددة باللون الأحمر.");
            } else {
                showAlert(ErrorMessages.of(error));
            }
        });
    }

    private void showAlert(String text) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-error");
        ViewSupport.show(formAlert, true);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
