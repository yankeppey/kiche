package eu.buney.kiche.ktor

import eu.buney.kiche.ktor.webtransport.*
import eu.buney.kiche.ktor.server.webtransport.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.test.*

/**
 * Integration tests for WebTransport over HTTP/3.
 *
 * Each test starts an embedded Kiche server with WebTransport routing,
 * connects a Kiche client, and verifies end-to-end behavior over real
 * QUIC + UDP on localhost.
 */
class KicheWebTransportTest {

    private val testServer = KicheTestServer()
    private lateinit var client: HttpClient

    private val testUrl: String get() = testServer.baseUrl

    @BeforeTest
    fun setUp() {
        testServer.start {
            // WebTransport routes alongside standard HTTP routes
            webTransport("/wt") {
                // Echo server: echoes bidirectional streams and datagrams
                launch {
                    // Echo bidi streams
                    for (stream in incomingBidirectionalStreams) {
                        launch {
                            val buf = ByteArray(65535)
                            try {
                                while (!stream.incoming.isClosedForRead) {
                                    val n = stream.incoming.readAvailable(buf)
                                    if (n > 0) {
                                        stream.outgoing.writeFully(buf, 0, n)
                                        stream.outgoing.flush()
                                    }
                                }
                            } catch (_: Throwable) { }
                            stream.finish()
                        }
                    }
                }

                launch {
                    // Echo datagrams
                    for (dgram in datagrams.incoming) {
                        datagrams.outgoing.send(dgram)
                    }
                }

                launch {
                    // Echo unidirectional streams back as server-initiated uni streams
                    for (stream in incomingUnidirectionalStreams) {
                        launch {
                            val data = readAll(stream.incoming)
                            val outStream = createUnidirectionalStream()
                            outStream.outgoing.writeFully(data)
                            outStream.finish()
                        }
                    }
                }

                // Keep session alive until closed by client
                closed.await()
            }

            webTransport("/wt/immediate-close") {
                // Immediately close the session
                close(WebTransportCloseInfo(42u, "bye"))
            }

            webTransport("/wt/server-push") {
                // Server opens a bidi stream and writes data
                val stream = createBidirectionalStream()
                stream.outgoing.writeStringUtf8("pushed-from-server")
                stream.outgoing.flush()
                stream.finish()

                // Keep alive until client closes
                closed.await()
            }
        }
        client = HttpClient(Kiche) {
            engine { verifyPeer = false }
            install(WebTransport)
        }
    }

    @AfterTest
    fun tearDown() {
        client.close()
        testServer.stop()
    }

    //region Session lifecycle

