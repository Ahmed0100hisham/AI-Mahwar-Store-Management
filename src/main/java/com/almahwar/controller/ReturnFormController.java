package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.controller.support.FormErrors;
import com.almahwar.controller.support.NumberInput;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseFilter;
import com.almahwar.model.PurchaseStatus;
import com.almahwar.model.RefundPlan;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.model.ReturnReason;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleFilter;
import com.almahwar.model.SaleStatus;
import com.almahwar.service.ReturnService;
import com.almahwar.service.ValidationException;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.service.ReturnService.*;

/**
 * New return: pick the posted original sale / purchase, then the quantities (never more than what is still
 * returnable), the reason and the method money moves with. The preview shows how the value will be settled; the
 * service decides it again inside the transaction. One request id per form, so a double click never posts twice.
 */
public class ReturnFormController {

    /** Where the form returns to. */
    public interface Host {
        void returnSaved(ReturnDocument saved, String message);

        void returnCancelled();
    }

    /** A posted original document as offered in step 1. */
    public record Candidate(int id, String number, LocalDateTime date, String party, BigDecimal total, BigDecimal paid) {
    }

    /** One editable line: the original line plus the typed quantity. */
    public static final class Line {
        final ReturnLine line;
        final StringProperty quantity = new SimpleStringProperty("");
        final javafx.beans.property.ReadOnlyStringWrapper amount = new javafx.beans.property.ReadOnlyStringWrapper("—");

        Line(ReturnLine line) {
            this.line = line;
        }

        public ReturnLine getLine() {
            return line;
        }
    }

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label formAlert;
    @FXML private VBox pickCard;
    @FXML private Label pickTitle;
    @FXML private TextField pickSearch;
    @FXML private DatePicker pickFrom;
    @FXML private DatePicker pickTo;
    @FXML private TableView<Candidate> originalsTable;
    @FXML private TableColumn<Candidate, String> origNoColumn;
    @FXML private TableColumn<Candidate, String> origDateColumn;
    @FXML private TableColumn<Candidate, String> origPartyColumn;
    @FXML private TableColumn<Candidate, String> origTotalColumn;
    @FXML private TableColumn<Candidate, String> origPaidColumn;
    @FXML private Button pickButton;
    @FXML private VBox linesCard;
    @FXML private Label originalLabel;
    @FXML private Button changeButton;
    @FXML private TableView<Line> linesTable;
    @FXML private TableColumn<Line, String> productColumn;
    @FXML private TableColumn<Line, String> unitColumn;
    @FXML private TableColumn<Line, String> soldColumn;
    @FXML private TableColumn<Line, String> returnedColumn;
    @FXML private TableColumn<Line, String> remainingColumn;
    @FXML private TableColumn<Line, String> priceColumn;
    @FXML private TableColumn<Line, Line> qtyColumn;
    @FXML private TableColumn<Line, String> amountColumn;
    @FXML private Label linesError;
    @FXML private ComboBox<ReturnReason> reasonCombo;
    @FXML private Label reasonError;
    @FXML private Label methodCaption;
    @FXML private ComboBox<PaymentMethod> methodCombo;
    @FXML private Label methodError;
    @FXML private TextField notesField;
    @FXML private Label notesError;
    @FXML private Label valueLabel;
    @FXML private Label accountCaption;
    @FXML private Label accountLabel;
    @FXML private Label refundCaption;
    @FXML private Label refundLabel;
    @FXML private Label planHint;
    @FXML private Button saveButton;

