package com.almahwar.api.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API must accept every password hash the desktop wrote, and the desktop every hash the API writes. Checked
 * against the real class of the frozen desktop 1.0.0 release ({@code com.almahwar.util.PasswordHasher}), not against a
 * copy — so a change on either side breaks this test.
 */
class PasswordCompatibilityTest {

    private static final String PASSWORD = "Kuwait#Store2026";

    @Test
    void desktopHashesVerifyInTheApi() {
        String desktopHash = com.almahwar.util.PasswordHasher.hash(PASSWORD.toCharArray());
        assertThat(PasswordHasher.verify(PASSWORD.toCharArray(), desktopHash)).isTrue();
        assertThat(PasswordHasher.verify("kuwait#Store2026".toCharArray(), desktopHash)).isFalse();
        assertThat(PasswordHasher.needsRehash(desktopHash)).isFalse();
    }

    @Test
    void apiHashesVerifyOnTheDesktop() {
        String apiHash = PasswordHasher.hash(PASSWORD.toCharArray());
        assertThat(com.almahwar.util.PasswordHasher.verify(PASSWORD.toCharArray(), apiHash)).isTrue();
        assertThat(com.almahwar.util.PasswordHasher.verify("other".toCharArray(), apiHash)).isFalse();
        assertThat(com.almahwar.util.PasswordHasher.needsRehash(apiHash)).isFalse();
    }

    @Test
    void sameStoredFormatAndParameters() {
        String apiHash = PasswordHasher.hash(PASSWORD.toCharArray());
        String desktopHash = com.almahwar.util.PasswordHasher.hash(PASSWORD.toCharArray());
        for (String hash : new String[] {apiHash, desktopHash}) {
            String[] parts = hash.split("\\$");
            assertThat(parts).hasSize(4);
            assertThat(parts[0]).isEqualTo("pbkdf2_sha256");
            assertThat(parts[1]).isEqualTo("600000");
            assertThat(Base64.getDecoder().decode(parts[2])).hasSize(16);   // salt
            assertThat(Base64.getDecoder().decode(parts[3])).hasSize(32);   // 256-bit key
        }
        assertThat(PasswordHasher.ITERATIONS).isEqualTo(com.almahwar.util.PasswordHasher.ITERATIONS);
        assertThat(apiHash).isNotEqualTo(PasswordHasher.hash(PASSWORD.toCharArray()));   // random salt
    }

    @Test
    void olderIterationCountsVerifyAndAreFlaggedForUpgradeOnBothSides() {
        String old = com.almahwar.util.PasswordHasher.hash(PASSWORD.toCharArray(), 1_000);
        assertThat(PasswordHasher.verify(PASSWORD.toCharArray(), old)).isTrue();
        assertThat(PasswordHasher.needsRehash(old)).isTrue();
        assertThat(com.almahwar.util.PasswordHasher.needsRehash(old)).isTrue();
    }

    @Test
    void malformedOrForeignHashesNeverVerify() {
        for (String bad : new String[] {null, "", "plain-text", "pbkdf2_sha256$600000$!!$!!",
                "bcrypt$600000$AAAA$AAAA", "pbkdf2_sha256$abc$AAAA$AAAA", "pbkdf2_sha256$600000$AAAA"}) {
            assertThat(PasswordHasher.verify(PASSWORD.toCharArray(), bad)).as(String.valueOf(bad)).isFalse();
            assertThat(com.almahwar.util.PasswordHasher.verify(PASSWORD.toCharArray(), bad)).isFalse();
            assertThat(PasswordHasher.needsRehash(bad)).isTrue();
        }
    }

    @Test
    void arabicAndSymbolPasswordsAreCompatible() {
        String arabic = "كلمة_سر#2026";
        String desktopHash = com.almahwar.util.PasswordHasher.hash(arabic.toCharArray());
        assertThat(PasswordHasher.verify(arabic.toCharArray(), desktopHash)).isTrue();
    }

    @Test
    void wipeClearsThePassword() {
        char[] password = PASSWORD.toCharArray();
        PasswordHasher.wipe(password);
        assertThat(new String(password)).isEqualTo("\0".repeat(PASSWORD.length()));
    }
}
