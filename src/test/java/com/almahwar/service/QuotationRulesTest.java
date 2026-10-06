package com.almahwar.service;

import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static com.almahwar.model.QuotationStatus.ACCEPTED;
import static com.almahwar.model.QuotationStatus.CONVERTED;
import static com.almahwar.model.QuotationStatus.DRAFT;
import static com.almahwar.model.QuotationStatus.EXPIRED;
import static com.almahwar.model.QuotationStatus.REJECTED;
import static com.almahwar.model.QuotationStatus.SENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quotation rules that need no database: workflow, totals and validation. */
class QuotationRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static QuotationItem item(String qty, String price, String discount) {
        QuotationItem i = new QuotationItem();
        i.setProductId(1);
        i.setProductName("صنف");
        i.setQuantity(d(qty));
        i.setUnitPrice(d(price));
        i.setDiscountAmount(d(discount));
        return i;
    }

    /** The field errors of {@link QuotationServiceImpl#validateFields} (empty when valid). */
    private static Map<String, String> check(Quotation q, LocalDate today) {
        try {
            QuotationServiceImpl.validateFields(q, today).throwIfAny();
            return Map.of();
        } catch (ValidationException e) {
            return e.getErrors();
        }
    }

    private static Quotation quotation(String discount, QuotationItem... items) {
        Quotation q = new Quotation();
        q.setCustomerId(1);
        q.setPriceType(SaleType.RETAIL);
        q.setValidUntil(TODAY.plusDays(14));
        q.setDiscountAmount(d(discount));
        q.setItems(new ArrayList<>(List.of(items)));
        return q;
    }

    // ---------- workflow ----------

    @Test
    void workflowTransitions() {
        assertEquals(EnumSet.of(SENT, ACCEPTED, EXPIRED), DRAFT.next());
        assertEquals(EnumSet.of(ACCEPTED, REJECTED, DRAFT, EXPIRED), SENT.next(), "a sent quotation can be reopened");
        assertEquals(EnumSet.of(CONVERTED, EXPIRED), ACCEPTED.next());
        for (QuotationStatus end : List.of(REJECTED, EXPIRED, CONVERTED)) {
            assertTrue(end.isFinal(), end + " is final");
            assertTrue(end.next().isEmpty());
        }
        assertFalse(DRAFT.canMoveTo(REJECTED), "only a sent quotation is rejected");
        assertFalse(DRAFT.canMoveTo(CONVERTED), "only an accepted quotation is converted");
        assertFalse(SENT.canMoveTo(CONVERTED));
        assertFalse(ACCEPTED.canMoveTo(DRAFT), "an accepted quotation is not edited any more");
        assertFalse(EXPIRED.canMoveTo(ACCEPTED), "an expired quotation cannot be accepted");
        assertFalse(CONVERTED.canMoveTo(ACCEPTED));
    }

    @Test
    void onlyDraftsAreEditable() {
        for (QuotationStatus s : QuotationStatus.values()) {
            assertEquals(s == DRAFT, s.isEditable(), s.name());
        }
    }

    @Test
    void invalidTransitionMessagesExplainWhy() {
        assertTrue(QuotationServiceImpl.invalidTransition(EXPIRED, ACCEPTED).getMessage().contains("انتهت صلاحية"));
        assertTrue(QuotationServiceImpl.invalidTransition(CONVERTED, ACCEPTED).getMessage().contains("فاتورة"));
        assertTrue(QuotationServiceImpl.invalidTransition(REJECTED, ACCEPTED).getMessage().contains("مرفوض"));
    }

    // ---------- totals ----------

    @Test
    void totalsUseTheSaleFormulas() {
        // 3 × 2.500 − 0.500 = 7.000 ; 2.5 × 4.000 = 10.000 ; subtotal 17.000 ; total 17.000 − 1.000 = 16.000
        Quotation q = quotation("1.000", item("3", "2.500", "0.500"), item("2.5", "4.000", "0"));
        assertTrue(check(q, TODAY).isEmpty());
        assertEquals(d("7.000"), q.getItems().get(0).getLineTotal());
        assertEquals(d("17.000"), q.getSubtotal());
        assertEquals(d("16.000"), q.getTotalAmount());
    }

    @Test
    void roundingMatchesMoneyUtil() {
        // 1.333 × 3 = 3.999 ; 0.333 × 0.5 = 0.1665 → 0.167 (HALF_UP, 3 decimals like every money value)
        Quotation q = quotation("0", item("3", "1.333", "0"), item("0.5", "0.333", "0"));
        check(q, TODAY);
        assertEquals(d("3.999"), q.getItems().get(0).getLineTotal());
        assertEquals(d("0.167"), q.getItems().get(1).getLineTotal());
        assertEquals(d("4.166"), q.getTotalAmount());
    }

    @Test
    void lineToSaleItemKeepsTheAgreedValues() {
        QuotationItem i = item("2", "5.000", "0.250");
        i.setProductCode("P1");
        SaleItem s = i.toSaleItem();
        assertEquals(i.getProductId(), s.getProductId());
        assertEquals(0, d("2").compareTo(s.getQuantity()));
        assertEquals(0, d("5.000").compareTo(s.getUnitPrice()), "the quotation price, not today's list price");
        assertEquals(0, d("0.250").compareTo(s.getDiscountAmount()));
        assertEquals(i.getLineTotal(), s.getLineTotal());
    }

    @Test
    void walkInQuotationsShowTheProspect() {
        Quotation q = quotation("0", item("1", "1", "0"));
        q.setCustomerCode(com.almahwar.model.Customer.CASH_CUSTOMER_CODE);
        q.setCustomerName("عميل نقدي");
        q.setProspectName("شركة الأمل");
        q.setProspectPhone("55512345");
        assertTrue(q.isWalkIn());
        assertEquals("شركة الأمل", q.getDisplayName());
        assertEquals("55512345", q.getDisplayPhone());
        q.setCustomerCode("C-1");
        q.setCustomerName("أحمد");
        q.setCustomerPhone("99988877");
        assertEquals("أحمد", q.getDisplayName(), "a registered customer is shown with its own name");
        assertEquals("99988877", q.getDisplayPhone());
    }

    // ---------- validation ----------

    @Test
    void requiredFields() {
        Quotation q = new Quotation();
        q.setPriceType(null);   // RETAIL by default
        Map<String, String> v = check(q, TODAY);
        assertTrue(v.containsKey(QuotationService.CUSTOMER));
        assertTrue(v.containsKey("priceType"));
        assertTrue(v.containsKey(QuotationService.VALID_UNTIL));
        assertTrue(v.containsKey(QuotationService.ITEMS), "at least one line");
    }

    @Test
    void validUntilCannotBeBeforeTheQuotationDate() {
        Quotation q = quotation("0", item("1", "1", "0"));
        q.setValidUntil(TODAY.minusDays(1));
        assertTrue(check(q, TODAY).containsKey(QuotationService.VALID_UNTIL));
        q.setValidUntil(TODAY);
        assertTrue(check(q, TODAY).isEmpty(), "valid until today is allowed");
        q.setValidUntil(null);
        assertTrue(check(q, TODAY).containsKey(QuotationService.VALID_UNTIL));
    }

    @Test
    void lineRulesAreTheSaleRules() {
        for (QuotationItem bad : List.of(item("0", "1", "0"), item("-1", "1", "0"), item("1.0001", "1", "0"),
                item("1", "-1", "0"), item("1", "1.0005", "0"), item("1", "1", "-0.5"), item("2", "1", "2.001"))) {
            Map<String, String> v = check(quotation("0", bad), TODAY);
            assertTrue(v.containsKey(QuotationService.ITEMS),
                    "rejected: " + bad.getQuantity() + " × " + bad.getUnitPrice() + " − " + bad.getDiscountAmount());
            assertTrue(v.get(QuotationService.ITEMS).startsWith("السطر 1"), "names the line");
        }
        assertTrue(check(quotation("0", item("0.25", "0", "0")), TODAY).isEmpty(),
                "a free item and a fractional quantity pass here (the unit rule needs the database)");
    }

    @Test
    void quotationDiscountRules() {
        Map<String, String> negative = check(quotation("-1", item("1", "5", "0")), TODAY);
        assertTrue(negative.containsKey(QuotationService.DISCOUNT));
        Map<String, String> tooBig = check(quotation("5.001", item("1", "5", "0")), TODAY);
        assertTrue(tooBig.containsKey(QuotationService.DISCOUNT), "total cannot be negative");
        Map<String, String> decimals = check(quotation("0.0001", item("1", "5", "0")), TODAY);
        assertTrue(decimals.containsKey(QuotationService.DISCOUNT));
        Quotation free = quotation("5", item("1", "5", "0"));
        assertTrue(check(free, TODAY).isEmpty());
        assertEquals(0, free.getTotalAmount().signum(), "a discount equal to the subtotal gives zero");
    }

    @Test
    void textLimits() {
        Quotation q = quotation("0", item("1", "1", "0"));
        q.setNotes("x".repeat(501));
        q.setTerms("x".repeat(1001));
        q.setProspectName("x".repeat(151));
        q.setProspectPhone("1".repeat(21));
        Map<String, String> v = check(q, TODAY);
        for (String field : List.of(QuotationService.NOTES, QuotationService.TERMS, QuotationService.PROSPECT_NAME,
                QuotationService.PROSPECT_PHONE)) {
            assertTrue(v.containsKey(field), field);
        }
        assertNotEquals(0, v.size());
    }
}
