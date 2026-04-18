package eu.buney.kiche.ktor

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Sizes that probe critical boundaries, mirroring Ktor's shared ContentTest.testSize:
 * - 0, 1: small edge cases
 * - 4095, 4096, 4097: ByteChannel internal 4KB buffer boundary
 * - 40960 (10 * 4KB): multiple full 4KB chunks
 * - 41120 (10 * 4KB + 10 * 8): slightly misaligned multi-chunk
 * - 65534, 65535, 65536: MAX_BODY_CHUNK boundary (quiche send chunk size)
 * - 8 * 1024 * 1024: large, stresses QUIC flow control across many round-trips
 */
private val testSizes = listOf(
    0,
    1,
    4 * 1024 - 1,
    4 * 1024,
    4 * 1024 + 1,
    10 * 4 * 1024,
    10 * 4 * (1024 + 8),
    65534,
    65535,
    65536,
    8 * 1024 * 1024,
)

private val testArrays = testSizes.map { size ->
    ByteArray(size) { (it % 256).toByte() }
}

/**
 * Tests for streaming request body support (WriteChannelContent, ReadChannelContent).
 *
 * These exercise the unified send/recv event loop in [KicheEngine] that handles
 * body data alongside QUIC flow control and response receiving.
 */
class KicheStreamingBodyTest {

    private val testServer = KicheTestServer()
    private lateinit var client: HttpClient

    private val testUrl: String get() = testServer.baseUrl

    @BeforeTest
    fun setUp() {
        testServer.start()
        client = testServer.createClient()
    }

    @AfterTest
    fun tearDown() {
        client.close()
        testServer.stop()
    }

    //region Parametric size coverage (mirrors CIO's ContentTest)

    @Test
    fun `send ByteReadChannel across all test sizes`() = runBlocking {
        for (content in testArrays) {
            val data = client.post("$testUrl/echo-body") {
                setBody(ByteReadChannel(content))
            }.readRawBytes()

            assertContentEquals(
                content, data,
                "ReadChannel round-trip failed for size ${content.size} (got ${data.size})",
            )

        }
    }

    @Test
    fun `send ChannelWriterContent across all test sizes`() = runBlocking {
        for (content in testArrays) {
            val data = client.post("$testUrl/echo-body") {
                setBody(
                    ChannelWriterContent(
                        body = { writeFully(content) },
                        contentType = ContentType.Application.OctetStream,
                    )
                )
            }.readRawBytes()

            assertContentEquals(
                content, data,
                "WriteChannel round-trip failed for size ${content.size} (got ${data.size})",
            )

        }
    }

    //endregion

    //region WriteChannelContent behavioral tests

    @Test
    fun `WriteChannelContent sends body correctly`() = runBlocking {
        val payload = "hello from WriteChannelContent"

        val response = client.post("$testUrl/echo-body") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeStringUtf8(payload)
                    channel.flushAndClose()
                }
            })
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(payload, response.bodyAsText())
    }

    @Test
    fun `WriteChannelContent sends multiple chunks`() = runBlocking {
        val chunk1 = "first-chunk"
        val chunk2 = "second-chunk"
        val expected = chunk1 + chunk2

        val response = client.post("$testUrl/echo-body") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeStringUtf8(chunk1)
                    channel.flush()
                    channel.writeStringUtf8(chunk2)
                    channel.flushAndClose()
                }
            })
        }

        assertEquals(expected, response.bodyAsText())
    }

    @Test
    fun `WriteChannelContent with delayed writes`() = runBlocking {
        val part1 = "before-delay"
        val part2 = "after-delay"

        val response = client.post("$testUrl/echo-body") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeStringUtf8(part1)
                    channel.flush()
                    delay(200)
                    channel.writeStringUtf8(part2)
                    channel.flushAndClose()
                }
            })
        }

        assertEquals(part1 + part2, response.bodyAsText())
    }

    @Test
    fun `WriteChannelContent many small writes`() = runBlocking {
        val parts = (1..50).map { "part$it" }
        val expected = parts.joinToString("")

        val response = client.post("$testUrl/echo-body") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    for (part in parts) {
                        channel.writeStringUtf8(part)
                        channel.flush()
                    }
                    channel.flushAndClose()
                }
            })
        }

        assertEquals(expected, response.bodyAsText())
    }

    @Test
    fun `WriteChannelContent with empty body`() = runBlocking {
        val response = client.post("$testUrl/echo-body") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.flushAndClose()
                }
            })
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }

    //endregion

    //region ReadChannelContent behavioral tests

    @Test
    fun `ReadChannelContent sends body correctly`() = runBlocking {
        val payload = "hello from ReadChannelContent"
        val payloadBytes = payload.encodeToByteArray()

        val response = client.post("$testUrl/echo-body") {
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override val contentLength = payloadBytes.size.toLong()
                override fun readFrom(): ByteReadChannel = ByteReadChannel(payloadBytes)
            })
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(payload, response.bodyAsText())
    }

    @Test
    fun `ReadChannelContent with empty body`() = runBlocking {
        val response = client.post("$testUrl/echo-body") {
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override val contentLength = 0L
                override fun readFrom(): ByteReadChannel = ByteReadChannel(ByteArray(0))
            })
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }

    //endregion

    //region Error propagation

    @Test
    fun `WriteChannelContent exception propagates to caller`(): Unit = runBlocking {
        val error = assertFails {
            client.post("$testUrl/echo-body") {
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeStringUtf8("partial data")
                        channel.flush()
                        throw IllegalStateException("writer failed")
                    }
                })
            }
        }

        val cause = generateSequence(error) { it.cause }
            .firstOrNull { it is IllegalStateException && it.message == "writer failed" }
        assertNotNull(cause, "Expected 'writer failed' in cause chain, got: $error")
    }

    @Test
    fun `WriteChannelContent exception propagates even with no data written`(): Unit = runBlocking {
        val error = assertFails {
            client.post("$testUrl/echo-body") {
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        throw IllegalStateException("immediate failure")
                    }
                })
            }
        }

        val cause = generateSequence(error) { it.cause }
            .firstOrNull { it is IllegalStateException && it.message == "immediate failure" }
        assertNotNull(cause, "Expected 'immediate failure' in cause chain, got: $error")
    }

    //endregion
}
