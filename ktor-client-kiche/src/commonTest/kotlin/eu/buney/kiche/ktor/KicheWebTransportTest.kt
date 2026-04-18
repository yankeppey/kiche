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

    //region HTTP/3 alongside WebTransport

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