    @Test
    fun `session connects and ready completes`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            // If we get here, ready has already completed (webTransport awaits it)
            assertTrue(ready.isCompleted)
        }
    }

    @Test
    fun `session closed deferred completes on close`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            assertFalse(closed.isCompleted)
            close()
            assertTrue(closed.isCompleted)
        }
    }

    //endregion

    //region Bidirectional streams

    @Test
    fun `echo over bidirectional stream`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            val stream = createBidirectionalStream()
            val message = "hello webtransport"
            stream.outgoing.writeStringUtf8(message)
            stream.outgoing.flush()
            stream.finish()

            val response = readAll(stream.incoming)
            assertEquals(message, response.decodeToString())
        }
    }

    @Test
    fun `multiple bidirectional streams`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            val streams = (1..3).map { i ->
                val stream = createBidirectionalStream()
                val msg = "stream-$i"
                stream.outgoing.writeStringUtf8(msg)
                stream.outgoing.flush()
                stream.finish()
                msg to stream
            }

            for ((expected, stream) in streams) {
                val response = readAll(stream.incoming)
                assertEquals(expected, response.decodeToString())
            }
        }
    }

    @Test
    fun `large payload over bidirectional stream`() = runBlocking {
        val payload = ByteArray(64 * 1024) { (it % 256).toByte() }

        client.webTransport("$testUrl/wt") {
            val stream = createBidirectionalStream()
            stream.outgoing.writeFully(payload)
            stream.outgoing.flush()
            stream.finish()

            val response = readAll(stream.incoming)
            assertContentEquals(payload, response)
        }
    }

    //endregion

    //region Datagrams

    @Test
    fun `echo datagram`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            val payload = "ping".encodeToByteArray()
            datagrams.outgoing.send(payload)

            val response = withTimeout(5000) {
                datagrams.incoming.receive()
            }
            assertContentEquals(payload, response)
        }
    }

    @Test
    fun `multiple datagrams`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            val count = 5
            val sent = (1..count).map { "dgram-$it".encodeToByteArray() }
            for (payload in sent) {
                datagrams.outgoing.send(payload)
            }

            // Datagrams are unreliable — we expect most to arrive but not necessarily all
            val received = mutableListOf<ByteArray>()
            withTimeoutOrNull(3000) {
                repeat(count) {
                    received.add(datagrams.incoming.receive())
                }
            }
            assertTrue(received.isNotEmpty(), "Should receive at least one datagram")
        }
    }

    //endregion

    //region Session lifecycle — additional

    @Test
    fun `server-initiated close carries close info`() = runBlocking {
        val session = client.webTransportSession("$testUrl/wt/immediate-close")
        session.ready.await()

        // Server immediately closes with code=42, reason="bye"
        val closeInfo = withTimeout(5000) { session.closed.await() }
        assertNotNull(closeInfo)
        assertEquals(42u, closeInfo.code)
        assertEquals("bye", closeInfo.reason)
    }

    @Test
    fun `session rejects invalid path`() = runBlocking {
        var reachedHandler = false
        try {
            client.webTransport("$testUrl/nonexistent-wt-path") {
                reachedHandler = true
            }
        } catch (_: Throwable) {
            // Expected — server should reject the CONNECT request
        }
        assertFalse(reachedHandler, "Should not reach session handler for invalid path")
    }

    //endregion

    //region Server-initiated streams

    // TODO: Server-initiated H3 request streams are not supported by quiche's H3 API.
    //  Requires a different mechanism (e.g., capsule protocol on the CONNECT stream body).
    @Ignore
    @Test
    fun `server-initiated bidirectional stream`() = runBlocking {
        client.webTransport("$testUrl/wt/server-push") {
            val stream = withTimeout(5000) {
                incomingBidirectionalStreams.receive()
            }
            val data = readAll(stream.incoming)
            assertEquals("pushed-from-server", data.decodeToString())
        }
    }

    //endregion

    //region Unidirectional streams

    // TODO: Server-initiated streams require a different mechanism than H3 request streams.
    //  The server echo uses createUnidirectionalStream() which can't create H3 streams.
    @Ignore
    @Test
    fun `unidirectional stream echo`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            // Client sends a uni stream, server echoes back as a new server→client uni stream
            val outStream = createUnidirectionalStream()
            outStream.outgoing.writeStringUtf8("uni-hello")
            outStream.outgoing.flush()
            outStream.finish()

            val inStream = withTimeout(5000) {
                incomingUnidirectionalStreams.receive()
            }
            val response = readAll(inStream.incoming)
            assertEquals("uni-hello", response.decodeToString())
        }
    }

    //endregion

    //region Datagrams — additional

    @Test
    fun `max datagram size is positive`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            val maxSize = datagrams.maxDatagramSize
            assertTrue(maxSize > 0, "maxDatagramSize should be positive, got $maxSize")
        }
    }

    //endregion

    //region Mixed traffic

    @Test
    fun `streams and datagrams concurrently`() = runBlocking {
        client.webTransport("$testUrl/wt") {
            // Send on a bidi stream and datagrams simultaneously
            val stream = createBidirectionalStream()
            stream.outgoing.writeStringUtf8("stream-data")
            stream.outgoing.flush()
            stream.finish()

            val dgramPayload = "dgram-data".encodeToByteArray()
            datagrams.outgoing.send(dgramPayload)

            // Verify both come back
            val streamResponse = async { readAll(stream.incoming) }
            val dgramResponse = async {
                withTimeout(5000) { datagrams.incoming.receive() }
            }

            assertEquals("stream-data", streamResponse.await().decodeToString())
            assertContentEquals(dgramPayload, dgramResponse.await())
        }
    }

    @Test
    fun `HTTP request works alongside WebTransport routes`() = runBlocking {
        // Standard HTTP/3 request should still work on the same server
        val httpClient = testServer.createClient()
        try {
            val response = httpClient.get("$testUrl/hello")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("hello", response.bodyAsText())
        } finally {
            httpClient.close()
        }
    }

    //endregion
}

/** Reads all remaining bytes from a [ByteReadChannel]. */
private suspend fun readAll(channel: ByteReadChannel): ByteArray {
    val parts = mutableListOf<ByteArray>()
    val buf = ByteArray(65535)
    while (!channel.isClosedForRead) {
        val n = channel.readAvailable(buf)
        if (n > 0) parts.add(buf.copyOf(n))
        else if (n < 0) break
    }
    val total = parts.sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    for (part in parts) {
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}
