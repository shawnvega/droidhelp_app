package com.bighelp.droidhelp.platform

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Handles the client-side pieces of the OAuth flow:
 *
 *  - PKCE ([code_verifier] / [code_challenge]) per RFC 7636.
 *  - CSRF [state] generation and validation.
 *  - Access/refresh token persistence in [SecureStorage].
 *  - Refresh-token rotation with reuse detection.
 *
 * Tokens are only ever written through [SecureStorage], which is backed by the
 * Android Keystore in production, so the newly issued refresh token is stored
 * securely rather than in plain preferences.
 */
class Auth(private val secureStorage: SecureStorage) {
    companion object {
        private const val VERIFIER_KEY = "pkce_code_verifier"
        private const val STATE_KEY = "oauth_state"
        private const val ACCESS_TOKEN_KEY = "access_token"
        private const val REFRESH_TOKEN_KEY = "refresh_token"

        /**
         * Registry of refresh tokens that have already been exchanged for a new
         * pair. Only SHA-256 digests are kept, never the raw token, so the record
         * cannot be replayed even if the backing store leaks.
         */
        private const val CONSUMED_REFRESH_TOKENS_KEY = "consumed_refresh_tokens"

        /** Upper bound on retained digests so the registry cannot grow forever. */
        private const val MAX_CONSUMED_TOKENS = 64

        private const val HEX_CHARS = "0123456789abcdef"
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
     *
     * The consumed-refresh-token registry is intentionally left in place: it holds
     * only digests and keeping it means a token that was already rotated away is
     * still recognised as reused after a sign-out/sign-in cycle.
     */
    suspend fun clearTokens() {
        secureStorage.delete(ACCESS_TOKEN_KEY)
        secureStorage.delete(REFRESH_TOKEN_KEY)
    }

    /**
     * Rotates the refresh token after it has been exchanged for a new token pair.
     *
     * Rotation is a single-use operation: once [oldRefreshToken] has been used to
     * obtain [newAccessToken]/[newRefreshToken] it must never be accepted again.
     * This method therefore:
     *
     *  1. Rejects [oldRefreshToken] outright if it was already consumed (explicit
     *     reuse detection).
     *  2. Performs a compare-and-swap against the currently stored refresh token.
     *     If a different token is already stored, the presented one has been
     *     rotated away by another caller and is treated as reuse.
     *  3. Persists the new pair through [SecureStorage] and records the old token
     *     as consumed.
     *
     * @param oldRefreshToken The refresh token that was used to obtain new tokens.
     * @param newAccessToken The new access token received.
     * @param newRefreshToken The new refresh token received.
     * @throws TokenReuseException if a reuse attempt is detected.
     */
    suspend fun rotateTokens(oldRefreshToken: String, newAccessToken: String, newRefreshToken: String) {
        val oldDigest = digest(oldRefreshToken)

        // 1. Explicit reuse detection: this exact token was already exchanged.
        if (isConsumed(oldDigest)) {
            throw TokenReuseException("Refresh token reuse detected!")
        }

        // 2. Compare-and-swap: the presented token must be the one we currently hold.
        //    A different stored token means ours was already rotated away elsewhere.
        val current = secureStorage.getString(REFRESH_TOKEN_KEY)
        if (current != null && current != oldRefreshToken) {
            throw TokenReuseException("Refresh token reuse detected!")
        }

        // 3. Persist the new pair, then retire the old token.
        secureStorage.saveString(ACCESS_TOKEN_KEY, newAccessToken)
        secureStorage.saveString(REFRESH_TOKEN_KEY, newRefreshToken)

        // A non-rotating server may hand back the same token; only retire it when
        // the value actually changed, otherwise we would lock ourselves out.
        if (newRefreshToken != oldRefreshToken) {
            markConsumed(oldDigest)
        }
    }

    /**
     * Returns true if [refreshToken] has already been exchanged via [rotateTokens]
     * and must therefore be rejected as reused.
     */
    suspend fun isRefreshTokenConsumed(refreshToken: String): Boolean =
        isConsumed(digest(refreshToken))

    private suspend fun isConsumed(digest: String): Boolean =
        loadConsumedDigests().contains(digest)

    private suspend fun markConsumed(digest: String) {
        val digests = loadConsumedDigests().toMutableList()
        if (!digests.contains(digest)) {
            digests.add(digest)
        }
        // Keep only the most recent entries so the registry stays bounded.
        val trimmed = if (digests.size > MAX_CONSUMED_TOKENS) {
            digests.subList(digests.size - MAX_CONSUMED_TOKENS, digests.size)
        } else {
            digests
        }
        secureStorage.saveString(CONSUMED_REFRESH_TOKENS_KEY, trimmed.joinToString("\n"))
    }

    private suspend fun loadConsumedDigests(): List<String> {
        val raw = secureStorage.getString(CONSUMED_REFRESH_TOKENS_KEY) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun digest(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(hash.size * 2)
        for (byte in hash) {
            builder.append(HEX_CHARS[(byte.toInt() shr 4) and 0x0F])
            builder.append(HEX_CHARS[byte.toInt() and 0x0F])
        }
        return builder.toString()
    }

    class TokenReuseException(message: String) : Exception(message)
}
