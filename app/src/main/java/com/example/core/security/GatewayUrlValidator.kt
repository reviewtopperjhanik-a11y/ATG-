package com.example.core.security

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

sealed interface GatewayUrlValidation {
    data class Valid(val normalizedBaseUrl: String, val isLoopback: Boolean) : GatewayUrlValidation
    data class Invalid(val reason: String) : GatewayUrlValidation
}

/**
 * Validates JH GetWay Pro, MCP, and runtime manifest base URLs.
 *
 * Security policy:
 * - Enforces HTTPS for all remote endpoints; allows HTTP strictly for loopback (127.0.0.1 / localhost / ::1).
 * - Rejects embedded credentials, query parameters, fragments, cloud metadata hostnames,
 *   unspecified bind addresses, link-local, multicast, CGNAT, and RFC1918/ULA private IPs on remote URLs.
 * - Provides [ssrfGuardDns] and [validateResolvedHost] to defend OkHttp connections against DNS rebinding.
 */
object GatewayUrlValidator {
    private val loopbackHosts = setOf("127.0.0.1", "localhost", "::1")
    private val blockedMetadataHosts = setOf(
        "metadata",
        "metadata.google.internal",
        "metadata.goog",
        "instance-data",
    )

    fun validate(
        rawUrl: String,
        allowQueryAndFragment: Boolean = false,
    ): GatewayUrlValidation {
        val rawTrimmed = rawUrl.trim()
        val trimmed = if (allowQueryAndFragment) rawTrimmed else rawTrimmed.trimEnd('/')
        if (trimmed.isEmpty()) {
            return GatewayUrlValidation.Invalid("Gateway base URL cannot be empty.")
        }
        val parsed: HttpUrl = trimmed.toHttpUrlOrNull()
            ?: return GatewayUrlValidation.Invalid("Enter a valid HTTP/HTTPS URL (e.g. http://127.0.0.1:8787 or https://gateway.example.com).")

        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return GatewayUrlValidation.Invalid("Embedded URL credentials are not permitted.")
        }
        if (!allowQueryAndFragment && (parsed.query != null || parsed.fragment != null)) {
            return GatewayUrlValidation.Invalid("Gateway base URL must not contain query parameters or fragments.")
        }

        val host = parsed.host.lowercase().trim('[', ']')
        val isLoopback = host in loopbackHosts

        if (parsed.scheme == "http" && !isLoopback) {
            return GatewayUrlValidation.Invalid(
                "Remote gateway endpoints must use HTTPS. Plain HTTP is allowed only for loopback (127.0.0.1 / localhost)."
            )
        }
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return GatewayUrlValidation.Invalid("Only HTTPS (or loopback HTTP) is supported.")
        }

        if (!isLoopback) {
            if (host in blockedMetadataHosts || host.endsWith(".internal")) {
                return GatewayUrlValidation.Invalid("Internal or cloud metadata hosts are blocked by SSRF policy.")
            }
            if (isBlockedLiteralIpHost(host)) {
                return GatewayUrlValidation.Invalid(
                    "Private, link-local, multicast, or unspecified IP addresses are blocked by SSRF policy."
                )
            }
        }

        val basePath = if (allowQueryAndFragment && (!parsed.encodedQuery.isNullOrEmpty() || parsed.encodedFragment != null)) {
            parsed.encodedPath
        } else {
            parsed.encodedPath.trimEnd('/')
        }
        val portSegment = if ((parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)
        ) {
            ""
        } else {
            ":${parsed.port}"
        }
        val normalizedHost = if (host.contains(':')) "[$host]" else host
        val querySuffix = if (allowQueryAndFragment && parsed.encodedQuery != null) "?${parsed.encodedQuery}" else ""
        val fragmentSuffix = if (allowQueryAndFragment && parsed.encodedFragment != null) "#${parsed.encodedFragment}" else ""
        val normalized = "${parsed.scheme}://$normalizedHost$portSegment$basePath$querySuffix$fragmentSuffix"
        return GatewayUrlValidation.Valid(normalizedBaseUrl = normalized, isLoopback = isLoopback)
    }

    /**
     * Resolves DNS for non-loopback hosts and rejects any resolution to loopback, private,
     * link-local, multicast, unspecified, or CGNAT IP ranges (anti-DNS-rebinding).
     */
    fun validateResolvedHost(
        rawUrl: String,
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): GatewayUrlValidation {
        val base = validate(rawUrl)
        if (base is GatewayUrlValidation.Invalid || (base as GatewayUrlValidation.Valid).isLoopback) {
            return base
        }
        val host = base.normalizedBaseUrl.toHttpUrlOrNull()?.host?.trim('[', ']')
            ?: return GatewayUrlValidation.Invalid("Unable to parse host from URL.")
        val addresses = try {
            resolver(host)
        } catch (_: UnknownHostException) {
            return GatewayUrlValidation.Invalid("Unable to resolve host: $host")
        }
        if (addresses.isEmpty()) {
            return GatewayUrlValidation.Invalid("DNS returned no addresses for $host")
        }
        if (addresses.any(::isBlockedRemoteAddress)) {
            return GatewayUrlValidation.Invalid(
                "Blocked SSRF/DNS-rebinding target: '$host' resolved to a local or private IP."
            )
        }
        return base
    }

    /**
     * Socket-time OkHttp [Dns] guard preventing DNS rebinding from resolving remote hostnames
     * into loopback, link-local metadata, RFC1918 private, or CGNAT addresses.
     */
    fun ssrfGuardDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val clean = hostname.trim().lowercase().trim('[', ']')
            val resolved = delegate.lookup(hostname)
            if (clean in loopbackHosts) {
                return resolved
            }
            if (clean in blockedMetadataHosts || clean.endsWith(".internal")) {
                throw UnknownHostException("Blocked SSRF internal/metadata host: $hostname")
            }
            if (resolved.any(::isBlockedRemoteAddress)) {
                throw UnknownHostException("Blocked SSRF/DNS-rebinding IP resolution for host: $hostname")
            }
            return resolved
        }
    }

    fun isBlockedRemoteAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address
        if (addr is Inet4Address && bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 0.0.0.0/8 ("this network")
            if (b0 == 0) return true
            // 100.64.0.0/10 (Carrier-Grade NAT shared space)
            if (b0 == 100 && b1 in 64..127) return true
            // 169.254.0.0/16 (Link-local / cloud metadata)
            if (b0 == 169 && b1 == 254) return true
            // 198.18.0.0/15 (Benchmarking internal net)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
        }
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xFF
            // fc00::/7 Unique Local IPv6
            if ((first and 0xFE) == 0xFC) return true
        }
        return false
    }

    private fun isBlockedLiteralIpHost(host: String): Boolean {
        if (host == "::" || host == "::0" || host == "0.0.0.0") return true
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val b0 = ipv4Parts[0].toInt()
            val b1 = ipv4Parts[1].toInt()
            return b0 == 0 ||
                b0 == 10 ||
                b0 == 127 ||
                (b0 == 100 && b1 in 64..127) ||
                (b0 == 169 && b1 == 254) ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 168) ||
                b0 >= 224
        }
        if (host.contains(':')) {
            val lower = host.lowercase()
            if (lower.startsWith("fe80:") ||
                lower.startsWith("fc") ||
                lower.startsWith("fd") ||
                lower.startsWith("ff")
            ) {
                return true
            }
        }
        return false
    }
}
