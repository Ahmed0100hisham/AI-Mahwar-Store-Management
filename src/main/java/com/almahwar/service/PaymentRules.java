package com.almahwar.service;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentType;
import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * How a document's total is settled; shared by purchases now and sales / POS later.
 * <ul>
 *   <li>CASH, KNET, BANK_TRANSFER, CHEQUE → paid = total (paid in full now);</li>
 *   <li>CREDIT → paid = 0;</li>
 *   <li>PARTIAL → 0 &lt; paid &lt; total, paid with an immediate method chosen by the user.</li>
 * </ul>
 */
public final class PaymentRules {

    public static final String TYPE = "paymentType";
    public static final String PAID = "paidAmount";
    public static final String METHOD = "paymentMethod";

    /** The resolved settlement: what is paid now, and with which method ({@code CREDIT} when nothing is). */
    public record PaymentPlan(PaymentType type, PaymentMethod method, BigDecimal paid) {

        public BigDecimal remaining(BigDecimal total) {
            return MoneyUtil.of(total.subtract(paid));
        }

        /** Money actually changes hands now. */
        public boolean movesMoney() {
            return paid.signum() > 0;
        }
    }

    private PaymentRules() {
    }

    /**
     * @param partialMethod method of the paid part, used only for PARTIAL
     * @param partialPaid   amount paid now, used only for PARTIAL
     * @throws ValidationException with Arabic messages
     */
    public static PaymentPlan resolve(PaymentType type, PaymentMethod partialMethod, BigDecimal partialPaid,
                                      BigDecimal total) {
        Validation v = new Validation();
        check(v, type, partialMethod, partialPaid, total);
        v.throwIfAny();
        return plan(type, partialMethod, partialPaid, total);
    }

    /** Adds payment errors to {@code v} (so they are reported together with the document's other errors). */
    static void check(Validation v, PaymentType type, PaymentMethod partialMethod, BigDecimal partialPaid,
                      BigDecimal total) {
        if (type == null) {
            v.error(TYPE, "اختر طريقة الدفع.");
            return;
        }
        if (type != PaymentType.PARTIAL) {
            return;
        }
        if (partialMethod == null || !PaymentType.isImmediateMethod(partialMethod)) {
            v.error(METHOD, "اختر طريقة دفع المبلغ المدفوع (نقدًا، كي نت، تحويل بنكي أو شيك).");
        }
        if (partialPaid == null) {
            v.error(PAID, "أدخل المبلغ المدفوع.");
        } else if (partialPaid.signum() < 0) {
            v.error(PAID, "المبلغ المدفوع لا يمكن أن يكون سالبًا.");
        } else if (partialPaid.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            v.error(PAID, "المبلغ المدفوع: الحد الأقصى 3 منازل عشرية.");
        } else if (partialPaid.signum() == 0) {
            v.error(PAID, "في الدفعة الجزئية يجب أن يكون المدفوع أكبر من صفر؛ اختر \"آجل\" إذا لم يُدفع شيء.");
        } else if (total != null && partialPaid.compareTo(total) > 0) {
            v.error(PAID, "المبلغ المدفوع لا يمكن أن يتجاوز إجمالي الفاتورة.");
        } else if (total != null && partialPaid.compareTo(total) == 0) {
            v.error(PAID, "المبلغ المدفوع يساوي الإجمالي؛ اختر طريقة دفع كاملة بدل الدفعة الجزئية.");
        }
    }

    static PaymentPlan plan(PaymentType type, PaymentMethod partialMethod, BigDecimal partialPaid, BigDecimal total) {
        return switch (type) {
            case CREDIT -> new PaymentPlan(type, PaymentMethod.CREDIT, MoneyUtil.ZERO);
            case PARTIAL -> new PaymentPlan(type, partialMethod, MoneyUtil.of(partialPaid));
            default -> new PaymentPlan(type, type.getMethod(), MoneyUtil.of(total));
        };
    }
}
