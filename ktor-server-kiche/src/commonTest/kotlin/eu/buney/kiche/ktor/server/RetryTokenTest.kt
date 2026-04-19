package eu.buney.kiche.ktor.server

import eu.buney.kiche.KicheAddress
import kotlin.test.*

class RetryTokenTest {

    private val ipv4Addr = KicheAddress(byteArrayOf(127, 0, 0, 1), 1234)
    private val ipv6Addr = KicheAddress(
        byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), 5678
    )
    private val dcid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

    @Test
    fun `round-trip IPv4`() {
        val token = KicheApplicationEngine.mintToken(dcid, ipv4Addr)
        val extracted = KicheApplicationEngine.validateToken(token, ipv4Addr)
        assertNotNull(extracted)
        assertContentEquals(dcid, extracted)
    }

    @Test
    fun `round-trip IPv6`() {
        val token = KicheApplicationEngine.mintToken(dcid, ipv6Addr)
        val extracted = KicheApplicationEngine.validateToken(token, ipv6Addr)
        assertNotNull(extracted)
        assertContentEquals(dcid, extracted)
    }

    @Test
    fun `wrong IP rejects`() {
        val token = KicheApplicationEngine.mintToken(dcid, ipv4Addr)
        val wrongAddr = KicheAddress(byteArrayOf(10, 0, 0, 1), 1234)
        assertNull(KicheApplicationEngine.validateToken(token, wrongAddr))
    }

    @Test
    fun `wrong port rejects`() {
        val token = KicheApplicationEngine.mintToken(dcid, ipv4Addr)
        val wrongPort = KicheAddress(byteArrayOf(127, 0, 0, 1), 9999)
        assertNull(KicheApplicationEngine.validateToken(token, wrongPort))
    }

    @Test
    fun `truncated token rejects`() {
        val token = KicheApplicationEngine.mintToken(dcid, ipv4Addr)
        // Truncate at various points
        assertNull(KicheApplicationEngine.validateToken(byteArrayOf(), ipv4Addr))
        assertNull(KicheApplicationEngine.validateToken(token.copyOf(3), ipv4Addr))
        assertNull(KicheApplicationEngine.validateToken(token.copyOf(6), ipv4Addr)) // prefix only
        assertNull(KicheApplicationEngine.validateToken(token.copyOf(10), ipv4Addr)) // prefix + ip, no port
    }

    @Test
    fun `garbage prefix rejects`() {
        val token = KicheApplicationEngine.mintToken(dcid, ipv4Addr)
        token[0] = 0xFF.toByte() // corrupt prefix
        assertNull(KicheApplicationEngine.validateToken(token, ipv4Addr))
    }

    @Test
    fun `token without dcid rejects`() {
        // Mint with empty dcid — validateToken should return null (offset >= token.size)
        val token = KicheApplicationEngine.mintToken(byteArrayOf(), ipv4Addr)
        assertNull(KicheApplicationEngine.validateToken(token, ipv4Addr))
    }
}
