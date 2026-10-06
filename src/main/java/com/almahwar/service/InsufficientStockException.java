package com.almahwar.service;

import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.util.List;

/**
 * A sale asks for more than is in stock. Thrown inside the posting transaction after the products were
 * locked, so the figures are the real stock at that moment (another cashier may just have sold it).
 */
public class InsufficientStockException extends ValidationException {

    public static final String MESSAGE = "الكمية المطلوبة غير متوفرة بالمخزون";

    /** One product that is short. */
    public record Shortage(int productId, String productName, BigDecimal available, BigDecimal requested) {

        public String describe() {
            return "\"" + productName + "\": المتوفر " + QuantityUtil.format(available)
                    + "، المطلوب " + QuantityUtil.format(requested);
        }
    }

    private final List<Shortage> shortages;

    public InsufficientStockException(List<Shortage> shortages) {
        super(SaleService.ITEMS, message(shortages));
        this.shortages = List.copyOf(shortages);
    }

    public List<Shortage> getShortages() {
        return shortages;
    }

    private static String message(List<Shortage> shortages) {
        StringBuilder b = new StringBuilder(MESSAGE).append(":");
        for (Shortage s : shortages) {
            b.append("\n• ").append(s.describe());
        }
        return b.toString();
    }
}
