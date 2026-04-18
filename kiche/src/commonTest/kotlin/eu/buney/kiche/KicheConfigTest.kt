/*
 * Tier 1 tests: KicheConfig and basic API.
 *
 * These tests verify config creation, all setters, and error handling
 * without creating any connections. Ported from quiche's Rust tests:
 * - tests.rs:config_version_reserved()
 * - tests.rs:config_set_cc_algorithm_name()
 * - tests.rs:configuration_values_are_limited_to_max_varint()
 */
package eu.buney.kiche

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class KicheConfigTest {

    //region Version and lifecycle

    @Test
    fun testQuicheVersion() {
        val version = Kiche.quicheVersion()
        assertTrue(version.isNotEmpty(), "quiche version should not be empty")
        assertTrue(version.contains("."), "version should contain a dot: $version")
        println("    quiche version: $version ... OK.")
    }

    @Test
    fun testConfigCreateWithDefaultVersion() {
        val config = KicheConfig()
        config.close()
        println("    KicheConfig() default version ... OK.")
    }

    @Test
    fun testConfigCreateWithExplicitVersion() {
        val config = KicheConfig(QUICHE_PROTOCOL_VERSION)
        config.close()
        println("    KicheConfig(QUICHE_PROTOCOL_VERSION) ... OK.")
    }

    /**
     * Ported from tests.rs:config_version_reserved()
     * Reserved versions (with 0x?a?a?a?a pattern) should be accepted.
     */
    @Test
    fun testConfigVersionReserved() {
        KicheConfig(0xbabababa.toUInt()).close()
        KicheConfig(0x1a2a3a4a.toUInt()).close()
        println("    config reserved versions ... OK.")
    }

    @Test
    fun testConfigDoubleClose() {
        val config = KicheConfig()
        config.close()
        config.close() // should not crash
        println("    config double close ... OK.")
    }

    @Test
    fun testConfigUseAfterClose() {
        val config = KicheConfig()
        config.close()
        assertFailsWith<IllegalStateException> {
            config.verifyPeer(false)
        }
        println("    config use-after-close throws ... OK.")
    }

    //endregion

    //region TLS settings

    @Test
    fun testConfigVerifyPeer() {
        KicheConfig().use { config ->
            config.verifyPeer(false)
            config.verifyPeer(true)
        }
        println("    verifyPeer() ... OK.")
    }

    @Test
    fun testConfigGrease() {
        KicheConfig().use { config ->
            config.grease(true)
            config.grease(false)
        }
        println("    grease() ... OK.")
    }

    @Test
    fun testConfigLogKeys() {
        KicheConfig().use { config ->
            config.logKeys()
        }
        println("    logKeys() ... OK.")
    }

    @Test
    fun testConfigEnableEarlyData() {
        KicheConfig().use { config ->
            config.enableEarlyData()
        }
        println("    enableEarlyData() ... OK.")
    }

    //endregion

    //region Application protocols

    @Test
    fun testConfigSetApplicationProtos() {
        KicheConfig().use { config ->
            // Wire format: length-prefixed protocol names
            val h3Protos = byteArrayOf(0x02, 'h'.code.toByte(), '3'.code.toByte())
            config.setApplicationProtos(h3Protos)
        }
        println("    setApplicationProtos(h3) ... OK.")
    }

    @Test
    fun testConfigSetApplicationProtosMultiple() {
        KicheConfig().use { config ->
            // Multiple ALPN protocols: "h3" and "h3-29"
            val protos = byteArrayOf(
                0x02, 'h'.code.toByte(), '3'.code.toByte(),
                0x05, 'h'.code.toByte(), '3'.code.toByte(), '-'.code.toByte(),
                '2'.code.toByte(), '9'.code.toByte()
            )
            config.setApplicationProtos(protos)
        }
        println("    setApplicationProtos(h3, h3-29) ... OK.")
    }

    //endregion

    //region Transport parameters

    @Test
    fun testConfigTransportParams() {
        KicheConfig().use { config ->
            config.setMaxIdleTimeout(30000)
            config.setInitialMaxData(10_000_000)
            config.setInitialMaxStreamDataBidiLocal(1_000_000)
            config.setInitialMaxStreamDataBidiRemote(1_000_000)
            config.setInitialMaxStreamDataUni(1_000_000)
            config.setInitialMaxStreamsBidi(100)
            config.setInitialMaxStreamsUni(100)
            config.setAckDelayExponent(3)
            config.setMaxAckDelay(25)
            config.setDisableActiveMigration(true)
            config.setActiveConnectionIdLimit(2)
        }
        println("    transport parameter setters ... OK.")
    }

    @Test
    fun testConfigUdpPayloadSize() {
        KicheConfig().use { config ->
            config.setMaxRecvUdpPayloadSize(65527)
            config.setMaxSendUdpPayloadSize(1200)
        }
        println("    UDP payload size setters ... OK.")
    }

    @Test
    fun testConfigMaxAmplificationFactor() {
        KicheConfig().use { config ->
            config.setMaxAmplificationFactor(3)
        }
        println("    setMaxAmplificationFactor() ... OK.")
    }

    @Test
    fun testConfigWindowSettings() {
        KicheConfig().use { config ->
            config.setMaxConnectionWindow(24 * 1024 * 1024)
            config.setMaxStreamWindow(16 * 1024 * 1024)
        }
        println("    window settings ... OK.")
    }

    //endregion

    //region Congestion control

    /**
     * Ported from tests.rs:config_set_cc_algorithm_name() — we test via enum.
     */
    @Test
    fun testConfigCcAlgorithms() {
        for (algo in KicheCcAlgorithm.entries) {
            KicheConfig().use { config ->
                config.setCcAlgorithm(algo)
            }
        }
        println("    setCcAlgorithm() all variants ... OK.")
    }

    @Test
    fun testConfigCongestionSettings() {
        KicheConfig().use { config ->
            config.setCcAlgorithm(KicheCcAlgorithm.Bbr2)
            config.setInitialCongestionWindowPackets(10)
            config.enableHystart(true)
            config.enablePacing(true)
            config.setMaxPacingRate(1_000_000)
        }
        println("    congestion control settings ... OK.")
    }

    //endregion

    //region PMTU discovery

    @Test
    fun testConfigDiscoverPmtu() {
        KicheConfig().use { config ->
            config.discoverPmtu(true)
            config.discoverPmtu(false)
        }
        println("    discoverPmtu() ... OK.")
    }

    //endregion

    //region Datagrams

    @Test
    fun testConfigEnableDgram() {
        KicheConfig().use { config ->
            config.enableDgram(true, 100, 100)
        }
        println("    enableDgram() ... OK.")
    }

    @Test
    fun testConfigEnableDgramDisabled() {
        KicheConfig().use { config ->
            config.enableDgram(false, 0, 0)
        }
        println("    enableDgram(false) ... OK.")
    }

    //endregion

    //region Stateless reset token

    @Test
    fun testConfigStatelessResetToken() {
        KicheConfig().use { config ->
            val token = ByteArray(16) { it.toByte() }
            config.setStatelessResetToken(token)
        }
        println("    setStatelessResetToken() ... OK.")
    }

    @Test
    fun testConfigStatelessResetTokenInvalidSize() {
        KicheConfig().use { config ->
            assertFailsWith<IllegalArgumentException> {
                config.setStatelessResetToken(ByteArray(8))
            }
        }
        println("    setStatelessResetToken() invalid size throws ... OK.")
    }

    //endregion

    //region DCID reuse

    @Test
    fun testConfigDisableDcidReuse() {
        KicheConfig().use { config ->
            config.setDisableDcidReuse(true)
            config.setDisableDcidReuse(false)
        }
        println("    setDisableDcidReuse() ... OK.")
    }

    //endregion

    //region Full client config (realistic full-surface)

    /**
     * Tests a realistic client configuration covering the full QUIC + HTTP/3 surface:
     * QUIC + HTTP/3 + datagrams + BBR2.
     */
    @Test
    fun testConfigRealisticClientStyle() {
        KicheConfig().use { config ->
            // ALPN: h3
            config.setApplicationProtos(byteArrayOf(0x02, 'h'.code.toByte(), '3'.code.toByte()))
            // Don't verify peer cert (for testing)
            config.verifyPeer(false)
            // Enable datagrams (RFC 9221)
            config.enableDgram(true, 1000, 1000)
            // BBR2 congestion control
            config.setCcAlgorithm(KicheCcAlgorithm.Bbr2)
            // Idle timeout
            config.setMaxIdleTimeout(30000)
            // Flow control
            config.setInitialMaxData(10_000_000)
            config.setInitialMaxStreamDataBidiLocal(1_000_000)
            config.setInitialMaxStreamDataBidiRemote(1_000_000)
            config.setInitialMaxStreamsBidi(100)
            config.setInitialMaxStreamsUni(100)
            // Window sizes
            config.setMaxConnectionWindow(24 * 1024 * 1024)
            config.setMaxStreamWindow(16 * 1024 * 1024)
            // Pacing
            config.enablePacing(true)
            // GREASE
            config.grease(true)
        }
        println("    Realistic client config ... OK.")
    }

    //endregion
}
