package com.bighelp.droidhelp.transport

private const val SCHEME_SEPARATOR = "://"
private const val DEFAULT_TLS_PORT = 443
private const val DEFAULT_PLAINTEXT_PORT = 80
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val MAX_URL_UTF8_BYTES = 2_048
private const val MAX_HOSTNAME_LENGTH = 253
private const val ENCODED_PART_COUNT = 5
private const val INDEX_SCHEME = 0
private const val INDEX_HOST = 1
private const val INDEX_PORT = 2
private const val INDEX_PATH = 3
private const val INDEX_PREFIX = 4
private const val ASCII_FIRST_PRINTABLE = 0x21
private const val ASCII_DEL = 0x7F

private const val IPV4_OCTET_COUNT = 4
private const val MAX_IPV4_OCTET_DIGITS = 3
private const val MAX_OCTET_VALUE = 255
private const val IPV4_LOOPBACK_FIRST = 127
private const val IPV4_RFC1918_10 = 10
private const val IPV4_RFC1918_172 = 172
private const val IPV4_RFC1918_172_MIN = 16
private const val IPV4_RFC1918_172_MAX = 31
private const val IPV4_RFC1918_192 = 192
private const val IPV4_RFC1918_192_SECOND = 168
private const val IPV4_CGNAT_FIRST = 100
private const val IPV4_CGNAT_MIN = 64
private const val IPV4_CGNAT_MAX = 127
private const val IPV4_LINK_LOCAL_FIRST = 169
private const val IPV4_LINK_LOCAL_SECOND = 254

private const val IPV6_BYTE_COUNT = 16
private const val IPV6_GROUP_COUNT = 8
private const val IPV6_LAST_BYTE_INDEX = 15
private const val HEX_RADIX = 16
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF
private const val IPV6_LINK_LOCAL_FIRST = 0xFE
private const val IPV6_LINK_LOCAL_SECOND_MASK = 0xC0
private const val IPV6_LINK_LOCAL_SECOND_VALUE = 0x80
private const val IPV6_ULA_MASK = 0xFE
private const val IPV6_ULA_PREFIX = 0xFC
private const val IPV6_MAPPED_ZERO_PREFIX_BYTES = 10
private const val IPV6_MAPPED_V4_OFFSET = 12
private const val IPV4_LAST_OCTET_INDEX = 3

private val ALLOWED_SCHEMES = setOf("http", "https", "ws", "wss")
private val SECURE_SCHEMES = setOf("https", "wss")
private val PLAINTEXT_SCHEMES = setOf("http", "ws")
private val DISALLOWED_URL_CHARS = setOf('\\', '?', '#', '|')
private val HOSTNAME_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
private val IPV6_HEX_GROUP_REGEX = Regex("^[0-9a-fA-F]{1,4}$")
private val DEPLOYMENT_PREFIX_REGEX = Regex("^[A-Za-z0-9_~-]+$")
private val ALLOWED_PATH_REGEX = Regex("^/[A-Za-z0-9._~/-]*$")
private val MULTI_SLASH_REGEX = Regex("/+")
private val TRAILING_SLASH_REGEX = Regex("/+$")

/**
 * Hostname suffixes that only resolve inside a private network (mDNS, home routers,
 * ICANN's reserved `.internal`, company intranets, Tailscale MagicDNS, RFC 6761 `.localhost`).
 */
