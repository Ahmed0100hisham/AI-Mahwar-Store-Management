package com.almahwar.service;

import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSummary;
import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseCategory;
import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field rules of payments, expenses and manual cash operations (no database). */
class FinanceRulesTest {

    private static PartyPayment payment(String amount) {
        PartyPayment p = new PartyPayment();
        p.setPartyType(PartyType.CUSTOMER);
        p.setPartyId(1);
        p.setAmount(amount == null ? null : new BigDecimal(amount));
        p.setPaymentMethod(PaymentMethod.CASH);
        return p;
    }

    private static String amountError(String amount) {
        return PaymentServiceImpl.validateFields(payment(amount)).has(PaymentService.AMOUNT)
                ? assertThrows(ValidationException.class, () -> PaymentServiceImpl.validateFields(payment(amount)).throwIfAny())
                .errorFor(PaymentService.AMOUNT)
                : null;
    }

    @Test
    void amountMustBePositiveWithAtMostThreeDecimals() {
        assertEquals("أدخل المبلغ.", amountError(null));
        assertEquals("المبلغ يجب أن يكون أكبر من صفر.", amountError("0"));
        assertEquals("المبلغ لا يمكن أن يكون سالبًا.", amountError("-1"));
        assertTrue(amountError("1.0001").contains("3 منازل"));
        assertEquals(null, amountError("0.001"), "one fils is fine");
        assertEquals(null, amountError("12.500"));
    }

    @Test
    void onlyMethodsThatMoveMoneyAreAccepted() {
        for (PaymentMethod m : PaymentMethod.values()) {
            PartyPayment p = payment("1");
            p.setPaymentMethod(m);
            boolean ok = !PaymentServiceImpl.validateFields(p).has(PaymentService.METHOD);
            assertEquals(PaymentService.METHODS.contains(m), ok, m.name());
        }
        assertFalse(PaymentService.METHODS.contains(PaymentMethod.CREDIT), "credit is not a payment");
    }

    @Test
    void partyAndFutureDayAreChecked() {
        PartyPayment p = payment("1");
        p.setPartyId(null);
        p.setPaymentDay(LocalDate.now().plusDays(1));
        ValidationException e = assertThrows(ValidationException.class, () -> PaymentServiceImpl.validateFields(p).throwIfAny());
        assertEquals("اختر العميل.", e.errorFor(PaymentService.PARTY));
        assertTrue(e.errorFor(PaymentService.DATE).contains("مستقبلي"));
        p.setPartyId(1);
        p.setPaymentDay(LocalDate.now().minusDays(10));
        PaymentServiceImpl.validateFields(p).throwIfAny();   // an earlier day is fine
    }

    @Test
    void expenseNeedsCategoryDescriptionAndAmount() {
        Expense e = new Expense();
        e.setCategory(null);
        e.setPaymentMethod(PaymentMethod.KNET);
        ValidationException ex = assertThrows(ValidationException.class, () -> ExpenseServiceImpl.validateFields(e).throwIfAny());
        assertTrue(ex.getErrors().keySet().containsAll(java.util.List.of(ExpenseService.CATEGORY,
                ExpenseService.DESCRIPTION, ExpenseService.AMOUNT)), ex.getErrors().toString());
        e.setCategory(ExpenseCategory.RENT);
        e.setDescription("إيجار أكتوبر");
        e.setAmount(new BigDecimal("450"));
        ExpenseServiceImpl.validateFields(e).throwIfAny();
        assertEquals("إيجار", ExpenseCategory.RENT.getLabelAr());
        assertEquals(9, ExpenseCategory.values().length);
    }

    @Test
    void manualCashNeedsAReason() {
        CashMovement m = new CashMovement();
        m.setAmount(new BigDecimal("5"));
        m.setPaymentMethod(PaymentMethod.CASH);
        assertTrue(CashboxServiceImpl.validateFields(m).has(CashboxService.REASON));
        m.setDescription("فكة للخزنة");
        CashboxServiceImpl.validateFields(m).throwIfAny();
    }

    @Test
    void summaryNetAndSignedAmounts() {
        CashSummary s = new CashSummary(LocalDate.now(), new BigDecimal("100.000"), new BigDecimal("30.500"),
                new BigDecimal("12.250"));
        assertEquals(new BigDecimal("18.250"), s.todayNet());
        CashMovement out = new CashMovement();
        out.setDirection(CashMovement.Direction.OUT);
        out.setAmount(new BigDecimal("4.000"));
        assertEquals(new BigDecimal("-4.000"), out.getSignedAmount());
        out.setTransactionId(42L);
        assertEquals("TRX-000042", out.getTransactionNo());
    }
}
