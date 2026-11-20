package com.bighelp.droidhelp.platform

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class BrowserAuthenticationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Validates OAuth loopback redirect callbacks per RFC 8252 Section 7.3.
 */
class BrowserAuthenticationCallback(
    val port: Int,
    val path: String,
    private val state: String,
    val url: URI
) {
    companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val ALLOWED_SCHEME = "http"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }

    init {
        checkValid(port in MIN_PORT..MAX_PORT) {
            "Port must be between $MIN_PORT and $MAX_PORT"
        }
        checkValid(path.startsWith("/")) { "Path must start with /" }
        checkValid(!path.contains("//")) { "Path must not contain //" }
        checkValid(!path.contains("..") && !path.contains("\\")) {
            "Path must not contain traversal sequences"
        }
        checkValid(state.isNotBlank()) { "State must not be empty" }
        checkValid(url.scheme == ALLOWED_SCHEME) { "Scheme must be http" }
        checkValid(url.userInfo == null) { "Redirect URI must not contain user info" }

        val host = url.host ?: throw BrowserAuthenticationException("Host is missing")
        checkValid(host in LOOPBACK_HOSTS) {
            "Redirect URI must be a loopback address (localhost, 127.0.0.1, or [::1])"
        }
    }

    /**
     * Validates an incoming redirect [incomingUrl] by parsing its raw query parameters
     * and verifying scheme, loopback host, port, path, CSRF state, and authorization code.
     */
    fun validate(incomingUrl: URI): String {
        val queryParameters = parseQueryParameters(incomingUrl.rawQuery)
        return validate(incomingUrl, queryParameters)
    }

    /**
     * Validates an incoming redirect [incomingUrl] and parsed [queryParameters],
     * returning the authorization code on success.
     */
    fun validate(incomingUrl: URI, queryParameters: Map<String, String>): String {
        checkValid(incomingUrl.scheme == ALLOWED_SCHEME) { "Scheme must be http" }
        checkValid(incomingUrl.userInfo == null) { "Redirect URI must not contain user info" }
        checkValid(incomingUrl.host == url.host) {
            "Host mismatch: expected ${url.host}, got ${incomingUrl.host}"
        }
        checkValid(incomingUrl.rawPath == path && incomingUrl.path == path) {
            "Path mismatch: expected $path, got ${incomingUrl.path}"
        }
        checkValid(incomingUrl.port == port) {
            "Port mismatch: expected $port, got ${incomingUrl.port}"
        }
        checkValid(constantTimeEquals(state, queryParameters["state"])) { "State mismatch" }

        val error = queryParameters["error"]
        if (!error.isNullOrBlank()) {
            val description = queryParameters["error_description"]
            val detail = if (!description.isNullOrBlank()) ": $description" else ""
            throw BrowserAuthenticationException("Authorization error ($error)$detail")
        }

        return queryParameters["code"]?.takeIf { it.isNotBlank() }
            ?: throw BrowserAuthenticationException("Missing code parameter")
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val params = mutableMapOf<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val parts = pair.split("=", limit = 2)
            val key = decodeQueryComponent(parts[0])
            val value = if (parts.size == 2) decodeQueryComponent(parts[1]) else ""
            checkValid(!params.containsKey(key)) { "Duplicate query parameter: $key" }
            params[key] = value
        }
        return params
    }

    private fun decodeQueryComponent(component: String): String =
        try {
            URLDecoder.decode(component, StandardCharsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            throw BrowserAuthenticationException("Malformed query parameter encoding: ${e.message}", e)
        }

    private fun constantTimeEquals(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private inline fun checkValid(condition: Boolean, message: () -> String) {
        if (!condition) {
            throw BrowserAuthenticationException(message())
        }
    }
}

