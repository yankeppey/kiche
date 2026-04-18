package eu.buney.kiche.ktor.webtransport

import eu.buney.kiche.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.*
import kotlin.coroutines.CoroutineContext

private fun log(msg: String) {
    val t = System.currentTimeMillis() % 100_000
    val thread = Thread.currentThread().name.takeLast(30)
    println("[WT $t $thread] $msg")
}

/**
 * WebTransport stream type markers as defined in the spec.
 *
 * When a WebTransport stream is opened, it is prefixed with a type byte
 * followed by the session ID (varint-encoded):
 * - Bidirectional: signal byte `0x41` + session ID
 * - Unidirectional: stream type `0x54` + session ID
 */
private const val WT_BIDI_STREAM_SIGNAL: Byte = 0x41
private const val WT_UNI_STREAM_TYPE: Byte = 0x54

/**
 * Implementation of [WebTransportSession] backed by a dedicated QUIC + H3 connection.
 *
 * The session is established by sending an Extended CONNECT request with
 * `:protocol = webtransport` over HTTP/3. Once the server responds with 200,
 * QUIC streams prefixed with the session's stream ID become WebTransport streams.
 *
 * All quiche calls are serialized via [mutex] because the C library is not thread-safe.
 */
internal class KicheWebTransportSession(
    private val conn: KicheConnection,
    private val h3Conn: KicheH3Connection,
    private val h3Config: KicheH3Config,
    private val quicConfig: KicheConfig,
    private val udpSocket: BoundDatagramSocket,
    private val peerSocketAddr: InetSocketAddress,
    private val localAddr: KicheAddress,
    private val peerAddr: KicheAddress,
    parentContext: CoroutineContext,
) : WebTransportSession {

    private val job = SupervisorJob(parentContext[Job])
    override val coroutineContext: CoroutineContext =
        parentContext + job + CoroutineName("webtransport-session")

    private val mutex = Mutex()

    /** The H3 stream ID of the CONNECT request — serves as the WT session ID. */
    private var sessionStreamId: Long = -1

    override val ready: CompletableDeferred<Unit> = CompletableDeferred()
    private val _closed = CompletableDeferred<WebTransportCloseInfo?>()
    override val closed: Deferred<WebTransportCloseInfo?> get() = _closed

    // Incoming stream channels
    private val _incomingBidi = Channel<WebTransportStream>(Channel.UNLIMITED)
    private val _incomingUni = Channel<WebTransportReceiveStream>(Channel.UNLIMITED)
    override val incomingBidirectionalStreams: ReceiveChannel<WebTransportStream> get() = _incomingBidi
    override val incomingUnidirectionalStreams: ReceiveChannel<WebTransportReceiveStream> get() = _incomingUni

    // Datagrams
    private val _dgramIncoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val _dgramOutgoing = Channel<ByteArray>(Channel.UNLIMITED)
    override val datagrams: WebTransportDatagrams = KicheWebTransportDatagrams(
        incoming = _dgramIncoming,
        outgoing = _dgramOutgoing,
        conn = conn,
        mutex = mutex,
    )

    /** Reusable send buffer. */
    private val sendBuf = ByteArray(MAX_DATAGRAM_SIZE)

    /** Active streams keyed by QUIC stream ID. */
    private val activeStreams = mutableMapOf<Long, KicheWebTransportStreamImpl>()

    /**
     * Establishes the WebTransport session by sending Extended CONNECT
     * and starting the event loop.
     */
    suspend fun establish(path: String, host: String) {
        mutex.withLock {
            // Send Extended CONNECT with :protocol = webtransport
            val headers = listOf(
                KicheH3Header(":method", "CONNECT"),
                KicheH3Header(":protocol", "webtransport"),
                KicheH3Header(":scheme", "https"),
                KicheH3Header(":authority", host),
                KicheH3Header(":path", path),
                KicheH3Header("sec-webtransport-http3-draft", "draft02"),
            )
            sessionStreamId = h3Conn.sendRequest(conn, headers, fin = false)
            log("establish: sent CONNECT on stream $sessionStreamId")
            drainSendLocked()
        }

        // Start event loop
        launch { eventLoop() }
    }

    override suspend fun createBidirectionalStream(): WebTransportStream {
        return mutex.withLock {
            // Open a new QUIC bidirectional stream via H3 and prefix with WT signal
            val streamId = openWebtransportStreamLocked(bidi = true)
            val stream = KicheWebTransportStreamImpl(
                id = streamId,
                conn = conn,
                h3Conn = h3Conn,
                mutex = mutex,
                parentScope = this,
                isBidi = true,
            )
            activeStreams[streamId] = stream
            log("createBidirectionalStream: stream $streamId")
            stream
        }
    }

    override suspend fun createUnidirectionalStream(): WebTransportSendStream {
        return mutex.withLock {
            val streamId = openWebtransportStreamLocked(bidi = false)
            val stream = KicheWebTransportStreamImpl(
                id = streamId,
                conn = conn,
                h3Conn = h3Conn,
                mutex = mutex,
                parentScope = this,
                isBidi = false,
            )
            activeStreams[streamId] = stream
            log("createUnidirectionalStream: stream $streamId")
            stream
        }
    }

    override suspend fun close(info: WebTransportCloseInfo) {
        log("close: code=${info.code} reason=${info.reason}")
        mutex.withLock {
            // Close the CONNECT stream to end the WT session
            if (sessionStreamId >= 0) {
                try {
                    conn.streamSend(
                        sessionStreamId,
                        ByteArray(0),
                        0,
                        true
                    )
                    drainSendLocked()
                } catch (_: KicheException) {
                    // Best effort
                }
            }
        }
        _closed.complete(info)
        cleanup()
    }

    private fun cleanup() {
        _incomingBidi.close()
        _incomingUni.close()
        _dgramIncoming.close()
        _dgramOutgoing.close()
        for (stream in activeStreams.values) {
            stream.closeChannels()
        }
        activeStreams.clear()
        job.cancel()
    }

    /**
     * Opens a new QUIC stream and writes the WebTransport stream header.
     * Must be called under mutex.
     */
    private fun openWebtransportStreamLocked(bidi: Boolean): Long {
        // For WebTransport, we use raw QUIC streams (not H3 request streams).
        // The stream header is: signal/type byte + session_id (varint).
        val header = buildStreamHeader(bidi)

        // Use the next available stream ID by sending the WT header as initial data.
        // For bidi streams, client-initiated bidi stream IDs are 0, 4, 8, ...
        // For uni streams, client-initiated uni stream IDs are 2, 6, 10, ...
        // quiche manages ID allocation — we just need to send on a new stream.
        val streamId = if (bidi) {
            conn.streamWritableNext()
        } else {
            // For unidirectional, use streamSend with a fresh stream ID
            conn.streamWritableNext()
        }

        conn.streamSend(streamId, header, header.size, false)
        drainSendLocked()
        return streamId
    }

    private fun buildStreamHeader(bidi: Boolean): ByteArray {
        val buf = mutableListOf<Byte>()
        if (bidi) {
            buf.add(WT_BIDI_STREAM_SIGNAL)
        } else {
            buf.add(WT_UNI_STREAM_TYPE)
        }
        // Encode session stream ID as varint
        encodeVarint(sessionStreamId, buf)
        return buf.toByteArray()
    }

    /**
     * Background event loop that drives QUIC I/O, processes H3 events,
     * and dispatches data to WebTransport streams and datagrams.
     */
    private suspend fun eventLoop() {
        val recvBuf = ByteArray(MAX_DATAGRAM_SIZE)
        log("eventLoop started")

        try {
            while (isActive) {
                if (conn.isClosed) {
                    log("eventLoop: conn closed")
                    break
                }

                val timeout = mutex.withLock {
                    conn.timeoutAsMillis().coerceIn(1, 1000)
                }

                // Receive UDP datagram or timeout
                val dgram = withTimeoutOrNull(timeout) {
                    udpSocket.receive()
                }

                mutex.withLock {
                    if (conn.isClosed) return@withLock

                    if (dgram != null) {
                        val bytes = dgram.packet.readByteArray()
                        conn.recv(bytes, bytes.size, peerAddr, localAddr)
                    } else {
                        conn.onTimeout()
                    }

                    // Drive outgoing datagrams from the send channel
                    driveOutgoingDatagramsLocked()

                    // Drive outgoing stream writes
                    driveStreamWritesLocked()

                    // Poll H3 events
                    while (true) {
                        val event = h3Conn.poll(conn) ?: break
                        dispatchEventLocked(event, recvBuf)
                    }

                    // Drain QUIC datagrams received
                    drainIncomingDatagramsLocked()

                    // Read data from readable streams
                    driveStreamReadsLocked(recvBuf)

                    // Flush outgoing QUIC packets
                    drainSendLocked()
                }
            }
        } catch (e: CancellationException) {
            log("eventLoop: cancelled")
        } catch (e: Throwable) {
            log("eventLoop: error: ${e.message}")
            _closed.complete(null)
        } finally {
            log("eventLoop: cleaning up")
            if (!_closed.isCompleted) _closed.complete(null)
            h3Conn.close()
            h3Config.close()
            conn.close()
            quicConfig.close()
            try { udpSocket.close() } catch (_: Throwable) {}
            cleanup()
            log("eventLoop: done")
        }
    }

    /** Dispatches an H3 event. Called under mutex. */
    private fun dispatchEventLocked(event: KicheH3Event, recvBuf: ByteArray) {
        log("dispatchEvent: type=${event.type} stream=${event.streamId}")

        when (event.type) {
            KicheH3EventType.Headers -> {
                if (event.streamId == sessionStreamId) {
                    // Response to our CONNECT request
                    val statusHeader = event.headers?.find { it.nameString == ":status" }
                    val status = statusHeader?.valueString?.toIntOrNull() ?: 0
                    if (status in 200..299) {
                        log("dispatchEvent: WT session established (status=$status)")
                        ready.complete(Unit)
                    } else {
                        log("dispatchEvent: WT session rejected (status=$status)")
                        ready.completeExceptionally(
                            WebTransportException("Server rejected WebTransport session with status $status")
                        )
                    }
                }
                // Other header events on WT streams are ignored (WT uses raw QUIC streams)
            }

            KicheH3EventType.Data -> {
                // Data on the CONNECT stream — WebTransport capsule protocol (for HTTP datagrams)
                if (event.streamId == sessionStreamId) {
                    while (true) {
                        val n = try {
                            h3Conn.recvBody(conn, event.streamId, recvBuf)
                        } catch (_: KicheException) { break }
                        if (n <= 0) break
                        // Parse capsule for datagrams — simplified: treat as raw datagram payload
                        _dgramIncoming.trySend(recvBuf.copyOf(n))
                    }
                }
            }

            KicheH3EventType.Finished -> {
                if (event.streamId == sessionStreamId) {
                    log("dispatchEvent: CONNECT stream finished — session ending")
                    if (!_closed.isCompleted) _closed.complete(null)
                } else {
                    val stream = activeStreams[event.streamId]
                    stream?.onFinished()
                }
            }

            KicheH3EventType.Reset -> {
                if (event.streamId == sessionStreamId) {
                    log("dispatchEvent: CONNECT stream reset — session ending")
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(
                            WebTransportException("WebTransport session reset by server")
                        )
                    }
                    if (!_closed.isCompleted) _closed.complete(null)
                } else {
                    val stream = activeStreams.remove(event.streamId)
                    stream?.onReset()
                }
            }

            KicheH3EventType.GoAway -> {
                log("dispatchEvent: GoAway received")
                if (!_closed.isCompleted) _closed.complete(null)
            }

            KicheH3EventType.PriorityUpdate -> { /* ignore */ }
        }
    }

    /** Reads available data from all readable streams. Called under mutex. */
    private fun driveStreamReadsLocked(buf: ByteArray) {
        // Check for new incoming streams (peer-initiated)
        while (true) {
            val streamId = conn.streamReadableNext()
            if (streamId < 0) break

            if (activeStreams.containsKey(streamId)) {
                // Existing stream has data — read it
                readStreamDataLocked(streamId, buf)
            } else if (streamId != sessionStreamId) {
                // New peer-initiated stream — create and deliver
                handleNewIncomingStreamLocked(streamId, buf)
            }
        }
    }

    private fun readStreamDataLocked(streamId: Long, buf: ByteArray) {
        val stream = activeStreams[streamId] ?: return
        while (conn.streamReadable(streamId)) {
            val result = try {
                conn.streamRecv(streamId, buf, buf.size)
            } catch (_: KicheException) { break }

            if (result.read > 0) {
                stream.onDataReceived(buf.copyOf(result.read))
            }
            if (result.fin) {
                stream.onFinished()
                break
            }
        }
    }

    private fun handleNewIncomingStreamLocked(streamId: Long, buf: ByteArray) {
        // Read initial bytes to check for WT stream header
        val result = try {
            conn.streamRecv(streamId, buf, buf.size)
        } catch (_: KicheException) { return }

        if (result.read <= 0) return

        val data = buf.copyOf(result.read)
        // TODO: Parse WT stream header (signal byte + session ID varint)
        // For now, accept all peer-initiated streams as WT streams

        val isBidi = (streamId % 4L) == 0L // Client bidi=0, Server bidi=1 mod 4
        val isPeerBidi = (streamId % 4L) == 1L
        val isPeerUni = (streamId % 4L) == 3L

        val stream = KicheWebTransportStreamImpl(
            id = streamId,
            conn = conn,
            h3Conn = h3Conn,
            mutex = mutex,
            parentScope = this,
            isBidi = isPeerBidi,
        )
        activeStreams[streamId] = stream

        // Feed the initial data (after the WT header) to the stream
        // TODO: Strip WT header bytes before passing data
        if (data.isNotEmpty()) {
            stream.onDataReceived(data)
        }
        if (result.fin) {
            stream.onFinished()
        }

        if (isPeerBidi) {
            _incomingBidi.trySend(stream)
        } else if (isPeerUni) {
            _incomingUni.trySend(stream)
        }
    }

    /** Drains outgoing datagrams from the send channel. Called under mutex. */
    private fun driveOutgoingDatagramsLocked() {
        while (true) {
            val data = _dgramOutgoing.tryReceive().getOrNull() ?: break
            try {
                conn.dgramSend(data, data.size)
            } catch (_: KicheException) {
                // Queue full or error — drop the datagram (unreliable)
                log("driveOutgoingDatagrams: dropped datagram (${data.size} bytes)")
            }
        }
    }

    /** Drains incoming QUIC datagrams. Called under mutex. */
    private fun drainIncomingDatagramsLocked() {
        val buf = ByteArray(conn.dgramMaxWritableLen().toInt().coerceAtLeast(1))
        while (conn.dgramRecvQueueLen() > 0) {
            val n = try {
                conn.dgramRecv(buf, buf.size)
            } catch (_: KicheException) { break }
            if (n > 0) {
                _dgramIncoming.trySend(buf.copyOf(n))
            }
        }
    }

    /** Drives pending writes for active streams. Called under mutex. */
    private fun driveStreamWritesLocked() {
        for (stream in activeStreams.values) {
            stream.driveWriteLocked()
        }
    }

    /** Flushes QUIC packets. Called under mutex. */
    private fun drainSendLocked() {
        while (true) {
            val result = conn.send(sendBuf, sendBuf.size) ?: break
            val packet = Buffer().apply { write(sendBuf, 0, result.written) }
            if (!udpSocket.outgoing.trySend(Datagram(packet, peerSocketAddr)).isSuccess) {
                break
            }
        }
    }

    companion object {
        const val MAX_DATAGRAM_SIZE = 65535
    }
}

/** Encodes a Long as a QUIC variable-length integer. */
private fun encodeVarint(value: Long, out: MutableList<Byte>) {
    when {
        value < 0x40 -> {
            out.add(value.toByte())
        }
        value < 0x4000 -> {
            out.add(((value shr 8) or 0x40).toByte())
            out.add((value and 0xFF).toByte())
        }
        value < 0x40000000 -> {
            out.add(((value shr 24) or 0x80).toByte())
            out.add(((value shr 16) and 0xFF).toByte())
            out.add(((value shr 8) and 0xFF).toByte())
            out.add((value and 0xFF).toByte())
        }
        else -> {
            out.add(((value shr 56) or 0xC0).toByte())
            out.add(((value shr 48) and 0xFF).toByte())
            out.add(((value shr 40) and 0xFF).toByte())
            out.add(((value shr 32) and 0xFF).toByte())
            out.add(((value shr 24) and 0xFF).toByte())
            out.add(((value shr 16) and 0xFF).toByte())
            out.add(((value shr 8) and 0xFF).toByte())
            out.add((value and 0xFF).toByte())
        }
    }
}

