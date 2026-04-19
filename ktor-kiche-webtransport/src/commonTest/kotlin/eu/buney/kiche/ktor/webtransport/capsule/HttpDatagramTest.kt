package eu.buney.kiche.ktor.webtransport.capsule

import kotlin.test.*

class HttpDatagramTest {

    @Test
    fun `encode prepends quarter stream ID`() {
        // Stream ID 0 → Quarter Stream ID 0 → single byte 0x00
        val payload = "hello".encodeToByteArray()
        val encoded = HttpDatagram.encode(0, payload)
        assertEquals(1 + payload.size, encoded.size)
        assertEquals(0x00, encoded[0].toInt())
        assertContentEquals(payload, encoded.copyOfRange(1, encoded.size))
    }

    @Test
    fun `encode with stream ID 4`() {
        // Stream ID 4 → Quarter Stream ID 1 → single byte 0x01
        val payload = byteArrayOf(0x42)
        val encoded = HttpDatagram.encode(4, payload)
        assertEquals(0x01, encoded[0].toInt())
        assertEquals(0x42, encoded[1].toInt())
    }

    @Test
    fun `round-trip encode and decode`() {
        val streamIds = listOf(0L, 4L, 8L, 252L, 256L, 1024L)
        for (streamId in streamIds) {
            val payload = "dgram-$streamId".encodeToByteArray()
            val encoded = HttpDatagram.encode(streamId, payload)
            val (decodedStreamId, decodedPayload) = HttpDatagram.decode(encoded)!!

            assertEquals(streamId, decodedStreamId, "Stream ID mismatch for $streamId")
            assertContentEquals(payload, decodedPayload, "Payload mismatch for $streamId")
        }
    }

    @Test
    fun `decode returns null on empty input`() {
        assertNull(HttpDatagram.decode(ByteArray(0)))
    }

    @Test
    fun `decode with empty payload`() {
        val encoded = HttpDatagram.encode(0, ByteArray(0))
        val (streamId, payload) = HttpDatagram.decode(encoded)!!
        assertEquals(0L, streamId)
        assertEquals(0, payload.size)
    }

    @Test
    fun `quarter stream ID uses correct division`() {
        // Stream ID 0 → QSID 0
        assertEquals(0x00, HttpDatagram.encode(0, ByteArray(0))[0].toInt())
        // Stream ID 4 → QSID 1
        assertEquals(0x01, HttpDatagram.encode(4, ByteArray(0))[0].toInt())
        // Stream ID 8 → QSID 2
        assertEquals(0x02, HttpDatagram.encode(8, ByteArray(0))[0].toInt())
    }
}
