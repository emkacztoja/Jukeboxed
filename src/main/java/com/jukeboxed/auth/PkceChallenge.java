package com.jukeboxed.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class PkceChallenge {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private PkceChallenge() {
    }

    public static String generateCodeVerifier() {
        byte[] bytes = new byte[48];
        RNG.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    public static String deriveCodeChallenge(String verifier) {
        if (verifier == null) {
            throw new IllegalArgumentException("verifier must not be null");
        }
        // RFC 7636 §4.2:  challenge = BASE64URL-ENCODE(SHA256(ASCII(verifier)))
        // The verifier is hashed *as a string*, NOT base64-decoded first. Common mistake.
        byte[] ascii = verifier.getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ascii);
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AuthException("SHA-256 unavailable on this JVM", e);
        }
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new AuthException("SHA-256 unavailable on this JVM", e);
        }
    }

    static byte[] randomBytes(int length) {
        byte[] b = new byte[length];
        RNG.nextBytes(b);
        return b;
    }
}