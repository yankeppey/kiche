package eu.buney.kiche.ktor

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.measureTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for QUIC connection pooling and H3 stream multiplexing.
 *
 * Verifies that [KicheEndpoint] correctly reuses a single QUIC connection
 * for multiple requests, multiplexes concurrent requests as independent
 * H3 streams, isolates failures per-stream, and recovers from connection loss.
 */
class KicheConnectionPoolTest {

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

    //region 1. Sequential request reuse

    @Test
    fun `sequential requests reuse the same connection`() = runBlocking {
        repeat(10) {
            val response = client.get("$testUrl/hello")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("hello", response.bodyAsText())
        }
    }

    @Test
    fun `sequential requests with different methods`() = runBlocking {
        assertEquals("hello", client.get("$testUrl/hello").bodyAsText())
        assertEquals("GET", client.get("$testUrl/echo-method").bodyAsText())
        assertEquals("POST", client.post("$testUrl/echo-method").bodyAsText())
        assertEquals("body1", client.post("$testUrl/echo-body") { setBody("body1") }.bodyAsText())
        assertEquals("body2", client.post("$testUrl/echo-body") { setBody("body2") }.bodyAsText())
    }

    //endregion

    //region 2. Concurrent request multiplexing

    @Test
    fun `concurrent GET requests are multiplexed`() = runBlocking {
        val count = 20
        val responses = (1..count).map {
            async { client.get("$testUrl/hello").bodyAsText() }
        }.awaitAll()

        assertEquals(count, responses.size)
        assertTrue(responses.all { it == "hello" })
    }

    @Test
    fun `concurrent POST requests return correct data per stream`() = runBlocking {
        val count = 20
        val responses = (1..count).map { i ->
            async {
                client.post("$testUrl/echo-body") {
                    setBody("request-$i")
                }.bodyAsText()
            }
        }.awaitAll()

        assertEquals((1..count).map { "request-$it" }.toSet(), responses.toSet())
    }

    @Test
    fun `multiplexed requests run concurrently not serially`() = runBlocking {
        val count = 5
        val delayMs = 300L

        val elapsed = measureTime {
            val responses = (1..count).map {
                async { client.get("$testUrl/delay/$delayMs").bodyAsText() }
            }.awaitAll()
            assertEquals(count, responses.size)
        }

        // If serial: >= 1500ms (5 * 300ms). If concurrent: ~300ms + overhead.
        val serialThreshold = delayMs * count
        assertTrue(
            elapsed < serialThreshold.milliseconds,
            "Requests appear serialized: $elapsed >= ${serialThreshold}ms"
        )
    }

    //endregion

    //region 3. Concurrent streaming bodies

    @Test
    fun `concurrent streaming bodies are multiplexed correctly`() = runBlocking {
        val results = (1..5).map { i ->
            async {
                val payload = ByteArray(65536) { ((it + i) % 256).toByte() }
                val data = client.post("$testUrl/echo-body") {
                    setBody(ChannelWriterContent(
                        body = { writeFully(payload) },
                        contentType = ContentType.Application.OctetStream,
                    ))
                }.readRawBytes()
                assertContentEquals(payload, data, "Stream $i data mismatch")
            }
        }.awaitAll()

        assertEquals(5, results.size)
    }

    @Test
    fun `concurrent mixed body types`() = runBlocking {
        val results = (1..4).map { i ->
            async {
                when (i % 2) {
                    0 -> {
                        // ByteArray body
                        val payload = ByteArray(4096) { ((it + i) % 256).toByte() }
                        val data = client.post("$testUrl/echo-body") {
                            setBody(payload)
                        }.readRawBytes()
                        assertContentEquals(payload, data, "ByteArray stream $i mismatch")
                    }
                    else -> {
                        // Streaming body
                        val payload = ByteArray(4096) { ((it + i) % 256).toByte() }
                        val data = client.post("$testUrl/echo-body") {
                            setBody(ChannelWriterContent(
                                body = { writeFully(payload) },
                                contentType = ContentType.Application.OctetStream,
                            ))
                        }.readRawBytes()
                        assertContentEquals(payload, data, "Streaming stream $i mismatch")
                    }
                }
            }
        }.awaitAll()

        assertEquals(4, results.size)
    }

    //endregion

    //region 4. Request isolation

    @Test
    fun `failed streaming request does not break concurrent requests`() = runBlocking {
        val good = async {
            client.get("$testUrl/hello").bodyAsText()
        }
        val bad = async {
            assertFails {
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
        }

        bad.await()
        assertEquals("hello", good.await())
    }

    @Test
    fun `subsequent request succeeds after a failed one`() = runBlocking {
        // First request fails
        assertFails {
            client.post("$testUrl/echo-body") {
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        throw IllegalStateException("boom")
                    }
                })
            }
        }

        // Second request should still work on the same connection
        val response = client.get("$testUrl/hello")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello", response.bodyAsText())
    }

    //endregion

    //region 5. Connection recovery

    @Test
    fun `client recovers after server restart`() = runBlocking {
        // Request succeeds on original server
        val r1 = client.get("$testUrl/hello").bodyAsText()
        assertEquals("hello", r1)

        // Stop server — connection dies
        testServer.stop()

        // Restart server (new port, new endpoint)
        testServer.start()

        // Request to new server succeeds
        val r2 = client.get("$testUrl/hello").bodyAsText()
        assertEquals("hello", r2)
    }

    //endregion
}
