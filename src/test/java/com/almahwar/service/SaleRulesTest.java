package com.almahwar.service;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleType;
import com.almahwar.service.InsufficientStockException.Shortage;
import com.almahwar.service.PaymentRules.PaymentPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sales rules that need no database: calculations, line / invoice validation, price lists, payment. */
class SaleRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static SaleItem item(String qty, String price, String discount, String cost) {
        SaleItem i = new SaleItem();
        i.setProductId(1);
        i.setProductName("صنف");
        i.setQuantity(d(qty));
        i.setUnitPrice(d(price));
        i.setDiscountAmount(d(discount));
        i.setUnitCost(d(cost));
        return i;
    }

    private static Sale sale(String discount, SaleItem... items) {
        Sale s = new Sale();
        s.setCustomerId(1);
        s.setDiscountAmount(d(discount));
        s.setItems(new ArrayList<>(List.of(items)));
        return s;
    }

    @Test
    void totalsFollowTheFormulas() {
        // 3 × 2.500 − 0.500 = 7.000 ; 2 × 10.000 − 0 = 20.000 ; subtotal 27.000 ; total 27.000 − 1.000 = 26.000
        Sale s = sale("1.000", item("3", "2.500", "0.500", "1.200"), item("2", "10.000", "0", "5.000"));
        s.recalculate();
        assertEquals(d("7.000"), s.getItems().get(0).getLineTotal());
        assertEquals(d("27.000"), s.getSubtotal());
        assertEquals(d("26.000"), s.getTotalAmount());
        s.setPaidAmount(d("10.000"));
        assertEquals(d("16.000"), s.getRemainingAmount(), "remaining = total − paid");
        // cost total = 3 × 1.200 + 2 × 5.000 = 13.600 ; profit = 26.000 − 13.600 (both discounts reduce it)
        assertEquals(d("13.600"), s.itemsCostTotal());
        assertEquals(d("3.400"), s.getItems().get(0).getLineProfit(), "line profit = 7.000 − 3.600");
    }

    @Test
    void roundingIsHalfUpToThreeDecimals() {
        // 3 × 0.3335 is not a valid price (4 decimals), but quantities × prices can produce more decimals:
        // 1.5 m × 0.335 = 0.5025 → 0.503
        assertEquals(d("0.503"), item("1.5", "0.335", "0", "0").getLineTotal());
        assertEquals(d("0.503"), item("1.5", "0", "0", "0.335").getCostTotal());
    }

    @Test
    void validCartPasses() {
        Sale s = sale("0", item("2", "1.500", "0.250", "0"));
        Validation v = SaleServiceImpl.validateFields(s);
        v.throwIfAny();
        assertEquals(d("2.750"), s.getTotalAmount());
    }

    @Test
    void emptyCartAndMissingCustomerAreRefused() {
        Sale s = sale("0");
        s.setCustomerId(null);
        ValidationException e = assertThrows(ValidationException.class, () -> SaleServiceImpl.validateFields(s).throwIfAny());
        assertTrue(e.getErrors().containsKey(SaleService.ITEMS));
        assertTrue(e.getErrors().containsKey(SaleService.CUSTOMER));
    }

    @Test
    void badLinesAreRefusedInArabic() {
        assertEquals("الكمية يجب أن تكون أكبر من صفر.", SaleServiceImpl.itemError(item("0", "1", "0", "0")));
        assertEquals("الكمية يجب أن تكون أكبر من صفر.", SaleServiceImpl.itemError(item("-1", "1", "0", "0")));
        assertEquals("سعر البيع لا يمكن أن يكون سالبًا.", SaleServiceImpl.itemError(item("1", "-0.001", "0", "0")));
        assertEquals("خصم السطر لا يمكن أن يكون سالبًا.", SaleServiceImpl.itemError(item("1", "1", "-1", "0")));
        assertTrue(SaleServiceImpl.itemError(item("2", "1.000", "2.001", "0")).contains("سالبًا"),
                "a line discount cannot make the line total negative");
        assertTrue(SaleServiceImpl.itemError(item("1", "1.0005", "0", "0")).contains("3 منازل"));
        assertTrue(SaleServiceImpl.itemError(item("1.0001", "1", "0", "0")).contains("3 منازل"));
        assertNull(SaleServiceImpl.itemError(item("2", "1.000", "2.000", "0")), "a free line (total 0) is allowed");
    }

    @Test
    void invoiceDiscountCannotMakeTheTotalNegative() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> SaleServiceImpl.validateFields(sale("5.001", item("1", "5", "0", "0"))).throwIfAny());
        assertTrue(e.errorFor(SaleService.DISCOUNT).contains("سالبًا"));
        assertThrows(ValidationException.class,
                () -> SaleServiceImpl.validateFields(sale("-1", item("1", "5", "0", "0"))).throwIfAny());
        Sale free = sale("5.000", item("1", "5", "0", "0"));
        SaleServiceImpl.validateFields(free).throwIfAny();
        assertEquals(d("0.000"), free.getTotalAmount(), "a fully discounted invoice totals 0");
    }

    @Test
    void retailAndWholesalePriceLists() {
        assertEquals(d("10.000"), SaleType.RETAIL.priceOf(d("10.000"), d("8.000")));
        assertEquals(d("8.000"), SaleType.WHOLESALE.priceOf(d("10.000"), d("8.000")));
        assertEquals(d("10.000"), SaleType.WHOLESALE.priceOf(d("10.000"), d("0.000")),
                "no wholesale price → the retail price");
        assertEquals(SaleType.WHOLESALE, SaleType.fromCode("WHOLESALE"));
    }

    @Test
    void paymentRulesAreSharedWithPurchases() {
        BigDecimal total = d("100.000");
        assertEquals(total, PaymentRules.resolve(PaymentType.KNET, null, null, total).paid());
        PaymentPlan credit = PaymentRules.resolve(PaymentType.CREDIT, null, null, total);
        assertEquals(d("0.000"), credit.paid());
        assertEquals(PaymentMethod.CREDIT, credit.method());
        PaymentPlan partial = PaymentRules.resolve(PaymentType.PARTIAL, PaymentMethod.CASH, d("40"), total);
        assertEquals(d("60.000"), partial.remaining(total));
        assertThrows(ValidationException.class, () -> PaymentRules.resolve(PaymentType.PARTIAL, PaymentMethod.CASH, d("0"), total));
        assertThrows(ValidationException.class, () -> PaymentRules.resolve(PaymentType.PARTIAL, PaymentMethod.CASH, total, total));
        assertEquals(PaymentStatus.PARTIAL, PaymentStatus.of(total, d("40")));
    }

    @Test
    void stockMessageShowsAvailableAndRequested() {
        InsufficientStockException e = new InsufficientStockException(
                List.of(new Shortage(1, "محبس 1/2", d("1.000"), d("3.000"))));
        assertTrue(e.getMessage().startsWith("الكمية المطلوبة غير متوفرة بالمخزون"));
        assertTrue(e.getMessage().contains("المتوفر 1") && e.getMessage().contains("المطلوب 3"), e.getMessage());
        assertTrue(e.getErrors().containsKey(SaleService.ITEMS));
    }

    @Test
    void walkInCustomerIsRecognised() {
        Sale s = new Sale();
        s.setCustomerCode("CASH");
        assertTrue(s.isWalkIn());
    }
}
