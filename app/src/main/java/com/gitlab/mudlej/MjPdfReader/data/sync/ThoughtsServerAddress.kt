// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import java.net.URI
import java.util.Locale

/** Rules for the Thoughts server address and for the hosts that Thoughts links name. */
object ThoughtsServerAddress {
    /**
     * The public server this build names in citation links (Gradle property `thoughtsPublicServerUrl`,
     * default [EXAMPLE_BASE_URL]). A phone talks to the server saved in Settings; this address is
     * only the target of [PublicServerMigration].
     */
    val PUBLIC_BASE_URL: String = BuildConfig.THOUGHTS_PUBLIC_SERVER_URL.trim().trimEnd('/')
    val PUBLIC_HOST: String = hostOf(PUBLIC_BASE_URL) ?: "thoughts.example.org"

    /**
     * Former hosts of that server (Gradle property `thoughtsLegacyServerHosts`, comma-separated,
     * default none): links that name them keep opening in Thoughts.
     */
    val LEGACY_HOSTS: Set<String> = parseHosts(BuildConfig.THOUGHTS_LEGACY_SERVER_HOSTS)

    private const val TAILSCALE_HOST_SUFFIX = ".ts.net"

    /** Shown as a placeholder in the empty server field; never saved or contacted. */
    const val EXAMPLE_BASE_URL = "https://thoughts.example.org"

    /**
     * The address the server dialog starts with: the saved one, else blank. A new install must not
     * point at somebody else's server, so there is no built-in default.
     */
    fun dialogPrefill(configuredBaseUrl: String?): String = configuredBaseUrl?.trim().orEmpty()

    /**
     * Returns [raw] without surrounding whitespace or trailing slashes, or throws
     * [IllegalArgumentException] when it is not an `https` address with a host and without
     * user info, query or fragment.
     */
    fun normalize(raw: String): String {
        val normalized = raw.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(uri != null) { "The server address is not a valid URL" }
        require(uri.scheme == "https" || isLocalTestServer(normalized, uri)) { "The server address must start with https://" }
        require(!uri.host.isNullOrBlank()) { "The server address must include a host name" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "The server address must not include a user name, query or fragment"
        }
        return normalized
    }

    /**
     * Thoughts Test built with -PthoughtsTestServerUrl talks plain HTTP to a hermetic server on the
     * emulator's host (10.0.2.2); every other build and address needs https.
     */
    private fun isLocalTestServer(normalized: String, uri: URI): Boolean =
        com.gitlab.mudlej.MjPdfReader.BuildConfig.IS_TEST_ENVIRONMENT && uri.scheme == "http" &&
            uri.host == "10.0.2.2" && normalized == com.gitlab.mudlej.MjPdfReader.BuildConfig.TEST_SERVER_URL

