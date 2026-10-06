package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * A customer's credit position: what they owe, their limit, and how much more they may buy on credit.
 * A credit limit of 0 means "cash only".
 *
 * @param currentDebt     the customer's balance (negative = the customer has credit with us)
 * @param availableCredit {@code max(0, creditLimit - currentDebt)}
 * @param overLimit       the debt is above the limit
 */
public record CreditStatus(BigDecimal creditLimit, BigDecimal currentDebt, BigDecimal availableCredit,
                           boolean overLimit) {

    public static CreditStatus of(BigDecimal creditLimit, BigDecimal balance) {
        BigDecimal limit = MoneyUtil.of(creditLimit);
        BigDecimal debt = MoneyUtil.of(balance);
        BigDecimal available = limit.subtract(debt).max(BigDecimal.ZERO);
        return new CreditStatus(limit, debt, MoneyUtil.of(available), debt.compareTo(limit) > 0);
    }

    /** Whether a new credit amount (e.g. the unpaid part of an invoice) fits within the limit. */
    public boolean allows(BigDecimal amount) {
        BigDecimal a = MoneyUtil.of(amount);
        return a.signum() <= 0 || a.compareTo(availableCredit) <= 0;
    }
}
