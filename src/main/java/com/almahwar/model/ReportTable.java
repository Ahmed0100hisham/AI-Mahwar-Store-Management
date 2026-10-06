package com.almahwar.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A report as a plain table (title, summary figures, columns, rows) with raw values, built by the service from the
 * typed report data after the permission checks. The screen, the print preview and the Excel export all read it,
 * so they always show the same — authorised — figures.
 *
 * @param period    the covered range, or {@code null} for "now" reports (inventory, balances)
 * @param totalRows matching rows over all pages ({@code rows} holds one page, or everything for an export)
 */
public record ReportTable(ReportType type, String title, LocalDate from, LocalDate to, LocalDateTime generatedAt,
                          String generatedBy, List<Item> summary, List<Column> columns, List<List<Object>> rows,
                          int page, int pageSize, long totalRows, List<String> notes) {

    /** How a value is shown and exported. */
    public enum Kind {
        TEXT, MONEY, QUANTITY, INTEGER, PERCENT, DATE, DATETIME
    }

    public record Column(String header, Kind kind) {
    }

    /** A summary figure. */
    public record Item(String label, Object value, Kind kind) {
    }

    public int pageCount() {
        return pageSize <= 0 ? 1 : (int) Math.max(1, (totalRows + pageSize - 1) / pageSize);
    }
}
