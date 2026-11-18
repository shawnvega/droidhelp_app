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
        private const val STATE_KEY = "oauth_state"
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

    /**
     * Generates a cryptographically random [state] token and stores it in [secureStorage].
     * The token has at least 128 bits of entropy and is URL-safe encoded.
     * This is used for CSRF protection during OAuth flows.
     *
     * @return The generated [state] string.
     */
    suspend fun generateAndSaveState(): String {
        val bytes = ByteArray(16) // 128 bits
        SecureRandom().nextBytes(bytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        secureStorage.saveString(STATE_KEY, state)
        return state
    }

    /**
     * Validates the provided [receivedState] against the stored state.
     * If they match, the stored state is cleared and true is returned.
     * This prevents CSRF attacks by ensuring the response matches the request.
     *
     * @param receivedState The state token received from the OAuth callback.
     * @return True if the state matches and was successfully validated, false otherwise.
     */
    suspend fun validateAndClearState(receivedState: String): Boolean {
        val storedState = secureStorage.getString(STATE_KEY)
        if (storedState != null && storedState == receivedState) {
            secureStorage.delete(STATE_KEY)
            return true
        }
        return false
    }
}
