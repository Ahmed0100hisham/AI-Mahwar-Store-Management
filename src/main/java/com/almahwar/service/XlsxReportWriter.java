package com.almahwar.service;

import com.almahwar.model.ReportTable;
import com.almahwar.model.ReportTable.Column;
import com.almahwar.model.ReportTable.Item;
import com.almahwar.model.ReportTable.Kind;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link ReportTable} as a real Excel workbook (.xlsx, Apache POI streaming): a right-to-left sheet with
 * the company, report name, period, generation time and user, the summary figures, then the table. Amounts are
 * numeric cells formatted with 3 decimals (KWD), dates are date cells, Arabic text is kept as is.
 */
final class XlsxReportWriter {

    static final String MONEY_FORMAT = "#,##0.000";
    static final String QUANTITY_FORMAT = "#,##0.###";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private XlsxReportWriter() {
    }

    static void write(ReportTable t, String company, OutputStream out) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500)) {
            SXSSFSheet sheet = wb.createSheet(sheetName(t.title()));
            sheet.setRightToLeft(true);
            Styles st = new Styles(wb);

            int r = 0;
            r = text(sheet, r, st.title, company);
            r = text(sheet, r, st.subtitle, t.title());
            if (t.from() != null) {
                r = text(sheet, r, st.plain, "الفترة: من " + t.from() + " إلى " + t.to());
            }
            r = text(sheet, r, st.plain, "تاريخ الإنشاء: " + t.generatedAt().format(STAMP) + "   •   بواسطة: "
                    + t.generatedBy());
            r++;

            // summary figures
            for (Item item : t.summary()) {
                Row row = sheet.createRow(r++);
                Cell label = row.createCell(0);
                label.setCellValue(item.label());
                label.setCellStyle(st.bold);
                put(row.createCell(1), item.value(), item.kind(), st);
            }
            r++;

            // table
            List<Column> cols = t.columns();
            Row header = sheet.createRow(r++);
            for (int c = 0; c < cols.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(cols.get(c).header());
                cell.setCellStyle(st.header);
            }
            for (List<Object> values : t.rows()) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < cols.size() && c < values.size(); c++) {
                    put(row.createCell(c), values.get(c), cols.get(c).kind(), st);
                }
            }
            if (t.totalRows() > t.rows().size()) {
                r++;
                text(sheet, r++, st.bold, "تنبيه: صُدِّر " + t.rows().size() + " صفًا من أصل " + t.totalRows()
                        + "؛ استخدم الفلاتر لتقليل النتائج.");
            }
            for (String note : t.notes()) {
                text(sheet, r++, st.plain, note);
            }
            for (int c = 0; c < Math.max(cols.size(), 2); c++) {
                sheet.setColumnWidth(c, width(c < cols.size() ? cols.get(c) : null, c));
            }
            wb.write(out);
            wb.dispose();
        } catch (IOException e) {
            throw new UncheckedIOException("تعذّر إنشاء ملف Excel", e);
        }
    }

    private static int text(SXSSFSheet sheet, int r, CellStyle style, String value) {
        Cell cell = sheet.createRow(r).createCell(0);
        cell.setCellValue(value);
        cell.setCellStyle(style);
        return r + 1;
    }

    private static void put(Cell cell, Object value, Kind kind, Styles st) {
        if (value == null) {
            return;
        }
        if (value instanceof BigDecimal bd) {
            cell.setCellValue(bd.doubleValue());
            cell.setCellStyle(st.byKind.get(kind == Kind.QUANTITY ? Kind.QUANTITY : kind == Kind.PERCENT
                    ? Kind.PERCENT : Kind.MONEY));
        } else if (value instanceof Long || value instanceof Integer) {
            cell.setCellValue(((Number) value).doubleValue());
            cell.setCellStyle(st.byKind.get(Kind.INTEGER));
        } else if (value instanceof LocalDateTime dt) {
            cell.setCellValue(dt);
            cell.setCellStyle(st.byKind.get(Kind.DATETIME));
        } else if (value instanceof LocalDate d) {
            cell.setCellValue(d);
            cell.setCellStyle(st.byKind.get(Kind.DATE));
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private static int width(Column c, int index) {
        int chars = c == null ? 18 : switch (c.kind()) {
            case DATETIME -> 18;
            case DATE -> 12;
            case MONEY -> 14;
            case QUANTITY, INTEGER, PERCENT -> 11;
            case TEXT -> Math.max(14, Math.min(40, c.header().length() + 10));
        };
        return Math.max(chars, index == 0 ? 24 : 0) * 256;
    }

    /** Excel sheet names: at most 31 characters, without : \ / ? * [ ]. */
    static String sheetName(String title) {
        String clean = title.replaceAll("[:\\\\/?*\\[\\]]", " ");
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle plain;
        final CellStyle bold;
        final CellStyle header;
        final Map<Kind, CellStyle> byKind = new EnumMap<>(Kind.class);

        Styles(SXSSFWorkbook wb) {
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            Font strong = wb.createFont();
            strong.setBold(true);
            title = wb.createCellStyle();
            title.setFont(big);
            subtitle = wb.createCellStyle();
            subtitle.setFont(strong);
            plain = wb.createCellStyle();
            bold = wb.createCellStyle();
            bold.setFont(strong);
            header = wb.createCellStyle();
            header.setFont(strong);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            short money = wb.createDataFormat().getFormat(MONEY_FORMAT);
            short qty = wb.createDataFormat().getFormat(QUANTITY_FORMAT);
            short integer = wb.createDataFormat().getFormat("0");
            short percent = wb.createDataFormat().getFormat("0.00");
            short date = wb.createDataFormat().getFormat("yyyy/mm/dd");
            short dateTime = wb.createDataFormat().getFormat("yyyy/mm/dd hh:mm");
            byKind.put(Kind.MONEY, format(wb, money));
            byKind.put(Kind.QUANTITY, format(wb, qty));
            byKind.put(Kind.INTEGER, format(wb, integer));
            byKind.put(Kind.PERCENT, format(wb, percent));
            byKind.put(Kind.DATE, format(wb, date));
            byKind.put(Kind.DATETIME, format(wb, dateTime));
        }

        private static CellStyle format(SXSSFWorkbook wb, short format) {
            CellStyle s = wb.createCellStyle();
            s.setDataFormat(format);
            return s;
        }
    }
}
