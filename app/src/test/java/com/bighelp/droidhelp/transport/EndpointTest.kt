package com.bighelp.droidhelp.transport

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.bighelp.droidhelp.platform.FileBasedVersionedDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EndpointTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
            "localhost", "hermes.localhost", "hermes", "hermes.local", "hermes.lan", "hermes.internal",
            "nas.home.arpa", "app.intranet", "api.corp", "box.localdomain", "svc.private",
            "hermes.tail1234.ts.net", "hermes.beta.tailscale.net"
        )
        for (host in privateHosts) {
            assertFalse("http://$host", requireTls(Endpoint.build(scheme = "http", host = host, path = "/")))
            assertFalse("ws://$host", requireTls(Endpoint.build(scheme = "ws", host = host, path = "/")))
        }
    }

    @Test
    fun `requireTls returns false for http on private and loopback IPv4 ranges`() {
        val privateIPv4s = listOf(
            "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.8.0.5", "172.16.0.1", "172.31.255.254",
            "192.168.1.1", "192.168.1.20", "100.64.0.1", "100.127.255.254", "169.254.0.1"
        )
        for (ip in privateIPv4s) {
            assertFalse("http://$ip", requireTls(Endpoint.build(scheme = "http", host = ip, path = "/")))
            assertFalse("ws://$ip", requireTls(Endpoint.build(scheme = "ws", host = ip, path = "/")))
        }
    }

    @Test
    fun `requireTls returns false for http on private and loopback IPv6 ranges`() {
        val privateIPv6s = listOf(
            "::1", "fe80::1", "fd00::1", "fc00::1", "fd7a:115c:a1e0::1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:100.64.0.1"
        )
        for (ip in privateIPv6s) {
            assertFalse("http://[$ip]", requireTls(Endpoint.build(scheme = "http", host = ip, path = "/")))
            assertFalse("ws://[$ip]", requireTls(Endpoint.build(scheme = "ws", host = ip, path = "/")))
        }
    }

    @Test
    fun `requireTls returns true for http and ws on public IPs and hostnames`() {
        val publicHosts = listOf(
            "8.8.8.8", "192.0.2.1", "11.0.0.1", "172.15.255.255", "172.32.0.1", "100.63.255.255",
            "100.128.0.1", "2001:db8::1", "::ffff:8.8.8.8", "example.com", "127.0.0.1.example.com",
            "local.example.com", "internal.com", "hermes.lan.example.com", "ts.net.example.com",
            "example-ts.net", "tailscale.net"
        )
        for (host in publicHosts) {
            assertTrue("http://$host", requireTls(Endpoint.build(scheme = "http", host = host, path = "/")))
            assertTrue("ws://$host", requireTls(Endpoint.build(scheme = "ws", host = host, path = "/")))
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
            "", "127.1", "2130706433", "0x7f000001", "0177.0.0.1", "fe80::1%eth0", "1::2::3", "1:2:3:4:5:6:7::8"
        )
        for (host in rejected) {
            assertFalse("expected false for '$host'", isPrivateNetworkHost(host))
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
            "", "   ", "https://", "http:///path", "http:///api.example.com", "http://example.com:abc/path",
            "https://example.com:0", "https://example.com:70000", "https://example.com:/path",
            "https://user:password@example.com", "https://user@example.com", "https://example.com?token=fixture",
            "https://example.com/api?token=fixture", "https://example.com#fragment", "file:///tmp/host",
            "ftp://example.com", "https://example.com/a/../b", "https://example.com/./a",
            "https://example.com\\path", "https://example.com/a|b", "https://example.com/a b",
            "http://[::1", "http://[invalid]/path", "http://[::1]extra/path", "http://[1::2::3]/path",
            "http://[fe80::1%eth0]/path", "http://127.1/path", "http://0x7f000001/path",
            "http://0177.0.0.1/path", "https://example.com/" + "a".repeat(2_050)
        )
        for (url in invalidUrls) {
            assertThrows("expected throw: $url", IllegalArgumentException::class.java) { Endpoint.fromString(url) }
        }
    }

    @Test
    fun `build rejects invalid ports, prefixes, and paths`() {
        for (badPort in listOf(0, 70_000)) {
            assertThrows(IllegalArgumentException::class.java) {
                Endpoint.build(scheme = "https", host = "example.com", port = badPort)
            }
        }
        for (badPrefix in listOf("v1/v2", "v1.0", "v1|v2", "localhost")) {
            assertThrows(IllegalArgumentException::class.java) {
                Endpoint.build(scheme = "https", host = "example.com", deploymentPrefix = badPrefix)
            }
        }
        for (badPath in listOf("/a/../b", "/a|b")) {
            assertThrows(IllegalArgumentException::class.java) {
                Endpoint.build(scheme = "https", host = "example.com", path = badPath)
            }
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
            "bad|format", "https|example.com|443|/|v1|extra", "ftp|example.com|21|/|", "https||443|/|",
            "https|example.com|0|/|", "https|example.com|70000|/|", "https|example.com|abc|/|",
            "https|example.com|443|/a/../b|", "https|example.com|443|/|v1/v2", "http|127.1|80|/|"
        )
        for (encoded in invalidEncoded) {
            assertThrows(
                "expected throw: $encoded",
                IllegalArgumentException::class.java
            ) { Endpoint.fromEncodedString(encoded) }
        }
    }

    // ---- Deployment prefix parsing tests ----

    @Test
    fun `fromString parses deployment prefix with default ports, omitted paths, and allowed characters`() {
        val httpsPrefixed = Endpoint.fromString("https://v1/api.example.com")
        assertEquals(Endpoint("v1", "api.example.com", 443, "/", "https"), httpsPrefixed)

        val ipv4Prefixed = Endpoint.fromString("http://staging/10.0.0.1")
        assertEquals(Endpoint("staging", "10.0.0.1", 80, "/", "http"), ipv4Prefixed)

        val ipv6Prefixed = Endpoint.fromString("https://prod/[::1]")
        assertEquals(Endpoint("prod", "::1", 443, "/", "https"), ipv6Prefixed)

        val singleLabelPortHost = Endpoint.fromString("ws://edge/hermes:9119/api/ws")
        assertEquals(Endpoint("edge", "hermes", 9119, "/api/ws", "ws"), singleLabelPortHost)

        val mixedChars = Endpoint.fromString("https://Canary_1-beta~2/API.Example.COM/Users/")
        assertEquals(Endpoint("Canary_1-beta~2", "api.example.com", 443, "/Users", "https"), mixedChars)
    }

    @Test
    fun `fromString does not misclassify hostnames or paths as deployment prefixes`() {
        assertEquals(Endpoint(null, "hermes", 80, "/api/v1", "http"), Endpoint.fromString("http://hermes/api/v1"))
        assertEquals(
            Endpoint(null, "localhost", 80, "/api.json", "http"),
            Endpoint.fromString("http://LOCALHOST/api.json")
        )
        assertEquals(
            Endpoint(null, "api.example.com", 443, "/v1.0/status", "https"),
            Endpoint.fromString("https://api.example.com/v1.0/status")
        )
        assertEquals(
            Endpoint(null, "hermes", 9119, "/v1.0/status", "http"),
            Endpoint.fromString("http://hermes:9119/v1.0/status")
        )
        assertEquals(
            Endpoint(null, "::1", 8443, "/v1.0/status", "https"),
            Endpoint.fromString("https://[::1]:8443/v1.0/status")
        )
    }

    @Test
    fun `fromString and build normalize valid prefixes and reject invalid ones`() {
        val normalized = Endpoint.build(
            scheme = "https", host = "api.example.com", deploymentPrefix = "  /Staging_1-beta~2/  "
        )
        assertEquals("Staging_1-beta~2", normalized.deploymentPrefix)

        for (blankPrefix in listOf("", "   ", "/", "///")) {
            val ep = Endpoint.build(scheme = "https", host = "api.example.com", deploymentPrefix = blankPrefix)
            assertNull("expected null prefix for '$blankPrefix'", ep.deploymentPrefix)
        }
        for (badPrefix in listOf("LOCALHOST", "LocalHost", "v1+2", "v1%20", "v1@bad")) {
            assertThrows(IllegalArgumentException::class.java) {
                Endpoint.build(scheme = "https", host = "api.example.com", deploymentPrefix = badPrefix)
            }
        }
        for (badUrl in listOf(
            "https://v1+2/api.example.com/api",
            "https://v1%20/api.example.com/api",
            "https://v1@bad/api.example.com/api"
        )) {
            assertThrows(badUrl, IllegalArgumentException::class.java) { Endpoint.fromString(badUrl) }
        }
    }

    // ---- Standard-connection TLS requirement tests ----

    @Test
    fun `requireTls and enforceTls require TLS for standard public hosts and range boundaries`() {
        val publicBoundaryHosts = listOf(
            "9.255.255.255", "11.0.0.0", "126.255.255.255", "128.0.0.0", "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0", "100.63.255.255", "100.128.0.0", "169.253.255.255", "169.255.0.0",
            "::2", "fbff:ffff::1", "fe00::1", "fe7f::1", "fec0::1", "::ffff:172.32.0.1", "::ffff:192.0.2.1",
            "localhost.example.com", "api.example.com"
        )
        for (host in publicBoundaryHosts) {
            val httpEp = Endpoint.build(scheme = "http", host = host, path = "/api", deploymentPrefix = "prod")
            val wsEp = Endpoint.build(scheme = "ws", host = host, path = "/ws")
            val httpsEp = Endpoint.build(scheme = "https", host = host, path = "/api", deploymentPrefix = "prod")
            val wssEp = Endpoint.build(scheme = "wss", host = host, path = "/ws")

            assertFalse(httpEp.isSecure)
            assertFalse(wsEp.isSecure)
            assertTrue(httpsEp.isSecure)
            assertTrue(wssEp.isSecure)
            assertTrue("expected TLS required for http://$host", requireTls(httpEp))
            assertTrue("expected TLS required for ws://$host", requireTls(wsEp))

            val httpErr = assertThrows(IllegalArgumentException::class.java) { enforceTls(httpEp) }
            assertTrue(httpErr.message.orEmpty().contains("https") && httpErr.message.orEmpty().contains(httpEp.host))

            val wsErr = assertThrows(IllegalArgumentException::class.java) { enforceTls(wsEp) }
            assertTrue(wsErr.message.orEmpty().contains("wss") && wsErr.message.orEmpty().contains(wsEp.host))

            assertEquals(httpsEp, enforceTls(httpsEp))
            assertEquals(wssEp, enforceTls(wssEp))
        }
    }

    // ---- Private-network HTTP allowance tests ----

    @Test
    fun `enforceTls permits http and ws across private IPv4, IPv6, and hostname boundaries`() {
        val privateBoundaryHosts = listOf(
            "127.0.0.0", "127.255.255.255", "10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255",
            "192.168.0.0", "192.168.255.255", "100.64.0.0", "100.127.255.255", "169.254.0.0", "169.254.255.255",
            "0:0:0:0:0:0:0:1", "febf::1", "fdff::1", "::ffff:172.16.0.1", "::ffff:192.168.1.1",
            "::ffff:169.254.1.1", "::ffff:7f00:1", "localhost", "hermes", "hermes.local", "hermes.lan",
            "hermes.internal", "nas.home.arpa", "app.intranet", "api.corp", "box.localdomain", "svc.private",
            "dev.localhost", "hermes.tail1234.ts.net", "hermes.beta.tailscale.net"
        )
        for (host in privateBoundaryHosts) {
            val httpEp = Endpoint.build(scheme = "http", host = host, port = 9119, path = "/api")
            val wsEp = Endpoint.build(scheme = "ws", host = host, port = 9119, path = "/ws")
            assertFalse("expected HTTP allowed for $host", requireTls(httpEp))
            assertFalse("expected WS allowed for $host", requireTls(wsEp))
            assertEquals(httpEp, enforceTls(httpEp))
            assertEquals(wsEp, enforceTls(wsEp))
        }

        val prefixedPrivate = Endpoint.fromString("http://staging/192.168.1.20:9119/api")
        assertFalse(requireTls(prefixedPrivate))
        assertEquals(prefixedPrivate, enforceTls(prefixedPrivate))
    }

    @Test
    fun `isPrivateNetworkHost handles case, surrounding whitespace, and bracketed ipv6`() {
        assertTrue(isPrivateNetworkHost("  HERMES.LOCAL  "))
        assertTrue(isPrivateNetworkHost("LOCALHOST"))
        assertTrue(isPrivateNetworkHost("[::1]"))
        assertTrue(isPrivateNetworkHost("[FD7A:115C:A1E0::1]"))
        assertTrue(isPrivateNetworkHost(" 10.0.0.1 "))
        assertFalse(isPrivateNetworkHost("  EXAMPLE.COM  "))
        assertFalse(isPrivateNetworkHost("[2001:db8::1]"))
    }

    // ---- Persistence tests ----

    @Test
    fun `toEncodedString produces expected wire format and distinct identities`() {
        val withPrefix = Endpoint.build(
            scheme = "https", host = "api.example.com", port = 443, path = "/data", deploymentPrefix = "v1"
        )
        assertEquals("https|api.example.com|443|/data|v1", withPrefix.toEncodedString())

        val withoutPrefix = Endpoint.build(scheme = "http", host = "127.0.0.1", port = 80, path = "/")
        assertEquals("http|127.0.0.1|80|/|", withoutPrefix.toEncodedString())

        val otherHost = Endpoint.build(
            scheme = "https", host = "other.example.com", port = 443, path = "/data", deploymentPrefix = "v1"
        )
        assertNotEquals(withPrefix.canonical, otherHost.canonical)
        assertNotEquals(withPrefix.toEncodedString(), otherHost.toEncodedString())

        val decoded = Endpoint.fromEncodedString(withPrefix.toEncodedString())
        assertEquals(withPrefix, decoded)
        assertEquals(withPrefix.hashCode(), decoded.hashCode())
    }

    @Test
    fun `fromEncodedString trims whitespace, normalizes case, and preserves prefix case`() {
        val decoded = Endpoint.fromEncodedString(" HTTPS | API.Example.COM | 8443 | /v1/chat/ | Prod_1 ")
        val expected = Endpoint.build(
            scheme = "https", host = "api.example.com", port = 8443, path = "/v1/chat", deploymentPrefix = "Prod_1"
        )
        assertEquals(expected, decoded)
        assertEquals("Prod_1", decoded.deploymentPrefix)
    }

    @Test
    fun `endpoint persists to disk and reloads through FileBasedVersionedDatabase`() {
        val tempDir = tempFolder.root
        val dbFile = File(tempDir, "endpoints.db")
        val initial = Endpoint.build(
            scheme = "https", host = "api.example.com", port = 8443, path = "/chat/stream", deploymentPrefix = "prod_v1"
        )
        val privateFallback = Endpoint.build(
            scheme = "http", host = "fd7a:115c:a1e0::1", port = 9119, path = "/api", deploymentPrefix = "local-dev"
        )

        val writerDb = FileBasedVersionedDatabase(dbFile)
        assertEquals(1, writerDb.insert("active_endpoint", initial.toEncodedString()))
        assertEquals(2, writerDb.insert("active_endpoint", privateFallback.toEncodedString()))

        val readerDb = FileBasedVersionedDatabase(dbFile)
        val restoredV1 = Endpoint.fromEncodedString(requireNotNull(readerDb.retrieve("active_endpoint", version = 1)))
        val restoredLatest = Endpoint.fromEncodedString(requireNotNull(readerDb.retrieve("active_endpoint")))

        assertEquals(initial, restoredV1)
        assertEquals(initial.canonical, restoredV1.canonical)
        assertEquals(privateFallback, restoredLatest)
        assertEquals(privateFallback.canonical, restoredLatest.canonical)
        assertEquals(restoredV1, enforceTls(restoredV1))
        assertEquals(restoredLatest, enforceTls(restoredLatest))
    }

    @Test
    fun `persisted endpoints still enforce TLS policy and validation on reload`() {
        val tamperedPublicHttp = Endpoint.fromEncodedString("http|example.com|80|/api|")
        assertTrue(requireTls(tamperedPublicHttp))
        assertThrows(IllegalArgumentException::class.java) { enforceTls(tamperedPublicHttp) }

        val privateHttp = Endpoint.fromEncodedString("http|hermes.local|9119|/api|staging")
        assertFalse(requireTls(privateHttp))
        assertEquals(privateHttp, enforceTls(privateHttp))

        for (badPersisted in listOf("", "https|example.com|443|/|localhost", "http|fe80::1%eth0|80|/|")) {
            assertThrows(
                "expected throw: $badPersisted",
                IllegalArgumentException::class.java
            ) { Endpoint.fromEncodedString(badPersisted) }
        }
    }
}

