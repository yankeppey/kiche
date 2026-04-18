package eu.buney.kiche.ktor.h3adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AltSvcTest {

    @Test
    fun `parse simple h3 entry`() {
        val entries = parseAltSvcHeader("""h3=":443"; ma=2592000""")
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("h3", entry.protocolId)
        assertEquals("", entry.host)
        assertEquals(443, entry.port)
        assertEquals(2592000L, entry.maxAge)
    }

    @Test
    fun `parse multiple entries`() {
        val entries = parseAltSvcHeader("""h3=":443"; ma=2592000, h3-29=":443"; ma=86400""")
        assertEquals(2, entries.size)
        assertEquals("h3", entries[0].protocolId)
        assertEquals("h3-29", entries[1].protocolId)
        assertEquals(86400L, entries[1].maxAge)
    }

    @Test
    fun `parse with alternative host`() {
        val entries = parseAltSvcHeader("""h3="alt.example.com:8443"; ma=3600""")
        assertEquals(1, entries.size)
        assertEquals("alt.example.com", entries[0].host)
        assertEquals(8443, entries[0].port)
    }

    @Test
    fun `parse clear directive`() {
        val entries = parseAltSvcHeader("clear")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parse with default max-age`() {
        val entries = parseAltSvcHeader("""h3=":443"""")
        assertEquals(1, entries.size)
        assertEquals(86400L, entries[0].maxAge)
    }

    @Test
    fun `skip malformed entries gracefully`() {
        val entries = parseAltSvcHeader("""h3=":443", invalid-junk, h3-29=":8443"; ma=100""")
        assertEquals(2, entries.size)
        assertEquals("h3", entries[0].protocolId)
        assertEquals("h3-29", entries[1].protocolId)
    }

    @Test
    fun `expiry check`() {
        val entry = AltSvcEntry(
            protocolId = "h3",
            host = "",
            port = 443,
            maxAge = 1L, // 1 second
            receivedAt = 0L, // epoch — long expired
        )
        assertTrue(entry.isExpired())
    }
}
