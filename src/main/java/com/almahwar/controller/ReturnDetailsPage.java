package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.function.Function;

import static com.almahwar.controller.PurchasesController.WHEN;

/** One posted return (read-only: returns are never edited or deleted). */
final class ReturnDetailsPage {

    private ReturnDetailsPage() {
    }

    static ScrollPane create(ReturnKind kind, int returnId, String message, Runnable back) {
        Label title = new Label(kind.getLabelAr());
        title.getStyleClass().add("page-title");
        title.setId("returnTitle");
        Label subtitle = new Label();
        subtitle.getStyleClass().add("page-subtitle");
        Button backButton = new Button("رجوع", com.almahwar.controller.support.Icon.of("back"));
        backButton.getStyleClass().addAll("btn", "btn-secondary", "btn-back");
        backButton.setOnAction(e -> back.run());
        HBox header = new HBox(12, backButton, new VBox(4, title, subtitle));
        header.setAlignment(Pos.CENTER_LEFT);

        Label pageMessage = new Label(message == null ? "" : message);
        pageMessage.setId("pageMessage");
        pageMessage.setWrapText(true);
        pageMessage.setMaxWidth(Double.MAX_VALUE);
        pageMessage.getStyleClass().setAll("label", "form-alert", "form-alert-info");
        ViewSupport.show(pageMessage, message != null);

        GridPane info = new GridPane();
        info.setHgap(24);
        info.setVgap(10);
        for (int i = 0; i < 4; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            if (i % 2 == 1) {
                cc.setHgrow(Priority.ALWAYS);
            } else {
                cc.setMinWidth(120);
            }
            info.getColumnConstraints().add(cc);
        }
        Label infoTitle = new Label("بيانات المرتجع");
        infoTitle.getStyleClass().add("card-title");
        VBox infoCard = new VBox(12, infoTitle, info);
        infoCard.getStyleClass().add("card");

        TableView<ReturnLine> lines = new TableView<>();
        lines.getStyleClass().add("return-detail-lines");
        lines.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        lines.setPrefHeight(240);
        lines.getColumns().add(col("الكود", 90, ReturnLine::getProductCode, false));
        lines.getColumns().add(col("الصنف", 220, ReturnLine::getProductName, false));
        lines.getColumns().add(col("الكمية", 80, l -> QuantityUtil.format(l.getQuantity()), false));
        lines.getColumns().add(col("الوحدة", 60, ReturnLine::getUnitName, false));
        lines.getColumns().add(col("سعر الوحدة", 95, l -> MoneyUtil.format(l.getUnitPrice()), true));
        lines.getColumns().add(col("القيمة", 100, l -> MoneyUtil.format(l.getLineTotal()), true));
        TableColumn<ReturnLine, String> costCol = col("تكلفة الوحدة", 95,
                l -> l.getUnitCost() == null ? "" : MoneyUtil.format(l.getUnitCost()), true);
        lines.getColumns().add(costCol);

        VBox totals = new VBox(6);
        totals.getStyleClass().add("totals-card");
        totals.setMinWidth(330);
        HBox totalsRow = new HBox(new Region(), totals);
        HBox.setHgrow(totalsRow.getChildren().get(0), Priority.ALWAYS);
        Label linesTitle = new Label("الأصناف المرتجعة");
        linesTitle.getStyleClass().add("card-title");
        VBox linesCard = new VBox(12, linesTitle, lines, totalsRow);
        linesCard.getStyleClass().add("card");

        VBox page = new VBox(16, header, pageMessage, infoCard, linesCard);
        page.getStyleClass().add("page-content");
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");

        Async.run(() -> AppContext.get().returns().findById(kind, returnId).orElse(null), r -> {
            if (r == null) {
                back.run();
                return;
            }
            boolean sale = kind == ReturnKind.SALE;
            title.setText(kind.getLabelAr() + " " + r.getReturnNo());
            subtitle.setText((sale ? "العميل: " : "المورد: ") + r.getPartyName() + " (" + r.getPartyCode() + ")");
            int row = 0;
            row = info(info, row, "الفاتورة الأصلية", r.getOriginalNo(), "التاريخ", r.getReturnDate() == null ? null
                    : r.getReturnDate().format(WHEN));
            row = info(info, row, "السبب", r.getReason().getLabelAr(), "بواسطة", r.getUserName());
            info(info, row, "ملاحظات", r.getNotes(), sale ? "طريقة الرد" : "طريقة الاسترداد",
                    r.getRefundAmount().signum() > 0 ? r.getRefundMethod().getLabelAr() : "لا يوجد مبلغ نقدي (على الحساب)");
            lines.getItems().setAll(r.getLines());
            costCol.setVisible(r.getCostTotal() != null && sale);
            totals.getChildren().setAll(
                    total("قيمة المرتجع", MoneyUtil.formatWithCurrency(r.getTotalAmount()), true),
                    total(sale ? "خُصم من مديونية العميل" : "خُصم من مستحق المورد", MoneyUtil.format(r.getAccountAmount()), false),
                    total(sale ? "رُد للعميل نقدًا" : "استُرد من المورد نقدًا", MoneyUtil.format(r.getRefundAmount()), false));
            if (sale && r.getCostTotal() != null) {
                totals.getChildren().add(total("تكلفة البضاعة المرتجعة (تاريخية)", MoneyUtil.format(r.getCostTotal()), false));
                totals.getChildren().add(total("أثر المرتجع على الربح", "−" + MoneyUtil.format(r.getTotalAmount()
                        .subtract(r.getCostTotal()).max(BigDecimal.ZERO)), false));
            }
        }, error -> {
            pageMessage.setText(ErrorMessages.of(error));
            pageMessage.getStyleClass().setAll("label", "form-alert", "form-alert-error");
            ViewSupport.show(pageMessage, true);
        });
        return scroll;
    }

    private static int info(GridPane g, int row, String l1, String v1, String l2, String v2) {
        add(g, row, 0, l1, v1);
        add(g, row, 2, l2, v2);
        return row + 1;
    }

    private static void add(GridPane g, int row, int col, String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("muted");
        Label v = new Label(value == null || value.isBlank() ? "—" : value);
        v.getStyleClass().add("info-value");
        v.setWrapText(true);
        g.add(l, col, row);
        g.add(v, col + 1, row);
    }

    private static HBox total(String label, String value, boolean main) {
        Label l = new Label(label);
        Label v = new Label(value);
        v.getStyleClass().add("total-value");
        Region s = new Region();
        HBox.setHgrow(s, Priority.ALWAYS);
        HBox row = new HBox(l, s, v);
        row.getStyleClass().addAll("total-row");
        if (main) {
            row.getStyleClass().add("total-main");
        }
        return row;
    }

    private static TableColumn<ReturnLine, String> col(String title, double width, Function<ReturnLine, String> value,
                                                       boolean money) {
        TableColumn<ReturnLine, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        if (money) {
            c.setCellFactory(x -> ViewSupport.textCell("money-cell"));
        }
        return c;
    }
}
