package com.almahwar.controller.support;

import com.almahwar.service.DashboardService.Metric;
import com.almahwar.service.DashboardService.MetricValue;
import com.almahwar.util.MoneyUtil;

/**
 * Turns raw dashboard figures from the service into what the desktop cards show
 * (title, formatted value, caption, colour). Presentation only; a mobile client
 * would make its own choices from the same {@link MetricValue}s.
 */
public final class DashboardCards {

    /** Card colour; maps to the {@code kpi-*} CSS classes. */
    public enum Tone { PRIMARY, SUCCESS, INFO, WARNING, DANGER }

    /** Display form of one card. */
    public record CardView(Metric metric, String title, String value, String caption, Tone tone,
                           boolean money, boolean negative) {

        public String styleClass() {
            return "kpi-" + tone.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private DashboardCards() {
    }

    public static CardView view(MetricValue mv) {
        Metric m = mv.metric();
        boolean negative = mv.value().signum() < 0;
        String value = m.isMoney() ? MoneyUtil.formatWithCurrency(mv.value()) : mv.value().toPlainString();
        return switch (m) {
            case TODAY_SALES -> card(m, value, grossAndReturns(mv)
                    + "أمس: " + MoneyUtil.formatWithCurrency(mv.previousValue()), Tone.PRIMARY, negative);
            case MONTH_SALES -> card(m, value, grossAndReturns(mv) + "منذ بداية الشهر", Tone.PRIMARY, negative);
            case TODAY_INVOICES -> card(m, value, "فاتورة مكتملة اليوم", Tone.PRIMARY, false);
            case NET_PROFIT -> new CardView(m, negative ? "صافي الخسارة" : m.getTitle(), value,
                    "هذا الشهر، بعد التكلفة والمرتجعات والمصروفات", negative ? Tone.DANGER : Tone.SUCCESS,
                    true, negative);
            case EXPENSES -> card(m, value, "هذا الشهر", Tone.PRIMARY, negative);
            case CASH_BALANCE -> card(m, value, "النقدية المتاحة حاليًا", Tone.PRIMARY, negative);
            case RECEIVABLES -> card(m, value, "مبالغ مستحقة على العملاء", Tone.PRIMARY, negative);
            case PAYABLES -> card(m, value, "مبالغ مستحقة للموردين", Tone.PRIMARY, negative);
            case LOW_STOCK -> {
                boolean none = mv.value().signum() == 0;
                yield card(m, value, none ? "المخزون في الحد الآمن" : "عند الحد الأدنى أو أقل",
                        none ? Tone.SUCCESS : Tone.WARNING, false);
            }
        };
    }

    /** "الإجمالي X • المرتجعات Y • " under a net sales figure (empty when not provided). */
    static String grossAndReturns(MetricValue mv) {
        if (mv.gross() == null || mv.returns() == null) {
            return "";
        }
        return "الإجمالي " + MoneyUtil.format(mv.gross()) + "  •  المرتجعات " + MoneyUtil.format(mv.returns()) + "  •  ";
    }

    private static CardView card(Metric m, String value, String caption, Tone tone, boolean negative) {
        return new CardView(m, m.getTitle(), value, caption, tone, m.isMoney(), negative);
    }
}