    private final ReturnService returns = AppContext.get().returns();
    private final FormErrors errors = new FormErrors();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));
    private final PauseTransition planDelay = new PauseTransition(Duration.millis(250));

    private Host host;
    private ReturnKind kind;
    private Candidate original;
    private final UUID requestId = UUID.randomUUID();
    private boolean saving;
    private boolean fixedOriginal;

    @FXML
    private void initialize() {
        reasonCombo.getItems().setAll(ReturnReason.values());
        reasonCombo.setConverter(ViewSupport.converter(ReturnReason::getLabelAr));
        methodCombo.getItems().setAll(ReturnService.METHODS);
        methodCombo.setConverter(ViewSupport.converter(PaymentMethod::getLabelAr));
        methodCombo.setValue(PaymentMethod.CASH);
        errors.register(LINES, linesTable, linesError)
                .register(REASON, reasonCombo, reasonError)
                .register(METHOD, methodCombo, methodError)
                .register(NOTES, notesField, notesError);

        originalsTable.setPlaceholder(new Label("لا توجد فواتير معتمدة مطابقة"));
        originalsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        origNoColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().number()));
        origDateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().date() == null ? "" : c.getValue().date().format(WHEN)));
        origPartyColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().party()));
        origTotalColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().total())));
        origPaidColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().paid())));
        for (TableColumn<Candidate, String> col : List.of(origTotalColumn, origPaidColumn)) {
            col.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        origDateColumn.setMinWidth(150);
        originalsTable.setRowFactory(tv -> {
            TableRow<Candidate> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    choose(row.getItem());
                }
            });
            return row;
        });
        pickSearch.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        searchDelay.setOnFinished(e -> searchOriginals());
        pickFrom.valueProperty().addListener((o, a, b) -> searchOriginals());
        pickTo.valueProperty().addListener((o, a, b) -> searchOriginals());
        planDelay.setOnFinished(e -> updatePlan());
        setUpLines();
    }

    /** @param originalId a posted sale / purchase to return from directly, or {@code null} to pick one */
    public void open(Host host, ReturnKind kind, Integer originalId) {
        this.host = host;
        this.kind = kind;
        boolean sale = kind == ReturnKind.SALE;
        titleLabel.setText(sale ? "مرتجع مبيعات جديد" : "مرتجع مشتريات جديد");
        pickTitle.setText(sale ? "١) اختر فاتورة البيع الأصلية (المعتمدة)" : "١) اختر فاتورة الشراء الأصلية (المعتمدة)");
        methodCaption.setText(sale ? "طريقة رد المبلغ (إن وُجد)" : "طريقة استلام المبلغ (إن وُجد)");
        accountCaption.setText(sale ? "يُخصم من مديونية العميل" : "يُخصم من مستحق المورد");
        refundCaption.setText(sale ? "يُرد للعميل نقدًا" : "يُسترد من المورد نقدًا");
        origPartyColumn.setText(sale ? "العميل" : "المورد");
        Async.run(() -> returns.suggestNumber(kind), no -> subtitleLabel.setText("الرقم المتوقع: " + no
                + " (يُثبَّت عند الاعتماد). المرتجع لا يُعدَّل ولا يُحذف بعد اعتماده."), e -> { });
        if (originalId != null) {
            fixedOriginal = true;
            ViewSupport.show(changeButton, false);
            Async.run(() -> candidate(originalId), this::choose, error -> showAlert(ErrorMessages.of(error)));
        } else {
            searchOriginals();
        }
    }

    // ---------- step 1 ----------

    private Candidate candidate(int id) {
        if (kind == ReturnKind.SALE) {
            Sale s = AppContext.get().sales().findById(id).orElseThrow();
            return new Candidate(s.getSaleId(), s.getSaleNo(), s.getSaleDate(), s.getCustomerName(), s.getTotalAmount(),
                    s.getPaidAmount());
        }
        Purchase p = AppContext.get().purchases().findById(id).orElseThrow();
        return new Candidate(p.getPurchaseId(), p.getPurchaseNo(), p.getPurchaseDate(), p.getSupplierName(),
                p.getTotalAmount(), p.getPaidAmount());
    }

    private void searchOriginals() {
        String text = pickSearch.getText();
        var from = pickFrom.getValue();
        var to = pickTo.getValue();
        Async.run(() -> kind == ReturnKind.SALE
                ? AppContext.get().sales().search(new SaleFilter(text, from, to, null, null, null, null, SaleStatus.POSTED))
                .stream().map(s -> new Candidate(s.getSaleId(), s.getSaleNo(), s.getSaleDate(), s.getCustomerName(),
                        s.getTotalAmount(), s.getPaidAmount())).toList()
                : AppContext.get().purchases().search(new PurchaseFilter(text, from, to, null, null, PurchaseStatus.POSTED))
                .stream().map(p -> new Candidate(p.getPurchaseId(), p.getPurchaseNo(), p.getPurchaseDate(), p.getSupplierName(),
                        p.getTotalAmount(), p.getPaidAmount())).toList(),
                rows -> originalsTable.getItems().setAll(rows), error -> showAlert(ErrorMessages.of(error)));
    }

    @FXML
    private void onPick() {
        Candidate c = originalsTable.getSelectionModel().getSelectedItem();
        if (c != null) {
            choose(c);
        }
    }

    private void choose(Candidate c) {
        original = c;
        ViewSupport.show(formAlert, false);
        Async.run(() -> returns.returnableLines(kind, c.id()), lines -> {
            linesTable.getItems().setAll(lines.stream().map(Line::new).toList());
            originalLabel.setText("٢) الفاتورة " + c.number() + "  •  " + c.party() + "  •  الإجمالي "
                    + MoneyUtil.formatWithCurrency(c.total()) + "  •  المدفوع " + MoneyUtil.format(c.paid()));
            ViewSupport.show(pickCard, false);
            ViewSupport.show(linesCard, true);
            recalculate();
        }, error -> {
            showAlert(ErrorMessages.of(error));
            if (fixedOriginal) {
                saveButton.setDisable(true);
            }
        });
    }

    @FXML
    private void onChangeOriginal() {
        ViewSupport.show(linesCard, false);
        ViewSupport.show(pickCard, true);
        original = null;
    }

    // ---------- step 2 ----------

    private void setUpLines() {
        linesTable.setPlaceholder(new Label("لا توجد أصناف"));
        linesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        productColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().line.getProductName()
                + " — " + c.getValue().line.getProductCode()));
        unitColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().line.getUnitName()));
        soldColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().line.getOriginalQuantity())));
        returnedColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().line.getReturnedQuantity())));
        remainingColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().line.getRemainingQuantity())));
        priceColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().line.getUnitPrice())));
        priceColumn.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        amountColumn.setCellValueFactory(c -> c.getValue().amount.getReadOnlyProperty());
        amountColumn.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        qtyColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        qtyColumn.setCellFactory(col -> new TableCell<>() {
            private final TextField field = new TextField();
            private StringProperty bound;

            {
                NumberInput.install(field);
                field.getStyleClass().add("line-input");
                field.setPromptText("0");
                field.textProperty().addListener((o, a, b) -> recalculate());
            }

            @Override
            protected void updateItem(Line line, boolean empty) {
                super.updateItem(line, empty);
                if (bound != null) {
                    field.textProperty().unbindBidirectional(bound);
                    bound = null;
                }
                setText(null);
                if (empty || line == null) {
                    setGraphic(null);
                    return;
                }
                bound = line.quantity;
                field.textProperty().bindBidirectional(bound);
                field.setDisable(line.line.getRemainingQuantity().signum() <= 0);
                setGraphic(field);
            }
        });
        productColumn.setMinWidth(170);
        qtyColumn.setMinWidth(100);
    }

    private static BigDecimal tryParse(String text) {
        try {
            return NumberInput.parse(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal recalculateValue() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : linesTable.getItems()) {
            BigDecimal q = tryParse(l.quantity.get());
            if (q == null || q.signum() <= 0) {
                l.amount.set("—");
                continue;
            }
            BigDecimal amount = MoneyUtil.of(q.multiply(l.line.getUnitPrice()));
            boolean over = q.compareTo(l.line.getRemainingQuantity()) > 0;
            l.amount.set(over ? "أكثر من المتبقي" : MoneyUtil.format(amount));
            if (!over) {
                sum = sum.add(amount);
            }
        }
        return MoneyUtil.of(sum);
    }

    private void recalculate() {
        valueLabel.setText(MoneyUtil.formatWithCurrency(recalculateValue()));
        planDelay.playFromStart();
    }

    private void updatePlan() {
        if (original == null) {
            return;
        }
        BigDecimal value = recalculateValue();
        int id = original.id();
        Async.run(() -> returns.plan(kind, id, value), plan -> showPlan(plan), e -> { });
    }

    private void showPlan(RefundPlan plan) {
        accountLabel.setText(MoneyUtil.format(plan.accountCredit()));
        refundLabel.setText(MoneyUtil.format(plan.refund()));
        boolean sale = kind == ReturnKind.SALE;
        if (plan.balanceBefore() == null && sale && plan.accountCredit().signum() == 0) {
            planHint.setText("عميل نقدي (أو بدون صلاحية عرض الرصيد): تُرد القيمة نقدًا إن لم يكن للعميل مديونية.");
        } else {
            planHint.setText((sale ? "القيمة تُخصم أولًا من مديونية العميل، وما يزيد عنها فقط يُرد نقدًا"
                    : "القيمة تُخصم أولًا من المستحق للمورد، وما يزيد عنه فقط يُسترد نقدًا")
                    + (plan.balanceBefore() == null ? "." : " (الرصيد الحالي " + MoneyUtil.format(plan.balanceBefore()) + ").")
                    + " التسوية النهائية تُحسب عند الاعتماد.");
        }
        ViewSupport.show(methodCombo.getParent(), plan.movesMoney());
    }

    @FXML
    private void onSave() {
        if (saving || original == null) {
            return;   // a second click while the first save is still running
        }
        errors.clear();
        ViewSupport.show(formAlert, false);
        ReturnDocument r = new ReturnDocument();
        r.setKind(kind);
        r.setOriginalId(original.id());
        for (Line l : linesTable.getItems()) {
            String text = l.quantity.get();
            if (text == null || text.isBlank()) {
                continue;
            }
            BigDecimal q = tryParse(text);
            if (q == null) {
                errors.set(LINES, "\"" + l.line.getProductName() + "\": أدخل كمية صحيحة.");
                return;
            }
            ReturnLine req = new ReturnLine();
            req.setOriginalItemId(l.line.getOriginalItemId());
            req.setQuantity(q);
            r.getLines().add(req);
        }
        r.setReason(reasonCombo.getValue());
        r.setRefundMethod(methodCombo.getValue());
        r.setNotes(notesField.getText());
        r.setRequestId(requestId);

        saving = true;
        saveButton.setDisable(true);
        Async.run(() -> returns.create(r), saved -> {
            saving = false;
            boolean sale = kind == ReturnKind.SALE;
            String message = "تم اعتماد " + kind.getLabelAr() + " " + saved.getReturnNo() + " بقيمة "
                    + MoneyUtil.formatWithCurrency(saved.getTotalAmount())
                    + (saved.getRefundAmount().signum() > 0 ? (sale ? "، رُد للعميل " : "، استُرد من المورد ")
                    + MoneyUtil.format(saved.getRefundAmount()) + " (" + saved.getRefundMethod().getLabelAr() + ")" : "")
                    + (saved.getAccountAmount().signum() > 0 ? "، خُصم من الحساب " + MoneyUtil.format(saved.getAccountAmount()) : "")
                    + (sale ? "، وأُعيدت الكميات للمخزون." : "، وخُصمت الكميات من المخزون.");
            host.returnSaved(saved, message);
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
        host.returnCancelled();
    }

    private void showAlert(String text) {
        formAlert.setText(text);
        formAlert.getStyleClass().setAll("label", "form-alert", "form-alert-error");
        ViewSupport.show(formAlert, true);
    }
}
