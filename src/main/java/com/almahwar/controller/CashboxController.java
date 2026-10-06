package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.model.CashFilter;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSource;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.Permission;
import com.almahwar.model.User;
import com.almahwar.service.CashboxService;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.service.CashboxService.*;

/** The cashbox: figures, every movement with filters, and manual deposits / withdrawals. */
public class CashboxController {

    @FXML private Button depositButton;
    @FXML private Button withdrawButton;
    @FXML private Label pageMessage;
    @FXML private Label balanceLabel;
    @FXML private Label todayInLabel;
    @FXML private Label todayLabel;
    @FXML private Label todayOutLabel;
    @FXML private Label todayNetLabel;
    @FXML private VBox formCard;
    @FXML private Label formTitle;
    @FXML private Label formAlert;
    @FXML private TextField amountField;
    @FXML private Label amountError;
    @FXML private ComboBox<PaymentMethod> methodCombo;
    @FXML private Label methodError;
    @FXML private TextField reasonField;
    @FXML private Label reasonError;
    @FXML private TextField referenceField;
    @FXML private Label referenceError;
    @FXML private TextField notesField;
    @FXML private Label notesError;
    @FXML private Button saveButton;
    @FXML private TextField searchField;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private ComboBox<CashMovement.Direction> directionFilter;
    @FXML private ComboBox<CashSource> sourceFilter;
    @FXML private ComboBox<PaymentMethod> methodFilter;
    @FXML private ComboBox<User> userFilter;
    @FXML private TableView<CashMovement> movementsTable;
    @FXML private TableColumn<CashMovement, String> dateColumn;
    @FXML private TableColumn<CashMovement, String> noColumn;
    @FXML private TableColumn<CashMovement, String> typeColumn;
    @FXML private TableColumn<CashMovement, CashMovement> directionColumn;
    @FXML private TableColumn<CashMovement, String> amountColumn;
    @FXML private TableColumn<CashMovement, String> methodColumn;
    @FXML private TableColumn<CashMovement, String> documentColumn;
    @FXML private TableColumn<CashMovement, String> referenceColumn;
    @FXML private TableColumn<CashMovement, String> userColumn;
    @FXML private TableColumn<CashMovement, String> notesColumn;
    @FXML private Label countLabel;

    private final SecurityContext security = AppContext.get().security();
    private final CashboxService cashbox = AppContext.get().cashbox();
    private final FormErrors errors = new FormErrors();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    private CashMovement.Direction formDirection;
    private UUID requestId;
    private boolean saving;
    private boolean loadingFilters;

    @FXML
    private void initialize() {
        boolean canAdjust = security.hasPermission(Permission.CASH_ADJUST);
        ViewSupport.show(depositButton, canAdjust);
        ViewSupport.show(withdrawButton, canAdjust);
        methodCombo.getItems().setAll(CashboxService.METHODS);
        methodCombo.setConverter(ViewSupport.converter(PaymentMethod::getLabelAr));
        NumberInput.install(amountField);
        errors.register(AMOUNT, amountField, amountError)
                .register(METHOD, methodCombo, methodError)
                .register(REASON, reasonField, reasonError)
                .register(REFERENCE, referenceField, referenceError)
                .register(NOTES, notesField, notesError);
        setUpFilters();
        setUpTable();
        refresh();
    }

    // ---------- Filters ----------

