package com.bighelp.droidhelp.platform

import java.net.URI
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Interface for making HTTP requests.
 */
interface HttpClient {
    suspend fun post(url: String, body: Map<String, String>): Response
}

/**
 * Simple OkHttp-based HTTP client implementation.
 */
class OkHttpClientImpl(private val httpClient: OkHttpClient) : HttpClient {

    override suspend fun post(url: String, body: Map<String, String>): Response {
        val requestBody = body.entries.joinToString("&") { "${it.key}=${it.value}" }
        val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
        val requestBodyObj = requestBody.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBodyObj)
            .build()
        return httpClient.newCall(request).execute()
    }
}

/**
 * Handles the complete sign-in callback flow for OAuth with PKCE.
 *
 * This class integrates all components into the sign-in callback handler:
 * - Parses the authorization response from the callback URL
 * - Validates state to prevent CSRF attacks
 * - Validates loopback redirect URI
 * - Exchanges the authorization code using PKCE (sends code_verifier)
 * - Stores tokens including refresh token
 * - Handles errors gracefully
 *
 * The flow works end-to-end with test scenarios covering success and failure paths.
 */
class SignInCallbackHandler(
    private val auth: Auth,
    private val httpClient: HttpClient,
    private val tokenEndpoint: String
) {

    /**
     * Processes the sign-in callback from the OAuth provider.
     *
     * @param callbackUrl The full callback URL received from the OAuth provider
     * @param expectedPort The expected port for the loopback redirect
     * @param expectedPath The expected path for the loopback redirect
     * @return Pair of access token and refresh token
     * @throws SignInCallbackException if any step of the callback flow fails
     */
    suspend fun processCallback(
        callbackUrl: String,
        expectedPort: Int,
        expectedPath: String
    ): Pair<String, String> {
        try {
            // 1. Parse the authorization response from the callback URL
            val parsed = parseCallbackUrl(callbackUrl)

            // 2. Validate state to prevent CSRF attacks
            val stateValid = auth.validateAndClearState(parsed.state)
            if (!stateValid) {
                throw SignInCallbackException(
                    "Invalid state: CSRF protection failed",
                    SignInCallbackException.ErrorType.INVALID_STATE
                )
            }

            // 3. Validate loopback redirect URI
            val callback = BrowserAuthenticationCallback(
                port = expectedPort,
                path = expectedPath,
                state = parsed.state,
                url = URI("http://localhost:$expectedPort$expectedPath")
            )

            val code = callback.validate(parsed.incomingUrl, parsed.queryParams)

            // 4. Exchange the authorization code using PKCE
            val verifier = auth.getOrCreateVerifier()
            val (accessToken, refreshToken) = exchangeCodeWithPKCE(code, verifier)

            // 5. Store tokens including refresh token
            auth.saveTokens(accessToken, refreshToken)

            // 6. Clear the used code_verifier
            auth.clearVerifier()

            // 7. Return the tokens
            return Pair(accessToken, refreshToken)

        } catch (e: SignInCallbackException) {
            // Re-throw our own exceptions
            throw e
        } catch (e: Exception) {
            // Wrap other exceptions
            throw SignInCallbackException(
                "Failed to process sign-in callback: ${e.message}",
                SignInCallbackException.ErrorType.UNKNOWN,
                e
            )
        }
    }

    /**
     * Parses the callback URL to extract authorization code and state.
     *
     * @param callbackUrl The full callback URL
     * @return ParsedCallbackData containing the extracted information
     * @throws SignInCallbackException if the URL cannot be parsed
     */
    private fun parseCallbackUrl(callbackUrl: String): ParsedCallbackData {
        try {
            val uri = URI(callbackUrl)

            // Validate scheme
            if (uri.scheme != "http") {
                throw SignInCallbackException(
                    "Invalid scheme: only 'http' is allowed",
                    SignInCallbackException.ErrorType.INVALID_REDIRECT
                )
            }

            // Validate host (must be localhost or 127.0.0.1)
            val host = uri.host ?: throw SignInCallbackException(
                "Host is missing",
                SignInCallbackException.ErrorType.INVALID_REDIRECT
            )
            if (host != "localhost" && host != "127.0.0.1") {
                throw SignInCallbackException(
                    "Redirect URI must be a loopback address (localhost or 127.0.0.1)",
                    SignInCallbackException.ErrorType.INVALID_REDIRECT
                )
            }

            // Parse query parameters
            val queryParams = mutableMapOf<String, String>()
            val query = uri.query
            if (query != null) {
                val params = query.split("&")
                for (param in params) {
                    val keyValue = param.split("=", limit = 2)
                    if (keyValue.size == 2) {
                        val key = keyValue[0]
                        val value = keyValue[1]
                        queryParams[key] = value
                    }
                }
            }

            // Extract state and code
            val state = queryParams["state"] ?: throw SignInCallbackException(
                "Missing state parameter",
                SignInCallbackException.ErrorType.INVALID_STATE
            )

            val code = queryParams["code"] ?: throw SignInCallbackException(
                "Missing code parameter",
                SignInCallbackException.ErrorType.MISSING_CODE
            )

            return ParsedCallbackData(
                incomingUrl = uri,
                queryParams = queryParams,
                state = state,
                code = code
            )

        } catch (e: SignInCallbackException) {
            throw e
        } catch (e: Exception) {
            throw SignInCallbackException(
                "Failed to parse callback URL: ${e.message}",
                SignInCallbackException.ErrorType.PARSE_ERROR,
                e
            )
        }
    }

    /**
     * Exchanges the authorization code for tokens using PKCE.
     *
     * @param code The authorization code received from the OAuth provider
     * @param verifier The code_verifier used to generate the code_challenge
     * @return Pair of access token and refresh token
     * @throws SignInCallbackException if the token exchange fails
     */
    private suspend fun exchangeCodeWithPKCE(
        code: String,
        verifier: String
    ): Pair<String, String> {
        try {
            // Build the request body
            val requestBody = mapOf(
                "code" to code,
                "code_verifier" to verifier
            )

            // Send the token exchange request
            val response = httpClient.post(tokenEndpoint, requestBody)

            if (!response.isSuccessful) {
                throw SignInCallbackException(
                    "Token exchange failed with HTTP ${response.code}",
                    SignInCallbackException.ErrorType.TOKEN_EXCHANGE_FAILED
                )
            }

            val responseBody = response.body?.string()
                ?: throw SignInCallbackException(
                    "Empty response from token endpoint",
                    SignInCallbackException.ErrorType.TOKEN_EXCHANGE_FAILED
                )

            // Parse JSON response
            val accessToken = extractJsonField(responseBody, "access_token")
                ?: throw SignInCallbackException(
                    "Missing access_token in response",
                    SignInCallbackException.ErrorType.TOKEN_EXCHANGE_FAILED
                )

            val refreshToken = extractJsonField(responseBody, "refresh_token")
                ?: throw SignInCallbackException(
                    "Missing refresh_token in response",
                    SignInCallbackException.ErrorType.TOKEN_EXCHANGE_FAILED
                )

            return Pair(accessToken, refreshToken)

        } catch (e: SignInCallbackException) {
            throw e
        } catch (e: Exception) {
            throw SignInCallbackException(
                "Failed to exchange code for tokens: ${e.message}",
                SignInCallbackException.ErrorType.TOKEN_EXCHANGE_FAILED,
                e
            )
        }
    }

    /**
     * Extracts a field from a JSON string (simplified implementation).
     * In a real implementation, use a proper JSON library like Gson or Kotlinx Serialization.
     */
    private fun extractJsonField(json: String, field: String): String? {
        // Simplified JSON parsing - in production use a proper JSON library
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }

    /**
     * Exception thrown when sign-in callback processing fails.
     */
    class SignInCallbackException(
        message: String,
        val errorType: ErrorType,
        cause: Throwable? = null
    ) : Exception(message, cause) {

        /**
         * Types of errors that can occur during sign-in callback processing.
         */
        enum class ErrorType {
            /** Invalid state parameter (CSRF protection failed) */
            INVALID_STATE,

            /** Invalid redirect URI (host, scheme, port, or path mismatch) */
            INVALID_REDIRECT,

            /** Missing code parameter in the callback */
            MISSING_CODE,

            /** Failed to parse the callback URL */
            PARSE_ERROR,

            /** Failed to exchange authorization code for tokens */
            TOKEN_EXCHANGE_FAILED,

            /** Unknown error */
            UNKNOWN
        }
    }

    /**
     * Data class containing parsed callback information.
     */
    data class ParsedCallbackData(
        val incomingUrl: URI,
        val queryParams: Map<String, String>,
        val state: String,
        val code: String
    )
}