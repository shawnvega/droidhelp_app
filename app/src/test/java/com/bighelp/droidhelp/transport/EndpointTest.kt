package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EndpointTest {

    // ---- Round-trip parse/serialize tests ----

    @Test
    fun `round-trip canonical form without prefix`() {
        val ep = Endpoint.build(scheme = "https", host = "api.example.com", port = 443, path = "/users")
        assertEquals("https://api.example.com:443/users", ep.canonical)
        val roundtripped = Endpoint.fromString(ep.canonical)
        assertEquals(ep, roundtripped)
    }

    @Test
    fun `round-trip canonical form with deployment prefix`() {
        val ep = Endpoint.build(
            scheme = "https",
            host = "api.example.com",
            port = 443,
            path = "/data",
            deploymentPrefix = "v1"
        )
        assertEquals("https://v1/api.example.com:443/data", ep.canonical)
        val roundtripped = Endpoint.fromString(ep.canonical)
        assertEquals(ep, roundtripped)
    }

    @Test
    fun `round-trip with non-default port`() {
        val ep = Endpoint.build(scheme = "http", host = "example.com", port = 8080, path = "/api")
        assertEquals("http://example.com:8080/api", ep.canonical)
        val roundtripped = Endpoint.fromString(ep.canonical)
        assertEquals(ep, roundtripped)
    }

    @Test
    fun `round-trip with trailing slash normalization`() {
        val ep = Endpoint.build(scheme = "https", host = "example.com", path = "/users/")
        assertEquals("https://example.com:443/users", ep.canonical)
        val roundtripped = Endpoint.fromString(ep.canonical)
        assertEquals(ep, roundtripped)
    }

    @Test
    fun `round-trip with path containing segments`() {
        val ep = Endpoint.build(scheme = "https", host = "example.com", path = "/a/b/c")
        assertEquals("https://example.com:443/a/b/c", ep.canonical)
        val roundtripped = Endpoint.fromString(ep.canonical)
        assertEquals(ep, roundtripped)
    }

    // ---- Default port normalization ----

    @Test
    fun `https defaults to port 443`() {
        val ep = Endpoint.fromString("https://api.example.com/users")
        assertEquals(443, ep.port)
    }

    @Test
    fun `http defaults to port 80`() {
        val ep = Endpoint.fromString("http://api.example.com/users")
        assertEquals(80, ep.port)
    }

    @Test
    fun `explicit port overrides default`() {
        val ep = Endpoint.fromString("https://api.example.com:8080/users")
        assertEquals(8080, ep.port)
    }

    // ---- TLS policy tests ----

    @Test
    fun `requireTls returns true for https everywhere`() {
        assertTrue(requireTls(Endpoint.build(scheme = "https", host = "example.com", path = "/")))
        assertTrue(requireTls(Endpoint.build(scheme = "https", host = "public.example.com", path = "/api")))
    }

    @Test
    fun `requireTls returns false for http on localhost`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "localhost", path = "/")))
    }

    @Test
    fun `requireTls returns false for http on loopback IPv4`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "127.0.0.1", path = "/")))
    }

    @Test
    fun `requireTls returns false for http on RFC1918 10 slash 8`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "10.0.0.1", path = "/")))
    }

    @Test
    fun `requireTls returns false for http on RFC1918 172 dot 16 slash 12`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "172.16.0.1", path = "/")))
    }

    @Test
    fun `requireTls returns false for http on RFC1918 192 dot 168 slash 16`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "192.168.1.1", path = "/")))
    }

    @Test
    fun `requireTls returns false for http on link-local IPv4`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "169.254.0.1", path = "/")))
    }

    @Test
    fun `requireTls returns true for http on public IP`() {
        assertTrue(requireTls(Endpoint.build(scheme = "http", host = "8.8.8.8", path = "/")))
    }

    @Test
    fun `requireTls returns true for http on public hostname`() {
        assertTrue(requireTls(Endpoint.build(scheme = "http", host = "example.com", path = "/")))
    }

    // ---- Additional coverage tests ----

    @Test
    fun `fromString treats single-label host as host not prefix`() {
        val ep = Endpoint.fromString("http://localhost/api")
        assertEquals("localhost", ep.host)
        assertNull(ep.deploymentPrefix)
        assertEquals("/api", ep.path)
    }

    @Test
    fun `requireTls returns false for http on ipv6 loopback`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "::1", path = "/")))
    }

    @Test
    fun `requireTls returns false for http on ipv6 link-local`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "fe80::1", path = "/")))
    }

    @Test
    fun `requireTls returns false for http on ipv6 unique-local`() {
        assertFalse(requireTls(Endpoint.build(scheme = "http", host = "fd00::1", path = "/")))
    }

    @Test
    fun `requireTls returns true for http on public ipv6`() {
        assertTrue(requireTls(Endpoint.build(scheme = "http", host = "2001:db8::1", path = "/")))
    }

    @Test
    fun `fromString handles bracketed IPv6 host`() {
        val ep = Endpoint.fromString("http://[::1]:8080/path")
        assertEquals("::1", ep.host)
        assertEquals(8080, ep.port)
        assertEquals("/path", ep.path)
    }

    @Test
    fun `fromString throws on missing host`() {
        assertThrows(IllegalArgumentException::class.java) { Endpoint.fromString("http:///path") }
    }

    @Test
    fun `fromString throws on invalid port`() {
        assertThrows(IllegalArgumentException::class.java) { Endpoint.fromString("http://example.com:abc/path") }
    }

    @Test
    fun `fromEncodedString throws on bad format`() {
        assertThrows(IllegalArgumentException::class.java) { Endpoint.fromEncodedString("bad|format") }
    }

    @Test
    fun `encoded string round-trips with prefix`() {
        val ep = Endpoint.build(
            scheme = "https",
            host = "api.example.com",
            port = 443,
            path = "/data",
            deploymentPrefix = "v1"
        )
        val encoded = ep.toEncodedString()
        val decoded = Endpoint.fromEncodedString(encoded)
        assertEquals(ep, decoded)
        assertEquals("v1", decoded.deploymentPrefix)
    }

    @Test
    fun `encoded string round-trips without prefix`() {
        val ep = Endpoint.build(scheme = "http", host = "example.com", port = 80, path = "/")
        val encoded = ep.toEncodedString()
        val decoded = Endpoint.fromEncodedString(encoded)
        assertEquals(ep, decoded)
        assertNull(decoded.deploymentPrefix)
    }
}
