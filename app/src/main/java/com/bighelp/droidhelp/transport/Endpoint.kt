package com.bighelp.droidhelp.transport

import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException

/**
 * Canonical representation of a network endpoint: scheme://[prefix/]host:port/path
 * The deployment prefix, if present, is folded between `://` and `host` in the
 * canonical string, and extracted back during parsing.
 */
data class Endpoint(
    val deploymentPrefix: String?,
    val host: String,
    val port: Int,
    val path: String,
    val scheme: String
) {
    /** Canonical string: scheme://[prefix/]host:port/path */
    val canonical: String
        get() {
            val prefixPart = deploymentPrefix
                ?.trim('/')
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$it/" } ?: ""
            return "$scheme://$prefixPart$host:$port$path"
        }

    /** Serialize to a pipe-delimited encoded string for persistence. */
    fun toEncodedString(): String {
        val prefix = deploymentPrefix ?: ""
        return "$scheme|$host|$port|$path|$prefix"
    }

    companion object {
        @JvmOverloads
        fun build(
            scheme: String = "http",
            host: String,
            port: Int? = null,
            path: String = "/",
            deploymentPrefix: String? = null
        ): Endpoint {
            require(scheme == "http" || scheme == "https") {
                "Unsupported scheme '$scheme'; only 'http' and 'https' are allowed"
            }
            require(host.isNotBlank()) { "Host must not be blank" }
            val p = port ?: if (scheme == "https") 443 else 80
            val normalizedPath = normalizePath(path)
            return Endpoint(deploymentPrefix, host, p, normalizedPath, scheme)
        }

        fun fromEncodedString(encoded: String): Endpoint {
            val parts = encoded.split('|')
            if (parts.size != 5) {
                throw IllegalArgumentException(
                    "Invalid encoded endpoint string: expected 5 pipe-separated parts, got ${parts.size}"
                )
            }
            val scheme = parts[0].trim()
            val host = parts[1].trim()
            val portStr = parts[2].trim()
            val path = parts[3].trim()
            val deploymentPrefix = if (parts[4].isEmpty()) null else parts[4].trim()
            val port = portStr.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid port in encoded endpoint: '$portStr'")
            return Endpoint(deploymentPrefix, host, port, normalizePath(path), scheme)
        }

        fun fromString(url: String): Endpoint {
            if (url.isBlank()) {
                throw IllegalArgumentException("Endpoint URL cannot be empty")
            }

            val lower = url.lowercase()

            // 1. Extract scheme
            val schemeIdx = lower.indexOf("://")
            if (schemeIdx < 0) {
                throw IllegalArgumentException("Missing '://' in endpoint URL: '$url'")
            }
            val scheme = url.substring(0, schemeIdx).trim().lowercase()
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException(
                    "Unsupported scheme '$scheme'; only 'http' and 'https' are allowed"
                )
            }
            val rest = url.substring(schemeIdx + 3)

            // 2. Determine if there's a deployment prefix.
            //    The segment before the first '/' is a prefix only when the
            //    segment after it looks host-like (contains '.' or ':');
            //    otherwise the first segment is the host itself (e.g. localhost).
            var prefix: String? = null
            var hostPortPath = rest
            val slashIdx = rest.indexOf('/')
            if (slashIdx >= 0) {
                val potentialPrefix = rest.substring(0, slashIdx)
                val remainder = rest.substring(slashIdx + 1)
                val nextSlash = remainder.indexOf('/')
                val nextSegment = if (nextSlash >= 0) remainder.substring(0, nextSlash) else remainder
                val nextSegmentIsHostLike = nextSegment.contains('.') || nextSegment.contains(':')
                if (nextSegmentIsHostLike &&
                    !potentialPrefix.contains('.') && !potentialPrefix.contains(':')
                ) {
                    prefix = potentialPrefix
                    hostPortPath = remainder
                }
            }

            // 3. Parse host:port/path from hostPortPath
            val cleanHostPortPath = hostPortPath.trim()

            var host = ""
            var port = if (scheme == "https") 443 else 80
            var path = "/"

            if (cleanHostPortPath.isNotEmpty()) {
                // Find the first '/' to separate host:port from path
                val pathStart = cleanHostPortPath.indexOf('/')
                val hostPortPart = if (pathStart >= 0) {
                    path = cleanHostPortPath.substring(pathStart)
                    cleanHostPortPath.substring(0, pathStart)
                } else {
                    cleanHostPortPath
                }

                // Parse host and port from hostPortPart
                if (hostPortPart.startsWith("[")) {
                    // Bracketed IPv6 literal: [::1] or [::1]:8080
                    val closeIdx = hostPortPart.indexOf(']')
                    if (closeIdx < 0) {
                        throw IllegalArgumentException("Malformed IPv6 host in endpoint URL: '$url'")
                    }
                    host = hostPortPart.substring(1, closeIdx).trim()
                    val after = hostPortPart.substring(closeIdx + 1).trim()
                    if (after.isNotEmpty()) {
                        if (!after.startsWith(":")) {
                            throw IllegalArgumentException(
                                "Invalid characters after IPv6 host in endpoint URL: '$url'"
                            )
                        }
                        port = parsePort(after.substring(1), url)
                    }
                } else {
                    val colonInHostPort = hostPortPart.indexOf(':')
                    if (colonInHostPort >= 0) {
                        host = hostPortPart.substring(0, colonInHostPort).trim()
                        port = parsePort(hostPortPart.substring(colonInHostPort + 1).trim(), url)
                    } else {
                        host = hostPortPart.trim()
                    }
                }
            }

            if (host.isEmpty()) {
                throw IllegalArgumentException("Missing host in endpoint URL: '$url'")
            }

            // 4. Normalize path
            val normalizedPath = normalizePath(path)

            return Endpoint(prefix, host, port, normalizedPath, scheme)
        }

        private fun parsePort(portStr: String, url: String): Int {
            if (portStr.isEmpty()) {
                throw IllegalArgumentException("Missing port after ':' in endpoint URL: '$url'")
            }
            return portStr.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Invalid port '$portStr' in endpoint URL: '$url'")
        }
    }
}

