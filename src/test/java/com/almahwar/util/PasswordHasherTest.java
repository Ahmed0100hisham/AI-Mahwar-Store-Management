package com.almahwar.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void verifiesCorrectPasswordOnly() {
        String hash = PasswordHasher.hash("Mahwar2026".toCharArray());
        assertTrue(PasswordHasher.verify("Mahwar2026".toCharArray(), hash));
        assertFalse(PasswordHasher.verify("mahwar2026".toCharArray(), hash));
        assertFalse(PasswordHasher.verify("".toCharArray(), hash));
    }

    @Test
    void hashIsSaltedAndNeverContainsThePassword() {
        String a = PasswordHasher.hash("Secret123".toCharArray(), 1_000);
        String b = PasswordHasher.hash("Secret123".toCharArray(), 1_000);
        assertNotEquals(a, b);
        assertFalse(a.contains("Secret123"));
        assertTrue(a.startsWith("pbkdf2_sha256$1000$"));
        assertTrue(a.length() <= 255, "must fit Users.password_hash");
    }

    @Test
    void supportsArabicPasswords() {
        String hash = PasswordHasher.hash("كلمةسر2026".toCharArray(), 1_000);
        assertTrue(PasswordHasher.verify("كلمةسر2026".toCharArray(), hash));
    }

    @Test
    void detectsWeakOrMalformedHashes() {
        assertTrue(PasswordHasher.needsRehash(PasswordHasher.hash("x1".toCharArray(), 1_000)));
        assertFalse(PasswordHasher.needsRehash(PasswordHasher.hash("x1".toCharArray())));

        for (String bad : new String[]{null, "", "plain-text", "md5$1$a$b", "pbkdf2_sha256$x$y$z", "pbkdf2_sha256$1000$!!$!!"}) {
            assertFalse(PasswordHasher.verify("x1".toCharArray(), bad), String.valueOf(bad));
            assertTrue(PasswordHasher.needsRehash(bad));
        }
    }

    @Test
    void wipeClearsArray() {
        char[] pw = "abc".toCharArray();
        PasswordHasher.wipe(pw);
        assertArrayEquals(new char[3], pw);
    }
}
