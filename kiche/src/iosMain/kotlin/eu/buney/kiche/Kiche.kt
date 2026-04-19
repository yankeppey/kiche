@file:OptIn(ExperimentalForeignApi::class)

package eu.buney.kiche

import kotlinx.cinterop.*
import platform.posix.fprintf
import platform.posix.stderr
import quiche.c.*

actual object Kiche {
    actual fun quicheVersion(): String = quiche_version()?.toKString() ?: "unknown"

    actual fun versionIsSupported(version: UInt): Boolean = quiche_version_is_supported(version)

    actual fun enableDebugLogging(): Boolean =
        quiche_enable_debug_logging(staticCFunction { line, _ ->
            if (line != null) fprintf(stderr, "%s\n", line)
        }, null) == 0

    actual fun headerInfo(buf: ByteArray, len: Int, dcil: Int): KicheHeaderInfo = memScoped {
        val version = alloc<UIntVar>()
        val type = alloc<UByteVar>()
        val scid = ByteArray(QUICHE_MAX_CONN_ID_LEN.toInt())
        val scidLen = alloc<ULongVar> { value = scid.size.toULong() }
        val dcid = ByteArray(QUICHE_MAX_CONN_ID_LEN.toInt())
        val dcidLen = alloc<ULongVar> { value = dcid.size.toULong() }
        val token = ByteArray(512)
        val tokenLen = alloc<ULongVar> { value = token.size.toULong() }

        val rc = buf.usePinned { pinBuf ->
            scid.usePinned { pinScid ->
                dcid.usePinned { pinDcid ->
                    token.usePinned { pinToken ->
                        quiche_header_info(
                            pinBuf.addressOf(0).reinterpret(), len.toULong(), dcil.toULong(),
                            version.ptr, type.ptr,
                            pinScid.addressOf(0).reinterpret(), scidLen.ptr,
                            pinDcid.addressOf(0).reinterpret(), dcidLen.ptr,
                            pinToken.addressOf(0).reinterpret(), tokenLen.ptr,
                        )
                    }
                }
            }
        }
        if (rc < 0) KicheException.check(rc)

        KicheHeaderInfo(
            version = version.value,
            type = KichePacketType.fromValue(type.value.toInt()),
            scid = scid.copyOf(scidLen.value.toInt()),
            dcid = dcid.copyOf(dcidLen.value.toInt()),
            token = token.copyOf(tokenLen.value.toInt()),
        )
    }

    actual fun negotiateVersion(scid: ByteArray, dcid: ByteArray, out: ByteArray): Int {
        val written = scid.usePinned { pinScid ->
            dcid.usePinned { pinDcid ->
                out.usePinned { pinOut ->
                    quiche_negotiate_version(
                        pinScid.addressOf(0).reinterpret(), scid.size.toULong(),
                        pinDcid.addressOf(0).reinterpret(), dcid.size.toULong(),
                        pinOut.addressOf(0).reinterpret(), out.size.toULong(),
                    )
                }
            }
        }
        if (written < 0) KicheException.check(written.toInt())
        return written.toInt()
    }

    actual fun retry(
        scid: ByteArray, dcid: ByteArray, newScid: ByteArray,
        token: ByteArray, version: UInt, out: ByteArray,
    ): Int {
        val written = scid.usePinned { pinScid ->
            dcid.usePinned { pinDcid ->
                newScid.usePinned { pinNewScid ->
                    token.usePinned { pinToken ->
                        out.usePinned { pinOut ->
                            quiche_retry(
                                pinScid.addressOf(0).reinterpret(), scid.size.toULong(),
                                pinDcid.addressOf(0).reinterpret(), dcid.size.toULong(),
                                pinNewScid.addressOf(0).reinterpret(), newScid.size.toULong(),
                                pinToken.addressOf(0).reinterpret(), token.size.toULong(),
                                version,
                                pinOut.addressOf(0).reinterpret(), out.size.toULong(),
                            )
                        }
                    }
                }
            }
        }
        if (written < 0) KicheException.check(written.toInt())
        return written.toInt()
    }
}
