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
import kotlin.test.assertNull
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

    //region tests.rs:stream_limit_update_bidi (line 4961)

    /**
     * Ported from tests.rs:stream_limit_update_bidi()
     * With max_streams_bidi=3, the client can open 3 bidi streams. After both
     * sides close all 3 and the server reads the data, the server sends
     * MAX_STREAMS allowing the client to open 3 more. A 4th then fails
     * with StreamLimit.
     */
    @Test
    fun testStreamLimitUpdateBidi() {
        TestPipe.newWithSmallLimits().use { pipe ->
            pipe.handshake()

            // Client opens 3 bidi streams (0, 4, 8) and sends data with fin.
            pipe.client.streamSend(0, "a".encodeToByteArray(), 1, fin = false)
            pipe.advance()
            pipe.client.streamSend(4, "a".encodeToByteArray(), 1, fin = false)
            pipe.advance()
            pipe.client.streamSend(4, "b".encodeToByteArray(), 1, fin = true)
            pipe.advance()
            pipe.client.streamSend(0, "b".encodeToByteArray(), 1, fin = true)
            pipe.advance()

            // Server reads all stream data (consuming it frees the stream slots).
            val buf = ByteArray(1024)
            pipe.server.streamRecv(0, buf, buf.size)
            pipe.server.streamRecv(4, buf, buf.size)
            pipe.advance()

            // Server responds and closes both streams.
            pipe.server.streamSend(0, "a".encodeToByteArray(), 1, fin = false)
            pipe.advance()
            pipe.server.streamSend(4, "a".encodeToByteArray(), 1, fin = false)
            pipe.advance()
            pipe.server.streamSend(4, "b".encodeToByteArray(), 1, fin = true)
            pipe.advance()
            pipe.server.streamSend(0, "b".encodeToByteArray(), 1, fin = true)

            // Server sends MAX_STREAMS in this advance.
            pipe.advance()

            // Client can now open 3 new bidi streams (8, 12, 16).
            assertEquals(1, pipe.client.streamSend(8, "a".encodeToByteArray(), 1, fin = false))
            pipe.advance()
            assertEquals(1, pipe.client.streamSend(12, "a".encodeToByteArray(), 1, fin = false))
            pipe.advance()
            assertEquals(1, pipe.client.streamSend(16, "a".encodeToByteArray(), 1, fin = false))
            pipe.advance()

            // 4th stream exceeds limit.
            assertFailsWith<KicheException> {
                pipe.client.streamSend(20, "a".encodeToByteArray(), 1, fin = false)
            }.also { assertEquals(KicheError.StreamLimit, it.error) }
        }
    }

    //endregion

    //region tests.rs:stream_limit_update_uni (line 5040)

    /**
     * Ported from tests.rs:stream_limit_update_uni()
     * Same as bidi variant but for unidirectional streams.
     * Client-initiated uni stream IDs: 2, 6, 10, 14, 18, 22, ...
     */
    @Test
    fun testStreamLimitUpdateUni() {
        TestPipe.newWithSmallLimits().use { pipe ->
            pipe.handshake()

            // Client opens 3 uni streams (2, 6) and sends data with fin.
            pipe.client.streamSend(2, "a".encodeToByteArray(), 1, fin = false)
            pipe.advance()
            pipe.client.streamSend(6, "a".encodeToByteArray(), 1, fin = false)
            pipe.advance()
            pipe.client.streamSend(6, "b".encodeToByteArray(), 1, fin = true)
            pipe.advance()
            pipe.client.streamSend(2, "b".encodeToByteArray(), 1, fin = true)
            pipe.advance()

            // Server reads all stream data.
            val buf = ByteArray(1024)
            pipe.server.streamRecv(2, buf, buf.size)
            pipe.server.streamRecv(6, buf, buf.size)

            // Server sends MAX_STREAMS.
            pipe.advance()

            // Client can now open 3 new uni streams (10, 14, 18).
            assertEquals(1, pipe.client.streamSend(10, "a".encodeToByteArray(), 1, fin = false))
            pipe.advance()
            assertEquals(1, pipe.client.streamSend(14, "a".encodeToByteArray(), 1, fin = false))
            pipe.advance()
            assertEquals(1, pipe.client.streamSend(18, "a".encodeToByteArray(), 1, fin = false))
            pipe.advance()

            // 4th stream exceeds limit.
            assertFailsWith<KicheException> {
                pipe.client.streamSend(22, "a".encodeToByteArray(), 1, fin = false)
            }.also { assertEquals(KicheError.StreamLimit, it.error) }
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

    //region tests.rs:handshake_resumption (line 544)

    /**
     * Ported from tests.rs:handshake_resumption()
     * Performs an initial handshake, extracts the TLS session ticket,
     * then verifies a second connection using the saved session resumes.
     */
    @Test
    fun testHandshakeResumption() {
        val certDir = quicheCertDir()
        val ticketKey = ByteArray(48) { 0x0a }

        // First handshake — establish and extract session.
        val serverConfig1 = KicheConfig().apply {
            loadCertChainFromPemFile("$certDir/cert.crt")
            loadPrivKeyFromPemFile("$certDir/cert.key")
            setApplicationProtos(TestPipe.PROTOS)
            setInitialMaxData(30)
            setInitialMaxStreamDataBidiLocal(15)
            setInitialMaxStreamDataBidiRemote(15)
            setInitialMaxStreamsBidi(3)
            setMaxIdleTimeout(180_000)
            verifyPeer(false)
            setTicketKey(ticketKey)
        }
        val session: ByteArray
        TestPipe.newWithServerConfig(serverConfig1).use { pipe ->
            pipe.handshake()
            assertTrue(pipe.client.isEstablished)
            assertTrue(pipe.server.isEstablished)
            assertFalse(pipe.client.isResumed)
            assertFalse(pipe.server.isResumed)
            session = pipe.client.session() ?: fail("session ticket should be available")
        }

        // Second handshake — resume using saved session.
        val serverConfig2 = KicheConfig().apply {
            loadCertChainFromPemFile("$certDir/cert.crt")
            loadPrivKeyFromPemFile("$certDir/cert.key")
            setApplicationProtos(TestPipe.PROTOS)
            setInitialMaxData(30)
            setInitialMaxStreamDataBidiLocal(15)
            setInitialMaxStreamDataBidiRemote(15)
            setInitialMaxStreamsBidi(3)
            setMaxIdleTimeout(180_000)
            verifyPeer(false)
            setTicketKey(ticketKey)
        }
        TestPipe.newWithServerConfig(serverConfig2).use { pipe ->
            pipe.client.setSession(session)
            pipe.handshake()
            assertTrue(pipe.client.isEstablished)
            assertTrue(pipe.server.isEstablished)
            assertTrue(pipe.client.isResumed)
            assertTrue(pipe.server.isResumed)
        }
    }

    //endregion

    //region tests.rs:handshake_alpn_mismatch (line 617)

    /**
     * Ported from tests.rs:handshake_alpn_mismatch()
     * Client uses different ALPN protocols than server, handshake fails with TlsFail.
     *
     * Note: The Rust test also checks `sent_count == 1` (internal field). We
     * verify the observable behavior: both sides report empty application_proto.
     */
    @Test
    fun testHandshakeAlpnMismatch() {
        // Client uses proto3/proto4, server uses proto1/proto2 (default).
        val mismatchedProtos = byteArrayOf(
            0x06, 'p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(),
            't'.code.toByte(), 'o'.code.toByte(), '3'.code.toByte(),
            0x06, 'p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(),
            't'.code.toByte(), 'o'.code.toByte(), '4'.code.toByte(),
        )
        val clientConfig = KicheConfig().apply {
            setApplicationProtos(mismatchedProtos)
            setMaxIdleTimeout(180_000)
            verifyPeer(false)
        }
        TestPipe.newWithClientConfig(clientConfig).use { pipe ->
            try {
                pipe.handshake()
                fail("handshake should fail with ALPN mismatch")
            } catch (_: KicheException) {
                // Expected: TlsFail during handshake
            }

            // Both sides should report empty ALPN.
            val clientProto = pipe.client.applicationProto()
            assertTrue(clientProto == null || clientProto.isEmpty())
            val serverProto = pipe.server.applicationProto()
            assertTrue(serverProto == null || serverProto.isEmpty())
        }
    }

    //endregion

    //region tests.rs:stream_left_reset_bidi (line 2129)

    /**
     * Ported from tests.rs:stream_left_reset_bidi()
     * Client opens 3 bidi streams (the limit), resets one, server resets its
     * side too → client gets one stream slot back. Repeat for all 3 → all
     * slots recovered.
     */
    @Test
    fun testStreamLeftResetBidi() {
        TestPipe.newWithSmallLimits().use { pipe ->
            pipe.handshake()

            assertEquals(3, pipe.client.peerStreamsLeftBidi())
            assertEquals(3, pipe.server.peerStreamsLeftBidi())

            // Client opens 3 bidi streams.
            pipe.client.streamSend(0, "a".encodeToByteArray(), 1, fin = false)
            assertEquals(2, pipe.client.peerStreamsLeftBidi())
            pipe.client.streamSend(4, "a".encodeToByteArray(), 1, fin = false)
            assertEquals(1, pipe.client.peerStreamsLeftBidi())
            pipe.client.streamSend(8, "a".encodeToByteArray(), 1, fin = false)
            assertEquals(0, pipe.client.peerStreamsLeftBidi())

            // Client resets stream 0 (Write direction sends RESET_STREAM).
            pipe.client.streamShutdown(0, KicheShutdown.Write, 1001)
            pipe.advance()

            assertEquals(0, pipe.client.peerStreamsLeftBidi())

            // Server reads stream 0 and gets StreamReset.
            val buf = ByteArray(1024)
            assertFailsWith<KicheException> {
                pipe.server.streamRecv(0, buf, buf.size)
            }.also { assertEquals(KicheError.StreamReset, it.error) }

            // Server resets stream 0 in response.
            pipe.server.streamShutdown(0, KicheShutdown.Write, 1001)
            pipe.advance()

            // Client gets one slot back.
            assertEquals(1, pipe.client.peerStreamsLeftBidi())

            // Reset remaining 2 streams from both sides.
            pipe.client.streamShutdown(4, KicheShutdown.Write, 1001)
            pipe.client.streamShutdown(8, KicheShutdown.Write, 1001)
            pipe.advance()

            assertFailsWith<KicheException> {
                pipe.server.streamRecv(4, buf, buf.size)
            }.also { assertEquals(KicheError.StreamReset, it.error) }
            assertFailsWith<KicheException> {
                pipe.server.streamRecv(8, buf, buf.size)
            }.also { assertEquals(KicheError.StreamReset, it.error) }

            pipe.server.streamShutdown(4, KicheShutdown.Write, 1001)
            pipe.server.streamShutdown(8, KicheShutdown.Write, 1001)
            pipe.advance()

            // All 3 slots recovered.
            assertEquals(3, pipe.client.peerStreamsLeftBidi())
        }
    }

    //endregion

    //region tests.rs:stream_shutdown_uni (line 4097)

    /**
     * Ported from tests.rs:stream_shutdown_uni()
     * Validates that only valid shutdown directions are allowed on uni streams:
     * can shutdown Write on a send-only stream, Read on a receive-only stream,
     * but not the reverse.
     */
    @Test
    fun testStreamShutdownUni() {
        TestPipe.newWithSmallLimits().use { pipe ->
            pipe.handshake()

            // Client sends on uni stream 2 (client-initiated uni), server on 3 (server-initiated uni).
            pipe.client.streamSend(2, "hello, world".encodeToByteArray(), 10, fin = false)
            pipe.server.streamSend(3, "hello, world".encodeToByteArray(), 10, fin = false)
            pipe.advance()

            // Client can shutdown Write on stream 2 (it's the sender).
            pipe.client.streamShutdown(2, KicheShutdown.Write, 42)

            // Client cannot shutdown Read on stream 2 (it's send-only for client).
            assertFailsWith<KicheException> {
                pipe.client.streamShutdown(2, KicheShutdown.Read, 42)
            }.also { assertEquals(KicheError.InvalidStreamState, it.error) }

            // Client cannot shutdown Write on stream 3 (it's receive-only for client).
            assertFailsWith<KicheException> {
                pipe.client.streamShutdown(3, KicheShutdown.Write, 42)
            }.also { assertEquals(KicheError.InvalidStreamState, it.error) }

            // Client can shutdown Read on stream 3 (it's the receiver).
            pipe.client.streamShutdown(3, KicheShutdown.Read, 42)
        }
    }

    //endregion

    //region tests.rs:stream_shutdown_write_unsent_tx_cap (line 4220)

    /**
     * Ported from tests.rs:stream_shutdown_write_unsent_tx_cap()
     * With max_data=15, the server fills the connection-level flow control
     * window by buffering data on stream 4. After shutting down Write on
     * stream 4, unsent data is dropped and the freed capacity allows the
     * server to send on a different stream (8).
     *
     * Note: Rust test also checks internal pipe.server.tx_data and
     * pipe.client.flow_control.should_update_max_data() — skipped here.
     */
    @Test
    fun testStreamShutdownWriteUnsentTxCap() {
        TestPipe.newWithSmallMaxData().use { pipe ->
            pipe.handshake()

            // Client sends data on stream 4 so the server can respond.
            pipe.client.streamSend(4, "hello".encodeToByteArray(), 5, fin = true)
            pipe.advance()

            // Server reads client data.
            val buf = ByteArray(1024)
            pipe.server.streamRecv(4, buf, buf.size)

            // Server sends 5 bytes (delivered).
            assertEquals(5, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))
            pipe.advance()

            // Server buffers more data until connection-level flow control exhausted.
            assertEquals(5, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))
            assertEquals(5, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))
            // max_data=15 reached, next send should return Done.
            assertEquals(-1, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))

            // Server shuts down Write on stream 4 → unsent buffered data dropped.
            pipe.server.streamShutdown(4, KicheShutdown.Write, 42)

            // Server can now send on a different stream (flow control freed).
            pipe.client.streamSend(8, "hello".encodeToByteArray(), 5, fin = true)
            pipe.advance()

            assertEquals(5, pipe.server.streamSend(8, "hello".encodeToByteArray(), 5, fin = false))
            assertEquals(5, pipe.server.streamSend(8, "hello".encodeToByteArray(), 5, fin = false))
            // Should hit the limit again.
            assertEquals(-1, pipe.server.streamSend(8, "hello".encodeToByteArray(), 5, fin = false))
            pipe.advance()
        }
    }

    //endregion

    //region tests.rs:stop_sending_unsent_tx_cap (line 3437)

    /**
     * Ported from tests.rs:stop_sending_unsent_tx_cap()
     * Same as stream_shutdown_write_unsent_tx_cap but the flow control is
     * freed by the client sending STOP_SENDING (via stream_shutdown Read)
     * instead of the server shutting down Write.
     *
     * Note: Rust test injects a raw StopSending frame via send_pkt_to_server.
     * We achieve the same effect using the public API: client calls
     * stream_shutdown(Read) which sends STOP_SENDING to the server.
     */
    @Test
    fun testStopSendingUnsentTxCap() {
        TestPipe.newWithSmallMaxData().use { pipe ->
            pipe.handshake()

            // Client sends data on stream 4 so the server can respond.
            pipe.client.streamSend(4, "hello".encodeToByteArray(), 5, fin = true)
            pipe.advance()

            // Server reads client data.
            val buf = ByteArray(1024)
            pipe.server.streamRecv(4, buf, buf.size)

            // Server sends 5 bytes (delivered).
            assertEquals(5, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))
            pipe.advance()

            // Server buffers more data until connection-level flow control exhausted.
            assertEquals(5, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))
            assertEquals(5, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))
            assertEquals(-1, pipe.server.streamSend(4, "hello".encodeToByteArray(), 5, fin = false))

            // Client sends STOP_SENDING on stream 4 (shutdown Read direction).
            pipe.client.streamShutdown(4, KicheShutdown.Read, 42)
            pipe.advance()

            // Server can now send on a different stream (flow control freed by STOP_SENDING).
            pipe.client.streamSend(8, "hello".encodeToByteArray(), 5, fin = true)
            pipe.advance()

            assertEquals(5, pipe.server.streamSend(8, "hello".encodeToByteArray(), 5, fin = false))
            assertEquals(5, pipe.server.streamSend(8, "hello".encodeToByteArray(), 5, fin = false))
            assertEquals(-1, pipe.server.streamSend(8, "hello".encodeToByteArray(), 5, fin = false))
            pipe.advance()
        }
    }

    //endregion

    //region tests.rs:dgram_send_fails_invalidstate (line 8130)

    /**
     * Ported from tests.rs:dgram_send_fails_invalidstate()
     * Sending a datagram on a connection with datagrams disabled → InvalidState.
     */
    @Test
    fun testDgramSendFailsInvalidState() {
        TestPipe.newNoDgram().use { pipe ->
            pipe.handshake()

            assertFailsWith<KicheException> {
                pipe.client.dgramSend("hello, world".encodeToByteArray(), 12)
            }.also { assertEquals(KicheError.InvalidState, it.error) }
        }
    }

    //endregion

    //region tests.rs:dgram_recv_queue_overflow (line 8398)

    /**
     * Ported from tests.rs:dgram_recv_queue_overflow()
     * With recv queue len=2, sending 3 datagrams drops the oldest;
     * only the 2 most recent are received.
     */
    @Test
    fun testDgramRecvQueueOverflow() {
        TestPipe.new(dgramRecvQueueLen = 2, dgramSendQueueLen = 10).use { pipe ->
            pipe.handshake()

            pipe.client.dgramSend("hello, world".encodeToByteArray(), 12)
            pipe.client.dgramSend("ciao, mondo".encodeToByteArray(), 11)
            pipe.client.dgramSend("hola, mundo".encodeToByteArray(), 11)

            pipe.advance()

            // Oldest ("hello, world") was dropped. "ciao, mondo" is first.
            val buf = ByteArray(1024)
            val r1 = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(11, r1)
            assertEquals('c'.code.toByte(), buf[0])
            assertEquals('i'.code.toByte(), buf[1])

            val r2 = pipe.server.dgramRecv(buf, buf.size)
            assertEquals(11, r2)
            assertEquals('h'.code.toByte(), buf[0])
            assertEquals('o'.code.toByte(), buf[1])

            // No more datagrams (Done → returns -1).
            assertEquals(-1, pipe.server.dgramRecv(buf, buf.size))
        }
    }

    //endregion

    //region tests.rs:app_peer_error (line 8921)

    /**
     * Ported from tests.rs:app_peer_error()
     * Complement of peer_error — tests APPLICATION_CLOSE (is_app=true).
     */
    @Test
    fun testAppPeerError() {
        TestPipe.new().use { pipe ->
            pipe.handshake()

            pipe.server.closeConnection(app = true, err = 0x1234, reason = "hello!".encodeToByteArray())
            pipe.advance()

            val err = pipe.client.peerError()
            assertNotNull(err)
            assertTrue(err.isApp)
            assertEquals(0x1234, err.errorCode)
        }
    }

    //endregion

    //region tests.rs:app_close_by_server_during_handshake_established (line 8803)

    /**
     * Ported from tests.rs:app_close_by_server_during_handshake_established()
     * Server closes with app error after both sides are established but before
     * the final handshake ACK is sent. Error propagates correctly.
     */
    @Test
    fun testAppCloseByServerDuringHandshakeEstablished() {
        TestPipe.new().use { pipe ->
            // Client → Server (Initial)
            pipe.flushClient()
            // Server → Client (Handshake)
            pipe.flushServer()

            assertFalse(pipe.server.isEstablished)
            assertTrue(pipe.client.isEstablished)

            // Client sends 1-RTT data
            pipe.client.streamSend(0, "badauthtoken".encodeToByteArray(), 12, fin = true)
            pipe.flushClient()

            // Server is now established
            assertTrue(pipe.server.isEstablished)

            // Server closes with app error
            pipe.server.closeConnection(app = true, err = 123, reason = "Invalid authentication".encodeToByteArray())
            pipe.advance()

            val localErr = pipe.server.localError()
            assertNotNull(localErr)
            assertTrue(localErr.isApp)
            assertEquals(123, localErr.errorCode)

            val peerErr = pipe.client.peerError()
            assertNotNull(peerErr)
            assertTrue(peerErr.isApp)
            assertEquals(123, peerErr.errorCode)
        }
    }

    //endregion

    //region tests.rs:transport_close_by_client_during_handshake_established (line 8859)

    /**
     * Ported from tests.rs:transport_close_by_client_during_handshake_established()
     * Client sends transport close after it becomes established (but before
     * server is fully established). Error propagates to server.
     */
    @Test
    fun testTransportCloseByClientDuringHandshakeEstablished() {
        TestPipe.new().use { pipe ->
            // Client → Server (Initial)
            pipe.flushClient()
            // Server → Client (Handshake)
            pipe.flushServer()

            assertFalse(pipe.server.isEstablished)
            assertTrue(pipe.client.isEstablished)

            // Client sends transport close
            pipe.client.closeConnection(app = false, err = 123, reason = "connection close".encodeToByteArray())
            pipe.flushClient()

            val peerErr = pipe.server.peerError()
            assertNotNull(peerErr)
            assertFalse(peerErr.isApp)
            assertEquals(123, peerErr.errorCode)

            val localErr = pipe.client.localError()
            assertNotNull(localErr)
            assertFalse(localErr.isApp)
            assertEquals(123, localErr.errorCode)
        }
    }

    //endregion
}
