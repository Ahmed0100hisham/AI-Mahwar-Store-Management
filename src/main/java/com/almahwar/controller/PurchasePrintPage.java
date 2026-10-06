package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.PurchaseStatus;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;
import javafx.geometry.HPos;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Purchase invoice preview (A4-like sheet) with a print button. Printing only reads the saved
 * invoice: if no printer is available or printing fails, nothing about the invoice changes.
 */
final class PurchasePrintPage {

    private static final Logger LOG = Logger.getLogger(PurchasePrintPage.class.getName());

    private PurchasePrintPage() {
    }

    static ScrollPane create(PurchasePages host, int purchaseId) {
        Label message = new Label();
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        ViewSupport.show(message, false);

        StackPane sheetHolder = new StackPane(new Label("جارٍ تحميل الفاتورة..."));
        sheetHolder.setAlignment(Pos.TOP_CENTER);

        Button back = new Button("رجوع للفاتورة", com.almahwar.controller.support.Icon.of("back"));
        back.getStyleClass().addAll("btn", "btn-secondary", "btn-back");
        back.setOnAction(e -> host.showPurchaseDetails(purchaseId, null));
        Button print = new Button("طباعة");
        print.getStyleClass().addAll("btn", "btn-primary");
        print.setDisable(true);
        Label title = new Label("معاينة فاتورة المشتريات");
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

        Async.run(() -> new Object[]{AppContext.get().purchases().findById(purchaseId).orElse(null),
                CompanyHeader.load()}, data -> {
            Purchase p = (Purchase) data[0];
            if (p == null) {
                host.closePurchase("فاتورة المشتريات غير موجودة.");
                return;
            }
            VBox sheet = sheet(p, (CompanyHeader.Data) data[1]);
            sheetHolder.getChildren().setAll(sheet);
            print.setDisable(false);
            print.setOnAction(e -> print(sheet, message));
        }, error -> {
            message.setText(ErrorMessages.of(error));
            message.getStyleClass().setAll("label", "form-alert", "form-alert-error");
            ViewSupport.show(message, true);
        });
        return scroll;
    }

