/*
 * Config and basic API tests.
 *
 * Ported from quiche's Rust tests:
 * - tests.rs:config_version_reserved() line 231
 * - tests.rs:config_set_cc_algorithm_name() line 5515
 */
package eu.buney.kiche

import kotlin.test.Test
import kotlin.test.assertTrue

class KicheConfigTest {

    //region tests.rs:config_version_reserved (line 231)

    /**
     * Ported from tests.rs:config_version_reserved()
     * Reserved versions (with 0x?a?a?a?a pattern) should be accepted.
     */
    @Test
    fun testConfigVersionReserved() {
        KicheConfig(0xbabababa.toUInt()).close()
        KicheConfig(0x1a2a3a4a.toUInt()).close()
    }

    //endregion

    //region tests.rs:config_set_cc_algorithm_name (line 5515)

    /**
     * Ported from tests.rs:config_set_cc_algorithm_name()
     * All congestion control algorithm variants should be accepted.
     */
    @Test
    fun testConfigSetCcAlgorithm() {
        for (algo in KicheCcAlgorithm.entries) {
            KicheConfig().use { config ->
                config.setCcAlgorithm(algo)
            }
        }
    }

    //endregion

    //region quiche_version

    @Test
    fun testQuicheVersion() {
        val version = Kiche.quicheVersion()
        assertTrue(version.isNotEmpty(), "quiche version should not be empty")
        assertTrue(version.contains("."), "version should contain a dot: $version")
    }

    //endregion
}
