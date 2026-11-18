package tech.httptoolkit.android.manual

import tech.httptoolkit.android.Constants
import tech.httptoolkit.android.portfilter.MAX_PORT
import tech.httptoolkit.android.portfilter.MIN_PORT

/**
 * A proxy address as entered by the user, before it's resolved and validated over the network.
 */
data class ParsedProxyAddress(
    /**
     * The host to connect to: an IPv4/IPv6 literal or a hostname, without brackets or port.
     */
    val host: String,

    /**
     * The port included in the input (e.g. from "192.168.0.10:8000"), if there was one.
     * Otherwise connections should use whatever port the user entered separately.
     */
    val port: Int?
)

/**
 * Parses the address field of the manual connection screen.
 *
 * This is deliberately forgiving, since people paste addresses from all sorts of places: it
 * accepts "192.168.0.10", "192.168.0.10:8000", "http://192.168.0.10:8000/", "[::1]:8000" and
 * bare IPv6 literals, but it rejects anything that can't be a host at all.
 */
object ProxyAddressParser {

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    // Letters/digits for hostnames, dots and dashes for names/IPv4. Underscores are technically
    // invalid in hostnames, but they do appear in local network names in practice.
    private val VALID_HOSTNAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")

    // IPv6 literals, with an optional zone ID (e.g. fe80::1%wlan0).
    private val VALID_IPV6_REGEX = Regex("^[0-9a-fA-F:.]+(?:%[a-zA-Z0-9._-]+)?$")

    private val CONNECT_URL_REGEX = Regex(
        "^(?:https?://)?android\\.httptoolkit\\.tech/connect(?:[/?#]|$)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns the parsed host & port, or null if the input isn't a usable address.
     */
    fun parse(raw: String): ParsedProxyAddress? {
        val value = raw.trim().replace(SCHEME_REGEX, "")

        // Drop any path, query or fragment (e.g. from a pasted URL). This happens before we look
        // for a port so that "host:8000/some/path" still works.
        val authority = value
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')

        if (authority.isEmpty() || authority.any { it.isWhitespace() }) return null
        if ('@' in authority) return null // Credentials aren't supported, and likely a mistake

        val host: String
        var portText: String? = null

        if (authority.startsWith("[")) {
            // Bracketed IPv6 literal, optionally followed by a port: "[::1]" or "[::1]:8000"
            val closingBracket = authority.indexOf(']')
            if (closingBracket < 0) return null

            host = authority.substring(1, closingBracket)
            if (!host.contains(':')) return null // Brackets are only meaningful for IPv6

            val remainder = authority.substring(closingBracket + 1)
            if (remainder.isNotEmpty()) {
                if (!remainder.startsWith(":")) return null
                portText = remainder.substring(1)
            }
        } else {
            when (authority.count { it == ':' }) {
                0 -> host = authority
                1 -> {
                    host = authority.substringBefore(':')
                    portText = authority.substringAfter(':')
                }
                // Multiple colons with no brackets: a bare IPv6 literal, which has no port
                else -> host = authority
            }
        }

        if (host.isEmpty()) return null
        if (host.contains(':')) {
            if (!VALID_IPV6_REGEX.matches(host)) return null
        } else if (!VALID_HOSTNAME_REGEX.matches(host)) {
            return null
        }

        val port = if (portText != null) {
            if (portText.isEmpty()) return null
            portText.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: return null
        } else {
            null
        }

        return ParsedProxyAddress(host, port)
    }

    /**
     * True if the input is an HTTP Toolkit QR code URL. Those can be handled (and validated) by
     * the QR code flow, and would give a very confusing error if treated as a plain address.
     */
    fun isConnectUrl(raw: String): Boolean {
        val value = raw.trim()
        return value.startsWith(Constants.QR_CODE_URL_PREFIX, ignoreCase = true) ||
                CONNECT_URL_REGEX.find(value) != null
    }
}