    private static void print(Node sheet, Label message) {
        String result;
        boolean ok = false;
        try {
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) {
                result = "لا توجد طابعة متاحة على هذا الجهاز. الفاتورة محفوظة ويمكن طباعتها لاحقًا.";
            } else if (!job.showPrintDialog(sheet.getScene() == null ? null : sheet.getScene().getWindow())) {
                result = "تم إلغاء الطباعة.";
                ok = true;
            } else {
                PageLayout layout = job.getJobSettings().getPageLayout();
                double scale = Math.min(1.0, Math.min(layout.getPrintableWidth() / sheet.getBoundsInParent().getWidth(),
                        layout.getPrintableHeight() / sheet.getBoundsInParent().getHeight()));
                Scale fit = new Scale(scale, scale);
                sheet.getTransforms().add(fit);
                boolean printed = job.printPage(sheet);
                sheet.getTransforms().remove(fit);
                if (printed && job.endJob()) {
                    result = "تم إرسال الفاتورة إلى الطابعة.";
                    ok = true;
                } else {
                    result = "تعذّرت الطباعة. الفاتورة محفوظة ولم يتغير فيها شيء؛ حاول مرة أخرى.";
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Printing failed", e);
            result = "تعذّرت الطباعة. الفاتورة محفوظة ولم يتغير فيها شيء؛ حاول مرة أخرى.";
        }
        message.setText(result);
        message.getStyleClass().setAll("label", "form-alert", ok ? "form-alert-info" : "form-alert-error");
        ViewSupport.show(message, true);
    }

    /** The printable invoice. */
    static VBox sheet(Purchase p, CompanyHeader.Data companyData) {
        AppConfig cfg = AppConfig.getInstance();
        VBox sheet = new VBox(14);
        sheet.getStyleClass().add("print-sheet");
        sheet.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        sheet.setMaxWidth(760);
        sheet.setPrefWidth(760);

        // Company + title
        Label docTitle = new Label("فاتورة مشتريات");
        docTitle.getStyleClass().add("print-title");
        Label status = new Label(p.getStatus() == PurchaseStatus.POSTED ? "معتمدة" : p.getStatus().getLabelAr() + " — غير معتمدة");
        status.getStyleClass().add(p.getStatus() == PurchaseStatus.POSTED ? "print-muted" : "print-warning");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(CompanyHeader.brand(companyData), spacer, new VBox(2, docTitle, status));
        head.getStyleClass().add("print-head");

        // Document info
        GridPane info = new GridPane();
        info.setHgap(18);
        info.setVgap(6);
        PaymentType type = p.getPaymentType();
        infoRow(info, 0, "رقم الفاتورة", p.getPurchaseNo(), "فاتورة المورد", p.getSupplierInvoiceNo());
        infoRow(info, 1, "التاريخ", p.getPurchaseDate() == null ? "" : p.getPurchaseDate().format(PurchasesController.WHEN),
                "طريقة الدفع", type == null ? "" : type.getLabelAr());
        infoRow(info, 2, "المورد", p.getSupplierName() + " (" + p.getSupplierCode() + ")", "العملة",
                cfg.currencyCode() + " - " + cfg.currencySymbol());

        // Lines
        GridPane lines = new GridPane();
        lines.getStyleClass().add("print-lines");
        String[] headers = {"#", "الصنف", "الكمية", "التكلفة", "الخصم", "الإجمالي"};
        double[] widths = {30, 300, 90, 100, 90, 110};
        for (int c = 0; c < headers.length; c++) {
            ColumnConstraints cc = new ColumnConstraints(widths[c]);
            cc.setHgrow(c == 1 ? Priority.ALWAYS : Priority.NEVER);
            cc.setHalignment(c == 1 ? HPos.LEFT : HPos.CENTER);
            lines.getColumnConstraints().add(cc);
            lines.add(cell(headers[c], "print-th"), c, 0);
        }
        int r = 1;
        for (PurchaseItem i : p.getItems()) {
            lines.add(cell(String.valueOf(r), "print-td"), 0, r);
            lines.add(cell(i.getProductName() + " — " + i.getProductCode(), "print-td"), 1, r);
            lines.add(cell(QuantityUtil.format(i.getQuantity()) + " " + i.getUnitName(), "print-td"), 2, r);
            lines.add(cell(money(i.getUnitCost()), "print-td"), 3, r);
            lines.add(cell(money(i.getDiscountAmount()), "print-td"), 4, r);
            lines.add(cell(money(i.getLineTotal()), "print-td"), 5, r);
            r++;
        }

        // Totals
        VBox totals = new VBox(4,
                totalRow("مجموع الأصناف", money(p.getSubtotal()), false),
                totalRow("الخصم", money(p.getDiscountAmount()), false),
                totalRow("الإجمالي", p.getTotalAmount() == null ? "" : MoneyUtil.formatWithCurrency(p.getTotalAmount()), true),
                totalRow("المدفوع", money(p.getPaidAmount()), false),
                totalRow("المتبقي", money(p.getRemainingAmount()), false));
        totals.setMinWidth(300);
        totals.setPrefWidth(300);
        totals.setMaxWidth(300);
        HBox totalsRow = new HBox(totals);
        totalsRow.setAlignment(Pos.CENTER_LEFT);

        Label notes = new Label(p.getNotes() == null ? "" : "ملاحظات: " + p.getNotes());
        notes.setWrapText(true);
        Label footer = new Label("طُبعت بواسطة " + AppContext.get().security().currentUser().getFullName() + " — "
                + LocalDateTime.now().format(PurchasesController.WHEN));
        footer.getStyleClass().add("print-muted");

        sheet.getChildren().addAll(head, info, lines, totalsRow, notes, footer);
        return sheet;
    }

    private static void infoRow(GridPane g, int row, String l1, String v1, String l2, String v2) {
        g.add(cell(l1, "print-label"), 0, row);
        g.add(cell(v1 == null || v1.isBlank() ? "—" : v1, "print-value"), 1, row);
        g.add(cell(l2, "print-label"), 2, row);
        g.add(cell(v2 == null || v2.isBlank() ? "—" : v2, "print-value"), 3, row);
    }

    private static Label cell(String text, String style) {
        Label l = new Label(text);
        l.getStyleClass().add(style);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setWrapText(true);
        return l;
    }

    private static HBox totalRow(String label, String value, boolean main) {
        Label l = new Label(label);
        Label v = new Label(value);
        Region s = new Region();
        HBox.setHgrow(s, Priority.ALWAYS);
        HBox row = new HBox(l, s, v);
        row.getStyleClass().add(main ? "print-total-main" : "print-total");
        return row;
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "" : MoneyUtil.format(amount);
    }
}
