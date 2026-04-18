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
            assertHeadersContain(ev1.headers!!, ":method", "GET")

            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Finished, ev2.type)
            assertEquals(stream, ev2.streamId)

            // Server sends response
            s.sendResponse(stream, fin = true)

            // Client receives response headers + finished
            val ev3 = s.pollClient()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Headers, ev3.type)
            assertEquals(stream, ev3.streamId)
            assertNotNull(ev3.headers)
            assertHeadersContain(ev3.headers!!, ":status", "200")

            val ev4 = s.pollClient()
            assertNotNull(ev4)
            assertEquals(KicheH3EventType.Finished, ev4.type)

            // No more events
            assertNull(s.pollClient())
        }
        println("    request_no_body_response_no_body ... OK.")
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

            // Server receives headers + finished
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)

            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Finished, ev2.type)

            // Server sends response headers (not fin) + body (fin)
            s.sendResponse(stream, fin = false)
            val body = s.sendBodyServer(stream, fin = true)

            // Client receives headers
            val ev3 = s.pollClient()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Headers, ev3.type)
            assertHeadersContain(ev3.headers!!, ":status", "200")

            // Client receives data event
            val ev4 = s.pollClient()
            assertNotNull(ev4)
            assertEquals(KicheH3EventType.Data, ev4.type)

            // Read body
            val recvBuf = ByteArray(body.size)
            val read = s.recvBodyClient(stream, recvBuf)
            assertEquals(body.size, read)

            // Finished
            val ev5 = s.pollClient()
            assertNotNull(ev5)
            assertEquals(KicheH3EventType.Finished, ev5.type)

            assertNull(s.pollClient())
        }
        println("    request_no_body_response_one_chunk ... OK.")
    }

    //endregion

    //region h3/mod.rs:request_no_body_response_many_chunks (line 3793)

    /**
     * Ported from h3/mod.rs:request_no_body_response_many_chunks()
     * Send a request with no body, get a response with multiple DATA frames.
     */
    @Test
    fun testRequestNoBodyResponseManyChunks() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = true)

            // Server drains headers + finished
            s.pollServer() // Headers
            s.pollServer() // Finished

            // Server sends response + 4 body chunks
            s.sendResponse(stream, fin = false)
            for (i in 0 until 3) {
                s.sendBodyServer(stream, fin = false)
            }
            val body = s.sendBodyServer(stream, fin = true)

            // Client receives headers
            val ev = s.pollClient()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.Headers, ev.type)

            // Client receives data event
            val evData = s.pollClient()
            assertNotNull(evData)
            assertEquals(KicheH3EventType.Data, evData.type)

            // Read all body chunks
            val recvBuf = ByteArray(body.size)
            var totalRead = 0
            for (i in 0 until 4) {
                totalRead += s.recvBodyClient(stream, recvBuf)
            }
            assertTrue(totalRead > 0)

            // Finished
            val evFin = s.pollClient()
            assertNotNull(evFin)
            assertEquals(KicheH3EventType.Finished, evFin.type)

            assertNull(s.pollClient())
        }
        println("    request_no_body_response_many_chunks ... OK.")
    }

    //endregion

    //region h3/mod.rs:request_one_chunk_response_no_body (line 3927)

    /**
     * Ported from h3/mod.rs:request_one_chunk_response_no_body()
     * Send a request with one body chunk, get a response with no body.
     */
    @Test
    fun testRequestOneChunkResponseNoBody() {
        TestSession.new().use { s ->
            val stream = s.sendRequest(fin = false)
            val body = s.sendBodyClient(stream, fin = true)

            // Server receives headers
            val ev1 = s.pollServer()
            assertNotNull(ev1)
            assertEquals(KicheH3EventType.Headers, ev1.type)

            // Server receives data event
            val ev2 = s.pollServer()
            assertNotNull(ev2)
            assertEquals(KicheH3EventType.Data, ev2.type)

            // Read body
            val recvBuf = ByteArray(body.size)
            val read = s.recvBodyServer(stream, recvBuf)
            assertEquals(body.size, read)

            // Finished
            val ev3 = s.pollServer()
            assertNotNull(ev3)
            assertEquals(KicheH3EventType.Finished, ev3.type)

            // Server responds
            s.sendResponse(stream, fin = true)

            // Client receives response
            val ev4 = s.pollClient()
            assertNotNull(ev4)
            assertEquals(KicheH3EventType.Headers, ev4.type)

            val ev5 = s.pollClient()
            assertNotNull(ev5)
            assertEquals(KicheH3EventType.Finished, ev5.type)
        }
        println("    request_one_chunk_response_no_body ... OK.")
    }

    //endregion

    //region h3/mod.rs:goaway_from_client_good (line 4768)

    /**
     * Ported from h3/mod.rs:goaway_from_client_good()
     * Send a GOAWAY frame from the client.
     */
    @Test
    fun testGoawayFromClientGood() {
        TestSession.new().use { s ->
            s.client.sendGoaway(s.pipe.client, 100)
            s.advance()

            val ev = s.pollServer()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.GoAway, ev.type)
        }
        println("    goaway_from_client_good ... OK.")
    }

    //endregion

    //region h3/mod.rs:goaway_from_server_good (line 4782)

    /**
     * Ported from h3/mod.rs:goaway_from_server_good()
     * Send a GOAWAY frame from the server.
     */
    @Test
    fun testGoawayFromServerGood() {
        TestSession.new().use { s ->
            s.server.sendGoaway(s.pipe.server, 4000)
            s.advance()

            val ev = s.pollClient()
            assertNotNull(ev)
            assertEquals(KicheH3EventType.GoAway, ev.type)
            assertEquals(4000, ev.streamId)
        }
        println("    goaway_from_server_good ... OK.")
    }

    //endregion

    // --- Helpers ---

    private fun assertHeadersContain(headers: List<KicheH3Header>, name: String, value: String) {
        val found = headers.any { it.nameString == name && it.valueString == value }
        assertTrue(found, "Expected header $name: $value, got: ${headers.map { "${it.nameString}: ${it.valueString}" }}")
    }
}
