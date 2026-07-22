package com.jointheparty.app.spotify.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * AUTH-02: PKCE verifier/challenge generation (technical-requirements.md
 * §3.1 step 1). Deliberately pure JVM code — no Android imports — so it's
 * directly unit-testable
 * (android/app/src/test/.../spotify/auth/PkceTest.kt) against the RFC 7636
 * Appendix B known-answer vector without Robolectric or instrumentation.
 */

private val secureRandom = SecureRandom()
private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

/**
 * A CSPRNG `code_verifier` per RFC 7636 §4.1: 64 random bytes, base64url
 * (no padding) encoded, yielding 86 characters — comfortably inside the
 * mandated 43–128 char window. Base64url's alphabet ([A-Za-z0-9\-_]) is a
 * subset of RFC 7636's unreserved set, so no further escaping is needed.
 */
fun generateCodeVerifier(): String {
    val bytes = ByteArray(64)
    secureRandom.nextBytes(bytes)
    return urlEncoder.encodeToString(bytes)
}

/**
 * RFC 7636 §4.2: `code_challenge = BASE64URL(SHA256(ASCII(code_verifier)))`.
 */
fun codeChallengeS256(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII))
    return urlEncoder.encodeToString(digest)
}
