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

    // --- Helpers ---

    private fun assertHeadersContain(headers: List<KicheH3Header>, name: String, value: String) {
        val found = headers.any { it.nameString == name && it.valueString == value }
        assertTrue(found, "Expected header $name: $value, got: ${headers.map { "${it.nameString}: ${it.valueString}" }}")
    }
}
