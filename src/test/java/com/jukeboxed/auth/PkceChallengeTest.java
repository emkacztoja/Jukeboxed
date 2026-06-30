package com.jukeboxed.auth;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PkceChallengeTest {

    @RepeatedTest(20)
    void verifierIs64CharsBase64UrlAlphabet() {
        String verifier = PkceChallenge.generateCodeVerifier();
        assertEquals(64, verifier.length(), "verifier should be 64 Base64URL chars (encoding 48 bytes)");
        assertTrue(verifier.matches("[A-Za-z0-9_-]+"), "verifier must use only URL-safe Base64 chars");
    }

    @Test
    void deriveCodeChallengeRfc7636KnownVector() {
        // Test vector from RFC 7636 Appendix B.
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expected, PkceChallenge.deriveCodeChallenge(verifier));
    }

    @Test
    void deriveCodeChallengeRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> PkceChallenge.deriveCodeChallenge(null));
    }

    @Test
    void challengeIsUrlSafeBase64() {
        String challenge = PkceChallenge.deriveCodeChallenge(
                PkceChallenge.generateCodeVerifier());
        assertTrue(challenge.matches("[A-Za-z0-9_-]+"));
        // SHA-256 digests 32 bytes → 43 chars of base64url (no padding).
        assertEquals(43, challenge.length());
    }
}
