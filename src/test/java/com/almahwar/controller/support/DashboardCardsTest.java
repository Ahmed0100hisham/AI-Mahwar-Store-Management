package com.almahwar.controller.support;

import com.almahwar.controller.support.DashboardCards.CardView;
import com.almahwar.controller.support.DashboardCards.Tone;
import com.almahwar.service.DashboardService.Metric;
import com.almahwar.service.DashboardService.MetricValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The desktop cards must look exactly as before the service started returning raw values. */
class DashboardCardsTest {

    private static CardView view(Metric m, String value, String previous) {
        return DashboardCards.view(new MetricValue(m, new BigDecimal(value),
                previous == null ? null : new BigDecimal(previous)));
    }

    @Test
    void todaySalesShowsYesterdayAsCaption() {
        CardView c = view(Metric.TODAY_SALES, "1250.750", "81.060");
        assertEquals("مبيعات اليوم", c.title());
        assertEquals("1,250.750 د.ك", c.value());
        assertEquals("أمس: 81.060 د.ك", c.caption());
        assertEquals(Tone.PRIMARY, c.tone());
        assertTrue(c.money());
        assertEquals("kpi-primary", c.styleClass());
    }

    @Test
    void countsAreShownAsPlainNumbers() {
        CardView c = view(Metric.TODAY_INVOICES, "6", null);
        assertEquals("6", c.value());
        assertEquals("فاتورة مكتملة اليوم", c.caption());
        assertFalse(c.money());
    }

    @Test
    void lossTurnsTheProfitCardRed() {
        CardView profit = view(Metric.NET_PROFIT, "103.566", null);
        assertEquals("صافي الربح", profit.title());
        assertEquals(Tone.SUCCESS, profit.tone());

        CardView loss = view(Metric.NET_PROFIT, "-12.500", null);
        assertEquals("صافي الخسارة", loss.title());
        assertEquals("-12.500 د.ك", loss.value());
        assertEquals(Tone.DANGER, loss.tone());
        assertTrue(loss.negative());
    }

    @Test
    void lowStockIsGreenWhenZero() {
        assertEquals(Tone.SUCCESS, view(Metric.LOW_STOCK, "0", null).tone());
        assertEquals("المخزون في الحد الآمن", view(Metric.LOW_STOCK, "0", null).caption());
        assertEquals(Tone.WARNING, view(Metric.LOW_STOCK, "7", null).tone());
    }

    @Test
    void everyMetricHasACard() {
        for (Metric m : Metric.values()) {
            CardView c = view(m, "1.000", "0.000");
            assertEquals(m, c.metric());
            assertFalse(c.caption().isBlank(), m.name());
        }
    }
}
