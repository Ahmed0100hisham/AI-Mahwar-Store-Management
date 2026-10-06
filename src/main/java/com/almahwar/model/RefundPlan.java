package com.almahwar.model;

import java.math.BigDecimal;

/**
 * How the value of a return is settled.
 *
 * @param value         value of the returned goods
 * @param accountCredit part that only reduces the party's balance (value − refund)
 * @param refund        money that actually moves (paid back to the customer / received from the supplier)
 * @param balanceBefore the party's balance before the return ({@code null} for the walk-in customer)
 */
public record RefundPlan(BigDecimal value, BigDecimal accountCredit, BigDecimal refund, BigDecimal balanceBefore) {

    public boolean movesMoney() {
        return refund.signum() > 0;
    }
}
