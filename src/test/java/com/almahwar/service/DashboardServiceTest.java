package com.almahwar.service;

import com.almahwar.model.DashboardLists.SalesPoint;
import com.almahwar.service.DashboardService.TrendRange;
import com.almahwar.util.QuantityUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @Test
    void dailyTrendHasEveryDayOldestFirst() {
        List<SalesPoint> rows = List.of(
                new SalesPoint(TODAY, new BigDecimal("12.500"), 2),
                new SalesPoint(TODAY.minusDays(3), new BigDecimal("7.250"), 1));

        List<SalesPoint> points = DashboardServiceImpl.fillGaps(TrendRange.LAST_7_DAYS, TODAY, rows);

        assertEquals(7, points.size());
        assertEquals(TODAY.minusDays(6), points.get(0).period());
        assertEquals(TODAY, points.get(6).period());
        assertEquals(new BigDecimal("7.250"), points.get(3).total());
        assertEquals(new BigDecimal("12.500"), points.get(6).total());
        assertEquals(new BigDecimal("0.000"), points.get(5).total());
        assertEquals(0, points.get(5).invoices());
    }

    @Test
    void monthlyTrendCrossesTheYear() {
        List<SalesPoint> rows = List.of(new SalesPoint(LocalDate.of(2025, 12, 1), new BigDecimal("900.000"), 40));

        List<SalesPoint> points = DashboardServiceImpl.fillGaps(TrendRange.LAST_12_MONTHS, TODAY, rows);

        assertEquals(12, points.size());
        assertEquals(LocalDate.of(2025, 11, 1), points.get(0).period());
        assertEquals(LocalDate.of(2026, 10, 1), points.get(11).period());
        assertEquals(new BigDecimal("900.000"), points.get(1).total());
    }

    @Test
    void emptyRangeIsAllZero() {
        List<SalesPoint> points = DashboardServiceImpl.fillGaps(TrendRange.LAST_30_DAYS, TODAY, List.of());
        assertEquals(30, points.size());
        assertEquals(0, points.stream().filter(p -> p.total().signum() != 0).count());
    }

    @Test
    void quantitiesAreShownWithoutTrailingZeros() {
        assertEquals("12", QuantityUtil.format(new BigDecimal("12.000")));
        assertEquals("2.5", QuantityUtil.format(new BigDecimal("2.500")));
        assertEquals("0.125", QuantityUtil.format(new BigDecimal("0.125")));
        assertEquals("0", QuantityUtil.format(null));
    }
}
