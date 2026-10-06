package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleStatus;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.almahwar.controller.PurchasesController.WHEN;

/**
 * Customer sales invoice preview (A4-like sheet) with a print button. Never shows cost or profit.
 * Printing only reads the saved invoice: if no printer is available or printing fails, the sale is untouched.
 */
final class SalePrintPage {

    private static final Logger LOG = Logger.getLogger(SalePrintPage.class.getName());

    private SalePrintPage() {
    }

    static ScrollPane create(SalePages host, int saleId) {
        Label message = new Label();
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        ViewSupport.show(message, false);

        StackPane sheetHolder = new StackPane(new Label("جارٍ تحميل الفاتورة..."));
        sheetHolder.setAlignment(Pos.TOP_CENTER);

        Button back = new Button("رجوع", com.almahwar.controller.support.Icon.of("back"));
        back.getStyleClass().addAll("btn", "btn-secondary", "btn-back");
        back.setOnAction(e -> host.showSaleDetails(saleId, null));
        Button print = new Button("طباعة");
        print.getStyleClass().addAll("btn", "btn-primary");
        print.setDisable(true);
        Label title = new Label("معاينة فاتورة المبيعات");
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

        Async.run(() -> new Object[]{AppContext.get().sales().findById(saleId).orElse(null), CompanyHeader.load()}, data -> {
            Sale s = (Sale) data[0];
            if (s == null) {
                host.closeSale("فاتورة البيع غير موجودة.");
                return;
            }
            VBox sheet = sheet(s, (CompanyHeader.Data) data[1]);
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

    static void print(Node sheet, Label message) {
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
                    result = "تعذّرت الطباعة. البيع محفوظ ولم يتغير فيه شيء؛ حاول مرة أخرى.";
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Printing failed", e);
            result = "تعذّرت الطباعة. البيع محفوظ ولم يتغير فيه شيء؛ حاول مرة أخرى.";
        }
        message.setText(result);
        message.getStyleClass().setAll("label", "form-alert", ok ? "form-alert-info" : "form-alert-error");
        ViewSupport.show(message, true);
    }

    /** The printable customer invoice: no cost, no profit; company block and footer from the settings. */
    static VBox sheet(Sale s, CompanyHeader.Data company) {
        AppConfig cfg = AppConfig.getInstance();
        VBox sheet = new VBox(14);
        sheet.getStyleClass().add("print-sheet");
        sheet.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        sheet.setMaxWidth(760);
        sheet.setPrefWidth(760);

        // Company (central profile, logo if stored) + title
        HBox brand = CompanyHeader.brand(company);
        Label docTitle = new Label("فاتورة مبيعات");
        docTitle.getStyleClass().add("print-title");
        Label status = new Label(s.getStatus() == SaleStatus.POSTED ? "Sales Invoice"
                : s.getStatus().getLabelAr() + " — غير معتمدة");
        status.getStyleClass().add(s.getStatus() == SaleStatus.POSTED ? "print-muted" : "print-warning");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(brand, spacer, new VBox(2, docTitle, status));
        head.getStyleClass().add("print-head");

        // Document info
        GridPane info = new GridPane();
        info.setHgap(18);
        info.setVgap(6);
        PaymentType type = s.getPaymentType();
        infoRow(info, 0, "رقم الفاتورة", s.getSaleNo(), "التاريخ والوقت",
                s.getSaleDate() == null ? "" : s.getSaleDate().format(WHEN));
        infoRow(info, 1, "العميل", s.getCustomerName() + (s.isWalkIn() ? "" : " (" + s.getCustomerCode() + ")"),
                "الهاتف", s.isWalkIn() ? null : PhoneNumbers.format(s.getCustomerPhone()));
        infoRow(info, 2, "الكاشير", s.getUserName(), "طريقة الدفع", type == null ? "" : type.getLabelAr()
                + (type == PaymentType.PARTIAL ? " (" + s.getPaymentMethod().getLabelAr() + ")" : ""));
        infoRow(info, 3, "نوع البيع", s.getSaleType().getLabelAr(), "العملة",
                cfg.currencyCode() + " - " + cfg.currencySymbol());

        // Lines
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
        for (SaleItem i : s.getItems()) {
            lines.add(cell(String.valueOf(r), "print-td"), 0, r);
            lines.add(cell(i.getProductName() + " — " + i.getProductCode(), "print-td"), 1, r);
            lines.add(cell(QuantityUtil.format(i.getQuantity()) + " " + i.getUnitName(), "print-td"), 2, r);
            lines.add(cell(money(i.getUnitPrice()), "print-td"), 3, r);
            lines.add(cell(money(i.getDiscountAmount()), "print-td"), 4, r);
            lines.add(cell(money(i.getLineTotal()), "print-td"), 5, r);
            r++;
        }

        // Totals
        VBox totals = new VBox(4,
                totalRow("المجموع", money(s.getSubtotal()), false),
                totalRow("الخصم", money(s.getDiscountAmount()), false),
                totalRow("الإجمالي", MoneyUtil.formatWithCurrency(s.getTotalAmount()), true),
                totalRow("المدفوع", money(s.getPaidAmount()), false),
                totalRow("المتبقي", money(s.getRemainingAmount()), false));
        totals.setMinWidth(300);
        totals.setPrefWidth(300);
        totals.setMaxWidth(300);
        HBox totalsRow = new HBox(totals);
        totalsRow.setAlignment(Pos.CENTER_LEFT);

        Label notes = new Label(s.getNotes() == null ? "" : "ملاحظات: " + s.getNotes());
        notes.setWrapText(true);
        Label thanks = CompanyHeader.footer(company.system().invoiceFooter(), "printInvoiceFooter");
        Label footer = new Label("طُبعت بواسطة " + AppContext.get().security().currentUser().getFullName() + " — "
                + LocalDateTime.now().format(WHEN) + "  •  جميع المبالغ بالدينار الكويتي (3 منازل عشرية)");
        footer.getStyleClass().add("print-muted");

        sheet.getChildren().addAll(head, info, lines, totalsRow, notes);
        if (thanks != null) {
            sheet.getChildren().add(thanks);
        }
        sheet.getChildren().add(footer);
        return sheet;
    }

    static void infoRow(GridPane g, int row, String l1, String v1, String l2, String v2) {
        g.add(cell(l1, "print-label"), 0, row);
        g.add(cell(v1 == null || v1.isBlank() ? "—" : v1, "print-value"), 1, row);
        g.add(cell(l2, "print-label"), 2, row);
        g.add(cell(v2 == null || v2.isBlank() ? "—" : v2, "print-value"), 3, row);
    }

    static Label cell(String text, String style) {
        Label l = new Label(text);
        l.getStyleClass().add(style);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setWrapText(true);
        return l;
    }

    static HBox totalRow(String label, String value, boolean main) {
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