private fun normalizePath(p: String): String {
    var result = p
    if (!result.startsWith("/")) result = "/" + result
    result = result.replace(Regex("/+"), "/")
    result = result.replace(Regex("/+$"), "")
    if (result.isEmpty()) result = "/"
    return result
}

/** TLS policy: mandates https everywhere except plain http is allowed
 *  when the host resolves to a private-network address. */
fun requireTls(endpoint: Endpoint): Boolean {
    if (endpoint.scheme == "https") return true
    if (endpoint.scheme != "http") return true // non-http, non-https always require TLS

    // Private-network hosts that allow plain http:
    // - loopback 127.0.0.0/8 and ::1
    // - RFC1918: 10/8, 172.16/12, 192.168/16
    // - link-local: 169.254.0.0/16 (IPv4), fe80::/10 (IPv6)
    // - hostname 'localhost'
    val host = endpoint.host.lowercase()

    return !isPrivateNetworkHost(host)
}

/** Check if a host string represents a private-network address. */
private fun isPrivateNetworkHost(host: String): Boolean {
    if (host == "localhost") return true
    // Try to resolve as IPv4 or IPv6
    return try {
        val addr = InetAddress.getByName(host)
        isPrivateAddress(addr)
    } catch (e: UnknownHostException) {
        false
    }
}

/** Check if an InetAddress is a private-network address. */
private fun isPrivateAddress(addr: InetAddress): Boolean = when (addr) {
    is Inet4Address -> isPrivateIPv4(addr)
    is Inet6Address -> isPrivateIPv6(addr)
    else -> false
}

/** IPv4 private-network check: loopback, RFC1918, link-local */
private fun isPrivateIPv4(addr: Inet4Address): Boolean {
    val b = addr.address
    if (b.size != 4) return false
    val b0 = b[0].toInt() and 0xFF
    val b1 = b[1].toInt() and 0xFF
    return when {
        b0 == 127 -> true                    // 127.0.0.0/8 loopback
        b0 == 10 -> true                     // 10.0.0.0/8
        b0 == 172 && b1 in 16..31 -> true    // 172.16.0.0/12
        b0 == 192 && b1 == 168 -> true       // 192.168.0.0/16
        b0 == 169 && b1 == 254 -> true       // 169.254.0.0/16 link-local
        else -> false
    }
}

/** IPv6 private-network check: ::1, link-local, unique local */
private fun isPrivateIPv6(addr: Inet6Address): Boolean {
    val b = addr.address
    if (b.size != 16) return false

    // ::1 (IPv6 loopback): all bytes zero except the last, which is 1
    var allZeroExceptLast = true
    for (i in 0 until 15) {
        if (b[i] != 0.toByte()) {
            allZeroExceptLast = false
            break
        }
    }
    if (allZeroExceptLast && b[15] == 1.toByte()) return true

    // Link-local: fe80::/10 (first 10 bits are 1111111010)
    if (b[0] == 0xFE.toByte() && (b[1].toInt() and 0xC0) == 0x80) return true

    // Unique local: fc00::/7 (first 7 bits are 1111110)
    if ((b[0].toInt() and 0xFE) == 0xFC) return true

    return false
}