private val PRIVATE_NAME_SUFFIXES = listOf(
    "local",
    "lan",
    "internal",
    "home.arpa",
    "intranet",
    "corp",
    "localdomain",
    "private",
    "localhost",
    "ts.net",
    "beta.tailscale.net"
)

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
    /** Whether this endpoint uses a TLS-secured scheme (`https` or `wss`). */
    val isSecure: Boolean
        get() = scheme in SECURE_SCHEMES

    /** Canonical string: scheme://[prefix/]host:port/path */
    val canonical: String
        get() {
            val prefixPart = deploymentPrefix
                ?.trim('/')
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$it/" } ?: ""
            val formattedHost = if (host.contains(':') && !host.startsWith("[")) {
                "[$host]"
            } else {
                host
            }
            return "$scheme://$prefixPart$formattedHost:$port$path"
        }

    /** Serialize to a pipe-delimited encoded string for persistence. */
    fun toEncodedString(): String {
        val prefix = deploymentPrefix?.trim('/')?.takeIf { it.isNotEmpty() } ?: ""
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
            val normalizedScheme = scheme.trim().lowercase()
            require(normalizedScheme in ALLOWED_SCHEMES) {
                "Unsupported scheme '$scheme'; only 'http', 'https', 'ws', and 'wss' are allowed"
            }

            val trimmedHost = host.trim()
            require(trimmedHost.isNotEmpty()) { "Host must not be blank" }
            val unbracketedHost = if (trimmedHost.startsWith("[") && trimmedHost.endsWith("]")) {
                val inner = trimmedHost.substring(1, trimmedHost.length - 1)
                require(parseIPv6(inner) != null) { "Invalid bracketed IPv6 host '$host'" }
                inner
            } else {
                trimmedHost
            }
            val normalizedHost = unbracketedHost.lowercase()
            require(!normalizedHost.contains('%')) { "Zone identifier is not allowed in host '$host'" }
            val isValidHost = parseIPv4(normalizedHost) != null ||
                parseIPv6(normalizedHost) != null ||
                isValidDnsHostname(normalizedHost)
            require(isValidHost) { "Invalid host '$host'" }

            val resolvedPort = port ?: if (normalizedScheme in SECURE_SCHEMES) {
                DEFAULT_TLS_PORT
            } else {
                DEFAULT_PLAINTEXT_PORT
            }
            require(resolvedPort in MIN_PORT..MAX_PORT) {
                "Invalid port '$resolvedPort'; expected $MIN_PORT..$MAX_PORT"
            }

            val candidatePath = if (path.startsWith("/")) path else "/$path"
            require(ALLOWED_PATH_REGEX.matches(candidatePath)) { "Invalid path '$path'" }
            val normalizedPath = candidatePath
                .replace(MULTI_SLASH_REGEX, "/")
                .replace(TRAILING_SLASH_REGEX, "")
                .ifEmpty { "/" }
            require(normalizedPath.split('/').none { it == "." || it == ".." }) {
                "Path traversal segments are not allowed in path '$path'"
            }

            val normalizedPrefix = deploymentPrefix?.trim()?.trim('/')?.ifEmpty { null }
            if (normalizedPrefix != null) {
                require(DEPLOYMENT_PREFIX_REGEX.matches(normalizedPrefix)) {
                    "Invalid deployment prefix '$deploymentPrefix'"
                }
                require(!normalizedPrefix.equals("localhost", ignoreCase = true)) {
                    "Deployment prefix cannot be 'localhost'"
                }
            }

            return Endpoint(normalizedPrefix, normalizedHost, resolvedPort, normalizedPath, normalizedScheme)
        }

        fun fromEncodedString(encoded: String): Endpoint {
            val parts = encoded.split('|')
            require(parts.size == ENCODED_PART_COUNT) {
                "Invalid encoded endpoint string: expected $ENCODED_PART_COUNT pipe-separated parts, got ${parts.size}"
            }
            val scheme = parts[INDEX_SCHEME].trim()
            val host = parts[INDEX_HOST].trim()
            val portStr = parts[INDEX_PORT].trim()
            val path = parts[INDEX_PATH].trim()
            val deploymentPrefix = parts[INDEX_PREFIX].trim().ifEmpty { null }
            val port = parsePort(portStr, encoded)
            return build(
                scheme = scheme,
                host = host,
                port = port,
                path = path,
                deploymentPrefix = deploymentPrefix
            )
        }

        fun fromString(url: String): Endpoint {
            require(url.isNotEmpty() && url.isNotBlank()) { "Endpoint URL cannot be empty" }
            require(url.toByteArray(Charsets.UTF_8).size <= MAX_URL_UTF8_BYTES) {
                "Endpoint URL exceeds maximum length of $MAX_URL_UTF8_BYTES bytes"
            }
            require(url.none { it.code < ASCII_FIRST_PRINTABLE || it.code == ASCII_DEL }) {
                "Endpoint URL contains invalid whitespace or control characters: '$url'"
            }
            require(url.none { it in DISALLOWED_URL_CHARS }) {
                "Endpoint URL contains disallowed characters: '$url'"
            }

            val schemeIdx = url.indexOf(SCHEME_SEPARATOR)
            require(schemeIdx > 0) { "Missing '$SCHEME_SEPARATOR' in endpoint URL: '$url'" }
            val scheme = url.substring(0, schemeIdx).lowercase()
            require(scheme in ALLOWED_SCHEMES) {
                "Unsupported scheme '$scheme'; only 'http', 'https', 'ws', and 'wss' are allowed"
            }

            val rest = url.substring(schemeIdx + SCHEME_SEPARATOR.length)
            require(rest.isNotEmpty()) { "Missing host in endpoint URL: '$url'" }

            // Determine if there's a deployment prefix.
            // The segment before the first '/' is a prefix only when it is non-empty,
            // is not 'localhost' or host-like itself, and the segment after it looks host-like.
            var prefix: String? = null
            var hostPortPath = rest
            val slashIdx = rest.indexOf('/')
            if (slashIdx >= 0) {
                val potentialPrefix = rest.substring(0, slashIdx)
                val remainder = rest.substring(slashIdx + 1)
                val nextSlash = remainder.indexOf('/')
                val nextSegment = if (nextSlash >= 0) remainder.substring(0, nextSlash) else remainder
                val nextSegmentIsHostLike = nextSegment.startsWith("[") ||
                    nextSegment.contains('.') ||
                    nextSegment.contains(':')
                val prefixHasHostChars = potentialPrefix.contains('.') || potentialPrefix.contains(':')
                val prefixCandidateValid = potentialPrefix.isNotEmpty() &&
                    !potentialPrefix.equals("localhost", ignoreCase = true) &&
                    !prefixHasHostChars
                if (prefixCandidateValid && nextSegmentIsHostLike) {
                    prefix = potentialPrefix
                    hostPortPath = remainder
                }
            }

            val pathStart = hostPortPath.indexOf('/')
            val hostPortPart = if (pathStart >= 0) hostPortPath.substring(0, pathStart) else hostPortPath
            val rawPath = if (pathStart >= 0) hostPortPath.substring(pathStart) else "/"
            val (host, port) = parseAuthority(hostPortPart, scheme, url)

            return build(
                scheme = scheme,
                host = host,
                port = port,
                path = rawPath,
                deploymentPrefix = prefix
            )
        }

        private fun parseAuthority(hostPortPart: String, scheme: String, url: String): Pair<String, Int> {
            require(hostPortPart.isNotEmpty()) { "Missing host in endpoint URL: '$url'" }
            require(!hostPortPart.contains('@')) { "Userinfo is not allowed in endpoint URL: '$url'" }
            val defaultPort = if (scheme in SECURE_SCHEMES) DEFAULT_TLS_PORT else DEFAULT_PLAINTEXT_PORT
            return if (hostPortPart.startsWith("[")) {
                val closeIdx = hostPortPart.indexOf(']')
                require(closeIdx > 1) { "Malformed IPv6 host in endpoint URL: '$url'" }
                val ipv6Host = hostPortPart.substring(1, closeIdx)
                require(parseIPv6(ipv6Host) != null) { "Malformed IPv6 host in endpoint URL: '$url'" }
                val after = hostPortPart.substring(closeIdx + 1)
                val port = if (after.isNotEmpty()) {
                    require(after.startsWith(":")) {
                        "Invalid characters after IPv6 host in endpoint URL: '$url'"
                    }
                    parsePort(after.substring(1), url)
                } else {
                    defaultPort
                }
                ipv6Host to port
            } else {
                val colonIdx = hostPortPart.indexOf(':')
                if (colonIdx >= 0) {
                    val host = hostPortPart.substring(0, colonIdx)
                    require(host.isNotEmpty()) { "Missing host in endpoint URL: '$url'" }
                    host to parsePort(hostPortPart.substring(colonIdx + 1), url)
                } else {
                    hostPortPart to defaultPort
                }
            }
        }

        private fun parsePort(portStr: String, context: String): Int {
            require(portStr.isNotEmpty()) { "Missing port after ':' in endpoint: '$context'" }
            require(portStr.all { it in '0'..'9' }) { "Invalid port '$portStr' in endpoint: '$context'" }
            val parsed = portStr.toIntOrNull()
            require(parsed != null && parsed in MIN_PORT..MAX_PORT) {
                "Invalid port '$portStr' in endpoint: '$context'"
            }
            return parsed
        }
    }
}

