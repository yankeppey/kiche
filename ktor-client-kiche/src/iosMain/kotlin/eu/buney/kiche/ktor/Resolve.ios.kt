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
internal actual fun resolveHostAddresses(host: String): List<ByteArray> = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_DGRAM

    val result = allocPointerTo<addrinfo>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped emptyList()

    try {
        // Collect every address in the order getaddrinfo returns them (IPv6-first per RFC 6724).
        val out = mutableListOf<ByteArray>()
        var node = result.value
        while (true) {
            val info = (node ?: break).pointed
            val sa = info.ai_addr
            if (sa != null) {
                when (info.ai_family) {
                    AF_INET -> {
                        val bytes = ByteArray(4)
                        bytes.usePinned {
                            memcpy(it.addressOf(0), sa.reinterpret<sockaddr_in>().pointed.sin_addr.ptr, 4.convert())
                        }
                        out += bytes
                    }
                    AF_INET6 -> {
                        val bytes = ByteArray(16)
                        bytes.usePinned {
                            memcpy(it.addressOf(0), sa.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr, 16.convert())
                        }
                        out += bytes
                    }
                }
            }
            node = info.ai_next
        }
        out
    } finally {
        freeaddrinfo(result.value)
    }
}
