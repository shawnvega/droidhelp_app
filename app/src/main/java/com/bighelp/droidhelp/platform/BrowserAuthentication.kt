package com.bighelp.droidhelp.platform

import java.net.URI

class BrowserAuthenticationException(message: String) : Exception(message)

class BrowserAuthenticationCallback(
    val port: Int,
    val path: String,
    private val state: String,
    val url: URI
) {
    init {
        if (port == 0) throw BrowserAuthenticationException("Port must not be zero")
        if (!path.startsWith("/")) throw BrowserAuthenticationException("Path must start with /")
        if (path.contains("//")) throw BrowserAuthenticationException("Path must not contain //")
        if (state.isEmpty()) throw BrowserAuthenticationException("State must not be empty")
        
        val host = url.host ?: throw BrowserAuthenticationException("Host is missing")
        if (host != "localhost" && host != "127.0.0.1") {
            throw BrowserAuthenticationException("Redirect URI must be a loopback address (localhost or 127.0.0.1)")
        }
        if (url.scheme != "http") {
            throw BrowserAuthenticationException("Scheme must be http")
        }
    }

    fun validate(incomingUrl: URI, queryParameters: Map<String, String>): String {
        if (incomingUrl.scheme != "http") {
            throw BrowserAuthenticationException("Scheme must be http")
        }
        if (incomingUrl.host != url.host) {
            throw BrowserAuthenticationException("Host mismatch: expected ${url.host}, got ${incomingUrl.host}")
        }
        if (incomingUrl.path != path) {
            throw BrowserAuthenticationException("Path mismatch: expected $path, got ${incomingUrl.path}")
        }
        if (incomingUrl.port != port) {
            throw BrowserAuthenticationException("Port mismatch: expected $port, got ${incomingUrl.port}")
        }
        if (queryParameters["state"] != state) {
            throw BrowserAuthenticationException("State mismatch")
        }
        return queryParameters["code"] ?: throw BrowserAuthenticationException("Missing code parameter")
    }
}