/**
 * TLS policy: mandates `https` / `wss` everywhere except plaintext `http` / `ws` is allowed
 * when the host is a private or loopback network address. No DNS resolution can authorize plaintext.
 */
fun requireTls(endpoint: Endpoint): Boolean {
    val scheme = endpoint.scheme.lowercase()
    return when (scheme) {
        in SECURE_SCHEMES -> true
        in PLAINTEXT_SCHEMES -> !isPrivateNetworkHost(endpoint.host)
        else -> true
    }
}

/**
 * Enforces the TLS policy for [endpoint], returning [endpoint] if it already uses TLS
 * (`https` / `wss`) or targets a private/loopback network host over `http` / `ws`, and
 * throwing [IllegalArgumentException] otherwise.
 */
fun enforceTls(endpoint: Endpoint): Endpoint {
    val requiredScheme = if (endpoint.scheme.equals("ws", ignoreCase = true)) "wss" else "https"
    require(endpoint.isSecure || !requireTls(endpoint)) {
        "TLS ($requiredScheme) is required for non-private host '${endpoint.host}'"
    }
    return endpoint
}

/**
 * Check if a host string represents a private or loopback network address without performing DNS lookup.
 */
fun isPrivateNetworkHost(host: String): Boolean {
    val normalized = host.trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase()
    if (normalized.isEmpty() || normalized.contains('%')) return false

    val v4 = parseIPv4(normalized)
    val v6 = if (v4 == null) parseIPv6(normalized) else null

    return when {
        v4 != null -> isPrivateIPv4Bytes(v4)
        v6 != null -> isPrivateIPv6Bytes(v6)
        else -> isValidDnsHostname(normalized) && (
            !normalized.contains('.') ||
                PRIVATE_NAME_SUFFIXES.any { suffix -> normalized.endsWith(".$suffix") }
            )
    }
}

