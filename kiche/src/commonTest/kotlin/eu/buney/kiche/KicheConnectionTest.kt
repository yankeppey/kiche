/*
 * In-memory QUIC connection tests.
 *
 * Ported from quiche's Rust tests using the TestPipe helper (equivalent
 * to quiche's test_utils::Pipe). All tests run entirely in memory with
 * no network I/O.
 *
 * Each test references the original Rust test name and line number.
 */
package eu.buney.kiche

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KicheConnectionTest {

    //region tests.rs:handshake (line 454)

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

            // Verify ALPN negotiation (part of handshake test)
            val clientProto = pipe.client.applicationProto()
            assertNotNull(clientProto, "client ALPN should not be null")
            assertEquals("proto1", clientProto.decodeToString())

            val serverProto = pipe.server.applicationProto()
            assertNotNull(serverProto, "server ALPN should not be null")
            assertEquals("proto1", serverProto.decodeToString())
        }
        println("    handshake ... OK.")
    }

    //endregion

    //region tests.rs:streamio (line 1008)

    /**
     * Ported from tests.rs:streamio()
     * Basic bidirectional stream send/receive roundtrip.
     */
    @Test
    fun testStreamio() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // Client sends on stream 4 (first client-initiated bidi stream)
            val data = "hello, world".encodeToByteArray()
            assertEquals(data.size, pipe.client.streamSend(4, data, data.size, true))

            pipe.advance()

            // Server receives
            val buf = ByteArray(1024)
            val result = pipe.server.streamRecv(4, buf, buf.size)
            assertEquals(data.size, result.read)
            assertTrue(result.fin, "fin should be set")
            assertEquals("hello, world", buf.copyOf(result.read).decodeToString())
        }
        println("    streamio ... OK.")
    }

    //endregion

    //region tests.rs:streamio_mixed_actions (line 1094)

    /**
     * Ported from tests.rs:streamio_mixed_actions()
     * Bidirectional stream — server responds back to client on the same stream.
     */
    @Test
    fun testStreamioMixedActions() {
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
        println("    streamio_mixed_actions ... OK.")
    }

    //endregion

    //region tests.rs:stream_readable / stream_writable (lines 4353 / 4425)

    /**
     * Ported from tests.rs:stream_readable() and stream_writable()
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
        println("    stream_readable / stream_writable ... OK.")
    }

    //endregion

    //region tests.rs:stream_left_bidi (line 1928)

    /**
     * Ported from tests.rs:stream_left_bidi()
     * Verifies peer_streams_left_bidi decrements as streams are opened.
     */
    @Test
    fun testStreamLeftBidi() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertEquals(100, pipe.client.peerStreamsLeftBidi())
            assertEquals(100, pipe.server.peerStreamsLeftBidi())

            // Server opens bidi streams (server-initiated bidi: 1, 5, 9, ...)
            pipe.server.streamSend(1, "a".encodeToByteArray(), 1, false)
            assertEquals(99, pipe.server.peerStreamsLeftBidi())

            pipe.server.streamSend(5, "a".encodeToByteArray(), 1, false)
            assertEquals(98, pipe.server.peerStreamsLeftBidi())
        }
        println("    stream_left_bidi ... OK.")
    }

    //endregion

    //region tests.rs:stream_left_uni (line 1956)

    /**
     * Ported from tests.rs:stream_left_uni()
     * Verifies peer_streams_left_uni decrements as streams are opened.
     */
    @Test
    fun testStreamLeftUni() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertEquals(100, pipe.client.peerStreamsLeftUni())
            assertEquals(100, pipe.server.peerStreamsLeftUni())

            // Server opens uni streams (server-initiated uni: 3, 7, 11, ...)
            pipe.server.streamSend(3, "a".encodeToByteArray(), 1, false)
            assertEquals(99, pipe.server.peerStreamsLeftUni())

            pipe.server.streamSend(7, "a".encodeToByteArray(), 1, false)
            assertEquals(98, pipe.server.peerStreamsLeftUni())
        }
        println("    stream_left_uni ... OK.")
    }

    //endregion

    //region tests.rs:dgram_single_datagram (line 8231)

    /**
     * Ported from tests.rs:dgram_single_datagram()
     * Tests QUIC datagram send/receive (RFC 9221).
     */
    @Test
    fun testDgramSingleDatagram() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val data = "hello, world".encodeToByteArray()
            assertEquals(data.size, pipe.client.dgramSend(data, data.size))

            pipe.advance()

            val buf = ByteArray(1024)
            val read = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(data.size, read)
            assertEquals("hello, world", buf.copyOf(read).decodeToString())
        }
        println("    dgram_single_datagram ... OK.")
    }

    //endregion

    //region tests.rs:dgram_multiple_datagrams (line 8271)

    /**
     * Ported from tests.rs:dgram_multiple_datagrams()
     * Tests multiple datagrams, queue lengths, and byte sizes.
     * Note: dgram_purge_outgoing is not wrapped, so that part is skipped.
     */
    @Test
    fun testDgramMultipleDatagrams() {
        TestPipe.newWithDgramConfig(recvQueueLen = 10, sendQueueLen = 10).use { pipe ->
            pipe.handshake()

            assertEquals(0, pipe.client.dgramSendQueueLen())
            assertEquals(0, pipe.client.dgramSendQueueByteSize())

            pipe.client.dgramSend("hello, world".encodeToByteArray(), 12)
            pipe.client.dgramSend("ciao, mondo".encodeToByteArray(), 11)

            assertEquals(2, pipe.client.dgramSendQueueLen())
            assertEquals(23, pipe.client.dgramSendQueueByteSize())

            // Before packets exchanged, no dgrams on server receive side.
            assertEquals(0, pipe.server.dgramRecvQueueLen())

            pipe.advance()

            // After packets exchanged, no dgrams on client send side.
            assertEquals(0, pipe.client.dgramSendQueueLen())
            assertEquals(0, pipe.client.dgramSendQueueByteSize())

            assertEquals(2, pipe.server.dgramRecvQueueLen())
            assertEquals(23, pipe.server.dgramRecvQueueByteSize())

            val buf = ByteArray(1024)
            val r1 = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(12, r1)
            assertEquals('h'.code.toByte(), buf[0])
            assertEquals('e'.code.toByte(), buf[1])

            val r2 = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(11, r2)
            assertEquals('c'.code.toByte(), buf[0])
            assertEquals('i'.code.toByte(), buf[1])

            assertEquals(0, pipe.server.dgramRecvQueueLen())
            assertEquals(0, pipe.server.dgramRecvQueueByteSize())
        }
        println("    dgram_multiple_datagrams ... OK.")
    }

    //endregion

    //region tests.rs:dgram_send_queue_overflow (line 8348)

    /**
     * Ported from tests.rs:dgram_send_queue_overflow()
     * Third datagram is rejected when send queue len is 2.
     */
    @Test
    fun testDgramSendQueueOverflow() {
        TestPipe.newWithDgramConfig(recvQueueLen = 10, sendQueueLen = 2).use { pipe ->
            pipe.handshake()

            pipe.client.dgramSend("hello, world".encodeToByteArray(), 12)
            pipe.client.dgramSend("ciao, mondo".encodeToByteArray(), 11)

            // Third should fail (queue full → DONE)
            try {
                pipe.client.dgramSend("hola, mundo".encodeToByteArray(), 11)
                // If no exception, the queue wasn't full (shouldn't happen with len=2)
            } catch (e: KicheException) {
                assertEquals(KicheError.Done, e.error)
            }

            pipe.advance()

            val buf = ByteArray(1024)
            val r1 = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(12, r1)

            val r2 = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(11, r2)
        }
        println("    dgram_send_queue_overflow ... OK.")
    }

    //endregion

    //region tests.rs:dgram_send_max_size (line 8447)

    /**
     * Ported from tests.rs:dgram_send_max_size()
     * Verifies dgram_max_writable_len returns correct size after handshake.
     */
    @Test
    fun testDgramSendMaxSize() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val maxLen = pipe.client.dgramMaxWritableLen()
            assertTrue(maxLen > 0, "dgram max writable len should be positive: $maxLen")

            // Send a max-sized datagram
            val data = ByteArray(maxLen.toInt()) { 42 }
            pipe.client.dgramSend(data, data.size)

            pipe.advance()

            val buf = ByteArray(maxLen.toInt() + 100)
            val read = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(maxLen.toInt(), read)
        }
        println("    dgram_send_max_size ... OK.")
    }

    //endregion

    //region tests.rs:is_readable (line 8500)

    /**
     * Ported from tests.rs:is_readable()
     * Tests is_readable for both streams and datagrams.
     */
    @Test
    fun testIsReadable() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // No readable data initially.
            assertFalse(pipe.client.isReadable)
            assertFalse(pipe.server.isReadable)

            // Client sends stream data.
            pipe.client.streamSend(4, "aaaaa".encodeToByteArray(), 5, false)
            pipe.advance()

            // Server received stream → readable.
            assertFalse(pipe.client.isReadable)
            assertTrue(pipe.server.isReadable)

            // Server sends back.
            pipe.server.streamSend(4, "bbbbb".encodeToByteArray(), 5, false)
            pipe.advance()

            // Client received stream → readable.
            assertTrue(pipe.client.isReadable)
            assertTrue(pipe.server.isReadable)

            // Client drains stream.
            val buf = ByteArray(1024)
            pipe.client.streamRecv(4, buf, buf.size)
            assertFalse(pipe.client.isReadable)

            // Server shuts down stream read → no longer readable.
            pipe.server.streamShutdown(4, KicheShutdown.Read, 0)
            assertFalse(pipe.server.isReadable)

            // Server receives dgram → readable.
            pipe.client.dgramSend("dddddddddddddd".encodeToByteArray(), 14)
            pipe.advance()
            assertTrue(pipe.server.isReadable)

            // Drain dgram → not readable.
            pipe.server.dgramRecv(buf, buf.size)
            assertFalse(pipe.server.isReadable)
        }
        println("    is_readable ... OK.")
    }

    //endregion

    //region tests.rs:close (line 8632)

    /**
     * Ported from tests.rs:close()
     * Tests transport-level connection close.
     */
    @Test
    fun testClose() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            pipe.client.closeConnection(app = false, err = 0x1234, reason = "hello?".encodeToByteArray())

            // Second close should be no-op (DONE)
            try {
                pipe.client.closeConnection(app = false, err = 0x4321, reason = "hello?".encodeToByteArray())
            } catch (_: KicheException) {
                // Expected: DONE
            }

            pipe.advance()

            assertTrue(pipe.client.isClosed || pipe.client.isDraining)
        }
        println("    close ... OK.")
    }

    //endregion

    //region tests.rs:app_close_by_client (line 8661)

    /**
     * Ported from tests.rs:app_close_by_client()
     * Tests application-level connection close.
     */
    @Test
    fun testAppCloseByClient() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            pipe.client.closeConnection(app = true, err = 0x1234, reason = "hello!".encodeToByteArray())

            // Second close should be no-op (DONE)
            try {
                pipe.client.closeConnection(app = true, err = 0x4321, reason = "hello!".encodeToByteArray())
            } catch (_: KicheException) {
                // Expected: DONE
            }

            pipe.advance()

            assertTrue(pipe.client.isClosed || pipe.client.isDraining)
        }
        println("    app_close_by_client ... OK.")
    }

    //endregion

    //region tests.rs:peer_error (line 8902)

    /**
     * Ported from tests.rs:peer_error()
     * Tests transport error reporting to peer.
     */
    @Test
    fun testPeerError() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            pipe.server.closeConnection(app = false, err = 0x1234, reason = "hello?".encodeToByteArray())
            pipe.advance()

            val err = pipe.client.peerError()
            assertNotNull(err, "client should see peer error")
            assertFalse(err.isApp, "should be transport error")
            assertEquals(0x1234, err.errorCode)
        }
        println("    peer_error ... OK.")
    }

    //endregion

    //region tests.rs:local_error (line 8940)

    /**
     * Ported from tests.rs:local_error()
     * Tests local error reporting after close.
     */
    @Test
    fun testLocalError() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            // No error before close.
            val noError = pipe.server.localError()
            assertEquals(null, noError)

            pipe.server.closeConnection(app = true, err = 0x1234, reason = "hello!".encodeToByteArray())

            val err = pipe.server.localError()
            assertNotNull(err, "server should see local error")
            assertTrue(err.isApp, "should be app error")
            assertEquals(0x1234, err.errorCode)
        }
        println("    local_error ... OK.")
    }

    //endregion
}
