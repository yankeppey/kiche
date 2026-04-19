package eu.buney.kiche.ktor.webtransport.capsule

import eu.buney.kiche.ktor.webtransport.WebTransportCloseInfo
import kotlin.test.*

class CloseWebTransportSessionTest {

    @Test
    fun `encode and decode with zero code and empty reason`() {
        val info = WebTransportCloseInfo(0u, "")
        val capsuleBytes = CloseWebTransportSession.encode(info)

        // Parse as capsule, verify type
        val (capsule, _) = parseCapsule(capsuleBytes)!!
        assertEquals(CapsuleType.CLOSE_WEBTRANSPORT_SESSION, capsule.type)

        // Decode payload
        val decoded = CloseWebTransportSession.decode(capsule.value)!!
        assertEquals(0u, decoded.code)
        assertEquals("", decoded.reason)
    }

    @Test
    fun `encode and decode with code and reason`() {
        val info = WebTransportCloseInfo(42u, "bye")
        val capsuleBytes = CloseWebTransportSession.encode(info)

        val (capsule, _) = parseCapsule(capsuleBytes)!!
        val decoded = CloseWebTransportSession.decode(capsule.value)!!
        assertEquals(42u, decoded.code)
        assertEquals("bye", decoded.reason)
    }

    @Test
    fun `encode and decode with large error code`() {
        val info = WebTransportCloseInfo(UInt.MAX_VALUE, "max code")
        val capsuleBytes = CloseWebTransportSession.encode(info)

        val (capsule, _) = parseCapsule(capsuleBytes)!!
        val decoded = CloseWebTransportSession.decode(capsule.value)!!
        assertEquals(UInt.MAX_VALUE, decoded.code)
        assertEquals("max code", decoded.reason)
    }

    @Test
    fun `reason truncated to 1024 bytes`() {
        val longReason = "a".repeat(2000)
        val info = WebTransportCloseInfo(1u, longReason)
        val capsuleBytes = CloseWebTransportSession.encode(info)

        val (capsule, _) = parseCapsule(capsuleBytes)!!
        val decoded = CloseWebTransportSession.decode(capsule.value)!!
        assertEquals(1u, decoded.code)
        assertTrue(decoded.reason.length <= CloseWebTransportSession.MAX_REASON_LENGTH)
        assertEquals(CloseWebTransportSession.MAX_REASON_LENGTH, decoded.reason.length)
    }

    @Test
    fun `UTF-8 truncation does not split multi-byte characters`() {
        // "a" repeated 1022 times + a 4-byte emoji (rocket = U+1F680)
        // Total UTF-8 bytes = 1022 + 4 = 1026, exceeds 1024
        // Should truncate to 1022 (drop the emoji, not split it)
        val emoji = "\uD83D\uDE80" // rocket emoji, 4 bytes in UTF-8
        val prefix = "a".repeat(1022)
        val reason = prefix + emoji

        val info = WebTransportCloseInfo(0u, reason)
        val capsuleBytes = CloseWebTransportSession.encode(info)

        val (capsule, _) = parseCapsule(capsuleBytes)!!
        val decoded = CloseWebTransportSession.decode(capsule.value)!!
        assertEquals(prefix, decoded.reason) // emoji dropped entirely
    }

    @Test
    fun `decode returns null for payload shorter than 4 bytes`() {
        assertNull(CloseWebTransportSession.decode(ByteArray(0)))
        assertNull(CloseWebTransportSession.decode(ByteArray(3)))
    }

    @Test
    fun `decode returns null for invalid UTF-8 reason`() {
        val payload = ByteArray(5)
        payload[4] = 0xFF.toByte() // invalid UTF-8 byte
        assertNull(CloseWebTransportSession.decode(payload))
    }

    @Test
    fun `wire format matches wtransport test vector`() {
        // From wtransport-proto capsule test:
        // bytes [104, 67, 4, 0, 0, 0, 0] = capsule type 0x2843, length 4, error code 0
        // 104 = 0x68 → varint header for 0x2843: first byte = 0x40 | (0x2843 >> 8) = 0x40 | 0x28 = 0x68
        // 67 = 0x43 → low byte of 0x2843
        // 4 → length varint = 4
        // 0, 0, 0, 0 → error code 0 (big-endian u32)

        val info = WebTransportCloseInfo(0u, "")
        val capsuleBytes = CloseWebTransportSession.encode(info)

        val expected = byteArrayOf(0x68, 0x43, 4, 0, 0, 0, 0)
        assertContentEquals(expected, capsuleBytes)
    }
}
