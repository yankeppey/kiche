package eu.buney.kiche

import kotlin.test.*

class QuicVarintTest {

    @Test
    fun `encode 1-byte values`() {
        // 0..63 → single byte
        assertContentEquals(byteArrayOf(0), QuicVarint.encode(0))
        assertContentEquals(byteArrayOf(37), QuicVarint.encode(37))
        assertContentEquals(byteArrayOf(63), QuicVarint.encode(63))
    }

    @Test
    fun `encode 2-byte values`() {
        // 64..16383 → 2 bytes, first byte has 01 prefix
        val b64 = QuicVarint.encode(64)
        assertEquals(2, b64.size)
        assertEquals(0x40, b64[0].toInt() and 0xFF)
        assertEquals(0x40, b64[1].toInt() and 0xFF)

        val b1337 = QuicVarint.encode(1337)
        assertEquals(2, b1337.size)
    }

    @Test
    fun `encode 4-byte values`() {
        val b = QuicVarint.encode(0x4000)
        assertEquals(4, b.size)
        assertEquals(0x80, b[0].toInt() and 0xC0) // top 2 bits = 10
    }

    @Test
    fun `encode 8-byte values`() {
        val b = QuicVarint.encode(0x40000000L)
        assertEquals(8, b.size)
        assertEquals(0xC0, b[0].toInt() and 0xC0) // top 2 bits = 11
    }

    @Test
    fun `round-trip for all encoding sizes`() {
        val values = listOf(0L, 1L, 63L, 64L, 1337L, 16383L, 16384L, 0x3FFFFFFFL, 0x40000000L, (1L shl 62) - 1)
        for (v in values) {
            val encoded = QuicVarint.encode(v)
            val (decoded, len) = QuicVarint.decode(encoded, 0)!!
            assertEquals(v, decoded, "Round-trip failed for $v")
            assertEquals(encoded.size, len, "Length mismatch for $v")
        }
    }

    @Test
    fun `decode returns null on empty buffer`() {
        assertNull(QuicVarint.decode(byteArrayOf(), 0))
    }

    @Test
    fun `decode returns null on truncated multi-byte`() {
        val encoded = QuicVarint.encode(1337)
        assertEquals(2, encoded.size)
        assertNull(QuicVarint.decode(encoded.copyOf(1), 0))
    }

    @Test
    fun `decode at offset`() {
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val varint = QuicVarint.encode(42)
        val buf = prefix + varint
        val (decoded, len) = QuicVarint.decode(buf, 2)!!
        assertEquals(42L, decoded)
        assertEquals(1, len)
    }

    @Test
    fun `encodedLength matches actual encoding`() {
        val values = listOf(0L, 63L, 64L, 16383L, 16384L, 0x3FFFFFFFL, 0x40000000L)
        for (v in values) {
            assertEquals(QuicVarint.encode(v).size, QuicVarint.encodedLength(v), "encodedLength mismatch for $v")
        }
    }

    @Test
    fun `negative value throws`() {
        assertFailsWith<IllegalArgumentException> { QuicVarint.encode(-1) }
    }

    @Test
    fun `value at max bound succeeds`() {
        val maxVal = (1L shl 62) - 1
        val encoded = QuicVarint.encode(maxVal)
        val (decoded, _) = QuicVarint.decode(encoded, 0)!!
        assertEquals(maxVal, decoded)
    }

    @Test
    fun `value exceeding max throws`() {
        assertFailsWith<IllegalArgumentException> { QuicVarint.encode(1L shl 62) }
    }
}
