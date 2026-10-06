package com.almahwar.service;

/**
 * The unpaid part of a sale would take the customer beyond the credit limit. Checked again inside the
 * posting transaction with the customer row locked.
 * <p>
 * {@link #isOverridable()} tells the screen whether the current user holds
 * {@code CUSTOMER_CREDIT_OVERRIDE}: it may then ask for confirmation and retry with the override.
 */
public class CreditLimitExceededException extends ValidationException {

    private final boolean overridable;

    public CreditLimitExceededException(String message, boolean overridable) {
        super(SaleService.CUSTOMER, message);
        this.overridable = overridable;
    }

    public boolean isOverridable() {
        return overridable;
    }
}
