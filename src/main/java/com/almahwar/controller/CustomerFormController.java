package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.model.Customer;
import com.almahwar.model.CustomerType;
import com.almahwar.model.Permission;
import com.almahwar.service.CustomerService;
import com.almahwar.service.SecurityContext;
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

import static com.almahwar.service.CustomerService.*;

/** Add / edit a customer. The opening balance is entered once, when the customer is added. */
public class CustomerFormController {

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label formAlert;
    @FXML private TextField codeField;
    @FXML private Label codeError;
    @FXML private ComboBox<CustomerType> typeCombo;
    @FXML private Label typeError;
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
    @FXML private TextField emailField;
    @FXML private Label emailError;
    @FXML private TextField addressField;
    @FXML private Label addressError;
    @FXML private VBox accountCard;
    @FXML private Label currencyLabel;
    @FXML private TextField creditLimitField;
    @FXML private Label creditLimitError;
    @FXML private VBox openingBox;
    @FXML private TextField openingField;
    @FXML private ComboBox<String> openingSideCombo;
    @FXML private Label openingError;
    @FXML private Label accountHint;
    @FXML private TextArea notesArea;
    @FXML private Label notesError;
    @FXML private CheckBox activeCheck;
    @FXML private Button saveButton;

    static final String OWES_US = "مدين (مستحق على العميل)";
    static final String WE_OWE = "دائن (رصيد لصالح العميل)";

    private final SecurityContext security = AppContext.get().security();
    private final CustomerService customers = AppContext.get().customers();
    private final FormErrors errors = new FormErrors();

    private CustomersController host;
    private Integer customerId;
    private boolean fromDetails;
    private Customer customer;
    private boolean canSeeBalances;

    @FXML
    private void initialize() {
        canSeeBalances = security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW);
        ViewSupport.show(accountCard, canSeeBalances);
        currencyLabel.setText("(" + AppConfig.getInstance().currencyCode() + ")");
        typeCombo.getItems().setAll(CustomerType.values());
        typeCombo.setValue(CustomerType.RETAIL);
        openingSideCombo.getItems().setAll(OWES_US, WE_OWE);
        openingSideCombo.setValue(OWES_US);
        NumberInput.install(creditLimitField);
        NumberInput.install(openingField);
        for (TextField f : List.of(codeField, phoneField, phone2Field)) {
            f.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        }
        errors.register(CODE, codeField, codeError)
                .register(TYPE, typeCombo, typeError)
                .register(NAME, nameField, nameError)
                .register(PHONE, phoneField, phoneError)
                .register(PHONE2, phone2Field, phone2Error)
                .register(AREA, areaField, areaError)
                .register(EMAIL, emailField, emailError)
                .register(ADDRESS, addressField, addressError)
                .register(CREDIT_LIMIT, creditLimitField, creditLimitError)
                .register(OPENING_BALANCE, openingField, openingError)
                .register(NOTES, notesArea, notesError);

        // Same number used by another customer: a warning only (families and companies share numbers)
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

    /** @param customerId {@code null} to add a new customer */
    public void open(CustomersController host, Integer customerId, boolean fromDetails) {
        this.host = host;
        this.customerId = customerId;
        this.fromDetails = fromDetails;
        boolean adding = customerId == null;
        titleLabel.setText(adding ? "إضافة عميل جديد" : "تعديل بيانات العميل");
        subtitleLabel.setText(adding
                ? "اترك الكود فارغًا ليُولَّد تلقائيًا. الرصيد الافتتاحي يُسجَّل كقيد في كشف حساب العميل."
                : "الرصيد لا يُعدَّل من هنا؛ كل تغيير في الرصيد يكون بقيد في كشف الحساب.");
        saveButton.setText(adding ? "إضافة العميل" : "حفظ التعديلات");
        ViewSupport.show(openingBox, adding);
        ViewSupport.show(activeCheck, !adding);
        accountHint.setText(adding
                ? "حد الائتمان 0 يعني أن العميل يشتري نقدًا فقط."
                : "حد الائتمان 0 يعني أن العميل يشتري نقدًا فقط. لتصحيح الرصيد تُستخدم قيود التسوية في المراحل القادمة.");

        Async.run(() -> adding ? null : customers.findById(customerId).orElse(null), loaded -> {
            customer = loaded == null ? new Customer() : loaded;
            if (!adding && loaded == null) {
                host.showList("العميل غير موجود.", null);
                return;
            }
            fill(customer);
            if (adding) {
                Async.run(customers::suggestCode, code -> codeField.setPromptText("تلقائي: " + code), e -> { });
            }
            nameField.requestFocus();
        }, error -> host.showList(ErrorMessages.of(error), null));
    }

