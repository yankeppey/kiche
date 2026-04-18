package eu.buney.kiche

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import quiche.c.quiche_version
import quiche.c.quiche_version_is_supported

actual object Kiche {
    @OptIn(ExperimentalForeignApi::class)
    actual fun quicheVersion(): String = quiche_version()?.toKString() ?: "unknown"

    @OptIn(ExperimentalForeignApi::class)
    actual fun versionIsSupported(version: UInt): Boolean = quiche_version_is_supported(version)
}
