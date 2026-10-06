package com.almahwar.model;

/**
 * How a document (purchase now, sales later) is settled, as chosen on screen.
 * <ul>
 *   <li>{@link #CASH}, {@link #KNET}, {@link #BANK_TRANSFER}, {@link #CHEQUE}: paid in full now;</li>
 *   <li>{@link #CREDIT}: nothing paid now (آجل);</li>
 *   <li>{@link #PARTIAL}: part paid now with an immediate method, the rest on account.</li>
 * </ul>
 * The stored {@code payment_method} is the method of the money actually paid (or CREDIT when nothing was).
 */
public enum PaymentType {

    CASH("نقدًا", PaymentMethod.CASH),
    KNET("كي نت", PaymentMethod.KNET),
    BANK_TRANSFER("تحويل بنكي", PaymentMethod.BANK_TRANSFER),
    CHEQUE("شيك", PaymentMethod.CHEQUE),
    CREDIT("آجل", PaymentMethod.CREDIT),
    PARTIAL("دفعة جزئية", null);

    private final String labelAr;
    private final PaymentMethod method;

    PaymentType(String labelAr, PaymentMethod method) {
        this.labelAr = labelAr;
        this.method = method;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** The fixed method of a full payment / credit; {@code null} for PARTIAL (the user picks it). */
    public PaymentMethod getMethod() {
        return method;
    }

    /** Paid in full at once. */
    public boolean isImmediate() {
        return this != CREDIT && this != PARTIAL;
    }

    /** Methods that can pay (part of) a document now and therefore move money. */
    public static boolean isImmediateMethod(PaymentMethod m) {
        return m == PaymentMethod.CASH || m == PaymentMethod.KNET || m == PaymentMethod.BANK_TRANSFER
                || m == PaymentMethod.CHEQUE;
    }

    /** The type a stored document corresponds to. */
    public static PaymentType of(PaymentMethod storedMethod, java.math.BigDecimal total, java.math.BigDecimal paid) {
        if (paid.signum() == 0 && total.signum() > 0) {
            return CREDIT;
        }
        if (paid.compareTo(total) < 0) {
            return PARTIAL;
        }
        for (PaymentType t : values()) {
            if (t.method == storedMethod && t.isImmediate()) {
                return t;
            }
        }
        return CASH;
    }
}
