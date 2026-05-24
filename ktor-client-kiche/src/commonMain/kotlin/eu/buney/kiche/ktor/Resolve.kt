package eu.buney.kiche.ktor

/**
 * Resolves [host] to its IP addresses — 4 bytes for IPv4, 16 bytes for IPv6 — in the OS resolver's
 * order (IPv6-first per RFC 6724 on Apple and most platforms). Returns an empty list if resolution
 * fails.
 *
 * The caller tries the addresses in order, falling back to the next on a failed QUIC handshake (see
 * the connect sites in [KicheEndpoint]). This deliberately bakes in **no** IP-family preference —
 * picking IPv4 unconditionally would be wrong for a general client (Apple requires IPv6-only
 * support) — but still survives a broken/unroutable family.
 *
 * ktor-network's `InetSocketAddress.resolveAddress()` only exposes the *first* resolved address with
 * no fallback, which is why we resolve the full list ourselves.
 */
internal expect fun resolveHostAddresses(host: String): List<ByteArray>
