package com.almahwar.api.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 256 random bits, canonical URL-safe encoding; only SHA-256 bytes cross the persistence boundary. */
public final class RefreshTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private RefreshTokens() { }
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "amr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static boolean validFormat(String raw) {
        if (raw == null || !raw.matches("amr_[A-Za-z0-9_-]{43}")) return false;
        byte[] decoded = Base64.getUrlDecoder().decode(raw.substring(4));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(raw.substring(4));
    }
    public static byte[] hash(String raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
