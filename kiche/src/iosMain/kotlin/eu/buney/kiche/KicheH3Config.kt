@file:OptIn(ExperimentalForeignApi::class)

package eu.buney.kiche

import kotlinx.cinterop.*
import quiche.c.*

actual class KicheH3Config actual constructor() : AutoCloseable {
    internal var ptr: COpaquePointer? = quiche_h3_config_new()
        ?: error("Failed to create quiche_h3_config")

    private fun cfg(): CPointer<cnames.structs.quiche_h3_config> =
        (ptr ?: error("KicheH3Config is closed")).reinterpret()

    actual fun setMaxFieldSectionSize(v: Long) =
        quiche_h3_config_set_max_field_section_size(cfg(), v.toULong())

    actual fun setQpackMaxTableCapacity(v: Long) =
        quiche_h3_config_set_qpack_max_table_capacity(cfg(), v.toULong())

    actual fun setQpackBlockedStreams(v: Long) =
        quiche_h3_config_set_qpack_blocked_streams(cfg(), v.toULong())

    actual fun enableExtendedConnect(enabled: Boolean) =
        quiche_h3_config_enable_extended_connect(cfg(), enabled)

    actual override fun close() {
        ptr?.let {
            quiche_h3_config_free(it.reinterpret())
            ptr = null
        }
    }
}
