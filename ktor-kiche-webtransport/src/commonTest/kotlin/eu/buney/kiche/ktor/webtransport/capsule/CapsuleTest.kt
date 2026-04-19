package eu.buney.kiche.ktor.webtransport.capsule

import kotlin.test.*

/**
 * Capsule protocol unit tests following the quic-go pattern
 * (see third_party/quic-go/http3/capsule_test.go).
 */
class CapsuleTest {

    //region Parsing

    @Test
    fun `parse capsule with content`() {
        val payload = "foobar".encodeToByteArray()
        val data = writeCapsule(1337, payload)

        val (capsule, consumed) = parseCapsule(data)!!
        assertEquals(1337L, capsule.type)
        assertContentEquals(payload, capsule.value)
        assertEquals(data.size, consumed)
    }

    @Test
    fun `parse capsule with empty content`() {
        val data = writeCapsule(1337, ByteArray(0))

        val (capsule, consumed) = parseCapsule(data)!!
        assertEquals(1337L, capsule.type)
        assertEquals(0, capsule.value.size)
        assertEquals(data.size, consumed)
    }

    @Test
    fun `parse returns null on empty input`() {
        assertNull(parseCapsule(ByteArray(0)))
    }

    //endregion

    //region Truncation at every byte position

    @Test
    fun `truncation with content`() {
        val payload = "foobar".encodeToByteArray()
        val full = writeCapsule(1337, payload)
        testTruncation(full)
    }

    @Test
    fun `truncation with empty content`() {
        val full = writeCapsule(1337, ByteArray(0))
        testTruncation(full)
    }

    private fun testTruncation(full: ByteArray) {
        for (i in 0 until full.size) {
            val truncated = full.copyOf(i)
            try {
                val result = parseCapsule(truncated)
                if (result != null) {
                    // Header parsed, but value should be truncated
                    fail("Expected null or exception at truncation point $i, got capsule type=${result.first.type}")
                }
                // null → header incomplete (type varint not parseable) — valid for i == 0
            } catch (_: CapsuleParseException) {
                // Header partially parsed but value truncated — expected for i > 0
                assertTrue(i > 0, "CapsuleParseException at position 0 is unexpected")
            }
        }
    }

    //endregion

    //region Round-trip write + parse

    @Test
    fun `round-trip write and parse`() {
        val payload = "hello capsule".encodeToByteArray()
        val data = writeCapsule(42, payload)

        val (capsule, consumed) = parseCapsule(data)!!
        assertEquals(42L, capsule.type)
        assertContentEquals(payload, capsule.value)
        assertEquals(data.size, consumed)
    }

    @Test
    fun `round-trip with large type value`() {
        val type = 0x2843L // CLOSE_WEBTRANSPORT_SESSION
        val payload = byteArrayOf(0, 0, 0, 0) // error code 0
        val data = writeCapsule(type, payload)

        val (capsule, _) = parseCapsule(data)!!
        assertEquals(type, capsule.type)
        assertContentEquals(payload, capsule.value)
    }

    //endregion

    //region Consecutive capsules

    @Test
    fun `consecutive empty capsules`() {
        val data = writeCapsule(1337, ByteArray(0)) + writeCapsule(1337, ByteArray(0))

        val (capsules, consumed) = parseAllCapsules(data)
        assertEquals(2, capsules.size)
        assertEquals(data.size, consumed)
        for (c in capsules) {
            assertEquals(1337L, c.type)
            assertEquals(0, c.value.size)
        }
    }

    @Test
    fun `consecutive capsules with content`() {
        val data = writeCapsule(1, "hello".encodeToByteArray()) +
            writeCapsule(2, "world".encodeToByteArray()) +
            writeCapsule(3, ByteArray(0))

        val (capsules, consumed) = parseAllCapsules(data)
        assertEquals(3, capsules.size)
        assertEquals(data.size, consumed)
        assertEquals(1L, capsules[0].type)
        assertEquals("hello", capsules[0].value.decodeToString())
        assertEquals(2L, capsules[1].type)
        assertEquals("world", capsules[1].value.decodeToString())
        assertEquals(3L, capsules[2].type)
        assertEquals(0, capsules[2].value.size)
    }

    @Test
    fun `parseAll stops at incomplete trailing capsule`() {
        val complete = writeCapsule(1, "hello".encodeToByteArray())
        val incomplete = writeCapsule(2, "world".encodeToByteArray())
        // Truncate the second capsule
        val data = complete + incomplete.copyOf(2)

        val (capsules, consumed) = parseAllCapsules(data)
        assertEquals(1, capsules.size)
        assertEquals(complete.size, consumed) // only the first capsule consumed
    }

    //endregion
}
