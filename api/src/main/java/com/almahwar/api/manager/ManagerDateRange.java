package com.almahwar.api.manager;

import com.almahwar.api.error.FieldValidationException;
import com.almahwar.model.ReportFilter;
import com.almahwar.model.ReportPeriod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

/** Inclusive accounting days; SQL predicates use [from midnight, to + 1 day midnight). */
public record ManagerDateRange(LocalDate from, LocalDate to) {
    public record Input(
            @Schema(description="today (default), this_week (Sunday), this_month, custom; omit with explicit from/to") String period,
            @Schema(description="Inclusive SQL-server business-local date, ISO yyyy-MM-dd") LocalDate from,
            @Schema(description="Inclusive SQL-server business-local date, ISO yyyy-MM-dd") LocalDate to) { }
    public static ManagerDateRange resolve(Input input, int maxDays, Supplier<LocalDate> today) {
        Input in=input==null?new Input(null,null,null):input;
        String period=in.period();
        LocalDate start=in.from(), end=in.to();
        if (start!=null || end!=null) {
            if (start==null || end==null || (period!=null && !period.equals("custom")))
                throw invalid("Supply both from/to; period must be omitted or custom.");
        } else {
            ReportPeriod preset=switch(period==null?"today":period) {
                case "today" -> ReportPeriod.TODAY;
                case "this_week" -> ReportPeriod.THIS_WEEK;
                case "this_month" -> ReportPeriod.THIS_MONTH;
                default -> throw invalid("Use today, this_week, this_month or custom with from/to.");
            };
            var range=preset.range(today.get()); start=range.from(); end=range.to();
        }
        if (start.isBefore(LocalDate.of(1900,1,1)) || end.isAfter(LocalDate.of(9998,12,31))
                || start.isAfter(end) || ChronoUnit.DAYS.between(start,end)+1>maxDays)
            throw invalid("Invalid range; maximum "+maxDays+" inclusive days.");
        return new ManagerDateRange(start,end);
    }
    public ReportFilter filter() { return ReportFilter.between(from,to); }
    private static FieldValidationException invalid(String message) { return new FieldValidationException("range",message); }
}
