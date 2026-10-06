package com.almahwar.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhoneNumbersTest {

    @Test
    void kuwaitiNumbersAreStoredAsEightDigits() {
        assertEquals("99887766", PhoneNumbers.normalize("9988 7766"));
        assertEquals("99887766", PhoneNumbers.normalize("9988-7766"));
        assertEquals("99887766", PhoneNumbers.normalize("+965 9988 7766"));
        assertEquals("99887766", PhoneNumbers.normalize("00965-99887766"));
        assertEquals("22445566", PhoneNumbers.normalize("٢٢٤٤٥٥٦٦"));   // Arabic-Indic digits
        assertNull(PhoneNumbers.validate("99887766"));
    }

    @Test
    void internationalNumbersKeepTheirCountryCode() {
        assertEquals("+971501234567", PhoneNumbers.normalize("00971 50 123 4567"));
        assertEquals("+201001234567", PhoneNumbers.normalize("+20 (100) 123-4567"));
        assertNull(PhoneNumbers.validate("+971501234567"));
    }

    @Test
    void invalidNumbersAreExplainedInArabic() {
        assertNotNull(PhoneNumbers.validate(PhoneNumbers.normalize("12345")));        // too short for Kuwait
        assertNotNull(PhoneNumbers.validate(PhoneNumbers.normalize("998877661")));    // 9 local digits
        assertNotNull(PhoneNumbers.validate(PhoneNumbers.normalize("9988abc6")));
        assertNotNull(PhoneNumbers.validate(PhoneNumbers.normalize("+12")));
        assertNull(PhoneNumbers.normalize("   "));
        assertNull(PhoneNumbers.validate(null));
    }

    @Test
    void displayAndSearchForms() {
        assertEquals("9988 7766", PhoneNumbers.format("99887766"));
        assertEquals("+971501234567", PhoneNumbers.format("+971501234567"));
        assertEquals("99887766", PhoneNumbers.digits("9988 7766"));
    }
}
