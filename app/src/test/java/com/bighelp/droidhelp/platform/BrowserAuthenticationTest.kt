package com.bighelp.droidhelp.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI

class BrowserAuthenticationTest {

    @Test
    fun testValidCallback() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://localhost:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("http://localhost:$port$path")
        val queryParams = mapOf("state" to state, "code" to "test-code")

        val code = callback.validate(incomingUrl, queryParams)
        assertEquals("test-code", code)
    }

    @Test
    fun testValidCallbackIp() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://127.0.0.1:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("http://127.0.0.1:$port$path")
        val queryParams = mapOf("state" to state, "code" to "test-code")

        val code = callback.validate(incomingUrl, queryParams)
        assertEquals("test-code", code)
    }

    @Test
    fun testInvalidHost() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://example.com:$port$path")
        assertThrows(BrowserAuthenticationException::class.java) {
            BrowserAuthenticationCallback(port, path, state, url)
        }
    }

    @Test
    fun testInvalidHostMismatch() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://localhost:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("http://127.0.0.1:$port$path")
        val queryParams = mapOf("state" to state, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testPathMismatch() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://localhost:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("http://localhost:$port/wrong-path")
        val queryParams = mapOf("state" to state, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testStateMismatch() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://localhost:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("http://localhost:$port$path")
        val queryParams = mapOf("state" to "wrong-state", "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testMissingCode() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://localhost:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("http://localhost:$port$path")
        val queryParams = mapOf("state" to state)

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testInvalidScheme() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://localhost:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("https://localhost:$port$path")
        val queryParams = mapOf("state" to state, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }

    @Test
    fun testInvalidPort() {
        val port = 8080
        val path = "/auth/callback"
        val state = "test-state"
        val url = URI("http://localhost:$port$path")
        val callback = BrowserAuthenticationCallback(port, path, state, url)

        val incomingUrl = URI("http://localhost:9090$path")
        val queryParams = mapOf("state" to state, "code" to "test-code")

        assertThrows(BrowserAuthenticationException::class.java) {
            callback.validate(incomingUrl, queryParams)
        }
    }
}