private fun isValidDnsHostname(lowerHost: String): Boolean {
    val isNumericAlias = lowerHost.all { it in '0'..'9' || it == '.' } || lowerHost.startsWith("0x")
    val validShape = !lowerHost.contains(':') && lowerHost.length <= MAX_HOSTNAME_LENGTH && !isNumericAlias
    return validShape && lowerHost.split('.').all { HOSTNAME_LABEL_REGEX.matches(it) }
}

private fun isPrivateIPv4Bytes(bytes: ByteArray): Boolean {
    val b0 = bytes[0].toInt() and BYTE_MASK
    val b1 = bytes[1].toInt() and BYTE_MASK
    return when {
        b0 == IPV4_LOOPBACK_FIRST -> true
        b0 == IPV4_RFC1918_10 -> true
        b0 == IPV4_RFC1918_172 && b1 in IPV4_RFC1918_172_MIN..IPV4_RFC1918_172_MAX -> true
        b0 == IPV4_RFC1918_192 && b1 == IPV4_RFC1918_192_SECOND -> true
        b0 == IPV4_CGNAT_FIRST && b1 in IPV4_CGNAT_MIN..IPV4_CGNAT_MAX -> true
        b0 == IPV4_LINK_LOCAL_FIRST && b1 == IPV4_LINK_LOCAL_SECOND -> true
        else -> false
    }
}

