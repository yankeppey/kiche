package eu.buney.kiche

import kotlin.test.Test
import kotlin.test.assertTrue

class KicheTest {
    @Test
    fun testQuicheVersion() {
        val version = Kiche.quicheVersion()
        assertTrue(version.isNotEmpty(), "quiche version should not be empty")
        println("quiche version: $version")
    }
}
