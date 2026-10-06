package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.model.OutstandingParty;
import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.service.PaymentService;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.almahwar.service.PaymentService.*;

/**
 * Records a customer collection (سند قبض) or a supplier payment (سند صرف). The list offers only parties with
 * something outstanding; the amount cannot exceed it (checked again by the service, with the party locked).
 * Each opening of the form has one request id, so a double click or a retry never pays twice.
 */
public class PartyPaymentController {

    /** Where the form returns to. */
    public interface Host {
        /** After saving: e.g. the party's details page with the message. */
        void paymentSaved(int partyId, String message);

        void paymentCancelled();
    }

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label formAlert;
    @FXML private Label partyCaption;
    @FXML private ComboBox<OutstandingParty> partyCombo;
    @FXML private Label partyError;
    @FXML private TextField amountField;
    @FXML private Button fullButton;
    @FXML private Label amountError;
    @FXML private ComboBox<PaymentMethod> methodCombo;
    @FXML private Label methodError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextField referenceField;
    @FXML private Label referenceError;
    @FXML private TextField notesField;
    @FXML private Label notesError;
    @FXML private Label outstandingCaption;
    @FXML private Label outstandingLabel;
    @FXML private Label paymentLabel;
    @FXML private Label afterLabel;
    @FXML private Label effectHint;
    @FXML private Button saveButton;

    private final PaymentService payments = AppContext.get().payments();
    private final FormErrors errors = new FormErrors();

    private Host host;
    private PartyType party;
    private final UUID requestId = UUID.randomUUID();
    private boolean saving;

    @FXML
    private void initialize() {
        methodCombo.getItems().setAll(PaymentService.METHODS);
        methodCombo.setConverter(ViewSupport.converter(PaymentMethod::getLabelAr));
        methodCombo.setValue(PaymentMethod.CASH);
        datePicker.setValue(LocalDate.now());
        NumberInput.install(amountField);
        amountField.textProperty().addListener((o, a, b) -> recalculate());
        partyCombo.valueProperty().addListener((o, a, b) -> recalculate());
        errors.register(PARTY, partyCombo, partyError)
                .register(AMOUNT, amountField, amountError)
                .register(METHOD, methodCombo, methodError)
                .register(DATE, datePicker, dateError)
                .register(REFERENCE, referenceField, referenceError)
                .register(NOTES, notesField, notesError);
    }

    /** @param partyId the customer / supplier to preselect, or {@code null} to choose */
    public void open(Host host, PartyType party, Integer partyId) {
        this.host = host;
        this.party = party;
        boolean customer = party == PartyType.CUSTOMER;
        titleLabel.setText(customer ? "تحصيل من عميل (سند قبض)" : "سداد لمورد (سند صرف)");
        partyCaption.setText(customer ? "العميل *" : "المورد *");
        outstandingCaption.setText(customer ? "المستحق على العميل" : "المستحق للمورد");
        saveButton.setText(customer ? "حفظ سند القبض" : "حفظ سند الصرف");
        effectHint.setText(customer
                ? "عند الحفظ: يُسجَّل السند، ويُخصم المبلغ من حساب العميل (PAYMENT)، ويدخل الخزنة — في عملية واحدة."
                : "عند الحفظ: يُسجَّل السند، ويُخصم المبلغ من حساب المورد (PAYMENT)، ويخرج من الخزنة — في عملية واحدة.");
        partyCombo.setConverter(ViewSupport.converter(p -> p.name() + "  (" + p.code() + ")  —  المستحق "
                + MoneyUtil.format(p.outstanding()) + (p.active() ? "" : "  •  معطّل")));

        Async.run(() -> new Object[]{payments.outstandingParties(party), payments.suggestNumber(party)}, data -> {
            @SuppressWarnings("unchecked") List<OutstandingParty> list = (List<OutstandingParty>) data[0];
            partyCombo.getItems().setAll(list);
            subtitleLabel.setText("الرقم المتوقع: " + data[1] + " (يُثبَّت عند الحفظ). تظهر فقط الأطراف التي عليها/لها مبلغ مستحق.");
            if (partyId != null) {
                OutstandingParty selected = list.stream().filter(p -> p.partyId() == partyId).findFirst().orElse(null);
                partyCombo.setValue(selected);
                if (selected == null) {
                    showAlert(customer ? "لا يوجد مبلغ مستحق على هذا العميل حاليًا." : "لا يوجد مبلغ مستحق لهذا المورد حاليًا.");
                    saveButton.setDisable(true);
                }
                amountField.requestFocus();
            } else {
                partyCombo.requestFocus();
            }
            recalculate();
        }, error -> showAlert(ErrorMessages.of(error)));
    }

    private void recalculate() {
        OutstandingParty p = partyCombo.getValue();
        BigDecimal amount = tryParse(amountField.getText());
        outstandingLabel.setText(p == null ? "—" : MoneyUtil.format(p.outstanding()));
        paymentLabel.setText(amount == null ? "—" : MoneyUtil.format(amount));
        if (p == null || amount == null) {
            afterLabel.setText("—");
            return;
        }
        BigDecimal after = p.outstanding().subtract(amount);
        afterLabel.setText(MoneyUtil.format(after));
        afterLabel.getStyleClass().remove("negative");
        if (after.signum() < 0) {
            afterLabel.getStyleClass().add("negative");   // more than owed: the service will refuse it
        }
    }

    private static BigDecimal tryParse(String text) {
        try {
            return NumberInput.parse(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @FXML
    private void onFullAmount() {
        OutstandingParty p = partyCombo.getValue();
        if (p != null) {
            amountField.setText(NumberInput.text(p.outstanding()));
        }
    }

    @FXML
    private void onSave() {
        if (saving) {
            return;   // a second click while the first save is still running
        }
        errors.clear();
        ViewSupport.show(formAlert, false);
        PartyPayment pay = new PartyPayment();
        pay.setPartyType(party);
        pay.setPartyId(partyCombo.getValue() == null ? null : partyCombo.getValue().partyId());
        BigDecimal amount = tryParse(amountField.getText());
        if (amount == null && !amountField.getText().isBlank()) {
            errors.set(AMOUNT, "أدخل رقمًا صحيحًا.");
            return;
        }
        pay.setAmount(amount);
        pay.setPaymentMethod(methodCombo.getValue());
        LocalDate day = datePicker.getValue();
        pay.setPaymentDay(day == null || day.equals(LocalDate.now()) ? null : day);   // today = the server's "now"
        pay.setReferenceNo(referenceField.getText());
        pay.setNotes(notesField.getText());
        pay.setRequestId(requestId);

        saving = true;
        saveButton.setDisable(true);
        Async.run(() -> payments.record(pay), saved -> {
            saving = false;
            String who = party == PartyType.CUSTOMER ? "العميل" : "المورد";
            host.paymentSaved(saved.getPartyId(), "تم حفظ " + (party == PartyType.CUSTOMER ? "سند القبض " : "سند الصرف ")
                    + saved.getPaymentNo() + " بمبلغ " + MoneyUtil.formatWithCurrency(saved.getAmount())
                    + ". رصيد " + who + " الآن " + MoneyUtil.format(saved.getBalanceAfter()) + ".");
        }, error -> {
            saving = false;
            saveButton.setDisable(false);
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                showAlert(other != null ? other : "يرجى تصحيح الأخطاء الموضحة.");
            } else {
                showAlert(ErrorMessages.of(error) + " لم يُحفظ أي شيء.");
            }
        });
    }

    @FXML
    private void onCancel() {
        host.paymentCancelled();
    }

    private void showAlert(String text) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-error");
        ViewSupport.show(formAlert, true);
    }
}
