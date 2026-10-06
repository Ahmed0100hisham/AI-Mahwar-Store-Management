package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.ExpenseFilter;
import com.almahwar.model.PaymentMethod;
import com.almahwar.service.ExpenseService;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.service.ExpenseService.*;

/** Expenses: record a new expense (cash out in the same transaction) and browse / filter the list. */
public class ExpensesController {

    @FXML private Label pageMessage;
    @FXML private Label numberHint;
    @FXML private Label formAlert;
    @FXML private ComboBox<ExpenseCategory> categoryCombo;
    @FXML private Label categoryError;
    @FXML private TextField descriptionField;
    @FXML private Label descriptionError;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private ComboBox<PaymentMethod> methodCombo;
    @FXML private Label methodError;
    @FXML private DatePicker datePicker;
    @FXML private Label dateError;
    @FXML private TextField referenceField;
    @FXML private Label referenceError;
    @FXML private TextField notesField;
    @FXML private Label notesError;
    @FXML private Button saveButton;
    @FXML private TextField searchField;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private ComboBox<ExpenseCategory> categoryFilter;
    @FXML private TableView<Expense> expensesTable;
    @FXML private TableColumn<Expense, String> noColumn;
    @FXML private TableColumn<Expense, String> dateColumn;
    @FXML private TableColumn<Expense, String> categoryColumn;
    @FXML private TableColumn<Expense, String> descriptionColumn;
    @FXML private TableColumn<Expense, String> amountColumn;
    @FXML private TableColumn<Expense, String> methodColumn;
    @FXML private TableColumn<Expense, String> referenceColumn;
    @FXML private TableColumn<Expense, String> userColumn;
    @FXML private Label countLabel;

    private final ExpenseService expenses = AppContext.get().expenses();
    private final FormErrors errors = new FormErrors();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));
    private UUID requestId = UUID.randomUUID();
    private boolean saving;

    @FXML
    private void initialize() {
        categoryCombo.getItems().setAll(ExpenseCategory.values());
        categoryCombo.setConverter(ViewSupport.converter(ExpenseCategory::getLabelAr));
        methodCombo.getItems().setAll(ExpenseService.METHODS);
        methodCombo.setConverter(ViewSupport.converter(PaymentMethod::getLabelAr));
        methodCombo.setValue(PaymentMethod.CASH);
        datePicker.setValue(LocalDate.now());
        NumberInput.install(amountField);
        errors.register(CATEGORY, categoryCombo, categoryError)
                .register(DESCRIPTION, descriptionField, descriptionError)
                .register(AMOUNT, amountField, amountError)
                .register(METHOD, methodCombo, methodError)
                .register(DATE, datePicker, dateError)
                .register(REFERENCE, referenceField, referenceError)
                .register(NOTES, notesField, notesError);

        PurchasesController.nullable(categoryFilter, ExpenseCategory.values(), ExpenseCategory::getLabelAr, "كل الأنواع");
        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh());
        categoryFilter.valueProperty().addListener((o, a, b) -> refresh());
        fromDate.valueProperty().addListener((o, a, b) -> refresh());
        toDate.valueProperty().addListener((o, a, b) -> refresh());

        expensesTable.setPlaceholder(new Label("لا توجد مصروفات مطابقة"));
        expensesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        noColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getExpenseNo()));
        dateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getExpenseDate() == null ? ""
                : c.getValue().getExpenseDate().format(WHEN)));
        categoryColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getCategory().getLabelAr()));
        descriptionColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getDescription()));
        amountColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().getAmount())));
        amountColumn.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        methodColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getPaymentMethod().getLabelAr()));
        referenceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getReferenceNo()));
        userColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUserName()));
        noColumn.setMinWidth(100);
        dateColumn.setMinWidth(130);
        showNumber();
        refresh();
    }

    private void showNumber() {
        Async.run(expenses::suggestNumber, no -> numberHint.setText("الرقم المتوقع: " + no + " — يُخصم المبلغ من الخزنة عند الحفظ."),
                e -> { });
    }

    private void refresh() {
        ExpenseFilter filter = new ExpenseFilter(searchField.getText(), fromDate.getValue(), toDate.getValue(),
                categoryFilter.getValue());
        Async.run(() -> new Object[]{expenses.search(filter), expenses.total(filter)}, data -> {
            @SuppressWarnings("unchecked") List<Expense> rows = (List<Expense>) data[0];
            expensesTable.getItems().setAll(rows);
            countLabel.setText(rows.size() + " مصروف  •  الإجمالي " + MoneyUtil.formatWithCurrency((BigDecimal) data[1])
                    + (rows.size() >= ExpenseService.MAX_LIST_ROWS ? "  •  استخدم الفلاتر لعرض مصروفات أقدم" : ""));
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        fromDate.setValue(null);
        toDate.setValue(null);
        categoryFilter.getSelectionModel().selectFirst();
        refresh();
    }

    @FXML
    private void onSave() {
        if (saving) {
            return;   // a second click while the first save is still running
        }
        errors.clear();
        ViewSupport.show(formAlert, false);
        Expense e = new Expense();
        e.setCategory(categoryCombo.getValue());
        e.setDescription(descriptionField.getText());
        try {
            e.setAmount(NumberInput.parse(amountField.getText()));
        } catch (NumberFormatException ex) {
            errors.set(AMOUNT, "أدخل رقمًا صحيحًا.");
            return;
        }
        e.setPaymentMethod(methodCombo.getValue());
        LocalDate day = datePicker.getValue();
        e.setExpenseDay(day == null || day.equals(LocalDate.now()) ? null : day);
        e.setReferenceNo(referenceField.getText());
        e.setNotes(notesField.getText());
        e.setRequestId(requestId);
        saving = true;
        saveButton.setDisable(true);
        Async.run(() -> expenses.create(e), saved -> {
            saving = false;
            saveButton.setDisable(false);
            requestId = UUID.randomUUID();   // the next expense is a new request
            descriptionField.clear();
            amountField.clear();
            referenceField.clear();
            notesField.clear();
            categoryCombo.setValue(null);
            datePicker.setValue(LocalDate.now());
            showMessage("تم حفظ المصروف " + saved.getExpenseNo() + " (" + saved.getCategory().getLabelAr() + ") بمبلغ "
                    + MoneyUtil.formatWithCurrency(saved.getAmount()) + " وخصمه من الخزنة.", false);
            showNumber();
            refresh();
        }, error -> {
            saving = false;
            saveButton.setDisable(false);
            String text;
            if (error instanceof ValidationException ve) {
                String other = errors.show(ve);
                text = other != null ? other : "يرجى تصحيح الأخطاء الموضحة.";
            } else {
                text = ErrorMessages.of(error) + " لم يُحفظ أي شيء.";
            }
            formAlert.setText(text);
            formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-error");
            ViewSupport.show(formAlert, true);
        });
    }

    private void showMessage(String text, boolean error) {
        pageMessage.setText(text);
        pageMessage.getStyleClass().setAll("label", "form-alert", error ? "form-alert-error" : "form-alert-info");
        ViewSupport.show(pageMessage, true);
    }
}
