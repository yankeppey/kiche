@file:OptIn(ExperimentalForeignApi::class)

package eu.buney.kiche

import kotlinx.cinterop.*
import quiche.c.*

actual class KicheConfig actual constructor(version: UInt) : AutoCloseable {
    internal var ptr: COpaquePointer? = quiche_config_new(version)
        ?: error("Failed to create quiche_config")

    private fun cfg(): CPointer<cnames.structs.quiche_config> =
        (ptr ?: error("KicheConfig is closed")).reinterpret()

    actual fun loadCertChainFromPemFile(path: String) {
        KicheException.check(quiche_config_load_cert_chain_from_pem_file(cfg(), path))
    }

    actual fun loadPrivKeyFromPemFile(path: String) {
        KicheException.check(quiche_config_load_priv_key_from_pem_file(cfg(), path))
    }

    actual fun loadVerifyLocationsFromFile(path: String) {
        KicheException.check(quiche_config_load_verify_locations_from_file(cfg(), path))
    }

    actual fun loadVerifyLocationsFromDirectory(path: String) {
        KicheException.check(quiche_config_load_verify_locations_from_directory(cfg(), path))
    }

    actual fun verifyPeer(verify: Boolean) = quiche_config_verify_peer(cfg(), verify)
    actual fun grease(enabled: Boolean) = quiche_config_grease(cfg(), enabled)
    actual fun discoverPmtu(enabled: Boolean) = quiche_config_discover_pmtu(cfg(), enabled)
    actual fun logKeys() = quiche_config_log_keys(cfg())
    actual fun enableEarlyData() = quiche_config_enable_early_data(cfg())

    actual fun setApplicationProtos(protos: ByteArray) {
        protos.usePinned { pinned ->
            KicheException.check(
                quiche_config_set_application_protos(
                    cfg(),
                    pinned.addressOf(0).reinterpret(),
                    protos.size.toULong()
                )
            )
        }
    }

    actual fun setMaxAmplificationFactor(factor: Long) =
        quiche_config_set_max_amplification_factor(cfg(), factor.toULong())

    actual fun setMaxIdleTimeout(millis: Long) =
        quiche_config_set_max_idle_timeout(cfg(), millis.toULong())

    actual fun setMaxRecvUdpPayloadSize(size: Long) =
        quiche_config_set_max_recv_udp_payload_size(cfg(), size.toULong())

    actual fun setMaxSendUdpPayloadSize(size: Long) =
        quiche_config_set_max_send_udp_payload_size(cfg(), size.toULong())

    actual fun setInitialMaxData(v: Long) =
        quiche_config_set_initial_max_data(cfg(), v.toULong())

    actual fun setInitialMaxStreamDataBidiLocal(v: Long) =
        quiche_config_set_initial_max_stream_data_bidi_local(cfg(), v.toULong())

    actual fun setInitialMaxStreamDataBidiRemote(v: Long) =
        quiche_config_set_initial_max_stream_data_bidi_remote(cfg(), v.toULong())

    actual fun setInitialMaxStreamDataUni(v: Long) =
        quiche_config_set_initial_max_stream_data_uni(cfg(), v.toULong())

    actual fun setInitialMaxStreamsBidi(v: Long) =
        quiche_config_set_initial_max_streams_bidi(cfg(), v.toULong())

    actual fun setInitialMaxStreamsUni(v: Long) =
        quiche_config_set_initial_max_streams_uni(cfg(), v.toULong())

    actual fun setAckDelayExponent(v: Long) =
        quiche_config_set_ack_delay_exponent(cfg(), v.toULong())

    actual fun setMaxAckDelay(v: Long) =
        quiche_config_set_max_ack_delay(cfg(), v.toULong())

    actual fun setDisableActiveMigration(disabled: Boolean) =
        quiche_config_set_disable_active_migration(cfg(), disabled)

    actual fun setCcAlgorithm(algo: KicheCcAlgorithm) =
        quiche_config_set_cc_algorithm(cfg(), algo.value.toUInt())

    actual fun setInitialCongestionWindowPackets(packets: Long) =
        quiche_config_set_initial_congestion_window_packets(cfg(), packets.toULong())

    actual fun enableHystart(enabled: Boolean) =
        quiche_config_enable_hystart(cfg(), enabled)

    actual fun enablePacing(enabled: Boolean) =
        quiche_config_enable_pacing(cfg(), enabled)

    actual fun setMaxPacingRate(v: Long) =
        quiche_config_set_max_pacing_rate(cfg(), v.toULong())

    actual fun enableDgram(enabled: Boolean, recvQueueLen: Long, sendQueueLen: Long) =
        quiche_config_enable_dgram(cfg(), enabled, recvQueueLen.toULong(), sendQueueLen.toULong())

    actual fun setMaxConnectionWindow(v: Long) =
        quiche_config_set_max_connection_window(cfg(), v.toULong())

    actual fun setMaxStreamWindow(v: Long) =
        quiche_config_set_max_stream_window(cfg(), v.toULong())

    actual fun setActiveConnectionIdLimit(v: Long) =
        quiche_config_set_active_connection_id_limit(cfg(), v.toULong())

    actual fun setStatelessResetToken(token: ByteArray) {
        require(token.size == 16) { "Stateless reset token must be 16 bytes" }
        token.usePinned { pinned ->
            quiche_config_set_stateless_reset_token(cfg(), pinned.addressOf(0).reinterpret())
        }
    }

    actual fun setDisableDcidReuse(disabled: Boolean) =
        quiche_config_set_disable_dcid_reuse(cfg(), disabled)

    actual fun setTicketKey(key: ByteArray) {
        key.usePinned { pinned ->
            KicheException.check(
                quiche_config_set_ticket_key(cfg(), pinned.addressOf(0).reinterpret(), key.size.toULong())
            )
        }
    }

    actual fun setEnableCubicIdleRestartFix(enabled: Boolean) =
        quiche_config_set_enable_cubic_idle_restart_fix(cfg(), enabled)

    actual override fun close() {
        ptr?.let {
            quiche_config_free(it.reinterpret())
            ptr = null
        }
    }
}
