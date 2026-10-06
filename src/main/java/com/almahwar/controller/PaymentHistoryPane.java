package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.util.MoneyUtil;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.Function;

import static com.almahwar.controller.PurchasesController.WHEN;

/** The "المدفوعات" tab of a customer or supplier: their receipts / payment vouchers, newest first. */
final class PaymentHistoryPane {

    private PaymentHistoryPane() {
    }

    static Node create(PartyType party, int partyId, Consumer<String> onError) {
        TableView<PartyPayment> table = new TableView<>();
        table.getStyleClass().add(party == PartyType.CUSTOMER ? "customer-payments" : "supplier-payments");
        table.setPlaceholder(new Label(party == PartyType.CUSTOMER ? "لا توجد مدفوعات من هذا العميل بعد"
                : "لا توجد مدفوعات لهذا المورد بعد"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.getColumns().add(col("رقم السند", 105, PartyPayment::getPaymentNo, false));
        table.getColumns().add(col("التاريخ والوقت", 140, p -> p.getPaymentDate() == null ? "" : p.getPaymentDate().format(WHEN), false));
        table.getColumns().add(col("المبلغ", 95, p -> MoneyUtil.format(p.getAmount()), true));
        table.getColumns().add(col("الطريقة", 85, p -> p.getPaymentMethod().getLabelAr(), false));
        table.getColumns().add(col("المرجع", 100, PartyPayment::getReferenceNo, false));
        table.getColumns().add(col("المستخدم", 110, PartyPayment::getUserName, false));
        table.getColumns().add(col("ملاحظات", 160, PartyPayment::getNotes, false));
        Label summary = new Label();
        summary.getStyleClass().add("muted");
        VBox box = new VBox(10, table, summary);
        box.getStyleClass().addAll("card", "module-card");
        Async.run(() -> AppContext.get().payments().history(party, partyId), rows -> {
            table.getItems().setAll(rows);
            summary.setText(rows.size() + " سند  •  الإجمالي " + MoneyUtil.formatWithCurrency(rows.stream()
                    .map(PartyPayment::getAmount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)));
        }, error -> onError.accept(ErrorMessages.of(error)));
        return box;
    }

    private static TableColumn<PartyPayment, String> col(String title, double width, Function<PartyPayment, String> value,
                                                         boolean money) {
        TableColumn<PartyPayment, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        if (money) {
            c.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        return c;
    }
}
