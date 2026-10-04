package com.almahwar.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyUtilTest {

    @Test
    void normalizesToThreeDecimals() {
        assertEquals(new BigDecimal("12.500"), MoneyUtil.of("12.5"));
        assertEquals(new BigDecimal("0.125"), MoneyUtil.of("0.1245"));
        assertEquals(MoneyUtil.ZERO, MoneyUtil.of((BigDecimal) null));
        assertEquals(MoneyUtil.ZERO, MoneyUtil.of(""));
    }

    @Test
    void arithmeticKeepsScale() {
        assertEquals(new BigDecimal("0.300"), MoneyUtil.add(new BigDecimal("0.1"), new BigDecimal("0.2")));
        assertEquals(new BigDecimal("7.875"), MoneyUtil.multiply(new BigDecimal("2.625"), new BigDecimal("3")));
        assertEquals(new BigDecimal("-1.250"), MoneyUtil.subtract(new BigDecimal("1"), new BigDecimal("2.25")));
    }

    @Test
    void formatsAndParses() throws ParseException {
        assertEquals("1,250.750", MoneyUtil.format(new BigDecimal("1250.75")));
        assertEquals(new BigDecimal("1250.750"), MoneyUtil.parse("1,250.750"));
    }
}
