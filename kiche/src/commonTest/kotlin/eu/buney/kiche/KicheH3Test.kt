/*
 * HTTP/3 tests.
 *
 * Ported from quiche's h3/mod.rs test suite using the TestSession helper
 * (equivalent to quiche's h3::testing::Session). All tests run entirely
 * in memory with no network I/O.
 *
 * Each test references the original Rust test name and line number.
 */
package eu.buney.kiche

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KicheH3Test {

    //region h3/mod.rs:request_no_body_response_no_body (line 3725)

    /**
     * Ported from h3/mod.rs:request_no_body_response_no_body()
     * Send a request with no body, get a response with no body.
     */
    @Test
    fun testRequestNoBodyResponseNoBody() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = true)
            assertEquals(0, stream)

            // Server receives request headers + finished
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)
            assertEquals(stream, ev1.streamId)
            assertNotNull(ev1.headers)
            assertHeadersContain(ev1.headers!!, name = ":method", value = "GET")

            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Finished, ev2.type)
            assertEquals(stream, ev2.streamId)

            // Server sends response
            s.sendResponse(streamId = stream, fin = true)

            // Client receives response headers + finished
            val ev3 = s.pollClient()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Headers, ev3.type)
            assertEquals(stream, ev3.streamId)
            assertNotNull(ev3.headers)
            assertHeadersContain(ev3.headers!!, name = ":status", value = "200")

            val ev4 = s.pollClient()
            assertNotNull(ev4)
            assertEquals(KicheH3EventType.Finished, ev4.type)

            assertNull(s.pollClient())
        }
    }

    //endregion

    //region h3/mod.rs:request_no_body_response_one_chunk (line 3755)

    /**
     * Ported from h3/mod.rs:request_no_body_response_one_chunk()
     * Send a request with no body, get a response with one DATA frame.
     */
    @Test
    fun testRequestNoBodyResponseOneChunk() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = true)
            assertEquals(0, stream)

            s.pollServer() // Headers
            s.pollServer() // Finished

            s.sendResponse(streamId = stream, fin = false)
            val body = s.sendBodyServer(streamId = stream, fin = true)

            // Client receives headers
            val ev = s.pollClient()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.Headers, ev.type)
            assertHeadersContain(ev.headers!!, name = ":status", value = "200")

            // Client receives data event
            val evData = s.pollClient()
            assertNotNull(evData)
            assertEquals(KicheH3EventType.Data, evData.type)

            // Read and verify body content
            val recvBuf = ByteArray(body.size)
            val read = s.recvBodyClient(streamId = stream, buf = recvBuf)
            assertEquals(body.size, read)
            assertContentEquals(body, recvBuf.copyOf(read))

            // Finished
            val evFin = s.pollClient()
            assertNotNull(evFin)
            assertEquals(KicheH3EventType.Finished, evFin.type)

            assertNull(s.pollClient())
        }
    }

    //endregion

    //region h3/mod.rs:request_no_body_response_many_chunks (line 3793)

    /**
     * Ported from tests.rs:request_no_body_response_many_chunks()
     * Send a request with no body, get a response with multiple DATA frames.
     */
    @Test
    fun testRequestNoBodyResponseManyChunks() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = true)

            s.pollServer() // Headers
            s.pollServer() // Finished

            val totalDataFrames = 4
            s.sendResponse(streamId = stream, fin = false)
            for (i in 0 until totalDataFrames - 1) {
                s.sendBodyServer(streamId = stream, fin = false)
            }
            val body = s.sendBodyServer(streamId = stream, fin = true)

            // Client receives headers
            val ev = s.pollClient()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.Headers, ev.type)

            // Client receives data event
            val evData = s.pollClient()
            assertNotNull(evData)
            assertEquals(KicheH3EventType.Data, evData.type)

            // Read all body chunks and verify each matches DEFAULT_BODY
            val recvBuf = ByteArray(body.size)
            for (i in 0 until totalDataFrames) {
                val read = s.recvBodyClient(streamId = stream, buf = recvBuf)
                assertEquals(body.size, read)
                assertContentEquals(body, recvBuf.copyOf(read))
            }

            // Finished
            val evFin = s.pollClient()
            assertNotNull(evFin)
            assertEquals(KicheH3EventType.Finished, evFin.type)

            assertNull(s.pollClient())
        }
    }

    //endregion

    //region h3/mod.rs:request_one_chunk_response_no_body (line 3927)

    /**
     * Ported from h3/mod.rs:request_one_chunk_response_no_body()
     */
    @Test
    fun testRequestOneChunkResponseNoBody() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = false)
            val body = s.sendBodyClient(streamId = stream, fin = true)

            // Server receives headers
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)

            // Server receives data event
            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Data, ev2.type)

            // Read and verify body
            val recvBuf = ByteArray(body.size)
            val read = s.recvBodyServer(streamId = stream, buf = recvBuf)
            assertEquals(body.size, read)
            assertContentEquals(body, recvBuf.copyOf(read))

            // Finished
            val ev3 = s.pollServer()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Finished, ev3.type)

            // Server responds
            s.sendResponse(streamId = stream, fin = true)

            // Client receives response
            val ev4 = s.pollClient()
            assertNotNull(ev4)
            assertEquals(KicheH3EventType.Headers, ev4.type)

            val ev5 = s.pollClient()
            assertNotNull(ev5)
            assertEquals(KicheH3EventType.Finished, ev5.type)
        }
    }

    //endregion

    //region h3/mod.rs:goaway_from_client_good (line 4768)

    /**
     * Ported from h3/mod.rs:goaway_from_client_good()
     * Rust test verifies: poll_server() == Ok((0, Event::GoAway))
     */
    @Test
    fun testGoawayFromClientGood() {
        TestSession.new().use { s ->
            s.client.sendGoaway(s.pipe.client, id = 100)
            s.advance()

            val ev = s.pollServer()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.GoAway, ev.type)
            assertEquals(0, ev.streamId)
        }
    }

    //endregion

    //region h3/mod.rs:goaway_from_server_good (line 4782)

    /**
     * Ported from h3/mod.rs:goaway_from_server_good()
     * Rust test verifies: poll_client() == Ok((4000, Event::GoAway))
     */
    @Test
    fun testGoawayFromServerGood() {
        TestSession.new().use { s ->
            s.server.sendGoaway(s.pipe.server, id = 4000)
            s.advance()

            val ev = s.pollClient()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.GoAway, ev.type)
            assertEquals(4000, ev.streamId)
        }
    }

    //endregion

    //region h3/mod.rs:request_many_chunks_response_no_body (line 3964)

    /**
     * Ported from h3/mod.rs:request_many_chunks_response_no_body()
     * Send a request with 4 body chunks, get a response with no body.
     */
    @Test
    fun testRequestManyChunksResponseNoBody() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = false)

            for (i in 0 until 3) {
                s.sendBodyClient(stream, fin = false)
            }
            val body = s.sendBodyClient(stream, fin = true)

            val recvBuf = ByteArray(body.size)

            // Server receives headers
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)
            assertEquals(stream, ev1.streamId)

            // Server receives Data event
            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Data, ev2.type)

            // No more events until we read the body
            assertNull(s.pollServer())

            // Read all 4 chunks
            for (i in 0 until 4) {
                assertEquals(body.size, s.recvBodyServer(stream, recvBuf))
            }

            // Finished
            val ev3 = s.pollServer()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Finished, ev3.type)

            // Server responds with no body
            s.sendResponse(stream, fin = true)

            // Client receives response headers + finished
            val ev4 = s.pollClient()
            assertNotNull(ev4)
            assertEquals(KicheH3EventType.Headers, ev4.type)
            assertHeadersContain(ev4.headers!!, ":status", "200")

            val ev5 = s.pollClient()
            assertNotNull(ev5)
            assertEquals(KicheH3EventType.Finished, ev5.type)
        }
    }

    //endregion

    //region h3/mod.rs:many_requests_many_chunks_response_one_chunk (line 4009)

    /**
     * Ported from h3/mod.rs:many_requests_many_chunks_response_one_chunk()
     * 3 concurrent requests with body chunks, each gets a response.
     */
    @Test
    fun testManyRequestsManyChunksResponseOneChunk() {
        TestSession.new().use { s ->
            val stream1 = s.sendRequest(fin = false)
            assertEquals(0, stream1)
            val stream2 = s.sendRequest(fin = false)
            assertEquals(4, stream2)
            val stream3 = s.sendRequest(fin = false)
            assertEquals(8, stream3)

            val body = s.sendBodyClient(stream1, fin = false)
            s.sendBodyClient(stream2, fin = false)
            s.sendBodyClient(stream3, fin = false)

            // Close in reverse order
            s.sendBodyClient(stream3, fin = true)
            s.sendBodyClient(stream2, fin = true)
            s.sendBodyClient(stream1, fin = true)

            val recvBuf = ByteArray(body.size)

            // Server receives headers for all 3 streams
            for (expectedStream in listOf(stream1, stream2, stream3)) {
                val ev = s.pollServer()
                assertNotNull(ev)
                assertEquals(KicheH3EventType.Headers, ev.type)
            }

            // Read data + finished for each stream
            for (stream in listOf(stream1, stream2, stream3)) {
                val ev = s.pollServer()
                assertNotNull(ev)
                assertEquals(KicheH3EventType.Data, ev.type)
                assertEquals(stream, ev.streamId)

                // Read 2 body chunks
                assertEquals(body.size, s.recvBodyServer(stream, recvBuf))
                assertEquals(body.size, s.recvBodyServer(stream, recvBuf))

                val fin = s.pollServer()
                assertNotNull(fin)
                assertEquals(KicheH3EventType.Finished, fin.type)
                assertEquals(stream, fin.streamId)
            }

            assertNull(s.pollServer())

            // Server responds to all 3
            s.sendResponse(stream1, fin = true)
            s.sendResponse(stream2, fin = true)
            s.sendResponse(stream3, fin = true)

            // Client receives all 3 responses
            for (i in 0 until 3) {
                val ev = s.pollClient()
                assertNotNull(ev)
                assertEquals(KicheH3EventType.Headers, ev.type)

                val fin = s.pollClient()
                assertNotNull(fin)
                assertEquals(KicheH3EventType.Finished, fin.type)
            }

            assertNull(s.pollClient())
        }
    }

    //endregion

    //region h3/mod.rs:body_response_before_headers (line 4191)

    /**
     * Ported from h3/mod.rs:body_response_before_headers()
     * Sending body before response headers is an error.
     */
    @Test
    fun testBodyResponseBeforeHeaders() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = true)

            // Server receives request
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)

            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Finished, ev2.type)

            // Server tries to send body before headers → error
            assertFailsWith<KicheException> {
                s.sendBodyServer(stream, fin = true)
            }

            assertNull(s.pollClient())
        }
    }

    //endregion

    //region h3/mod.rs:client_request_after_goaway (line 4797)

    /**
     * Ported from h3/mod.rs:client_request_after_goaway()
     * Client must not send requests after receiving GOAWAY.
     */
    @Test
    fun testClientRequestAfterGoaway() {
        TestSession.new().use { s ->
            s.server.sendGoaway(s.pipe.server, 4000)
            s.advance()

            val ev = s.pollClient()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.GoAway, ev.type)
            assertEquals(4000, ev.streamId)

            // Request after GOAWAY should fail
            assertFailsWith<KicheException> {
                s.sendRequest(fin = true)
            }
        }
    }

    //endregion

    //region h3/mod.rs:finished_once (line 7099)

    /**
     * Ported from h3/mod.rs:finished_once()
     * Finished event fires exactly once, even after trying to read again.
     */
    @Test
    fun testFinishedOnce() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = false)
            val body = s.sendBodyClient(stream, fin = true)

            val recvBuf = ByteArray(body.size)

            // Headers
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)

            // Data
            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Data, ev2.type)

            assertEquals(body.size, s.recvBodyServer(stream, recvBuf))

            // Finished
            val ev3 = s.pollServer()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Finished, ev3.type)

            // No more events — Finished does not repeat
            assertNull(s.pollServer())
        }
    }

    //endregion

    //region h3/mod.rs:zero_length_data (line 6339)

    /**
     * Ported from h3/mod.rs:zero_length_data()
     * Zero-length body with fin is accepted; without fin returns Done.
     */
    @Test
    fun testZeroLengthData() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = false)

            // Zero-length body without fin → should fail (Done)
            assertEquals(-1, s.client.sendBody(s.pipe.client, stream, ByteArray(0), false))

            // Zero-length body with fin → accepted
            assertEquals(0, s.client.sendBody(s.pipe.client, stream, ByteArray(0), true))
            s.advance()

            val recvBuf = ByteArray(100)

            // Headers
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)

            // Data event (for the zero-length fin frame)
            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Data, ev2.type)

            // Finished
            val ev3 = s.pollServer()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Finished, ev3.type)

            assertNull(s.pollServer())

            // Server responds similarly
            s.server.sendResponse(s.pipe.server, stream, TestSession.DEFAULT_RESPONSE_HEADERS, false)
            assertEquals(-1, s.server.sendBody(s.pipe.server, stream, ByteArray(0), false))
            assertEquals(0, s.server.sendBody(s.pipe.server, stream, ByteArray(0), true))
            s.advance()

            // Client receives response
            val ev4 = s.pollClient()
            assertNotNull(ev4)
            assertEquals(KicheH3EventType.Headers, ev4.type)
            assertHeadersContain(ev4.headers!!, ":status", "200")

            val ev5 = s.pollClient()
            assertNotNull(ev5)
            assertEquals(KicheH3EventType.Data, ev5.type)

            val ev6 = s.pollClient()
            assertNotNull(ev6)
            assertEquals(KicheH3EventType.Finished, ev6.type)

            assertNull(s.pollClient())
        }
    }

    //endregion

    //region h3/mod.rs:stream_backpressure (line 5647)

    /**
     * Ported from h3/mod.rs:stream_backpressure()
     * DATA frames are truncated by stream flow control. With per-stream
     * limit=150, 6 full sends of 10 bytes (60 total) succeed, but the
     * 7th is truncated (only 8 bytes fit due to H3 frame overhead).
     *
     * Note: Rust test also checks internal data_blocked counters — skipped.
     */
    @Test
    fun testStreamBackpressure() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = false)
            val bytes = TestSession.DEFAULT_BODY // 10 bytes

            // Send 6 full body frames
            for (i in 0 until 6) {
                assertEquals(
                    bytes.size,
                    s.client.sendBody(s.pipe.client, stream, bytes, false),
                )
                s.advance()
            }

            // 7th send is truncated (stream flow control limit reached partially)
            val lastSent = s.client.sendBody(s.pipe.client, stream, bytes, true)
            assertTrue(lastSent < bytes.size, "last send should be truncated, got $lastSent")
            s.advance()

            val recvBuf = ByteArray(bytes.size)

            // Server receives headers + data event
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)

            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Data, ev2.type)

            // Read all 6 full chunks
            for (i in 0 until 6) {
                assertEquals(bytes.size, s.recvBodyServer(stream, recvBuf))
            }

            // Read the truncated last chunk
            assertEquals(lastSent, s.recvBodyServer(stream, recvBuf))

            // Fin was NOT sent because the buffer was only partially written
            // (no Finished event yet — flow control blocked the rest)
            assertNull(s.pollServer())
        }
    }

    //endregion

    //region h3/mod.rs:headers_blocked (line 5851)

    /**
     * Ported from h3/mod.rs:headers_blocked()
     * With max_data=70, the first request succeeds but the second is
     * blocked by connection-level flow control (StreamBlocked). After
     * advancing (which triggers MAX_DATA), the second request succeeds.
     *
     * Note: Rust test also checks data_blocked counters — skipped.
     */
    @Test
    fun testHeadersBlocked() {
        TestSession.newWithQuicConfig {
            setInitialMaxData(70)
            setInitialMaxStreamDataBidiLocal(150)
            setInitialMaxStreamDataBidiRemote(150)
            setInitialMaxStreamDataUni(150)
            setInitialMaxStreamsBidi(100)
            setInitialMaxStreamsUni(5)
        }.use { s ->
            val req = listOf(
                KicheH3Header(":method", "GET"),
                KicheH3Header(":scheme", "https"),
                KicheH3Header(":authority", "quic.tech"),
                KicheH3Header(":path", "/test"),
            )

            // First request succeeds
            val stream1 = s.client.sendRequest(s.pipe.client, req, true)
            assertEquals(0L, stream1)

            // Second request is blocked by connection-level flow control
            assertFailsWith<KicheException> {
                s.client.sendRequest(s.pipe.client, req, true)
            }

            s.advance()

            // After server gives flow control credits, second request succeeds
            val stream2 = s.client.sendRequest(s.pipe.client, req, true)
            assertEquals(4L, stream2)
        }
    }

    //endregion

    //region h3/mod.rs:single_dgram (line 6734)

    /**
     * Ported from h3/mod.rs:single_dgram()
     * Send a single H3 datagram (varint flow_id=0 + payload) in each direction.
     */
    @Test
    fun testSingleDgram() {
        TestSession.new().use { s ->
            val buf = ByteArray(65535)
            // Expected: 11 bytes total (1 byte varint + 10 bytes data), flow_id=0, varint_len=1
            val expected = Triple(11, 0L, 1)

            s.sendDgramClient(0)

            assertNull(s.pollServer())
            assertEquals(expected, s.recvDgramServer(buf))

            s.sendDgramServer(0)
            assertNull(s.pollClient())
            assertEquals(expected, s.recvDgramClient(buf))
        }
    }

    //endregion

    //region h3/mod.rs:multiple_dgram (line 6754)

    /**
     * Ported from h3/mod.rs:multiple_dgram()
     * Send multiple H3 datagrams in each direction.
     */
    @Test
    fun testMultipleDgram() {
        TestSession.new().use { s ->
            val buf = ByteArray(65535)
            val expected = Triple(11, 0L, 1)

            s.sendDgramClient(0)
            s.sendDgramClient(0)
            s.sendDgramClient(0)

            assertNull(s.pollServer())
            assertEquals(expected, s.recvDgramServer(buf))
            assertEquals(expected, s.recvDgramServer(buf))
            assertEquals(expected, s.recvDgramServer(buf))

            s.sendDgramServer(0)
            s.sendDgramServer(0)
            s.sendDgramServer(0)

            assertNull(s.pollClient())
            assertEquals(expected, s.recvDgramClient(buf))
            assertEquals(expected, s.recvDgramClient(buf))
            assertEquals(expected, s.recvDgramClient(buf))
        }
    }

    //endregion

    //region h3/mod.rs:multiple_dgram_overflow (line 6785)

    /**
     * Ported from h3/mod.rs:multiple_dgram_overflow()
     * Default TestSession has dgram queue len=3. Sending 5 datagrams
     * means only 3 survive on the receive side.
     */
    @Test
    fun testMultipleDgramOverflow() {
        TestSession.new().use { s ->
            val buf = ByteArray(65535)
            val expected = Triple(11, 0L, 1)

            s.sendDgramClient(0)
            s.sendDgramClient(0)
            s.sendDgramClient(0)
            s.sendDgramClient(0)
            s.sendDgramClient(0)

            assertNull(s.pollServer())
            assertEquals(expected, s.recvDgramServer(buf))
            assertEquals(expected, s.recvDgramServer(buf))
            assertEquals(expected, s.recvDgramServer(buf))
            // Queue was 3, so no more
            assertEquals(-1, s.pipe.server.dgramRecv(buf, buf.size))
        }
    }

    //endregion

    // --- Helpers ---

    private fun assertHeadersContain(headers: List<KicheH3Header>, name: String, value: String) {
        val found = headers.any { it.nameString == name && it.valueString == value }
        assertTrue(found, "Expected header $name: $value, got: ${headers.map { "${it.nameString}: ${it.valueString}" }}")
    }
}
