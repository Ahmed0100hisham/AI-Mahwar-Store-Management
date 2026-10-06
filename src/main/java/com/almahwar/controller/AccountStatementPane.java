package com.almahwar.controller;

import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.util.MoneyUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

/**
 * Account statement (كشف حساب) of one customer or supplier: date range and text filters,
 * the entries with debit / credit / running balance, and the period totals.
 * Shared by the customer and supplier detail pages.
 */
public class AccountStatementPane extends VBox {

    /** Loads a statement in the background: (from, to, search) → statement. */
    @FunctionalInterface
    public interface Loader {
        AccountStatement load(LocalDate from, LocalDate to, String search);
    }

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d/M/yyyy  hh:mm a", Locale.forLanguageTag("ar"));

    private final Loader loader;
    private final DatePicker fromDate = new DatePicker();
    private final DatePicker toDate = new DatePicker();
    private final TextField searchField = new TextField();
    private final Label openingLabel = new Label();
    private final Label debitLabel = new Label();
    private final Label creditLabel = new Label();
    private final Label closingLabel = new Label();
    private final Label message = new Label();
    private final TableView<LedgerEntry> table = new TableView<>();

    /** @param balanceHint how to read a positive balance, e.g. "الرصيد الموجب = مستحق على العميل" */
    public AccountStatementPane(Loader loader, String balanceHint) {
        this.loader = loader;
        setSpacing(12);
        getStyleClass().addAll("card", "module-card", "statement-pane");

        fromDate.setPromptText("من تاريخ");
        toDate.setPromptText("إلى تاريخ");
        fromDate.setPrefWidth(150);
        toDate.setPrefWidth(150);
        searchField.setPromptText("بحث في البيان أو المرجع");
        searchField.setPrefWidth(240);
        HBox.setHgrow(searchField, Priority.SOMETIMES);
        searchField.setOnAction(e -> reload());
        Button show = new Button("عرض");
        show.getStyleClass().addAll("btn", "btn-primary");
        show.setMinWidth(Region.USE_PREF_SIZE);
        show.setOnAction(e -> reload());
        Button clear = new Button("مسح");
        clear.getStyleClass().addAll("btn", "btn-secondary");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> {
            fromDate.setValue(null);
            toDate.setValue(null);
            searchField.clear();
            reload();
        });
        Label from = new Label("من");
        from.getStyleClass().add("muted");
        Label to = new Label("إلى");
        to.getStyleClass().add("muted");
        HBox filters = new HBox(10, from, fromDate, to, toDate, searchField, show, clear);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.getStyleClass().add("filter-bar");

        HBox totals = new HBox(12,
                stat("رصيد سابق", openingLabel), stat("إجمالي مدين", debitLabel),
                stat("إجمالي دائن", creditLabel), stat("الرصيد الختامي", closingLabel));
        totals.setAlignment(Pos.CENTER_LEFT);
        closingLabel.getParent().getStyleClass().add("stat-chip-primary");

        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        ViewSupport.show(message, false);

        setUpTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        Label hint = new Label(balanceHint);
        hint.getStyleClass().add("muted");
        getChildren().addAll(filters, totals, message, table, hint);
    }

    private static HBox stat(String title, Label value) {
        Label t = new Label(title + ":");
        t.getStyleClass().add("muted");
        value.getStyleClass().add("stat-value");
        HBox box = new HBox(6, t, value);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("stat-chip");
        return box;
    }

    private void setUpTable() {
        table.setPlaceholder(new Label("لا توجد حركات على الحساب في هذه الفترة"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.getColumns().add(column("التاريخ", 140, e -> e.getEntryDate() == null ? "" : e.getEntryDate().format(WHEN)));

        TableColumn<LedgerEntry, LedgerEntry> type = new TableColumn<>("النوع");
        type.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        type.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LedgerEntry e, boolean empty) {
                super.updateItem(e, empty);
                setText(null);
                setGraphic(empty || e == null ? null : typeBadge(e.getEntryType()));
            }
        });
        type.setPrefWidth(120);
        type.setMinWidth(110);
        table.getColumns().add(type);

        table.getColumns().add(column("المرجع", 110, LedgerEntry::getReferenceNo));
        table.getColumns().add(column("البيان", 200, LedgerEntry::getDescription));
        TableColumn<LedgerEntry, String> debit = column("مدين", 100,
                e -> e.getDebit().signum() == 0 ? "" : MoneyUtil.format(e.getDebit()));
        debit.setCellFactory(col -> ViewSupport.textCell("money-cell"));
        TableColumn<LedgerEntry, String> credit = column("دائن", 100,
                e -> e.getCredit().signum() == 0 ? "" : MoneyUtil.format(e.getCredit()));
        credit.setCellFactory(col -> ViewSupport.textCell("money-cell"));
        TableColumn<LedgerEntry, String> balance = column("الرصيد", 110, e -> MoneyUtil.format(e.getRunningBalance()));
        balance.setCellFactory(col -> ViewSupport.textCell("balance-cell"));
        table.getColumns().add(debit);
        table.getColumns().add(credit);
        table.getColumns().add(balance);
        table.getColumns().add(column("المستخدم", 110, LedgerEntry::getUserName));
    }

    private static TableColumn<LedgerEntry, String> column(String title, double width, Function<LedgerEntry, String> value) {
        TableColumn<LedgerEntry, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    static Label typeBadge(LedgerEntryType type) {
        String style = switch (type) {
            case OPENING_BALANCE -> "badge-info";
            case SALE, PURCHASE -> "badge-warning";
            case PAYMENT -> "badge-success";
            case SALE_RETURN, PURCHASE_RETURN -> "badge-danger";
            case ADJUSTMENT -> "badge-muted";
        };
        return ViewSupport.badge(type.getLabelAr(), style);
    }

    /** Loads (or reloads) the statement with the current filters. */
    public void reload() {
        LocalDate from = fromDate.getValue();
        LocalDate to = toDate.getValue();
        String search = searchField.getText();
        ViewSupport.show(message, false);
        Async.run(() -> loader.load(from, to, search), this::show, error -> {
            message.setText(ErrorMessages.of(error));
            message.getStyleClass().setAll("label", "form-alert", "form-alert-error");
            ViewSupport.show(message, true);
        });
    }

    private void show(AccountStatement s) {
        table.getItems().setAll(s.entries());
        openingLabel.setText(MoneyUtil.formatWithCurrency(s.openingBalance()));
        debitLabel.setText(MoneyUtil.formatWithCurrency(s.totalDebit()));
        creditLabel.setText(MoneyUtil.formatWithCurrency(s.totalCredit()));
        closingLabel.setText(MoneyUtil.formatWithCurrency(s.closingBalance()));
        if (s.filtered()) {
            message.setText("يعرض الحركات المطابقة للبحث فقط؛ الرصيد في كل سطر هو الرصيد الفعلي للحساب في ذلك الوقت.");
            message.getStyleClass().setAll("label", "form-alert", "form-alert-info");
            ViewSupport.show(message, true);
        }
    }

    /** For checks in tests and the end-to-end harness. */
    public TableView<LedgerEntry> getTable() {
        return table;
    }

    public BigDecimal shownClosing() {
        return table.getItems().isEmpty() ? null : table.getItems().get(table.getItems().size() - 1).getRunningBalance();
    }
}
