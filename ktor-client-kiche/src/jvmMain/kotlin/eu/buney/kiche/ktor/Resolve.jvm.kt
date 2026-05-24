package eu.buney.kiche.ktor

import java.net.InetAddress

internal actual fun resolveHostAddresses(host: String): List<ByteArray> =
    try {
        InetAddress.getAllByName(host).map { it.address }
    } catch (_: Exception) {
        emptyList()
    }
