package eu.buney.kiche

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import quiche.c.quiche_version

actual object Kiche {
    @OptIn(ExperimentalForeignApi::class)
    actual fun quicheVersion(): String = quiche_version()?.toKString() ?: "unknown"
}
