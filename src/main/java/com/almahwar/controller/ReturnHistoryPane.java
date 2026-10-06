package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnFilter;
import com.almahwar.model.ReturnKind;
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

/** The "المرتجعات" tab of a customer (sales returns) or supplier (purchase returns), newest first. */
final class ReturnHistoryPane {

    private ReturnHistoryPane() {
    }

    static Node create(ReturnKind kind, int partyId, Consumer<String> onError) {
        TableView<ReturnDocument> table = new TableView<>();
        table.getStyleClass().add(kind == ReturnKind.SALE ? "customer-returns" : "supplier-returns");
        table.setPlaceholder(new Label("لا توجد مرتجعات بعد"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(360);
        table.getColumns().add(col("رقم المرتجع", 110, ReturnDocument::getReturnNo, false));
        table.getColumns().add(col("التاريخ", 140, r -> r.getReturnDate() == null ? "" : r.getReturnDate().format(WHEN), false));
        table.getColumns().add(col("الفاتورة الأصلية", 120, ReturnDocument::getOriginalNo, false));
        table.getColumns().add(col("القيمة", 95, r -> MoneyUtil.format(r.getTotalAmount()), true));
        table.getColumns().add(col(kind == ReturnKind.SALE ? "رُد نقدًا" : "استُرد نقدًا", 95,
                r -> MoneyUtil.format(r.getRefundAmount()), true));
        table.getColumns().add(col("السبب", 120, r -> r.getReason().getLabelAr(), false));
        table.getColumns().add(col("المستخدم", 110, ReturnDocument::getUserName, false));
        VBox box = new VBox(10, table);
        box.getStyleClass().addAll("card", "module-card");
        Async.run(() -> AppContext.get().returns().search(kind, ReturnFilter.forParty(partyId)),
                rows -> table.getItems().setAll(rows), error -> onError.accept(ErrorMessages.of(error)));
        return box;
    }

    private static TableColumn<ReturnDocument, String> col(String title, double width,
                                                           Function<ReturnDocument, String> value, boolean money) {
        TableColumn<ReturnDocument, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        if (money) {
            c.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        return c;
    }
}
