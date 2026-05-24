package eu.buney.kiche.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

@OptIn(ExperimentalForeignApi::class)
internal actual fun resolveHostPreferIpv4(host: String): ByteArray? = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_DGRAM

    val result = allocPointerTo<addrinfo>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped null

    try {
        // Prefer an IPv4 address; remember the first IPv6 as a fallback for v6-only hosts.
        var v6: ByteArray? = null
        var node = result.value
        while (true) {
            val info = (node ?: break).pointed
            val sa = info.ai_addr
            if (sa != null) {
                when (info.ai_family) {
                    AF_INET -> {
                        val out = ByteArray(4)
                        out.usePinned {
                            memcpy(it.addressOf(0), sa.reinterpret<sockaddr_in>().pointed.sin_addr.ptr, 4.convert())
                        }
                        return@memScoped out
                    }
                    AF_INET6 -> if (v6 == null) {
                        val out = ByteArray(16)
                        out.usePinned {
                            memcpy(it.addressOf(0), sa.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr, 16.convert())
                        }
                        v6 = out
                    }
                }
            }
            node = info.ai_next
        }
        v6
    } finally {
        freeaddrinfo(result.value)
    }
}