    private void fill(Customer c) {
        codeField.setText(nz(c.getCustomerCode()));
        codeField.setEditable(!c.isCashCustomer());
        typeCombo.setValue(c.getCustomerType());
        nameField.setText(nz(c.getName()));
        phoneField.setText(nz(c.getPhone()));
        phone2Field.setText(nz(c.getPhone2()));
        areaField.setText(nz(c.getArea()));
        emailField.setText(nz(c.getEmail()));
        addressField.setText(nz(c.getAddress()));
        creditLimitField.setText(c.getCreditLimit() == null || c.getCustomerId() == null ? "" : NumberInput.text(c.getCreditLimit()));
        notesArea.setText(nz(c.getNotes()));
        activeCheck.setSelected(c.isActive());
        activeCheck.setDisable(c.isCashCustomer());
    }

    private void warnSamePhone(TextField field, Label warning) {
        String text = field.getText();
        ViewSupport.show(warning, false);
        if (text == null || text.isBlank()) {
            return;
        }
        Async.run(() -> customers.findSamePhone(text, customerId), same -> {
            if (!same.isEmpty() && text.equals(field.getText())) {
                warning.setText("تنبيه: الرقم " + PhoneNumbers.format(PhoneNumbers.normalize(text)) + " مسجل أيضًا للعميل: "
                        + same.stream().limit(3).map(c -> c.getName() + " (" + c.getCustomerCode() + ")")
                        .collect(Collectors.joining("، ")) + ". يمكنك المتابعة إذا كان ذلك صحيحًا.");
                ViewSupport.show(warning, true);
            }
        }, e -> { });
    }

    @FXML
    private void onBack() {
        if (fromDetails && customerId != null) {
            host.showDetails(customerId, null);
        } else {
            host.showList(null, customerId);
        }
    }

    @FXML
    private void onSave() {
        errors.clear();
        ViewSupport.show(formAlert, false);
        Customer c = customer;
        c.setCustomerCode(codeField.getText());
        c.setCustomerType(typeCombo.getValue());
        c.setName(nameField.getText());
        c.setPhone(phoneField.getText());
        c.setPhone2(phone2Field.getText());
        c.setArea(areaField.getText());
        c.setEmail(emailField.getText());
        c.setAddress(addressField.getText());
        c.setNotes(notesArea.getText());
        if (customerId != null) {
            c.setActive(activeCheck.isSelected());
        }

        BigDecimal opening = null;
        boolean ok = true;
        if (canSeeBalances) {
            BigDecimal limit = number(creditLimitField, CREDIT_LIMIT);
            ok = limit != null || creditLimitField.getText().isBlank();
            c.setCreditLimit(limit == null ? BigDecimal.ZERO : limit);
            if (customerId == null) {
                BigDecimal value = number(openingField, OPENING_BALANCE);
                ok &= value != null || openingField.getText().isBlank();
                opening = value == null ? BigDecimal.ZERO : (WE_OWE.equals(openingSideCombo.getValue()) ? value.negate() : value);
            }
        }
        if (!ok) {
            showAlert("يرجى تصحيح الحقول المحددة باللون الأحمر.", true);
            return;
        }

        boolean adding = customerId == null;
        BigDecimal openingBalance = opening;
        saveButton.setDisable(true);
        Async.run(() -> adding ? customers.create(c, openingBalance) : customers.update(c), saved -> {
            saveButton.setDisable(false);
            String message = adding
                    ? "تمت إضافة العميل \"" + saved.getName() + "\" بالكود " + saved.getCustomerCode() + "."
                    + (openingBalance != null && openingBalance.signum() != 0
                    ? " الرصيد الافتتاحي " + MoneyUtil.formatWithCurrency(openingBalance.abs()) + " مسجل في كشف الحساب." : "")
                    : "تم حفظ بيانات العميل \"" + saved.getName() + "\".";
            host.showDetails(saved.getCustomerId(), message);
        }, error -> {
            saveButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                showAlert(other != null ? other : "يرجى تصحيح الحقول المحددة باللون الأحمر.", true);
            } else {
                showAlert(ErrorMessages.of(error), true);
            }
        });
    }

    private BigDecimal number(TextField field, String key) {
        try {
            BigDecimal value = NumberInput.parse(field.getText());
            if (value != null && value.stripTrailingZeros().scale() > 3) {
                errors.set(key, "الحد الأقصى 3 منازل عشرية (الفلس).");
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            errors.set(key, "أدخل رقمًا صحيحًا (مثال: 250.500).");
            return null;
        }
    }

    private void showAlert(String text, boolean error) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(formAlert, true);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
