package com.almahwar.controller;

import com.almahwar.controller.support.Async;
import com.almahwar.controller.support.ErrorMessages;
import com.almahwar.model.ReportTable;
import com.almahwar.model.ReportTable.Column;
import com.almahwar.model.ReportTable.Item;
import com.almahwar.model.ReportTable.Kind;
import javafx.geometry.HPos;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.almahwar.controller.PurchasesController.WHEN;
import static com.almahwar.controller.ReportViewPage.DAY;
import static com.almahwar.controller.ReportViewPage.format;
import static com.almahwar.controller.SalePrintPage.cell;

/**
 * Print preview of a report: A4 landscape sheets (logo, company, report name, period, generated at / by, summary,
 * then the table split over pages). "Print" sends every page; the system print dialog can save it as PDF.
 * Printing only reads the report already on screen.
 */
final class ReportPrintPage {

    private static final Logger LOG = Logger.getLogger(ReportPrintPage.class.getName());
    private static final double SHEET_WIDTH = 1020;
    private static final int ROWS_FIRST_PAGE = 18;
    private static final int ROWS_PER_PAGE = 28;

    private ReportPrintPage() {
    }

    static ScrollPane create(ReportTable t, Runnable back) {
        Label message = new Label();
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        ViewSupport.show(message, false);

        List<VBox> sheets = new ArrayList<>();
        Button backButton = new Button("رجوع للتقرير", com.almahwar.controller.support.Icon.of("back"));
        backButton.getStyleClass().addAll("btn", "btn-secondary", "btn-back");
        backButton.setId("printBackButton");
        backButton.setOnAction(e -> back.run());
        Button print = new Button("طباعة / حفظ PDF");
        print.getStyleClass().addAll("btn", "btn-primary");
        print.setId("printNowButton");
        print.setOnAction(e -> print(sheets, message));
        print.setDisable(true);
        Label title = new Label("معاينة الطباعة — " + t.title());
        title.getStyleClass().add("page-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, backButton, title, spacer, print);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox pages = new VBox(24, new Label("جارٍ تجهيز المعاينة..."));
        pages.setAlignment(Pos.TOP_CENTER);
        // the company block and footer come from the central settings (read off the JavaFX thread)
        Async.run(CompanyHeader::load, company -> {
            sheets.addAll(sheets(t, company));
            pages.getChildren().setAll(sheets);
            title.setText("معاينة الطباعة — " + t.title() + "  (" + sheets.size() + " صفحة)");
            print.setDisable(false);
        }, error -> {
            message.setText(ErrorMessages.of(error));
            message.getStyleClass().setAll("label", "form-alert", "form-alert-error");
            ViewSupport.show(message, true);
        });
        VBox page = new VBox(16, toolbar, message, pages);
        page.getStyleClass().add("page-content");
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        scroll.setId("reportPrintPage");
        return scroll;
    }

    /** The printable pages. */
    static List<VBox> sheets(ReportTable t, CompanyHeader.Data company) {
        List<VBox> pages = new ArrayList<>();
        List<List<Object>> rows = t.rows();
        int index = 0;
        do {
            boolean first = pages.isEmpty();
            int size = first ? ROWS_FIRST_PAGE : ROWS_PER_PAGE;
            List<List<Object>> chunk = rows.subList(index, Math.min(rows.size(), index + size));
            index += chunk.size();
            pages.add(sheet(t, chunk, first, company));
        } while (index < rows.size());
        for (int i = 0; i < pages.size(); i++) {
            Label n = new Label("صفحة " + (i + 1) + " من " + pages.size()
                    + (t.totalRows() > rows.size() ? "  •  المطبوع " + rows.size() + " صفًا من " + t.totalRows()
                    + " (استخدم التصدير أو الفلاتر للباقي)" : ""));
            n.getStyleClass().add("print-muted");
            Label footer = CompanyHeader.footer(company.system().reportFooter(), "printReportFooter");
            if (footer != null) {
                pages.get(i).getChildren().add(footer);
            }
            pages.get(i).getChildren().add(n);
        }
        return pages;
    }

    private static VBox sheet(ReportTable t, List<List<Object>> rows, boolean first, CompanyHeader.Data company) {
        VBox sheet = new VBox(10);
        sheet.getStyleClass().addAll("print-sheet", "report-sheet");
        sheet.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        sheet.setPrefWidth(SHEET_WIDTH);
        sheet.setMaxWidth(SHEET_WIDTH);
        sheet.getChildren().add(header(t, company));
        if (first && !t.summary().isEmpty()) {
            FlowPane summary = new FlowPane(16, 6);
            for (Item item : t.summary()) {
                Label l = new Label(item.label() + ": ");
                l.getStyleClass().add("print-label");
                Label v = new Label(format(item.value(), item.kind()));
                v.getStyleClass().add("print-value");
                summary.getChildren().add(new HBox(2, l, v));
            }
            sheet.getChildren().add(summary);
        }
        sheet.getChildren().add(grid(t.columns(), rows));
        if (first) {
            for (String note : t.notes()) {
                Label n = new Label("• " + note);
                n.setWrapText(true);
                n.getStyleClass().add("print-muted");
                sheet.getChildren().add(n);
            }
        }
        return sheet;
    }

    private static HBox header(ReportTable t, CompanyHeader.Data company) {
        Label title = new Label(t.title());
        title.getStyleClass().add("print-title");
        Label period = new Label(t.from() == null ? "حتى تاريخه" : "من " + t.from().format(DAY) + " إلى " + t.to().format(DAY));
        period.getStyleClass().add("print-muted");
        Label generated = new Label("أُنشئ " + t.generatedAt().format(WHEN) + " بواسطة " + t.generatedBy());
        generated.getStyleClass().add("print-muted");
        HBox brand = CompanyHeader.brand(company);
        ((VBox) brand.getChildren().get(brand.getChildren().size() - 1)).getChildren().add(1, title);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(brand, spacer, new VBox(2, period, generated));
        head.getStyleClass().add("print-head");
        return head;
    }

    private static GridPane grid(List<Column> columns, List<List<Object>> rows) {
        GridPane g = new GridPane();
        g.getStyleClass().add("print-lines");
        for (int c = 0; c < columns.size(); c++) {
            Column col = columns.get(c);
            ColumnConstraints cc = new ColumnConstraints();
            cc.setHgrow(col.kind() == Kind.TEXT ? Priority.ALWAYS : Priority.NEVER);
            cc.setMinWidth(col.kind() == Kind.TEXT ? 60 : 70);
            cc.setHalignment(col.kind() == Kind.TEXT ? HPos.LEFT : HPos.CENTER);
            g.getColumnConstraints().add(cc);
            g.add(cell(col.header(), "print-th"), c, 0);
        }
        int r = 1;
        for (List<Object> values : rows) {
            for (int c = 0; c < columns.size(); c++) {
                Object v = c < values.size() ? values.get(c) : null;
                g.add(cell(format(v, columns.get(c).kind()), "print-td"), c, r);
            }
            r++;
        }
        return g;
    }

    private static void print(List<VBox> sheets, Label message) {
        String result;
        boolean ok = false;
        try {
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) {
                result = "لا توجد طابعة متاحة على هذا الجهاز.";
            } else if (!job.showPrintDialog(sheets.get(0).getScene() == null ? null : sheets.get(0).getScene().getWindow())) {
                result = "تم إلغاء الطباعة.";
                ok = true;
            } else {
                Printer printer = job.getPrinter();
                PageLayout layout = printer.createPageLayout(Paper.A4, PageOrientation.LANDSCAPE, Printer.MarginType.DEFAULT);
                job.getJobSettings().setPageLayout(layout);
                boolean printed = true;
                for (VBox sheet : sheets) {
                    double scale = Math.min(1.0, Math.min(layout.getPrintableWidth() / sheet.getBoundsInParent().getWidth(),
                            layout.getPrintableHeight() / sheet.getBoundsInParent().getHeight()));
                    Scale fit = new Scale(scale, scale);
                    sheet.getTransforms().add(fit);
                    printed &= job.printPage(layout, sheet);
                    sheet.getTransforms().remove(fit);
                }
                if (printed && job.endJob()) {
                    result = "تم إرسال التقرير إلى الطابعة (" + sheets.size() + " صفحة).";
                    ok = true;
                } else {
                    result = "تعذّرت الطباعة؛ حاول مرة أخرى.";
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Report printing failed", e);
            result = "تعذّرت الطباعة؛ حاول مرة أخرى.";
        }
        message.setText(result);
        message.getStyleClass().setAll("label", "form-alert", ok ? "form-alert-info" : "form-alert-error");
        ViewSupport.show(message, true);
    }
}