private fun isPrivateIPv6Bytes(bytes: ByteArray): Boolean {
    val isLoopback = (0 until IPV6_LAST_BYTE_INDEX).all { bytes[it] == 0.toByte() } &&
        bytes[IPV6_LAST_BYTE_INDEX] == 1.toByte()
    val b0 = bytes[0].toInt() and BYTE_MASK
    val b1 = bytes[1].toInt() and BYTE_MASK
    val isLinkLocal = b0 == IPV6_LINK_LOCAL_FIRST &&
        (b1 and IPV6_LINK_LOCAL_SECOND_MASK) == IPV6_LINK_LOCAL_SECOND_VALUE
    val isUniqueLocal = (b0 and IPV6_ULA_MASK) == IPV6_ULA_PREFIX
    val isIPv4Mapped = (0 until IPV6_MAPPED_ZERO_PREFIX_BYTES).all { bytes[it] == 0.toByte() } &&
        (bytes[IPV6_MAPPED_ZERO_PREFIX_BYTES].toInt() and BYTE_MASK) == BYTE_MASK &&
        (bytes[IPV6_MAPPED_ZERO_PREFIX_BYTES + 1].toInt() and BYTE_MASK) == BYTE_MASK
    val isMappedPrivate = isIPv4Mapped && isPrivateIPv4Bytes(
        bytes.copyOfRange(IPV6_MAPPED_V4_OFFSET, IPV6_BYTE_COUNT)
    )
    val isNativePrivate = isLoopback || isLinkLocal || isUniqueLocal
    return isNativePrivate || isMappedPrivate
}

private fun parseIPv4(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != IPV4_OCTET_COUNT) return null
    val bytes = ByteArray(IPV4_OCTET_COUNT)
    var valid = true
    for (i in 0 until IPV4_OCTET_COUNT) {
        val part = parts[i]
        val hasLeadingZero = part.length > 1 && part[0] == '0'
        val validDigits = part.isNotEmpty() && part.length <= MAX_IPV4_OCTET_DIGITS && part.all { it in '0'..'9' }
        val value = if (validDigits && !hasLeadingZero) part.toInt() else -1
        if (value !in 0..MAX_OCTET_VALUE) {
            valid = false
            break
        }
        bytes[i] = value.toByte()
    }
    return if (valid) bytes else null
}

private fun parseIPv6(host: String): ByteArray? {
    val doubleColonIdx = host.indexOf("::")
    val validDoubleColon = doubleColonIdx < 0 || host.indexOf("::", doubleColonIdx + 2) < 0
    if (!host.contains(':') || host.contains('%') || !validDoubleColon) return null

    val split = if (doubleColonIdx >= 0) {
        val left = parseIPv6Groups(host.substring(0, doubleColonIdx), allowIPv4 = false)
        val right = parseIPv6Groups(host.substring(doubleColonIdx + 2), allowIPv4 = true)
        if (left != null && right != null && left.size + right.size < IPV6_GROUP_COUNT) left to right else null
    } else {
        val left = parseIPv6Groups(host, allowIPv4 = true)
        if (left != null && left.size == IPV6_GROUP_COUNT) left to emptyList() else null
    }

    return split?.let { (leftGroups, rightGroups) ->
        val bytes = ByteArray(IPV6_BYTE_COUNT)
        val zeroGroups = IPV6_GROUP_COUNT - leftGroups.size - rightGroups.size
        leftGroups.forEachIndexed { idx, group ->
            bytes[idx * 2] = (group ushr BITS_PER_BYTE).toByte()
            bytes[idx * 2 + 1] = (group and BYTE_MASK).toByte()
        }
        val rightOffset = leftGroups.size + zeroGroups
        rightGroups.forEachIndexed { idx, group ->
            bytes[(rightOffset + idx) * 2] = (group ushr BITS_PER_BYTE).toByte()
            bytes[(rightOffset + idx) * 2 + 1] = (group and BYTE_MASK).toByte()
        }
        bytes
    }
}

private fun parseIPv6Groups(segment: String, allowIPv4: Boolean): List<Int>? {
    if (segment.isEmpty()) return emptyList()
    val parts = segment.split(':')
    val groups = ArrayList<Int>(parts.size + 1)
    var valid = true
    for (i in parts.indices) {
        val part = parts[i]
        val v4 = if (allowIPv4 && i == parts.lastIndex && part.contains('.')) parseIPv4(part) else null
        when {
            v4 != null -> {
                groups.add(((v4[0].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (v4[1].toInt() and BYTE_MASK))
                groups.add(
                    ((v4[2].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                        (v4[IPV4_LAST_OCTET_INDEX].toInt() and BYTE_MASK)
                )
            }
            IPV6_HEX_GROUP_REGEX.matches(part) -> groups.add(part.toInt(HEX_RADIX))
            else -> {
                valid = false
                break
            }
        }
    }
    return if (valid) groups else null
}
