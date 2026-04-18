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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

            assertTrue(pipe.client.isEstablished)
            assertTrue(pipe.server.isEstablished)
            assertFalse(pipe.client.isClosed)
            assertFalse(pipe.server.isClosed)
            assertFalse(pipe.client.isServer)
            assertTrue(pipe.server.isServer)

            // Verify ALPN negotiation (part of handshake test)
            val clientProto = pipe.client.applicationProto()
            assertNotNull(clientProto)
            assertEquals("proto1", clientProto.decodeToString())

            val serverProto = pipe.server.applicationProto()
            assertNotNull(serverProto)
            assertEquals("proto1", serverProto.decodeToString())
        }
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

            val data = "hello, world".encodeToByteArray()
            assertEquals(data.size, pipe.client.streamSend(4, data, data.size, fin = true))

            pipe.advance()

            val buf = ByteArray(1024)
            val result = pipe.server.streamRecv(4, buf, buf.size)
            assertEquals(data.size, result.read)
            assertTrue(result.fin)
            assertEquals("hello, world", buf.copyOf(result.read).decodeToString())
        }
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

            val request = "request".encodeToByteArray()
            pipe.client.streamSend(4, request, request.size, fin = false)
            pipe.advance()

            val buf = ByteArray(1024)
            val recv1 = pipe.server.streamRecv(4, buf, buf.size)
            assertEquals("request", buf.copyOf(recv1.read).decodeToString())
            assertFalse(recv1.fin)

            val response = "response".encodeToByteArray()
            pipe.server.streamSend(4, response, response.size, fin = true)
            pipe.advance()

            val recv2 = pipe.client.streamRecv(4, buf, buf.size)
            assertEquals("response", buf.copyOf(recv2.read).decodeToString())
            assertTrue(recv2.fin)
        }
    }

    //endregion

    //region tests.rs:stream_readable / stream_writable (lines 4353 / 4425)

    @Test
    fun testStreamReadableWritable() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertEquals(-1, pipe.server.streamReadableNext())

            pipe.client.streamSend(4, "x".encodeToByteArray(), 1, fin = false)
            pipe.advance()

            assertTrue(pipe.server.streamReadable(4))
            assertEquals(4, pipe.server.streamReadableNext())
        }
    }

    //endregion

    //region tests.rs:stream_left_bidi (line 1928)

    /**
     * Ported from tests.rs:stream_left_bidi()
     */
    @Test
    fun testStreamLeftBidi() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertEquals(100, pipe.client.peerStreamsLeftBidi())
            assertEquals(100, pipe.server.peerStreamsLeftBidi())

            pipe.server.streamSend(1, "a".encodeToByteArray(), 1, fin = false)
            assertEquals(99, pipe.server.peerStreamsLeftBidi())

            pipe.server.streamSend(5, "a".encodeToByteArray(), 1, fin = false)
            assertEquals(98, pipe.server.peerStreamsLeftBidi())
        }
    }

    //endregion

    //region tests.rs:stream_left_uni (line 1956)

    /**
     * Ported from tests.rs:stream_left_uni()
     */
    @Test
    fun testStreamLeftUni() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertEquals(100, pipe.client.peerStreamsLeftUni())
            assertEquals(100, pipe.server.peerStreamsLeftUni())

            pipe.server.streamSend(3, "a".encodeToByteArray(), 1, fin = false)
            assertEquals(99, pipe.server.peerStreamsLeftUni())

            pipe.server.streamSend(7, "a".encodeToByteArray(), 1, fin = false)
            assertEquals(98, pipe.server.peerStreamsLeftUni())
        }
    }

    //endregion

    //region tests.rs:dgram_single_datagram (line 8231)

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
    }

    //endregion

    //region tests.rs:dgram_multiple_datagrams (line 8271)

    /**
     * Ported from tests.rs:dgram_multiple_datagrams()
     */
    @Test
    fun testDgramMultipleDatagrams() {
        TestPipe.new(dgramRecvQueueLen = 10, dgramSendQueueLen = 10).use { pipe ->
            pipe.handshake()

            assertEquals(0, pipe.client.dgramSendQueueLen())
            assertEquals(0, pipe.client.dgramSendQueueByteSize())

            pipe.client.dgramSend("hello, world".encodeToByteArray(), 12)
            pipe.client.dgramSend("ciao, mondo".encodeToByteArray(), 11)

            assertEquals(2, pipe.client.dgramSendQueueLen())
            assertEquals(23, pipe.client.dgramSendQueueByteSize())

            assertEquals(0, pipe.server.dgramRecvQueueLen())

            pipe.advance()

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
    }

    //endregion

    //region tests.rs:dgram_send_queue_overflow (line 8348)

    /**
     * Ported from tests.rs:dgram_send_queue_overflow()
     * Third datagram must be rejected with Done when send queue len is 2.
     */
    @Test
    fun testDgramSendQueueOverflow() {
        TestPipe.new(dgramRecvQueueLen = 10, dgramSendQueueLen = 2).use { pipe ->
            pipe.handshake()

            pipe.client.dgramSend("hello, world".encodeToByteArray(), 12)
            pipe.client.dgramSend("ciao, mondo".encodeToByteArray(), 11)

            // Third must fail with Done
            val e = assertFailsWith<KicheException> {
                pipe.client.dgramSend("hola, mundo".encodeToByteArray(), 11)
            }
            assertEquals(KicheError.Done, e.error)

            pipe.advance()

            val buf = ByteArray(1024)
            assertEquals(12, pipe.server.dgramRecv(buf, buf.size))
            assertEquals('h'.code.toByte(), buf[0])

            assertEquals(11, pipe.server.dgramRecv(buf, buf.size))
            assertEquals('c'.code.toByte(), buf[0])
        }
    }

    //endregion

    //region tests.rs:dgram_send_max_size (line 8447)

    /**
     * Ported from tests.rs:dgram_send_max_size()
     */
    @Test
    fun testDgramSendMaxSize() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            val maxLen = pipe.client.dgramMaxWritableLen()
            assertTrue(maxLen > 0, "dgram max writable len should be positive: $maxLen")

            val data = ByteArray(maxLen.toInt()) { 42 }
            pipe.client.dgramSend(data, data.size)

            pipe.advance()

            val buf = ByteArray(maxLen.toInt() + 100)
            val read = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(maxLen.toInt(), read)
        }
    }

    //endregion

    //region tests.rs:is_readable (line 8500)

    /**
     * Ported from tests.rs:is_readable()
     */
    @Test
    fun testIsReadable() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertFalse(pipe.client.isReadable)
            assertFalse(pipe.server.isReadable)

            pipe.client.streamSend(4, "aaaaa".encodeToByteArray(), 5, fin = false)
            pipe.advance()

            assertFalse(pipe.client.isReadable)
            assertTrue(pipe.server.isReadable)

            pipe.server.streamSend(4, "bbbbb".encodeToByteArray(), 5, fin = false)
            pipe.advance()

            assertTrue(pipe.client.isReadable)
            assertTrue(pipe.server.isReadable)

            // Client drains stream
            val buf = ByteArray(1024)
            pipe.client.streamRecv(4, buf, buf.size)
            assertFalse(pipe.client.isReadable)

            // Server shuts down stream read
            pipe.server.streamShutdown(4, KicheShutdown.Read, 0)
            assertFalse(pipe.server.isReadable)

            // Dgram makes server readable
            pipe.client.dgramSend("dddddddddddddd".encodeToByteArray(), 14)
            pipe.advance()
            assertTrue(pipe.server.isReadable)

            // Drain dgram
            pipe.server.dgramRecv(buf, buf.size)
            assertFalse(pipe.server.isReadable)
        }
    }

    //endregion

    //region tests.rs:close (line 8632)

    /**
     * Ported from tests.rs:close()
     * Second close must return Done.
     */
    @Test
    fun testClose() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            pipe.client.closeConnection(app = false, err = 0x1234, reason = "hello?".encodeToByteArray())

            // Second close should return Done
            val e = assertFailsWith<KicheException> {
                pipe.client.closeConnection(app = false, err = 0x4321, reason = "hello?".encodeToByteArray())
            }
            assertEquals(KicheError.Done, e.error)

            pipe.advance()

            assertTrue(pipe.client.isClosed || pipe.client.isDraining)
        }
    }

    //endregion

    //region tests.rs:app_close_by_client (line 8661)

    /**
     * Ported from tests.rs:app_close_by_client()
     * Second close must return Done.
     */
    @Test
    fun testAppCloseByClient() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            pipe.client.closeConnection(app = true, err = 0x1234, reason = "hello!".encodeToByteArray())

            val e = assertFailsWith<KicheException> {
                pipe.client.closeConnection(app = true, err = 0x4321, reason = "hello!".encodeToByteArray())
            }
            assertEquals(KicheError.Done, e.error)

            pipe.advance()

            assertTrue(pipe.client.isClosed || pipe.client.isDraining)
        }
    }

    //endregion

    //region tests.rs:peer_error (line 8902)

    /**
     * Ported from tests.rs:peer_error()
     */
    @Test
    fun testPeerError() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            pipe.server.closeConnection(app = false, err = 0x1234, reason = "hello?".encodeToByteArray())
            pipe.advance()

            val err = pipe.client.peerError()
            assertNotNull(err)
            assertFalse(err.isApp)
            assertEquals(0x1234, err.errorCode)
        }
    }

    //endregion

    //region tests.rs:flow_control_limit_send (line 4561)

    /**
     * Ported from tests.rs:flow_control_limit_send()
     * With max_data=30 and per-stream bidi limit=15, the client can send 15
     * bytes on two streams (30 total), but a third stream send returns Done
     * because the connection-level flow control window is exhausted.
     *
     * Note: the Rust test also asserts internal data_blocked_sent/recv counters
     * which are not exposed via the C API, so those checks are omitted.
     */
    @Test
    fun testFlowControlLimitSend() {
        TestPipe.newWithSmallLimits().use { pipe ->
            pipe.handshake()

            // First stream: 15 bytes fills stream 0's per-stream limit
            assertEquals(15, pipe.client.streamSend(0, ByteArray(15), 15, fin = false))
            pipe.advance()

            // Second stream: 15 bytes fills stream 4's per-stream limit (30 total = max_data)
            assertEquals(15, pipe.client.streamSend(4, ByteArray(15), 15, fin = false))
            pipe.advance()

            // Third stream: connection-level flow control exhausted → Done (-1)
            assertEquals(-1, pipe.client.streamSend(8, ByteArray(1), 1, fin = false))
            pipe.advance()

            // Server should have received data on exactly two streams
            val s1 = pipe.server.streamReadableNext()
            assertTrue(s1 >= 0, "first stream should be readable")
            val s2 = pipe.server.streamReadableNext()
            assertTrue(s2 >= 0, "second stream should be readable")
            assertEquals(-1, pipe.server.streamReadableNext())
        }
    }

    //endregion

    //region tests.rs:local_error (line 8940)

    /**
     * Ported from tests.rs:local_error()
     */
    @Test
    fun testLocalError() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            assertEquals(null, pipe.server.localError())

            pipe.server.closeConnection(app = true, err = 0x1234, reason = "hello!".encodeToByteArray())

            val err = pipe.server.localError()
            assertNotNull(err)
            assertTrue(err.isApp)
            assertEquals(0x1234, err.errorCode)
        }
    }

    //endregion
}
