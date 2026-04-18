package eu.buney.kiche.ktor.h3adaptive

import io.ktor.util.date.*

/**
 * A parsed Alt-Svc entry (RFC 7838).
 *
 * Example header: `h3=":443"; ma=2592000, h3-29=":443"; ma=2592000`
 */
public data class AltSvcEntry(
    /** ALPN protocol identifier, e.g. "h3", "h3-29". */
    val protocolId: String,
    /** Alternative authority host. Empty string means same host as the origin. */
    val host: String,
    /** Alternative authority port. */
    val port: Int,
    /** Max-age in seconds (default 24 hours per RFC 7838). */
    val maxAge: Long = 86400L,
    /** Timestamp (epoch millis) when this entry was received. */
    val receivedAt: Long = GMTDate().timestamp,
) {
    /** Whether this entry has expired based on [maxAge]. */
    public fun isExpired(): Boolean =
        GMTDate().timestamp - receivedAt > maxAge * 1000L
}

/**
 * Parses an `Alt-Svc` header value into a list of [AltSvcEntry] entries.
 *
 * Handles the `clear` directive (returns empty list) and multiple comma-separated entries.
 * Entries that fail to parse are silently skipped.
 *
 * Reference: RFC 7838, Section 3.
 */
public fun parseAltSvcHeader(headerValue: String): List<AltSvcEntry> {
    val trimmed = headerValue.trim()
    if (trimmed == "clear") return emptyList()

    return trimmed.split(',').mapNotNull { entry ->
        parseAltSvcEntry(entry.trim())
    }
}

/**
 * Parses a single Alt-Svc entry like `h3=":443"; ma=2592000`.
 */
private fun parseAltSvcEntry(entry: String): AltSvcEntry? {
    // Split into the protocol-authority part and parameters
    val parts = entry.split(';').map { it.trim() }
    if (parts.isEmpty()) return null

    // Parse protocol="authority"
    val protocolAuthority = parts[0]
    val eqIndex = protocolAuthority.indexOf('=')
    if (eqIndex < 0) return null

    val protocolId = protocolAuthority.substring(0, eqIndex).trim()
    val authorityQuoted = protocolAuthority.substring(eqIndex + 1).trim()

    // Remove surrounding quotes
    val authority = authorityQuoted.removeSurrounding("\"")

    // Parse host:port from authority
    val (host, port) = parseAuthority(authority) ?: return null

    // Parse parameters (ma=...)
    var maxAge = 86400L
    for (i in 1 until parts.size) {
        val param = parts[i]
        val paramEq = param.indexOf('=')
        if (paramEq < 0) continue
        val key = param.substring(0, paramEq).trim().lowercase()
        val value = param.substring(paramEq + 1).trim()
        if (key == "ma") {
            maxAge = value.toLongOrNull() ?: 86400L
        }
    }

    return AltSvcEntry(
        protocolId = protocolId,
        host = host,
        port = port,
        maxAge = maxAge,
    )
}

/**
 * Parses authority string like ":443" or "alt.example.com:443".
 * Returns (host, port) where host may be empty (meaning same-host).
 */
private fun parseAuthority(authority: String): Pair<String, Int>? {
    val lastColon = authority.lastIndexOf(':')
    if (lastColon < 0) return null

    val host = authority.substring(0, lastColon)
    val port = authority.substring(lastColon + 1).toIntOrNull() ?: return null
    return host to port
}
