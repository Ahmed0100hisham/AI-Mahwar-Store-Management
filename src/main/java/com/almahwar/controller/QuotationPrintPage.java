package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.QuotationStatus;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
import com.almahwar.util.QuantityUtil;
import javafx.geometry.HPos;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.controller.QuotationsController.DAY;
import static com.almahwar.controller.SalePrintPage.cell;
import static com.almahwar.controller.SalePrintPage.infoRow;
import static com.almahwar.controller.SalePrintPage.totalRow;

/**
 * Customer quotation preview (A4-like sheet, same layout as the sales invoice) with a print button; "print to PDF"
 * from the system print dialog gives a PDF. Shows only what the customer should see: no cost, profit or internal ids.
 */
final class QuotationPrintPage {

    private QuotationPrintPage() {
    }

    static ScrollPane create(QuotationPages host, int quotationId) {
        Label message = new Label();
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        ViewSupport.show(message, false);

        StackPane sheetHolder = new StackPane(new Label("جارٍ تحميل عرض السعر..."));
        sheetHolder.setAlignment(Pos.TOP_CENTER);

        Button back = new Button("رجوع", com.almahwar.controller.support.Icon.of("back"));
        back.getStyleClass().addAll("btn", "btn-secondary", "btn-back");
        back.setOnAction(e -> host.showDetails(quotationId, null));
        Button print = new Button("طباعة / PDF");
        print.getStyleClass().addAll("btn", "btn-primary");
        print.setDisable(true);
        Label title = new Label("معاينة عرض السعر");
        title.getStyleClass().add("page-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, back, title, spacer, print);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(16, toolbar, message, sheetHolder);
        page.getStyleClass().add("page-content");
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");

        Async.run(() -> new Object[]{AppContext.get().quotations().findById(quotationId).orElse(null),
                CompanyHeader.load()}, data -> {
            Quotation q = (Quotation) data[0];
            if (q == null) {
                host.closeQuotation("عرض السعر غير موجود.");
                return;
            }
            VBox sheet = sheet(q, (CompanyHeader.Data) data[1]);
            sheetHolder.getChildren().setAll(sheet);
            print.setDisable(false);
            print.setOnAction(e -> SalePrintPage.print(sheet, message));
        }, error -> {
            message.setText(ErrorMessages.of(error));
            message.getStyleClass().setAll("label", "form-alert", "form-alert-error");
            ViewSupport.show(message, true);
        });
        return scroll;
    }

    /** The printable quotation for the customer (company block from the central settings). */
    static VBox sheet(Quotation q, CompanyHeader.Data companyData) {
        AppConfig cfg = AppConfig.getInstance();
        VBox sheet = new VBox(14);
        sheet.getStyleClass().add("print-sheet");
        sheet.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        sheet.setMaxWidth(760);
        sheet.setPrefWidth(760);

        HBox brand = CompanyHeader.brand(companyData);
        Label docTitle = new Label("عرض سعر");
        docTitle.getStyleClass().add("print-title");
        boolean issued = q.getStatus() != QuotationStatus.DRAFT;
        Label status = new Label(issued ? "Quotation" : "مسودة — غير نهائية");
        status.getStyleClass().add(issued ? "print-muted" : "print-warning");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(brand, spacer, new VBox(2, docTitle, status));
        head.getStyleClass().add("print-head");

        GridPane info = new GridPane();
        info.setHgap(18);
        info.setVgap(6);
        infoRow(info, 0, "رقم العرض", q.getQuotationNo(), "التاريخ",
                q.getQuotationDate() == null ? "" : q.getQuotationDate().format(WHEN));
        infoRow(info, 1, "السادة", q.getDisplayName(), "الهاتف", PhoneNumbers.format(q.getDisplayPhone()));
        infoRow(info, 2, "صالح حتى", q.getValidUntil() == null ? "" : q.getValidUntil().format(DAY), "التسعير",
                q.getPriceType().getLabelAr());
        infoRow(info, 3, "أعدّه", q.getUserName(), "العملة", cfg.currencyCode() + " - " + cfg.currencySymbol());

        GridPane lines = new GridPane();
        lines.getStyleClass().add("print-lines");
        String[] headers = {"#", "الصنف", "الكمية", "السعر", "الخصم", "الإجمالي"};
        double[] widths = {30, 300, 90, 100, 90, 110};
        for (int c = 0; c < headers.length; c++) {
            ColumnConstraints cc = new ColumnConstraints(widths[c]);
            cc.setHgrow(c == 1 ? Priority.ALWAYS : Priority.NEVER);
            cc.setHalignment(c == 1 ? HPos.LEFT : HPos.CENTER);
            lines.getColumnConstraints().add(cc);
            lines.add(cell(headers[c], "print-th"), c, 0);
        }
        int r = 1;
        for (QuotationItem i : q.getItems()) {
            lines.add(cell(String.valueOf(r), "print-td"), 0, r);
            lines.add(cell(i.getProductName() + " — " + i.getProductCode(), "print-td"), 1, r);
            lines.add(cell(QuantityUtil.format(i.getQuantity()) + " " + i.getUnitName(), "print-td"), 2, r);
            lines.add(cell(money(i.getUnitPrice()), "print-td"), 3, r);
            lines.add(cell(money(i.getDiscountAmount()), "print-td"), 4, r);
            lines.add(cell(money(i.getLineTotal()), "print-td"), 5, r);
            r++;
        }

        VBox totals = new VBox(4,
                totalRow("المجموع", money(q.getSubtotal()), false),
                totalRow("الخصم", money(q.getDiscountAmount()), false),
                totalRow("الإجمالي", MoneyUtil.formatWithCurrency(q.getTotalAmount()), true));
        totals.setMinWidth(300);
        totals.setPrefWidth(300);
        totals.setMaxWidth(300);
        HBox totalsRow = new HBox(totals);
        totalsRow.setAlignment(Pos.CENTER_LEFT);

        VBox texts = new VBox(6);
        if (q.getTerms() != null) {
            Label terms = new Label("الشروط: " + q.getTerms());
            terms.setWrapText(true);
            texts.getChildren().add(terms);
        }
        if (q.getNotes() != null) {
            Label notes = new Label("ملاحظات: " + q.getNotes());
            notes.setWrapText(true);
            texts.getChildren().add(notes);
        }
        Label validity = new Label("هذا العرض صالح حتى " + (q.getValidUntil() == null ? "—" : q.getValidUntil().format(DAY))
                + "، والأسعار والكميات المتوفرة قابلة للتغيير بعد ذلك. عرض السعر ليس فاتورة ولا يحجز البضاعة.");
        validity.setWrapText(true);
        validity.getStyleClass().add("print-muted");
        texts.getChildren().add(validity);

        Label thanks = new Label("شكراً لاهتمامكم");
        thanks.getStyleClass().add("print-thanks");
        thanks.setMaxWidth(Double.MAX_VALUE);
        thanks.setAlignment(Pos.CENTER);
        Label footer = new Label("طُبع بواسطة " + AppContext.get().security().currentUser().getFullName() + " — "
                + LocalDateTime.now().format(WHEN) + "  •  جميع المبالغ بالدينار الكويتي (3 منازل عشرية)");
        footer.getStyleClass().add("print-muted");

        sheet.getChildren().addAll(head, info, lines, totalsRow, texts, thanks, footer);
        return sheet;
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "" : MoneyUtil.format(amount);
    }
}
