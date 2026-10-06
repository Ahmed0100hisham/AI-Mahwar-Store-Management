package com.almahwar.service;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Field rules shared by customer / supplier payments, expenses and manual cash operations. */
final class FinanceRules {

    /** Methods that move money now (the same set PaymentRules accepts for a paid amount). */
    static final List<PaymentMethod> METHODS = List.of(PaymentMethod.CASH, PaymentMethod.KNET,
            PaymentMethod.BANK_TRANSFER, PaymentMethod.CHEQUE);

    /** DECIMAL(18,3) holds at most 15 digits before the decimal point. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000");

    private FinanceRules() {
    }

    /** Required, &gt; 0, at most 3 decimals (KWD fils). */
    static void amount(Validation v, String field, BigDecimal amount) {
        if (amount == null) {
            v.error(field, "أدخل المبلغ.");
        } else if (amount.signum() == 0) {
            v.error(field, "المبلغ يجب أن يكون أكبر من صفر.");
        } else if (amount.signum() < 0) {
            v.error(field, "المبلغ لا يمكن أن يكون سالبًا.");
        } else if (amount.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            v.error(field, "المبلغ: الحد الأقصى 3 منازل عشرية (فلس).");
        } else if (amount.compareTo(MAX_AMOUNT) >= 0) {
            v.error(field, "المبلغ كبير جدًا.");
        }
    }

    static void method(Validation v, String field, PaymentMethod method) {
        if (method == null || !PaymentType.isImmediateMethod(method)) {
            v.error(field, "اختر طريقة الدفع (نقدًا، كي نت، تحويل بنكي أو شيك).");
        }
    }

    /** {@code null} = today; an earlier day is allowed, a future day is not. */
    static void day(Validation v, String field, LocalDate day) {
        if (day != null && day.isAfter(LocalDate.now())) {
            v.error(field, "لا يمكن تسجيل عملية بتاريخ مستقبلي.");
        }
    }
}
