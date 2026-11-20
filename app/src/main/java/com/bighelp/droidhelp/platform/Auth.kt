package com.bighelp.droidhelp.platform

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()

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
        const val MAX_CONSUMED_TOKENS = 64

        /** PKCE code challenge method per RFC 7636 Section 4.3. */
        const val CODE_CHALLENGE_METHOD_S256 = "S256"

        /** Minimum PKCE code_verifier length per RFC 7636 Section 4.1. */
        const val MIN_VERIFIER_LENGTH = 43

        /** Maximum PKCE code_verifier length per RFC 7636 Section 4.1. */
        const val MAX_VERIFIER_LENGTH = 128

        private val CODE_VERIFIER_REGEX = Regex("^[A-Za-z0-9._~-]{$MIN_VERIFIER_LENGTH,$MAX_VERIFIER_LENGTH}$")

        /** 32 random bytes encode to 43 base64url characters (256 bits of entropy, RFC 7636). */
        private const val VERIFIER_BYTE_LENGTH = 32

        /** 16 random bytes provide 128 bits of entropy for CSRF state tokens. */
        private const val STATE_BYTE_LENGTH = 16

        private const val NIBBLE_SHIFT = 4
        private const val NIBBLE_MASK = 0x0F
        private const val HEX_CHARS = "0123456789abcdef"

        /**
         * Returns true if [verifier] satisfies RFC 7636 Section 4.1
         * (43..128 ASCII unreserved characters: ALPHA / DIGIT / "-" / "." / "_" / "~").
         */
        fun isValidCodeVerifier(verifier: String): Boolean = CODE_VERIFIER_REGEX.matches(verifier)
    }

    /**
     * Retrieves the existing [code_verifier] from [secureStorage], or generates and stores a new one.
     * 
     * @return The [code_verifier] string.
     */
    suspend fun getOrCreateVerifier(): String = mutex.withLock {
        val existing = secureStorage.getString(VERIFIER_KEY)
        if (existing != null && isValidCodeVerifier(existing)) return@withLock existing

        val newVerifier = generateCodeVerifier()
        secureStorage.saveString(VERIFIER_KEY, newVerifier)
        newVerifier
    }

    /**
     * Generates a cryptographically random [code_verifier].
     * Length is between 43 and 128 characters, base64url encoded.
     * 
     * @return A high-entropy [code_verifier] string.
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(VERIFIER_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Derives a [code_challenge] from the provided [verifier] using SHA-256 (S256).
     * 
     * @param verifier The [code_verifier] string.
     * @return The derived [code_challenge] string.
     */
    fun generateCodeChallenge(verifier: String): String {
        require(verifier.isNotBlank()) { "Code verifier must not be blank" }
        require(verifier.length in MIN_VERIFIER_LENGTH..MAX_VERIFIER_LENGTH) {
            "Code verifier length must be between $MIN_VERIFIER_LENGTH and $MAX_VERIFIER_LENGTH characters"
        }
        require(isValidCodeVerifier(verifier)) {
            "Code verifier contains invalid RFC 7636 unreserved characters"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    /**
     * Removes the stored [code_verifier] from [secureStorage].
     */
    suspend fun clearVerifier() = mutex.withLock {
        secureStorage.delete(VERIFIER_KEY)
    }

    /**
     * Generates a cryptographically random [state] token and stores it in [secureStorage].
     * The token has at least 128 bits of entropy and is URL-safe encoded.
     * This is used for CSRF protection during OAuth flows.
     *
     * @return The generated [state] string.
     */
    suspend fun generateAndSaveState(): String = mutex.withLock {
        val bytes = ByteArray(STATE_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        secureStorage.saveString(STATE_KEY, state)
        state
    }

    /**
     * Validates the provided [receivedState] against the stored state using a
     * constant-time comparison. If they match, the stored state is cleared and
     * true is returned. This prevents CSRF attacks and replay by ensuring the
     * response matches the request and can only be validated once.
     *
     * @param receivedState The state token received from the OAuth callback.
     * @return True if the state matches and was successfully validated, false otherwise.
     */
    suspend fun validateAndClearState(receivedState: String): Boolean = mutex.withLock {
        if (receivedState.isBlank()) return@withLock false
        val storedState = secureStorage.getString(STATE_KEY)
        if (!storedState.isNullOrBlank() && constantTimeEquals(storedState, receivedState)) {
            secureStorage.delete(STATE_KEY)
            true
        } else {
            false
        }
    }

    /**
     * Removes any stored CSRF [state] token from [secureStorage].
     */
    suspend fun clearState() = mutex.withLock {
        secureStorage.delete(STATE_KEY)
    }

    /**
     * Stores the access and refresh tokens via [SecureStorage].
     *
     * Rejects blank tokens and refuses to store a [refreshToken] that has already
     * been consumed during an earlier rotation. If persisting the refresh token
     * fails, the previous access token state is restored.
     *
     * @param accessToken The new access token.
     * @param refreshToken The new refresh token.
     * @throws IllegalArgumentException if either token is blank.
     * @throws TokenReuseException if [refreshToken] was previously consumed.
     */
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        require(accessToken.isNotBlank()) { "Access token must not be blank" }
        require(refreshToken.isNotBlank()) { "Refresh token must not be blank" }

        mutex.withLock {
            val consumedDigests = loadConsumedDigests()
            if (containsDigest(consumedDigests, digest(refreshToken))) {
                throw TokenReuseException("Refresh token reuse detected!")
            }

            val previousAccessToken = secureStorage.getString(ACCESS_TOKEN_KEY)
            var accessSaved = false
            var committed = false
            try {
                secureStorage.saveString(ACCESS_TOKEN_KEY, accessToken)
                accessSaved = true
                secureStorage.saveString(REFRESH_TOKEN_KEY, refreshToken)
                committed = true
            } finally {
                if (!committed && accessSaved) {
                    withContext(NonCancellable) {
                        runCatching { restoreOrDelete(ACCESS_TOKEN_KEY, previousAccessToken) }
                    }
                }
            }
        }
    }

    /**
     * Retrieves the current access token.
     * 
     * @return The access token, or null if not stored.
     */
    suspend fun getAccessToken(): String? = mutex.withLock {
        secureStorage.getString(ACCESS_TOKEN_KEY)
    }

    /**
     * Retrieves the current refresh token.
     * 
     * @return The refresh token, or null if not stored.
     */
    suspend fun getRefreshToken(): String? = mutex.withLock {
        secureStorage.getString(REFRESH_TOKEN_KEY)
    }

    /**
     * Clears all stored tokens.
     *
     * The consumed-refresh-token registry is intentionally left in place: it holds
     * only digests and keeping it means a token that was already rotated away is
     * still recognised as reused after a sign-out/sign-in cycle.
     */
    suspend fun clearTokens() = mutex.withLock {
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
     *     reuse detection), and rejects [newRefreshToken] if it matches an already
     *     consumed token.
     *  2. Performs an atomic compare-and-swap against the currently stored refresh
     *     token. If a different token is already stored, the presented one has been
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
        require(oldRefreshToken.isNotBlank()) { "Old refresh token must not be blank" }
        require(newAccessToken.isNotBlank()) { "New access token must not be blank" }
        require(newRefreshToken.isNotBlank()) { "New refresh token must not be blank" }

        mutex.withLock {
            val oldDigest = digest(oldRefreshToken)
            val consumedDigests = loadConsumedDigests()
            val isSameToken = constantTimeEquals(newRefreshToken, oldRefreshToken)

            // 1. Explicit reuse detection: reject if the presented token was already
            //    exchanged or if the replacement token was previously consumed.
            // 2. Compare-and-swap: the presented token must be the one we currently hold.
            //    A different stored token means ours was already rotated away elsewhere.
            val current = secureStorage.getString(REFRESH_TOKEN_KEY)
            val isReuseDetected = containsDigest(consumedDigests, oldDigest) ||
                (current != null && !constantTimeEquals(current, oldRefreshToken)) ||
                (!isSameToken && containsDigest(consumedDigests, digest(newRefreshToken)))

            if (isReuseDetected) {
                throw TokenReuseException("Refresh token reuse detected!")
            }

            // 3. Persist the new pair and retire the old token atomically with rollback
            //    so a partial storage failure never leaves torn credentials behind.
            val previousAccessToken = secureStorage.getString(ACCESS_TOKEN_KEY)
            var accessSaved = false
            var refreshSaved = false
            var committed = false
            try {
                secureStorage.saveString(ACCESS_TOKEN_KEY, newAccessToken)
                accessSaved = true
                secureStorage.saveString(REFRESH_TOKEN_KEY, newRefreshToken)
                refreshSaved = true

                // A non-rotating server may hand back the same token; only retire it when
                // the value actually changed, otherwise we would lock ourselves out.
                if (!isSameToken) {
                    markConsumed(oldDigest, consumedDigests)
                }
                committed = true
            } finally {
                if (!committed) {
                    withContext(NonCancellable) {
                        if (refreshSaved) {
                            runCatching { restoreOrDelete(REFRESH_TOKEN_KEY, current) }
                        }
                        if (accessSaved) {
                            runCatching { restoreOrDelete(ACCESS_TOKEN_KEY, previousAccessToken) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns true if [refreshToken] has already been exchanged via [rotateTokens]
     * and must therefore be rejected as reused.
     */
    suspend fun isRefreshTokenConsumed(refreshToken: String): Boolean {
        if (refreshToken.isBlank()) return false
        return mutex.withLock {
            containsDigest(loadConsumedDigests(), digest(refreshToken))
        }
    }

    private suspend fun markConsumed(digest: String, existingDigests: List<String>) {
        val digests = existingDigests.toMutableList()
        if (!containsDigest(digests, digest)) {
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

    private suspend fun restoreOrDelete(key: String, previousValue: String?) {
        if (previousValue != null) {
            secureStorage.saveString(key, previousValue)
        } else {
            secureStorage.delete(key)
        }
    }

    private fun containsDigest(digests: List<String>, targetDigest: String): Boolean =
        digests.fold(false) { matched, entry -> matched or constantTimeEquals(entry, targetDigest) }

    private suspend fun loadConsumedDigests(): List<String> {
        val raw = secureStorage.getString(CONSUMED_REFRESH_TOKENS_KEY) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(
            first.toByteArray(Charsets.UTF_8),
            second.toByteArray(Charsets.UTF_8)
        )

    private fun digest(value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(hash.size * 2)
        for (byte in hash) {
            builder.append(HEX_CHARS[(byte.toInt() shr NIBBLE_SHIFT) and NIBBLE_MASK])
            builder.append(HEX_CHARS[byte.toInt() and NIBBLE_MASK])
        }
        return builder.toString()
    }

    class TokenReuseException(message: String) : Exception(message)
}
