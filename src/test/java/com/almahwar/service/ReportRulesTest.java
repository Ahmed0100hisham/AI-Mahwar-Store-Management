package com.almahwar.service;

import com.almahwar.model.Permission;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.ReportPeriod;
import com.almahwar.model.ReportPeriod.DateRange;
import com.almahwar.model.ReportTable;
import com.almahwar.model.ReportType;
import com.almahwar.model.Reports;
import com.almahwar.model.Reports.InventoryRow;
import com.almahwar.model.Reports.InventoryValuation;
import com.almahwar.model.Reports.ProductPerformance;
import com.almahwar.model.Role;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Report rules that need no database: date presets, filter validation, permissions, cost hiding, Excel output. */
class ReportRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // ---------- date presets (inclusive days) ----------

    @Test
    void periodRanges() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        assertEquals(new DateRange(monday, monday), ReportPeriod.TODAY.range(monday));
        assertEquals(new DateRange(monday.minusDays(1), monday.minusDays(1)), ReportPeriod.YESTERDAY.range(monday));
        assertEquals(new DateRange(LocalDate.of(2026, 10, 4), monday), ReportPeriod.THIS_WEEK.range(monday), "week starts Sunday");
        assertEquals(DayOfWeek.SUNDAY, ReportPeriod.FIRST_DAY_OF_WEEK);
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        assertEquals(new DateRange(sunday, sunday), ReportPeriod.THIS_WEEK.range(sunday));
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        assertEquals(new DateRange(sunday, saturday), ReportPeriod.THIS_WEEK.range(saturday));
        assertEquals(new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), ReportPeriod.THIS_MONTH.range(monday));
        assertEquals(new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), ReportPeriod.LAST_MONTH.range(monday));
        assertEquals(new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), ReportPeriod.THIS_YEAR.range(monday));
        assertNull(ReportPeriod.CUSTOM.range(monday));
    }

    @Test
    void periodRangesAcrossMonthAndYearBoundaries() {
        LocalDate newYear = LocalDate.of(2027, 1, 1);
        assertEquals(new DateRange(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 12, 31)), ReportPeriod.YESTERDAY.range(newYear));
        assertEquals(new DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)), ReportPeriod.LAST_MONTH.range(newYear));
        assertEquals(new DateRange(LocalDate.of(2026, 12, 27), newYear), ReportPeriod.THIS_WEEK.range(newYear), "week across the year");
        assertEquals(LocalDate.of(2028, 2, 29), ReportPeriod.THIS_MONTH.range(LocalDate.of(2028, 2, 10)).to(), "leap year");
        assertEquals(LocalDate.of(2027, 2, 28), ReportPeriod.THIS_MONTH.range(LocalDate.of(2027, 2, 10)).to());
        assertEquals(new DateRange(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)),
                ReportPeriod.LAST_MONTH.range(LocalDate.of(2028, 3, 31)));
    }

    // ---------- filter validation ----------

    private static void invalid(ReportType type, ReportFilter f, String field) {
        ValidationException e = assertThrows(ValidationException.class,
                () -> ReportServiceImpl.validate(type, f, ReportFilter.MAX_PAGE_SIZE));
        assertTrue(e.getErrors().containsKey(field), field + " in " + e.getErrors());
    }

    @Test
    void filterValidation() {
        LocalDate day = LocalDate.of(2026, 10, 5);
        invalid(ReportType.SALES, ReportFilter.none(), "from");
        invalid(ReportType.SALES, ReportFilter.between(day, day.minusDays(1)), "from");
        ReportServiceImpl.validate(ReportType.SALES, ReportFilter.between(day, day), ReportFilter.MAX_PAGE_SIZE);
        ReportServiceImpl.validate(ReportType.INVENTORY, ReportFilter.none(), ReportFilter.MAX_PAGE_SIZE);
        invalid(ReportType.SALES, ReportFilter.between(day, day).page(-1), "page");
        invalid(ReportType.SALES, ReportFilter.between(day, day).pageSize(0), "pageSize");
        invalid(ReportType.SALES, ReportFilter.between(day, day).pageSize(ReportFilter.MAX_PAGE_SIZE + 1), "pageSize");
        invalid(ReportType.BEST_SELLERS, ReportFilter.between(day, day).topN(0), "topN");
        invalid(ReportType.BEST_SELLERS, ReportFilter.between(day, day).topN(ReportService.MAX_TOP_N + 1), "topN");
        invalid(ReportType.SLOW_MOVING, ReportFilter.none().days(0), "days");
        invalid(ReportType.CUSTOMER_STATEMENT, ReportFilter.between(day, day), "customerId");
        invalid(ReportType.SUPPLIER_STATEMENT, ReportFilter.between(day, day), "supplierId");
        ReportServiceImpl.validate(ReportType.SALES, ReportFilter.between(day, day).pageSize(50_000), ReportService.EXPORT_MAX_ROWS);
    }

    @Test
    void filterCopyIsIndependent() {
        ReportFilter f = ReportFilter.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).search("x").customerId(4);
        ReportFilter c = f.copy().page(3).search("y");
        assertEquals("x", f.getSearch());
        assertEquals(0, f.getPage());
        assertEquals(4, c.getCustomerId());
        assertEquals(f.getFrom(), c.getFrom());
    }

    // ---------- report types and role permissions ----------

    @Test
    void everyReportDeclaresItsPermissionAndFilters() {
        for (ReportType t : ReportType.values()) {
            assertFalse(t.getPermissions().isEmpty(), t.name());
            assertEquals(t.isDated(), t.has(ReportType.Field.DATE), t.name() + ": dated ⇔ date filter");
        }
        assertTrue(ReportType.CUSTOMER_STATEMENT.has(ReportType.Field.CUSTOMER));
        assertTrue(ReportType.SUPPLIER_STATEMENT.has(ReportType.Field.SUPPLIER));
    }

    @Test
    void rolePermissions() {
        assertTrue(RolePermissions.forRole(Role.ADMIN).containsAll(Set.of(Permission.REPORTS_VIEW, Permission.REPORTS_PROFIT,
                Permission.REPORTS_AUDIT, Permission.REPORTS_EXPORT, Permission.REPORTS_INVENTORY)));
        Set<Permission> accountant = RolePermissions.forRole(Role.ACCOUNTANT);
        assertTrue(accountant.containsAll(Set.of(Permission.REPORTS_SALES, Permission.REPORTS_PROFIT, Permission.REPORTS_PURCHASES,
                Permission.REPORTS_EXPENSES, Permission.REPORTS_CASHBOX, Permission.REPORTS_PARTIES,
                Permission.REPORTS_QUOTATIONS, Permission.REPORTS_EXPORT)));
        assertFalse(accountant.contains(Permission.REPORTS_AUDIT));
        Set<Permission> store = RolePermissions.forRole(Role.STOREKEEPER);
        assertTrue(store.contains(Permission.REPORTS_INVENTORY));
        assertFalse(store.contains(Permission.REPORTS_PROFIT));
        assertFalse(store.contains(Permission.REPORTS_SALES));
        Set<Permission> cashier = RolePermissions.forRole(Role.CASHIER);
        assertTrue(cashier.contains(Permission.REPORTS_SALES));
        for (Permission p : List.of(Permission.REPORTS_PROFIT, Permission.REPORTS_EXPORT, Permission.REPORTS_CASHBOX,
                Permission.PRODUCT_COST, Permission.REPORTS_INVENTORY)) {
            assertFalse(cashier.contains(p), p.name());
        }
    }

    // ---------- cost / profit columns ----------

    @Test
    void costColumnsOnlyForPermittedUsers() {
        Reports.Result<InventoryValuation, InventoryRow> inv = new Reports.Result<>(new InventoryValuation(1, null),
                List.of(new InventoryRow("P1", null, "صنف", "قسم", null, "حبة", d("5"), d("1"), null, null, null, true)),
                0, 200, 1);
        ReportTable hidden = new ReportTables(ReportType.INVENTORY, ReportFilter.none(), LocalDate.now(), "u", false, false)
                .inventory(inv);
        assertTrue(hidden.columns().stream().noneMatch(c -> c.header().equals("القيمة") || c.header().equals("سعر الشراء")));
        assertEquals(hidden.columns().size(), hidden.rows().get(0).size(), "one value per column");
        ReportTable shown = new ReportTables(ReportType.INVENTORY, ReportFilter.none(), LocalDate.now(), "u", true, false)
                .inventory(inv);
        assertTrue(shown.columns().stream().anyMatch(c -> c.header().equals("القيمة")));
        assertEquals(shown.columns().size(), shown.rows().get(0).size());

        List<ProductPerformance> top = List.of(new ProductPerformance("P1", "صنف", null, "حبة", d("10"), d("2"), d("8"),
                d("16"), null));
        ReportTable noProfit = new ReportTables(ReportType.BEST_SELLERS, ReportFilter.none(), LocalDate.now(), "u", true, false)
                .bestSellers(top);
        assertTrue(noProfit.columns().stream().noneMatch(c -> c.header().contains("ربح")));
        assertTrue(noProfit.summary().stream().noneMatch(i -> i.label().contains("ربح")));
    }

    // ---------- Excel ----------

    @Test
    void xlsxWriterOutput() throws Exception {
        ReportTable t = new ReportTable(ReportType.EXPENSES, "تقرير المصروفات", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), LocalDateTime.of(2026, 10, 5, 9, 30), "مدير النظام",
                List.of(new ReportTable.Item("إجمالي المصروفات", d("1250.125"), ReportTable.Kind.MONEY)),
                List.of(new ReportTable.Column("الوصف", ReportTable.Kind.TEXT),
                        new ReportTable.Column("المبلغ", ReportTable.Kind.MONEY),
                        new ReportTable.Column("التاريخ", ReportTable.Kind.DATETIME)),
                List.of(List.of("إيجار المعرض", d("1250.125"), LocalDateTime.of(2026, 9, 1, 12, 0))),
                0, 1, 1, List.of("ملاحظة"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxReportWriter.write(t, "شركة المحور", out);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            assertTrue(s.isRightToLeft());
            assertEquals("تقرير المصروفات", s.getSheetName());
            assertEquals("شركة المحور", s.getRow(0).getCell(0).getStringCellValue());
            boolean found = false;
            for (org.apache.poi.ss.usermodel.Row row : s) {
                if (row.getCell(0) != null && "إيجار المعرض".equals(row.getCell(0).toString())) {
                    assertEquals(1250.125, row.getCell(1).getNumericCellValue(), 1e-9);
                    assertEquals(XlsxReportWriter.MONEY_FORMAT, row.getCell(1).getCellStyle().getDataFormatString());
                    found = true;
                }
            }
            assertTrue(found, "the data row");
        }
        assertEquals("a b c d e f g", XlsxReportWriter.sheetName("a:b\\c/d?e*f[g]").trim().replaceAll("\\s+", " "));
        assertEquals(31, XlsxReportWriter.sheetName("x".repeat(50)).length());
    }
}
