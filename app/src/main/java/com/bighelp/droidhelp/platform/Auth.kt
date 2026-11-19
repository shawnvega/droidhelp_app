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
        private const val ACCESS_TOKEN_KEY = "access_token"
        private const val REFRESH_TOKEN_KEY = "refresh_token"
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
    /**
     * Stores the access and refresh tokens.
     * 
     * @param accessToken The new access token.
     * @param refreshToken The new refresh token.
     */
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        secureStorage.saveString(ACCESS_TOKEN_KEY, accessToken)
        secureStorage.saveString(REFRESH_TOKEN_KEY, refreshToken)
    }

    /**
     * Retrieves the current access token.
     * 
     * @return The access token, or null if not stored.
     */
    suspend fun getAccessToken(): String? = secureStorage.getString(ACCESS_TOKEN_KEY)

    /**
     * Retrieves the current refresh token.
     * 
     * @return The refresh token, or null if not stored.
     */
    suspend fun getRefreshToken(): String? = secureStorage.getString(REFRESH_TOKEN_KEY)

    /**
     * Clears all stored tokens.
     */
    suspend fun clearTokens() {
        secureStorage.delete(ACCESS_TOKEN_KEY)
        secureStorage.delete(REFRESH_TOKEN_KEY)
    }

    /**
     * Rotates the refresh token.
     * 
     * @param oldRefreshToken The refresh token that was used to obtain new tokens.
     * @param newAccessToken The new access token received.
     * @param newRefreshToken The new refresh token received.
     * @throws TokenReuseException if a reuse attempt is detected.
     */
    suspend fun rotateTokens(oldRefreshToken: String, newAccessToken: String, newRefreshToken: String) {
        val current = secureStorage.getString(REFRESH_TOKEN_KEY)
        
        if (current != null && current != oldRefreshToken) {
            throw TokenReuseException("Refresh token reuse detected!")
        }

        secureStorage.saveString(ACCESS_TOKEN_KEY, newAccessToken)
        secureStorage.saveString(REFRESH_TOKEN_KEY, newRefreshToken)
    }

    class TokenReuseException(message: String) : Exception(message)
}
