package com.bighelp.droidhelp.platform

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Provides PKCE (Proof Key for Code Exchange) functionality.
 * Follows RFC 7636.
 */
class Auth(private val secureStorage: SecureStorage) {
    companion object {
        private const val VERIFIER_KEY = "pkce_code_verifier"
    }

    /**
     * Retrieves the existing [code_verifier] from [secureStorage], or generates and stores a new one.
     * 
     * @return The [code_verifier] string.
     */
    suspend fun getOrCreateVerifier(): String {
        val existing = secureStorage.getString(VERIFIER_KEY)
        if (existing != null) return existing

        val newVerifier = generateCodeVerifier()
        secureStorage.saveString(VERIFIER_KEY, newVerifier)
        return newVerifier
    }

    /**
     * Generates a cryptographically random [code_verifier].
     * Length is between 43 and 128 characters, base64url encoded.
     * 
     * @return A high-entropy [code_verifier] string.
     */
    fun generateCodeVerifier(): String {
        // Target ~32 bytes to get ~43 chars in base64url
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Derives a [code_challenge] from the provided [verifier] using SHA-256.
     * 
     * @param verifier The [code_verifier] string.
     * @return The derived [code_challenge] string.
     */
    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    /**
     * Removes the stored [code_verifier] from [secureStorage].
     */
    suspend fun clearVerifier() {
        secureStorage.delete(VERIFIER_KEY)
    }
}