    /** The lower-case host of [baseUrl], or null when it has none. */
    fun hostOf(baseUrl: String?): String? = baseUrl
        ?.let { runCatching { URI(it.trim()).host }.getOrNull() }
        ?.trimEnd('.')
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)

    /**
     * Only a Tailscale address needs the phone's VPN before a connection is tried: a `*.ts.net`
     * name, or an address literal in the ranges Tailscale gives its devices
     * (100.64.0.0/10 and fd7a:115c:a1e0::/48).
     */
    fun requiresVpn(baseUrl: String?): Boolean {
        val host = hostOf(baseUrl) ?: return false
        return host.endsWith(TAILSCALE_HOST_SUFFIX) || isTailscaleAddressLiteral(host)
    }

    private fun isTailscaleAddressLiteral(host: String): Boolean {
        ipv4Octets(host)?.let { return it[0] == 100 && it[1] in 64..127 }
        val groups = ipv6Groups(host) ?: return false
        return groups[0] == 0xfd7a && groups[1] == 0x115c && groups[2] == 0xa1e0
    }

    /** True when [host] names this device itself: `localhost`, 127.0.0.0/8 or `::1`. No lookup is made. */
    fun isLoopbackHost(host: String?): Boolean {
        val name = host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return false
        if (name == "localhost" || name.endsWith(".localhost")) return true
        ipv4Octets(name)?.let { return it[0] == 127 }
        val groups = ipv6Groups(name) ?: return false
        return groups.dropLast(1).all { it == 0 } && groups.last() == 1
    }

    /** The four numbers of a dotted IPv4 literal, or null for anything else (such as a host name). */
    private fun ipv4Octets(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            part.takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= 255 } ?: return null
        }
    }

    /** The eight groups of an IPv6 literal (with or without the URL's brackets), or null. */
    private fun ipv6Groups(host: String): List<Int>? {
        val literal = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (':' !in literal || literal.any { it != ':' && Character.digit(it, 16) < 0 }) return null
        fun groups(text: String): List<Int>? = if (text.isEmpty()) emptyList() else text.split(':').map { group ->
            group.takeIf { it.length in 1..4 }?.toInt(16) ?: return null
        }
        val halves = literal.split("::")
        return when (halves.size) {
            1 -> groups(literal)?.takeIf { it.size == 8 }
            2 -> {
                val head = groups(halves[0]) ?: return null
                val tail = groups(halves[1]) ?: return null
                if (head.size + tail.size > 7) return null
                head + List(8 - head.size - tail.size) { 0 } + tail
            }
            else -> null
        }
    }

    /** False only when the server needs Tailscale and no VPN is active. */
    fun vpnPrecheckPasses(baseUrl: String?, vpnActive: () -> Boolean): Boolean =
        !requiresVpn(baseUrl) || vpnActive()

    /** Lower-case host names from a comma- or space-separated list; blanks are dropped. */
    internal fun parseHosts(raw: String?): Set<String> = raw.orEmpty()
        .split(',', ' ', '\n', '\t')
        .map { it.trim().trimEnd('.').lowercase(Locale.ROOT) }
        .filter(String::isNotEmpty)
        .toSet()

    /** The host that generated citation links name: the configured server's, else [publicHost]. */
    fun citationLinkHost(baseUrl: String?, publicHost: String = PUBLIC_HOST): String = hostOf(baseUrl) ?: publicHost

    /**
     * The token that the server dialog saves: the entered one, or the saved one when the field is
     * left blank, so that changing only the address keeps the device token.
     */
    fun tokenToSave(enteredToken: String?, savedToken: String?): String =
        enteredToken?.trim().orEmpty().ifEmpty { savedToken.orEmpty() }

    /**
     * The origin a device token is entered for: scheme, host and port of [baseUrl] in lower case,
     * with the scheme's default port written out, so that a trailing slash, another path or an
     * explicit `:443` name the same origin. Null when the address has no scheme or host.
     */
    fun originOf(baseUrl: String?): String? {
        val uri = baseUrl?.trim()?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = hostOf(baseUrl) ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return null
        }
        return "$scheme://$host:$port"
    }

    /**
     * The token that the server dialog saves for [enteredAddress], or null when it has to be asked
     * for. A typed token is always the one saved. A blank field keeps the saved token only while
     * the address stays on the origin that token was entered for ([savedAddress]): the saved token
     * is never sent to another server.
     */
    fun tokenForAddress(
        enteredToken: String?,
        savedToken: String?,
        savedAddress: String?,
        enteredAddress: String,
    ): String? {
        enteredToken?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        val saved = savedToken?.takeIf(String::isNotBlank) ?: return null
        val savedOrigin = originOf(savedAddress) ?: return null
        return saved.takeIf { savedOrigin == originOf(enteredAddress) }
    }

    /** What the secret store does with a stored token, given the origin it was entered for. */
    enum class StoredToken {
        /** The token belongs to the saved address. */
        USE,
        /** A token saved before tokens were bound to an address: it belongs to the address saved with it. */
        BIND_TO_SAVED_ADDRESS,
        /** The token was entered for another server: it is not used for this address and is removed. */
        FORGET,
    }

    fun storedTokenUse(savedAddress: String, tokenOrigin: String?): StoredToken = when (tokenOrigin) {
        null -> StoredToken.BIND_TO_SAVED_ADDRESS
        originOf(savedAddress) -> StoredToken.USE
        else -> StoredToken.FORGET
    }

    /** Hosts whose `https` citation links Thoughts opens. */
    fun citationLinkHosts(
        configuredBaseUrl: String? = null,
        publicHost: String = PUBLIC_HOST,
        legacyHosts: Set<String> = LEGACY_HOSTS,
    ): Set<String> = setOf(publicHost) + legacyHosts + setOfNotNull(hostOf(configuredBaseUrl))
}
