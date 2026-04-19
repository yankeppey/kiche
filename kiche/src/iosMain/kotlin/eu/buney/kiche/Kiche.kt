package eu.buney.kiche

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.fprintf
import platform.posix.stderr
import quiche.c.quiche_enable_debug_logging
import quiche.c.quiche_version
import quiche.c.quiche_version_is_supported

actual object Kiche {
    @OptIn(ExperimentalForeignApi::class)
    actual fun quicheVersion(): String = quiche_version()?.toKString() ?: "unknown"

    @OptIn(ExperimentalForeignApi::class)
    actual fun versionIsSupported(version: UInt): Boolean = quiche_version_is_supported(version)

    @OptIn(ExperimentalForeignApi::class)
    actual fun enableDebugLogging(): Boolean =
        quiche_enable_debug_logging(staticCFunction { line, _ ->
            if (line != null) fprintf(stderr, "%s\n", line)
        }, null) == 0
}
