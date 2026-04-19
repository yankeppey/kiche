package eu.buney.kiche.ktor.webtransport

import eu.buney.kiche.*
import eu.buney.kiche.ktor.webtransport.capsule.*
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

    /** Accumulates close info data received on the CONNECT stream body. */
    private val connectStreamBody = mutableListOf<ByteArray>()

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
            // Use an H3 request stream as a WebTransport bidirectional stream.
            // quiche's H3 layer manages stream IDs — raw conn.streamSend() would
            // conflict with H3's internal stream allocation.
            val headers = listOf(
                KicheH3Header(":method", "CONNECT"),
                KicheH3Header(":protocol", "webtransport-stream"),
                KicheH3Header("wt-session-id", sessionStreamId.toString()),
            )
            val streamId = h3Conn.sendRequest(conn, headers, fin = false)
            drainSendLocked()
            val stream = KicheWebTransportStreamImpl(
                id = streamId,
                conn = conn,
                h3Conn = h3Conn,
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
            val headers = listOf(
                KicheH3Header(":method", "CONNECT"),
                KicheH3Header(":protocol", "webtransport-stream"),
                KicheH3Header("wt-session-id", sessionStreamId.toString()),
                KicheH3Header("wt-uni", "true"),
            )
            val streamId = h3Conn.sendRequest(conn, headers, fin = false)
            drainSendLocked()
            val stream = KicheWebTransportStreamImpl(
                id = streamId,
                conn = conn,
                h3Conn = h3Conn,
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
        try {
            mutex.withLock {
                if (sessionStreamId >= 0 && !conn.isClosed) {
                    // Send CLOSE_WEBTRANSPORT_SESSION capsule on the CONNECT stream
                    val capsuleBytes = CloseWebTransportSession.encode(info)
                    h3Conn.sendBody(conn, sessionStreamId, capsuleBytes, true)
                    drainSendLocked()
                }
            }
        } catch (_: Throwable) {
            // Best effort — connection may already be closed by event loop
        }
        if (!_closed.isCompleted) _closed.complete(info)
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
                } else if (!activeStreams.containsKey(event.streamId)) {
                    // Server-initiated sub-stream (response headers on a new stream)
                    handleNewIncomingStreamLocked(event.streamId, recvBuf)
                }
                // Headers on existing sub-streams are response headers — ignore
            }

            KicheH3EventType.Data -> {
                if (event.streamId == sessionStreamId) {
                    // Data on the CONNECT stream — may contain close info
                    while (true) {
                        val n = try {
                            h3Conn.recvBody(conn, event.streamId, recvBuf)
                        } catch (_: KicheH3Exception) { break }
                        if (n <= 0) break
                        connectStreamBody.add(recvBuf.copyOf(n))
                    }
                } else {
                    // Data on a WT sub-stream
                    val stream = activeStreams[event.streamId]
                    if (stream != null) {
                        readStreamDataLocked(event.streamId, recvBuf)
                    }
                }
            }

            KicheH3EventType.Finished -> {
                if (event.streamId == sessionStreamId) {
                    log("dispatchEvent: CONNECT stream finished — session ending")
                    if (!_closed.isCompleted) {
                        _closed.complete(parseCloseInfo())
                    }
                } else {
                    // Drain any remaining body data before closing the stream.
                    readStreamDataLocked(event.streamId, recvBuf)
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

    private fun readStreamDataLocked(streamId: Long, buf: ByteArray) {
        val stream = activeStreams[streamId] ?: return
        while (true) {
            val n = try {
                h3Conn.recvBody(conn, streamId, buf)
            } catch (_: KicheH3Exception) { break }
            if (n <= 0) break
            stream.onDataReceived(buf.copyOf(n))
        }
    }

    private fun handleNewIncomingStreamLocked(streamId: Long, buf: ByteArray) {
        // Server-initiated sub-stream — arrives as an H3 request
        // with a Headers event. Read any initial body data available.
        val stream = KicheWebTransportStreamImpl(
            id = streamId,
            conn = conn,
            h3Conn = h3Conn,
            parentScope = this,
            isBidi = true, // Server-initiated streams are bidi by default
        )
        activeStreams[streamId] = stream

        // Read any body data already available
        while (true) {
            val n = try {
                h3Conn.recvBody(conn, streamId, buf)
            } catch (_: KicheH3Exception) { break }
            if (n <= 0) break
            stream.onDataReceived(buf.copyOf(n))
        }

        _incomingBidi.trySend(stream)
    }

    /** Drains outgoing datagrams from the send channel. Called under mutex. */
    private fun driveOutgoingDatagramsLocked() {
        while (true) {
            val payload = _dgramOutgoing.tryReceive().getOrNull() ?: break
            try {
                val framed = HttpDatagram.encode(sessionStreamId, payload)
                conn.dgramSend(framed, framed.size)
            } catch (_: KicheException) {
                // Queue full or error — drop the datagram (unreliable)
                log("driveOutgoingDatagrams: dropped datagram (${payload.size} bytes)")
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
                // Strip Quarter Stream ID prefix (RFC 9297)
                val decoded = HttpDatagram.decode(buf.copyOf(n))
                if (decoded != null) {
                    val (streamId, payload) = decoded
                    // Only accept datagrams for our session
                    if (streamId == sessionStreamId) {
                        _dgramIncoming.trySend(payload)
                    }
                }
            }
        }
    }

    /** Drives pending writes for active streams. Called under mutex. */
    private fun driveStreamWritesLocked() {
        for (stream in activeStreams.values) {
            stream.driveWriteLocked()
        }
    }

    /** Parses close info from accumulated CONNECT stream body data (capsule-framed). */
    private fun parseCloseInfo(): WebTransportCloseInfo? {
        if (connectStreamBody.isEmpty()) return null
        val totalSize = connectStreamBody.sumOf { it.size }
        if (totalSize == 0) return null
        val data = ByteArray(totalSize)
        var offset = 0
        for (part in connectStreamBody) {
            part.copyInto(data, offset)
            offset += part.size
        }
        // Parse capsules — look for CLOSE_WEBTRANSPORT_SESSION
        val (capsules, _) = parseAllCapsules(data)
        for (capsule in capsules) {
            if (capsule.type == CapsuleType.CLOSE_WEBTRANSPORT_SESSION) {
                return CloseWebTransportSession.decode(capsule.value)
            }
            // Unknown capsule types are silently skipped (RFC 9297 §3.2)
        }
        return null
    }

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

