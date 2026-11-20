package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `round-trip canonical form with deployment prefix on ipv6 and localhost`() {
        val ipv6Ep = Endpoint.build(
            scheme = "https",
            host = "::1",
            port = 8443,
            path = "/data",
            deploymentPrefix = "/v1/"
        )
        assertEquals("v1", ipv6Ep.deploymentPrefix)
        assertEquals("https://v1/[::1]:8443/data", ipv6Ep.canonical)
        assertEquals(ipv6Ep, Endpoint.fromString(ipv6Ep.canonical))

        val localEp = Endpoint.build(
            scheme = "http",
            host = "localhost",
            port = 8080,
            path = "/api",
            deploymentPrefix = "staging"
        )
        assertEquals("http://staging/localhost:8080/api", localEp.canonical)
        assertEquals(localEp, Endpoint.fromString(localEp.canonical))
    }

    @Test
    fun `round-trip canonical form for ws and wss schemes`() {
        val wssEp = Endpoint.build(
            scheme = "wss",
            host = "ws.example.com",
            path = "/api/ws",
            deploymentPrefix = "prod"
        )
        assertEquals("wss://prod/ws.example.com:443/api/ws", wssEp.canonical)
        assertEquals(wssEp, Endpoint.fromString(wssEp.canonical))
        assertTrue(wssEp.isSecure)

        val wsEp = Endpoint.build(scheme = "ws", host = "127.0.0.1", port = 9119, path = "/api/ws")
        assertEquals("ws://127.0.0.1:9119/api/ws", wsEp.canonical)
        assertEquals(wsEp, Endpoint.fromString(wsEp.canonical))
        assertFalse(wsEp.isSecure)
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

    @Test
    fun `round-trip bracketed ipv6 canonical form`() {
        val ep = Endpoint.fromString("http://[::1]:8080/path")
        assertEquals("::1", ep.host)
        assertEquals(8080, ep.port)
        assertEquals("/path", ep.path)
        assertEquals("http://[::1]:8080/path", ep.canonical)
        assertEquals(ep, Endpoint.fromString(ep.canonical))
    }

    @Test
    fun `build normalizes scheme case, host case, bracketed ipv6, and blank prefix`() {
        val ep = Endpoint.build(
            scheme = "HTTPS",
            host = "API.Example.COM",
            path = "users//profile/",
            deploymentPrefix = " / "
        )
        assertEquals("https", ep.scheme)
        assertEquals("api.example.com", ep.host)
        assertEquals("/users/profile", ep.path)
        assertNull(ep.deploymentPrefix)

        val ipv6Ep = Endpoint.build(scheme = "https", host = "[FD7A:115C:A1E0::1]")
        assertEquals("fd7a:115c:a1e0::1", ipv6Ep.host)
        assertEquals("https://[fd7a:115c:a1e0::1]:443/", ipv6Ep.canonical)
    }

    // ---- Default port normalization ----

    @Test
    fun `https and wss default to port 443`() {
        assertEquals(443, Endpoint.fromString("https://api.example.com/users").port)
        assertEquals(443, Endpoint.fromString("wss://api.example.com/api/ws").port)
    }

    @Test
    fun `http and ws default to port 80`() {
        assertEquals(80, Endpoint.fromString("http://api.example.com/users").port)
        assertEquals(80, Endpoint.fromString("ws://localhost/api/ws").port)
    }

    @Test
    fun `explicit port overrides default`() {
        val ep = Endpoint.fromString("https://api.example.com:8080/users")
        assertEquals(8080, ep.port)
    }

    // ---- TLS policy tests ----

    @Test
    fun `requireTls returns true for https and wss everywhere`() {
        assertTrue(requireTls(Endpoint.build(scheme = "https", host = "example.com", path = "/")))
        assertTrue(requireTls(Endpoint.build(scheme = "https", host = "public.example.com", path = "/api")))
        assertTrue(requireTls(Endpoint.build(scheme = "https", host = "localhost", path = "/")))
        assertTrue(requireTls(Endpoint.build(scheme = "wss", host = "example.com", path = "/api/ws")))
        assertTrue(requireTls(Endpoint.build(scheme = "wss", host = "127.0.0.1", path = "/api/ws")))
    }

    @Test
    fun `requireTls returns false for http and ws on localhost and private suffixes`() {
        val privateHosts = listOf(
            "localhost",
            "hermes.localhost",
            "hermes",
            "hermes.local",
            "hermes.lan",
            "hermes.internal",
            "nas.home.arpa",
            "app.intranet",
            "api.corp",
            "box.localdomain",
            "svc.private",
            "hermes.tail1234.ts.net",
            "hermes.beta.tailscale.net"
        )
        for (host in privateHosts) {
            assertFalse(requireTls(Endpoint.build(scheme = "http", host = host, path = "/")), "http://$host")
            assertFalse(requireTls(Endpoint.build(scheme = "ws", host = host, path = "/")), "ws://$host")
        }
    }

    @Test
    fun `requireTls returns false for http on private and loopback IPv4 ranges`() {
        val privateIPv4s = listOf(
            "127.0.0.1",
            "127.255.255.254",
            "10.0.0.1",
            "10.8.0.5",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.1",
            "192.168.1.20",
            "100.64.0.1",
            "100.127.255.254",
            "169.254.0.1"
        )
        for (ip in privateIPv4s) {
            assertFalse(requireTls(Endpoint.build(scheme = "http", host = ip, path = "/")), "http://$ip")
            assertFalse(requireTls(Endpoint.build(scheme = "ws", host = ip, path = "/")), "ws://$ip")
        }
    }

    @Test
    fun `requireTls returns false for http on private and loopback IPv6 ranges`() {
        val privateIPv6s = listOf(
            "::1",
            "fe80::1",
            "fd00::1",
            "fc00::1",
            "fd7a:115c:a1e0::1",
            "::ffff:127.0.0.1",
            "::ffff:10.0.0.1",
            "::ffff:100.64.0.1"
        )
        for (ip in privateIPv6s) {
            assertFalse(requireTls(Endpoint.build(scheme = "http", host = ip, path = "/")), "http://[$ip]")
            assertFalse(requireTls(Endpoint.build(scheme = "ws", host = ip, path = "/")), "ws://[$ip]")
        }
    }

    @Test
    fun `requireTls returns true for http and ws on public IPs and hostnames`() {
        val publicHosts = listOf(
            "8.8.8.8",
            "192.0.2.1",
            "11.0.0.1",
            "172.15.255.255",
            "172.32.0.1",
            "100.63.255.255",
            "100.128.0.1",
            "2001:db8::1",
            "::ffff:8.8.8.8",
            "example.com",
            "127.0.0.1.example.com",
            "local.example.com",
            "internal.com",
            "hermes.lan.example.com",
            "ts.net.example.com",
            "example-ts.net",
            "tailscale.net"
        )
        for (host in publicHosts) {
            assertTrue(requireTls(Endpoint.build(scheme = "http", host = host, path = "/")), "http://$host")
            assertTrue(requireTls(Endpoint.build(scheme = "ws", host = host, path = "/")), "ws://$host")
        }
    }

    @Test
    fun `requireTls fails closed on unsupported schemes even for localhost`() {
        val ftpEndpoint = Endpoint(
            deploymentPrefix = null,
            host = "localhost",
            port = 21,
            path = "/",
            scheme = "ftp"
        )
        assertTrue(requireTls(ftpEndpoint))
        assertThrows(IllegalArgumentException::class.java) { enforceTls(ftpEndpoint) }
    }

    @Test
    fun `enforceTls permits https, wss, and private plaintext but rejects public plaintext`() {
        val httpsPublic = Endpoint.build(scheme = "https", host = "example.com")
        val wssPublic = Endpoint.build(scheme = "wss", host = "example.com")
        val httpPrivate = Endpoint.build(scheme = "http", host = "100.64.0.1", port = 9119)
        val wsLoopback = Endpoint.build(scheme = "ws", host = "::1", port = 9119)

        assertEquals(httpsPublic, enforceTls(httpsPublic))
        assertEquals(wssPublic, enforceTls(wssPublic))
        assertEquals(httpPrivate, enforceTls(httpPrivate))
        assertEquals(wsLoopback, enforceTls(wsLoopback))

        val httpPublic = Endpoint.build(scheme = "http", host = "example.com")
        val wsPublic = Endpoint.build(scheme = "ws", host = "8.8.8.8")
        val httpPublicCgnatEdge = Endpoint.build(scheme = "http", host = "100.63.255.255")

        assertThrows(IllegalArgumentException::class.java) { enforceTls(httpPublic) }
        assertThrows(IllegalArgumentException::class.java) { enforceTls(wsPublic) }
        assertThrows(IllegalArgumentException::class.java) { enforceTls(httpPublicCgnatEdge) }
    }

    @Test
    fun `isPrivateNetworkHost rejects numeric aliases, octal IPv4, and zone IDs`() {
        val rejected = listOf(
            "",
            "127.1",
            "2130706433",
            "0x7f000001",
            "0177.0.0.1",
            "fe80::1%eth0",
            "1::2::3",
            "1:2:3:4:5:6:7::8"
        )
        for (host in rejected) {
            assertFalse(isPrivateNetworkHost(host), "expected false for '$host'")
        }
    }

    // ---- Prefix & URL parsing edge cases ----

    @Test
    fun `fromString treats single-label host as host not prefix`() {
        val ep = Endpoint.fromString("http://localhost/api")
        assertEquals("localhost", ep.host)
        assertNull(ep.deploymentPrefix)
        assertEquals("/api", ep.path)

        val dottedFileEp = Endpoint.fromString("http://localhost/api.json")
        assertEquals("localhost", dottedFileEp.host)
        assertNull(dottedFileEp.deploymentPrefix)
        assertEquals("/api.json", dottedFileEp.path)
    }

    @Test
    fun `fromString rejects invalid, malformed, and unsafe URLs`() {
        val invalidUrls = listOf(
            "",
            "   ",
            "https://",
            "http:///path",
            "http:///api.example.com",
            "http://example.com:abc/path",
            "https://example.com:0",
            "https://example.com:70000",
            "https://example.com:/path",
            "https://user:password@example.com",
            "https://user@example.com",
            "https://example.com?token=fixture",
            "https://example.com/api?token=fixture",
            "https://example.com#fragment",
            "file:///tmp/host",
            "ftp://example.com",
            "https://example.com/a/../b",
            "https://example.com/./a",
            "https://example.com\\path",
            "https://example.com/a|b",
            "https://example.com/a b",
            "http://[::1",
            "http://[invalid]/path",
            "http://[::1]extra/path",
            "http://[1::2::3]/path",
            "http://[fe80::1%eth0]/path",
            "http://127.1/path",
            "http://0x7f000001/path",
            "http://0177.0.0.1/path",
            "https://example.com/" + "a".repeat(2_050)
        )
        for (url in invalidUrls) {
            assertThrows(IllegalArgumentException::class.java, { Endpoint.fromString(url) }, "expected throw: $url")
        }
    }

    @Test
    fun `build rejects invalid ports, prefixes, and paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", port = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", port = 70_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", deploymentPrefix = "v1/v2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", deploymentPrefix = "v1.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", deploymentPrefix = "v1|v2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", deploymentPrefix = "localhost")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", path = "/a/../b")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.build(scheme = "https", host = "example.com", path = "/a|b")
        }
    }

    // ---- Encoded persistence round-trip & validation tests ----

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

    @Test
    fun `encoded string round-trips for wss and ipv6 endpoints`() {
        val endpoints = listOf(
            Endpoint.build(
                scheme = "wss",
                host = "api.example.com",
                port = 8443,
                path = "/api/ws",
                deploymentPrefix = "v2"
            ),
            Endpoint.build(scheme = "ws", host = "::1", port = 9119, path = "/api/ws"),
            Endpoint.build(
                scheme = "https",
                host = "fd7a:115c:a1e0::1",
                port = 443,
                path = "/chat/events",
                deploymentPrefix = "prod"
            )
        )
        for (ep in endpoints) {
            val decoded = Endpoint.fromEncodedString(ep.toEncodedString())
            assertEquals(ep, decoded)
            assertEquals(ep.canonical, decoded.canonical)
        }
    }

    @Test
    fun `fromEncodedString rejects malformed or invalid persisted values`() {
        val invalidEncoded = listOf(
            "bad|format",
            "https|example.com|443|/|v1|extra",
            "ftp|example.com|21|/|",
            "https||443|/|",
            "https|example.com|0|/|",
            "https|example.com|70000|/|",
            "https|example.com|abc|/|",
            "https|example.com|443|/a/../b|",
            "https|example.com|443|/|v1/v2",
            "http|127.1|80|/|"
        )
        for (encoded in invalidEncoded) {
            assertThrows(
                IllegalArgumentException::class.java,
                { Endpoint.fromEncodedString(encoded) },
                "expected throw: $encoded"
            )
        }
    }
}
