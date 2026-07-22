package com.jointheparty.app.spotify.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH-02 acceptance: [generateCodeVerifier] stays inside the RFC 7636 §4.1
 * length/charset window, and [codeChallengeS256] matches the RFC 7636
 * Appendix B known-answer test vector.
 */
class PkceTest {

    private val unreservedChars =
        ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')

    @Test
    fun verifierLengthIsWithinRfc7636Window() {
        val verifier = generateCodeVerifier()
        assertTrue(
            "verifier length ${verifier.length} must be in [43, 128]",
            verifier.length in 43..128,
        )
    }

    @Test
    fun verifierUsesOnlyRfc7636UnreservedCharacters() {
        val verifier = generateCodeVerifier()
        verifier.forEach { c ->
            assertTrue("unexpected character '$c' in verifier", c in unreservedChars)
        }
    }

    @Test
    fun differentVerifiersProduceDifferentChallenges() {
        val verifierA = generateCodeVerifier()
        val verifierB = generateCodeVerifier()

        assertNotEquals(verifierA, verifierB)
        assertNotEquals(codeChallengeS256(verifierA), codeChallengeS256(verifierB))
    }

    @Test
    fun challengeMatchesRfc7636AppendixBKnownAnswer() {
        // RFC 7636 Appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expectedChallenge, codeChallengeS256(verifier))
    }
}