    private void setUpFilters() {
        PurchasesController.nullable(directionFilter, CashMovement.Direction.values(), CashMovement.Direction::getLabelAr, "وارد وصادر");
        PurchasesController.nullable(sourceFilter, CashSource.values(), CashSource::getLabelAr, "كل الأنواع");
        PurchasesController.nullable(methodFilter, CashboxService.METHODS.toArray(new PaymentMethod[0]),
                PaymentMethod::getLabelAr, "كل الطرق");
        userFilter.setConverter(ViewSupport.converter(u -> u.getUserId() == null ? "كل المستخدمين" : u.getFullName()));
        User all = new User();
        userFilter.getItems().setAll(all);
        userFilter.setValue(all);
        Async.run(cashbox::users, users -> {
            loadingFilters = true;
            List<User> items = new ArrayList<>(List.of(all));
            items.addAll(users);
            userFilter.getItems().setAll(items);
            userFilter.setValue(all);
            loadingFilters = false;
        }, e -> { });
        searchField.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> refresh());
        for (ComboBox<?> c : List.of(directionFilter, sourceFilter, methodFilter, userFilter)) {
            c.valueProperty().addListener((o, a, b) -> {
                if (!loadingFilters) {
                    refresh();
                }
            });
        }
        fromDate.valueProperty().addListener((o, a, b) -> refresh());
        toDate.valueProperty().addListener((o, a, b) -> refresh());
    }

    @FXML
    private void onClearFilters() {
        loadingFilters = true;
        searchField.clear();
        fromDate.setValue(null);
        toDate.setValue(null);
        for (ComboBox<?> c : List.of(directionFilter, sourceFilter, methodFilter, userFilter)) {
            c.getSelectionModel().selectFirst();
        }
        loadingFilters = false;
        refresh();
    }

    // ---------- Table ----------

    private void setUpTable() {
        movementsTable.setPlaceholder(new Label("لا توجد حركات مطابقة"));
        movementsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        dateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getTransactionDate() == null ? ""
                : c.getValue().getTransactionDate().format(WHEN)));
        noColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getTransactionNo()));
        typeColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getSource().getLabelAr()));
        directionColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        directionColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(CashMovement m, boolean empty) {
                super.updateItem(m, empty);
                setText(null);
                setGraphic(empty || m == null ? null : ViewSupport.badge(m.getDirection().getLabelAr(),
                        m.getDirection() == CashMovement.Direction.IN ? "badge-success" : "badge-danger"));
            }
        });
        amountColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().getSignedAmount())));
        amountColumn.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        methodColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getPaymentMethod().getLabelAr()));
        documentColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getDocumentNo()));
        referenceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getReferenceNo()));
        userColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getUserName()));
        notesColumn.setCellValueFactory(c -> {
            CashMovement m = c.getValue();
            String text = m.getDescription() == null ? "" : m.getDescription();
            return new ReadOnlyStringWrapper(m.getNotes() == null ? text : text + " — " + m.getNotes());
        });
        dateColumn.setMinWidth(150);
        noColumn.setMinWidth(90);
        typeColumn.setMinWidth(100);
    }

    private void refresh() {
        User user = userFilter.getValue();
        CashFilter filter = new CashFilter(searchField.getText(), fromDate.getValue(), toDate.getValue(),
                directionFilter.getValue(), sourceFilter.getValue(), methodFilter.getValue(),
                user == null ? null : user.getUserId());
        Async.run(() -> new Object[]{cashbox.summary(), cashbox.search(filter)}, data -> {
            com.almahwar.model.CashSummary s = (com.almahwar.model.CashSummary) data[0];
            @SuppressWarnings("unchecked") List<CashMovement> rows = (List<CashMovement>) data[1];
            balanceLabel.setText(MoneyUtil.formatWithCurrency(s.balance()));
            balanceLabel.getStyleClass().remove("negative");
            if (s.balance().signum() < 0) {
                balanceLabel.getStyleClass().add("negative");
            }
            todayInLabel.setText(MoneyUtil.formatWithCurrency(s.todayIn()));
            todayOutLabel.setText(MoneyUtil.formatWithCurrency(s.todayOut()));
            todayNetLabel.setText(MoneyUtil.formatWithCurrency(s.todayNet()));
            todayLabel.setText("مبيعات، تحصيل عملاء، إيداع");
            movementsTable.getItems().setAll(rows);
            BigDecimal in = rows.stream().filter(m -> m.getDirection() == CashMovement.Direction.IN)
                    .map(CashMovement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal out = rows.stream().filter(m -> m.getDirection() == CashMovement.Direction.OUT)
                    .map(CashMovement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            countLabel.setText(rows.size() + " حركة  •  وارد " + MoneyUtil.format(in) + "  •  صادر "
                    + MoneyUtil.format(out) + "  •  الصافي " + MoneyUtil.formatWithCurrency(in.subtract(out))
                    + (rows.size() >= CashboxService.MAX_LIST_ROWS ? "  •  استخدم الفلاتر لعرض حركات أقدم" : ""));
        }, error -> showMessage(ErrorMessages.of(error), true));
    }

    // ---------- Manual deposit / withdrawal ----------

    @FXML
    private void onDeposit() {
        openForm(CashMovement.Direction.IN);
    }

    @FXML
    private void onWithdraw() {
        openForm(CashMovement.Direction.OUT);
    }

    private void openForm(CashMovement.Direction direction) {
        formDirection = direction;
        requestId = UUID.randomUUID();   // one request per opening: a double click never records twice
        errors.clear();
        ViewSupport.show(formAlert, false);
        amountField.clear();
        reasonField.clear();
        referenceField.clear();
        notesField.clear();
        methodCombo.setValue(PaymentMethod.CASH);
        boolean in = direction == CashMovement.Direction.IN;
        formTitle.setText(in ? "إيداع يدوي في الخزنة" : "سحب يدوي من الخزنة");
        saveButton.setText(in ? "حفظ الإيداع" : "حفظ السحب");
        ViewSupport.show(formCard, true);
        amountField.requestFocus();
    }

    @FXML
    private void onCancelForm() {
        ViewSupport.show(formCard, false);
    }

    @FXML
    private void onSave() {
        if (saving || formDirection == null) {
            return;
        }
        errors.clear();
        ViewSupport.show(formAlert, false);
        CashMovement m = new CashMovement();
        BigDecimal amount;
        try {
            amount = NumberInput.parse(amountField.getText());
        } catch (NumberFormatException e) {
            errors.set(AMOUNT, "أدخل رقمًا صحيحًا.");
            return;
        }
        m.setAmount(amount);
        m.setPaymentMethod(methodCombo.getValue());
        m.setDescription(reasonField.getText());
        m.setReferenceNo(referenceField.getText());
        m.setNotes(notesField.getText());
        m.setRequestId(requestId);
        boolean in = formDirection == CashMovement.Direction.IN;
        saving = true;
        saveButton.setDisable(true);
        Async.run(() -> in ? cashbox.deposit(m) : cashbox.withdraw(m), saved -> {
            saving = false;
            saveButton.setDisable(false);
            ViewSupport.show(formCard, false);
            showMessage("تم تسجيل " + (in ? "الإيداع " : "السحب ") + saved.getTransactionNo() + " بمبلغ "
                    + MoneyUtil.formatWithCurrency(saved.getAmount()) + ".", false);
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
