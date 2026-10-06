package com.almahwar.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Report date presets. Every range is a pair of <b>inclusive</b> days (from 00:00:00 to 23:59:59.999 of {@code to});
 * "today" is the database server's date, like the dashboard. The week starts on Sunday (the Kuwaiti work week).
 */
public enum ReportPeriod {

    TODAY("اليوم"),
    YESTERDAY("أمس"),
    THIS_WEEK("هذا الأسبوع"),
    THIS_MONTH("هذا الشهر"),
    LAST_MONTH("الشهر الماضي"),
    THIS_YEAR("هذه السنة"),
    CUSTOM("فترة مخصصة");

    public static final DayOfWeek FIRST_DAY_OF_WEEK = DayOfWeek.SUNDAY;

    private final String labelAr;

    ReportPeriod(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** The inclusive range for {@code today}; {@code null} for {@link #CUSTOM}. */
    public DateRange range(LocalDate today) {
        return switch (this) {
            case TODAY -> new DateRange(today, today);
            case YESTERDAY -> new DateRange(today.minusDays(1), today.minusDays(1));
            case THIS_WEEK -> new DateRange(today.with(TemporalAdjusters.previousOrSame(FIRST_DAY_OF_WEEK)), today);
            case THIS_MONTH -> new DateRange(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()));
            case LAST_MONTH -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                yield new DateRange(first, first.with(TemporalAdjusters.lastDayOfMonth()));
            }
            case THIS_YEAR -> new DateRange(today.withDayOfYear(1), today.with(TemporalAdjusters.lastDayOfYear()));
            case CUSTOM -> null;
        };
    }

    /** Inclusive day range. */
    public record DateRange(LocalDate from, LocalDate to) {
    }
}
