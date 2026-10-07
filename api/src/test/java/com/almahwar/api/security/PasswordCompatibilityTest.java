package com.almahwar.api.security;

import com.almahwar.util.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared implementation checked against independent fixed vectors of the released Desktop/Phase 1 format. */
class PasswordCompatibilityTest {
    private static final String PASSWORD = "Kuwait#Store2026";

    private String frozenHash(String iterations) throws Exception {
        Properties vectors = new Properties();
        try (var in = getClass().getResourceAsStream("/phase1-passwords.properties")) { vectors.load(in); }
        return vectors.getProperty(iterations);
    }

    @Test
    void releasedFormatHashVerifiesWithTheSharedImplementation() throws Exception {
        String stored = frozenHash("600000");
        assertThat(PasswordHasher.verify(PASSWORD.toCharArray(), stored)).isTrue();
        assertThat(PasswordHasher.verify("kuwait#Store2026".toCharArray(), stored)).isFalse();
        assertThat(PasswordHasher.needsRehash(stored)).isFalse();
    }

    @Test
    void hashesRoundTrip() {
        String hash = PasswordHasher.hash(PASSWORD.toCharArray());
        assertThat(PasswordHasher.verify(PASSWORD.toCharArray(), hash)).isTrue();
        assertThat(PasswordHasher.verify("other".toCharArray(), hash)).isFalse();
    }

    @Test
    void sameStoredFormatAndParameters() {
        String hash = PasswordHasher.hash(PASSWORD.toCharArray());
        String[] parts = hash.split("\\$");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("pbkdf2_sha256");
        assertThat(parts[1]).isEqualTo("600000");
        assertThat(Base64.getDecoder().decode(parts[2])).hasSize(16);
        assertThat(Base64.getDecoder().decode(parts[3])).hasSize(32);
        assertThat(PasswordHasher.ITERATIONS).isEqualTo(600_000);
        assertThat(hash).isNotEqualTo(PasswordHasher.hash(PASSWORD.toCharArray()));
    }

    @Test
    void olderIterationCountStillVerifiesAndNeedsUpgrade() throws Exception {
        String old = frozenHash("1000");
        assertThat(PasswordHasher.verify(PASSWORD.toCharArray(), old)).isTrue();
        assertThat(PasswordHasher.needsRehash(old)).isTrue();
    }

    @Test
    void malformedOrForeignHashesNeverVerify() {
        for (String bad : new String[] {null, "", "plain-text", "pbkdf2_sha256$600000$!!$!!",
                "bcrypt$600000$AAAA$AAAA", "pbkdf2_sha256$abc$AAAA$AAAA", "pbkdf2_sha256$600000$AAAA"}) {
            assertThat(PasswordHasher.verify(PASSWORD.toCharArray(), bad)).as(String.valueOf(bad)).isFalse();
            assertThat(PasswordHasher.needsRehash(bad)).isTrue();
        }
    }

    @Test
    void arabicAndSymbolPasswordsRoundTrip() {
        char[] password = "كلمة_سر#2026".toCharArray();
        assertThat(PasswordHasher.verify(password, PasswordHasher.hash(password))).isTrue();
    }

    @Test
    void wipeClearsThePassword() {
        char[] password = PASSWORD.toCharArray();
        PasswordHasher.wipe(password);
        assertThat(password).containsOnly('\0');
        PasswordHasher.wipe(null);
    }
}
