package eu.buney.kiche.ktor

/**
 * Resolves [host] to raw IP address bytes (4 = IPv4, 16 = IPv6), preferring IPv4.
 *
 * ktor-network's `InetSocketAddress.resolveAddress()` returns whatever the OS resolver lists first.
 * On Apple platforms that is the IPv6 record, but the iOS Simulator can't route to the public
 * internet over IPv6, so the QUIC UDP send fails with EHOSTUNREACH ("No route to host"). Preferring
 * IPv4 keeps QUIC working there and matches the JVM resolver's default ordering.
 */
internal expect fun resolveHostPreferIpv4(host: String): ByteArray?
