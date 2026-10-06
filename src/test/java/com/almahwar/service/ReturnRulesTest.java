package com.almahwar.service;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.RefundPlan;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.model.ReturnReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Return rules that need no database: net unit value, settlement, remaining quantities, field checks. */
class ReturnRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void netUnitValueIncludesLineAndInvoiceDiscountsAndNeverRoundsUp() {
        // no discounts: the unit price
        assertEquals(d("5.000"), ReturnServiceImpl.netUnitValue(d("50.000"), d("10"), d("50.000"), d("50.000")));
        // invoice discount 10% of 15 → 13.5 over 3 units = 4.500
        assertEquals(d("4.500"), ReturnServiceImpl.netUnitValue(d("15.000"), d("3"), d("15.000"), d("13.500")));
        // line discount already in the line total: 3 × 5 − 1 = 14 → 4.666… → 4.666 (down, never above what was paid)
        assertEquals(d("4.666"), ReturnServiceImpl.netUnitValue(d("14.000"), d("3"), d("14.000"), d("14.000")));
        assertEquals(d("0.000"), ReturnServiceImpl.netUnitValue(d("5"), d("0"), d("5"), d("5")));
    }

    @Test
    void settlementReducesTheDebtFirstAndRefundsOnlyTheRest() {
        // walk-in: everything back in cash
        RefundPlan walkIn = ReturnServiceImpl.settle(d("12.500"), null);
        assertEquals(d("12.500"), walkIn.refund());
        assertEquals(d("0.000"), walkIn.accountCredit());
        assertNull(walkIn.balanceBefore());
        // credit customer owing 50, returns 15 → no cash, debt 35
        RefundPlan credit = ReturnServiceImpl.settle(d("15"), d("50"));
        assertEquals(d("0.000"), credit.refund());
        assertEquals(d("15.000"), credit.accountCredit());
        // partly paid: owes 30 of a 50 invoice, returns all 50 → 30 off the debt, 20 paid back (what was paid)
        RefundPlan partial = ReturnServiceImpl.settle(d("50"), d("30"));
        assertEquals(d("20.000"), partial.refund());
        assertEquals(d("30.000"), partial.accountCredit());
        // owes nothing: all back; already in credit (−5): all back, the existing credit is untouched
        assertEquals(d("8.000"), ReturnServiceImpl.settle(d("8"), d("0")).refund());
        assertEquals(d("8.000"), ReturnServiceImpl.settle(d("8"), d("-5")).refund());
        assertTrue(ReturnServiceImpl.settle(d("8"), d("100")).accountCredit().compareTo(d("8")) == 0);
    }

    private static ReturnServiceImpl.Original original() {
        ReturnLine a = new ReturnLine();
        a.setOriginalItemId(1);
        a.setProductId(10);
        a.setProductName("صنف أ");
        a.setUnitName("حبة");
        a.setOriginalQuantity(d("10"));
        a.setUnitPrice(d("5.000"));
        a.setUnitCost(d("2.000"));
        ReturnLine b = new ReturnLine();
        b.setOriginalItemId(2);
        b.setProductId(11);
        b.setProductName("ماسورة");
        b.setUnitName("متر");
        b.setUnitAllowsDecimal(true);
        b.setOriginalQuantity(d("4.5"));
        b.setUnitPrice(d("1.250"));
        b.setUnitCost(d("0.800"));
        return new ReturnServiceImpl.Original(ReturnKind.SALE, 1, "SAL-000001", 7, false, d("55.625"),
                PaymentMethod.CASH, List.of(a, b));
    }

    private static ReturnLine req(int item, String qty) {
        ReturnLine l = new ReturnLine();
        l.setOriginalItemId(item);
        l.setQuantity(qty == null ? null : d(qty));
        return l;
    }

    @Test
    void remainingQuantitiesAreEnforced() {
        // 3 + 4 already returned → 3 left
        Map<Integer, BigDecimal> returned = Map.of(1, d("7"));
        List<ReturnLine> ok = ReturnServiceImpl.buildLines(original(), List.of(req(1, "3"), req(2, "1.5")), returned);
        assertEquals(2, ok.size());
        assertEquals(d("15.000"), ok.get(0).getLineTotal(), "3 × 5");
        assertEquals(d("6.000"), ok.get(0).getCostTotal(), "historical cost 3 × 2");
        assertEquals(d("1.875"), ok.get(1).getLineTotal(), "1.5 m × 1.25");

        ValidationException over = assertThrows(ValidationException.class,
                () -> ReturnServiceImpl.buildLines(original(), List.of(req(1, "4")), returned));
        assertTrue(over.getMessage().contains("أكبر من المتبقي") && over.getMessage().contains("3"), over.getMessage());
        assertThrows(ValidationException.class, () -> ReturnServiceImpl.buildLines(original(), List.of(req(1, "1.5")), Map.of()),
                "pieces cannot be returned in fractions");
        assertThrows(ValidationException.class, () -> ReturnServiceImpl.buildLines(original(), List.of(req(99, "1")), Map.of()),
                "a line of another invoice");
        assertThrows(ValidationException.class, () -> ReturnServiceImpl.buildLines(original(), List.of(req(1, "1"), req(1, "1")), Map.of()),
                "the same line twice");
        assertThrows(ValidationException.class, () -> ReturnServiceImpl.buildLines(original(), List.of(req(1, "0")), Map.of()),
                "nothing to return");
    }

    @Test
    void fieldsAreChecked() {
        ReturnDocument r = new ReturnDocument();
        r.setKind(ReturnKind.SALE);
        r.setRefundMethod(PaymentMethod.CASH);
        r.setLines(new ArrayList<>(List.of(req(1, "0"))));
        ValidationException e = assertThrows(ValidationException.class, () -> ReturnServiceImpl.validateFields(r).throwIfAny());
        assertTrue(e.getErrors().keySet().containsAll(List.of(ReturnService.ORIGINAL, ReturnService.REASON, ReturnService.LINES)),
                e.getErrors().toString());
        r.setOriginalId(1);
        r.setReason(ReturnReason.DEFECTIVE);
        r.setLines(new ArrayList<>(List.of(req(1, "-1"))));
        assertTrue(ReturnServiceImpl.validateFields(r).has(ReturnService.LINES), "negative quantity");
        r.setLines(new ArrayList<>(List.of(req(1, "1.0001"))));
        assertTrue(ReturnServiceImpl.validateFields(r).has(ReturnService.LINES), "4 decimals");
        r.setLines(new ArrayList<>(List.of(req(1, "2"))));
        r.setRefundMethod(PaymentMethod.CREDIT);
        assertTrue(ReturnServiceImpl.validateFields(r).has(ReturnService.METHOD), "money moves with a real method");
        r.setRefundMethod(PaymentMethod.KNET);
        ReturnServiceImpl.validateFields(r).throwIfAny();
        assertEquals("عيب في المنتج", ReturnReason.DEFECTIVE.getLabelAr());
        assertEquals("SRN", ReturnKind.SALE.getPrefix());
        assertEquals("PRN", ReturnKind.PURCHASE.getPrefix());
    }
}
