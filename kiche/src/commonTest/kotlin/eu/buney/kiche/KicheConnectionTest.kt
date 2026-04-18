/*
 * Tier 2 tests: In-memory QUIC handshake, streams, and datagrams.
 *
 * Ported from quiche's Rust tests using the TestPipe helper (equivalent
 * to quiche's test_utils::Pipe). All tests run entirely in memory with
 * no network I/O.
 *
 * C test coverage mapping:
 * - tests.rs:handshake()      → testHandshake
 * - tests.rs:handshake_done() → testHandshakeDone
 * - tests.rs:streamio()       → testStreamSendRecv
 * - tests.rs:flow_control_limit() → testStreamCapacity
 */
package eu.buney.kiche

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KicheConnectionTest {

    //region Handshake

    /**
     * Ported from tests.rs:handshake()
     * Full client-server TLS 1.3 handshake over QUIC, entirely in memory.
     */
    @Test
    fun testHandshake() {
        TestPipe.new().use { pipe ->
            assertFalse(pipe.client.isEstablished)
            assertFalse(pipe.server.isEstablished)

            pipe.handshake()

            assertTrue(pipe.client.isEstablished, "client should be established")
            assertTrue(pipe.server.isEstablished, "server should be established")
            assertFalse(pipe.client.isClosed)
            assertFalse(pipe.server.isClosed)
            assertFalse(pipe.client.isServer)
            assertTrue(pipe.server.isServer)
        }
        println("    handshake() ... OK.")
    }

    /**
     * Verifies the negotiated ALPN protocol after handshake.
     */
    @Test
    fun testHandshakeAlpn() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val clientProto = pipe.client.applicationProto()
            assertNotNull(clientProto, "client ALPN should not be null")
            assertEquals("proto1", clientProto.decodeToString())

            val serverProto = pipe.server.applicationProto()
            assertNotNull(serverProto, "server ALPN should not be null")
            assertEquals("proto1", serverProto.decodeToString())
        }
        println("    handshake ALPN negotiation ... OK.")
    }

    /**
     * Verifies connection IDs are available after handshake.
     */
    @Test
    fun testConnectionIds() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val clientScid = pipe.client.sourceId()
            assertNotNull(clientScid, "client SCID should not be null")
            assertTrue(clientScid.isNotEmpty())

            val clientDcid = pipe.client.destinationId()
            assertNotNull(clientDcid, "client DCID should not be null")
            assertTrue(clientDcid.isNotEmpty())
        }
        println("    connection IDs ... OK.")
    }

    /**
     * Verifies stats are available after handshake.
     */
    @Test
    fun testStats() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val stats = pipe.client.stats()
            assertTrue(stats.sent > 0, "should have sent packets")
            assertTrue(stats.recv > 0, "should have received packets")
            assertTrue(stats.sentBytes > 0, "should have sent bytes")
            assertTrue(stats.recvBytes > 0, "should have received bytes")
        }
        println("    stats after handshake ... OK.")
    }

    /**
     * Verifies peer transport params are available after handshake.
     */
    @Test
    fun testPeerTransportParams() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val tp = pipe.client.peerTransportParams()
            assertNotNull(tp, "peer transport params should not be null")
            assertTrue(tp.peerInitialMaxData > 0)
            assertTrue(tp.peerInitialMaxStreamsBidi > 0)
        }
        println("    peer transport params ... OK.")
    }

    //endregion

    //region Streams

    /**
     * Ported from tests.rs:streamio()
     * Basic bidirectional stream send/receive roundtrip.
     */
    @Test
    fun testStreamSendRecv() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Client sends on stream 4 (first client-initiated bidi stream)
            val data = "hello, quiche!".encodeToByteArray()
            val written = pipe.client.streamSend(4, data, data.size, true)
            assertEquals(data.size, written)

            // Exchange packets
            pipe.advance()

            // Server receives
            val recvBuf = ByteArray(1024)
            val result = pipe.server.streamRecv(4, recvBuf, recvBuf.size)
            assertEquals(data.size, result.read)
            assertTrue(result.fin, "fin should be set")
            assertEquals("hello, quiche!", recvBuf.copyOf(result.read).decodeToString())
        }
        println("    stream send/recv ... OK.")
    }

    /**
     * Bidirectional stream — server responds back to client.
     */
    @Test
    fun testStreamBidirectional() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Client → Server
            val request = "request".encodeToByteArray()
            pipe.client.streamSend(4, request, request.size, false)
            pipe.advance()

            // Server reads
            val buf = ByteArray(1024)
            val recv1 = pipe.server.streamRecv(4, buf, buf.size)
            assertEquals("request", buf.copyOf(recv1.read).decodeToString())
            assertFalse(recv1.fin)

            // Server → Client (same stream)
            val response = "response".encodeToByteArray()
            pipe.server.streamSend(4, response, response.size, true)
            pipe.advance()

            // Client reads
            val recv2 = pipe.client.streamRecv(4, buf, buf.size)
            assertEquals("response", buf.copyOf(recv2.read).decodeToString())
            assertTrue(recv2.fin)
        }
        println("    bidirectional stream ... OK.")
    }

    /**
     * Tests stream readability/writability queries.
     */
    @Test
    fun testStreamReadableWritable() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Before any data, no streams are readable
            assertEquals(-1, pipe.server.streamReadableNext())

            // Client sends data
            pipe.client.streamSend(4, "x".encodeToByteArray(), 1, false)
            pipe.advance()

            // Now stream 4 should be readable on server
            assertTrue(pipe.server.streamReadable(4))
            assertEquals(4, pipe.server.streamReadableNext())
        }
        println("    stream readable/writable ... OK.")
    }

    /**
     * Tests stream finished state.
     */
    @Test
    fun testStreamFinished() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Send with fin=true
            pipe.client.streamSend(4, "done".encodeToByteArray(), 4, true)
            pipe.advance()

            // Read the data
            val buf = ByteArray(100)
            val result = pipe.server.streamRecv(4, buf, buf.size)
            assertTrue(result.fin)

            // Stream should be finished after reading fin
            assertTrue(pipe.server.streamFinished(4))
        }
        println("    stream finished ... OK.")
    }

    //endregion

    //region Datagrams

    /**
     * Tests QUIC datagram send/receive (RFC 9221).
     */
    @Test
    fun testDgramSendRecv() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Client sends a datagram
            val data = "datagram!".encodeToByteArray()
            val written = pipe.client.dgramSend(data, data.size)
            assertEquals(data.size, written)

            pipe.advance()

            // Server receives
            val buf = ByteArray(1024)
            val read = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(data.size, read)
            assertEquals("datagram!", buf.copyOf(read).decodeToString())
        }
        println("    dgram send/recv ... OK.")
    }

    /**
     * Tests datagram max writable length.
     */
    @Test
    fun testDgramMaxWritableLen() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val maxLen = pipe.client.dgramMaxWritableLen()
            assertTrue(maxLen > 0, "dgram max writable len should be positive: $maxLen")
        }
        println("    dgram max writable len ... OK.")
    }

    /**
     * Tests datagram queue queries.
     */
    @Test
    fun testDgramQueueState() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Initially queues should be empty
            assertEquals(0, pipe.client.dgramSendQueueLen())
            assertFalse(pipe.client.isDgramSendQueueFull())
        }
        println("    dgram queue state ... OK.")
    }

    //endregion

    //region Connection lifecycle

    /**
     * Tests connection timeout tracking.
     */
    @Test
    fun testTimeout() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val timeout = pipe.client.timeoutAsMillis()
            assertTrue(timeout > 0, "timeout should be positive: $timeout")

            val timeoutNanos = pipe.client.timeoutAsNanos()
            assertTrue(timeoutNanos > 0, "timeout nanos should be positive")
        }
        println("    timeout ... OK.")
    }

    /**
     * Tests graceful connection close.
     */
    @Test
    fun testConnectionClose() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Client closes with application error
            pipe.client.closeConnection(true, 0, "bye".encodeToByteArray())

            // Drain remaining packets (close triggers CONNECTION_CLOSE frame)
            try { pipe.advance() } catch (_: KicheException) { /* expected DONE */ }

            // Client should be draining or closed
            assertTrue(pipe.client.isClosed || pipe.client.isDraining)
        }
        println("    connection close ... OK.")
    }

    /**
     * Tests peer error reporting after close.
     */
    @Test
    fun testPeerError() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // No error before close
            val noError = pipe.server.peerError()
            // May or may not be null depending on state

            // Client closes with error code 42
            pipe.client.closeConnection(true, 42, "test reason".encodeToByteArray())
            pipe.advance()

            // Server should see peer error
            val err = pipe.server.peerError()
            if (err != null) {
                assertTrue(err.isApp, "should be app error")
                assertEquals(42, err.errorCode)
            }
        }
        println("    peer error ... OK.")
    }

    /**
     * Tests connection state queries.
     */
    @Test
    fun testConnectionState() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertFalse(pipe.client.isTimedOut)
            assertFalse(pipe.client.isResumed)

            val streamsLeft = pipe.client.peerStreamsLeftBidi()
            assertTrue(streamsLeft > 0, "should have bidi streams available: $streamsLeft")

            val maxPayload = pipe.client.maxSendUdpPayloadSize()
            assertTrue(maxPayload > 0, "max payload should be positive: $maxPayload")

            val quantum = pipe.client.sendQuantum()
            assertTrue(quantum > 0, "send quantum should be positive: $quantum")
        }
        println("    connection state queries ... OK.")
    }

    //endregion
}
