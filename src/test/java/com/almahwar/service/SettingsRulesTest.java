package com.almahwar.service;

import com.almahwar.model.CompanySettings;
import com.almahwar.model.SystemSettings;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings rules that need no database: validation, logo checks, defaults, version comparison. */
class SettingsRulesTest {

    private static CompanySettings company(String nameAr, String phone, String email) {
        return SettingsServiceImpl.normalise(new CompanySettings(nameAr, "Al Mahwar", phone, null, email, "الكويت",
                "الكويت", "KWD", null, null));
    }

    private static Map<String, String> errors(Validation v) {
        try {
            v.throwIfAny();
            return Map.of();
        } catch (ValidationException e) {
            return e.getErrors();
        }
    }

    static byte[] image(String format, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    // ---------- company profile ----------

    @Test
    void companyValidation() {
        assertTrue(errors(SettingsServiceImpl.validate(company("شركة المحور", "22223333", "info@almahwar.com"))).isEmpty());
        assertTrue(errors(SettingsServiceImpl.validate(company("  ", null, null))).containsKey(SettingsService.NAME_AR),
                "Arabic name required");
        assertTrue(errors(SettingsServiceImpl.validate(company("شركة", null, "not-an-email"))).containsKey(SettingsService.EMAIL));
        assertTrue(errors(SettingsServiceImpl.validate(company("شركة", "12", null))).containsKey(SettingsService.PHONE));
        assertTrue(errors(SettingsServiceImpl.validate(company("x".repeat(151), null, null))).containsKey(SettingsService.NAME_AR));
        CompanySettings usd = company("شركة", null, null).withCurrency("USD");
        assertTrue(errors(SettingsServiceImpl.validate(usd)).containsKey(SettingsService.CURRENCY), "the currency is fixed");
    }

    @Test
    void normaliseTrimsAndUsesThePhoneRules() {
        CompanySettings c = SettingsServiceImpl.normalise(new CompanySettings("  شركة المحور  ", " ", "٢٢٢٢ ٣٣٣٣", "", " a@b.co ",
                null, "الكويت", null, "  ", null));
        assertEquals("شركة المحور", c.nameAr());
        assertNull(c.nameEn(), "blank → null");
        assertEquals("a@b.co", c.email());
        assertNull(c.phone2());
        assertNull(c.taxNumber());
        assertEquals("KWD", c.currencyCode());
        assertTrue(c.phone().endsWith("22223333"), c.phone());
    }

    // ---------- system settings ----------

    @Test
    void systemValidation() {
        for (int ok : new int[]{1, 14, 365}) {
            assertTrue(errors(SettingsServiceImpl.validate(new SystemSettings(ok, null, null, null))).isEmpty(), "" + ok);
        }
        for (int bad : new int[]{0, -3, 366, 10_000}) {
            assertTrue(errors(SettingsServiceImpl.validate(new SystemSettings(bad, null, null, null)))
                    .containsKey(SettingsService.VALIDITY_DAYS), "" + bad);
        }
        assertTrue(errors(SettingsServiceImpl.validate(new SystemSettings(14, "x".repeat(1001), null, null)))
                .containsKey(SettingsService.QUOTATION_TERMS), "terms fit the quotation column");
        assertTrue(errors(SettingsServiceImpl.validate(new SystemSettings(14, null, "x".repeat(301), "x".repeat(301))))
                .keySet().containsAll(java.util.List.of(SettingsService.INVOICE_FOOTER, SettingsService.REPORT_FOOTER)));
    }

    @Test
    void missingKeysFallBackToTheDefaults() {
        assertEquals(14, SettingsServiceImpl.system(Map.of()).quotationValidityDays());
        assertEquals("شركة المحور للأدوات الصحية", SettingsServiceImpl.company(Map.of()).nameAr());
        assertEquals("KWD", SettingsServiceImpl.company(Map.of()).currencyCode());
        assertEquals(14, SettingsServiceImpl.system(Map.of("quotation.validity_days", "abc")).quotationValidityDays(),
                "an unreadable stored value does not break printing");
        java.util.HashMap<String, String> empty = new java.util.HashMap<>();
        empty.put("quotation.terms", null);
        assertNull(SettingsServiceImpl.system(empty).quotationTerms(), "a stored empty value stays empty");
    }

    // ---------- logo ----------

    @Test
    void logoChecks() {
        SettingsServiceImpl.ImageCheck png = SettingsServiceImpl.checkImage(image("png", 200, 80));
        assertEquals("image/png", png.contentType());
        assertEquals(200, png.width());
        assertEquals(80, png.height());
        assertEquals("image/jpeg", SettingsServiceImpl.checkImage(image("jpg", 64, 64)).contentType());

        assertThrows(ValidationException.class, () -> SettingsServiceImpl.checkImage(new byte[0]), "empty");
        assertThrows(ValidationException.class, () -> SettingsServiceImpl.checkImage(image("png", 8, 8)), "too small");
        assertThrows(ValidationException.class, () -> SettingsServiceImpl.checkImage(image("png", 4100, 20)), "too wide");
        assertThrows(ValidationException.class, () -> SettingsServiceImpl.checkImage(image("gif", 50, 50)), "not PNG / JPG");
        assertThrows(ValidationException.class, () -> SettingsServiceImpl.checkImage(
                "<svg>not an image</svg>".getBytes(StandardCharsets.UTF_8)), "text");
        byte[] good = image("png", 300, 300);
        assertThrows(ValidationException.class, () -> SettingsServiceImpl.checkImage(Arrays.copyOf(good, good.length / 2)),
                "truncated / corrupted");
        byte[] big = new byte[SettingsService.MAX_LOGO_BYTES + 1];
        System.arraycopy(good, 0, big, 0, good.length);
        assertThrows(ValidationException.class, () -> SettingsServiceImpl.checkImage(big), "over 1 MB");
    }

    // ---------- version ----------

    @Test
    void versionComparison() {
        assertEquals(0, SettingsServiceImpl.compareVersions("1.8.0", "1.8.0"));
        assertTrue(SettingsServiceImpl.compareVersions("1.10.0", "1.9.0") > 0, "numeric, not text");
        assertTrue(SettingsServiceImpl.compareVersions("1.7.0", "1.8.0") < 0);
        assertTrue(SettingsServiceImpl.compareVersions("2.0", "1.8.0") > 0);
        assertTrue(SettingsServiceImpl.compareVersions("1.8.0-SNAPSHOT", "1.8.0") == 0);
    }
}
