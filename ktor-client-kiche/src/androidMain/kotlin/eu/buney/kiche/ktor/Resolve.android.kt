package eu.buney.kiche.ktor

import java.net.Inet4Address
import java.net.InetAddress

internal actual fun resolveHostPreferIpv4(host: String): ByteArray? =
    try {
        val all = InetAddress.getAllByName(host)
        (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.address
    } catch (_: Exception) {
        null
    }
