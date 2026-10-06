package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cashbox figures, all computed from {@code Cash_Transactions} (Σ IN − Σ OUT). "Today" is the database server's date.
 */
public record CashSummary(LocalDate today, BigDecimal balance, BigDecimal todayIn, BigDecimal todayOut) {

    public BigDecimal todayNet() {
        return todayIn.subtract(todayOut);
    }
}
