package eu.buney.kiche.ktor

import eu.buney.kiche.*
import eu.buney.kiche.ktor.webtransport.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.network.sockets.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.*
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

private fun log(msg: String) {
    val t = System.currentTimeMillis() % 100_000 // last 5 digits for readability
    val thread = Thread.currentThread().name.takeLast(30)
    println("[KICHE $t $thread] $msg")
}

/**
 * Manages a single QUIC + HTTP/3 connection to a specific host:port.
 *
 * The endpoint owns a UDP socket, QUIC connection, H3 session, and a background
 * event loop. Multiple requests are multiplexed as independent H3 streams over the
 * shared connection. All quiche calls are serialized via [mutex] because the
 * underlying C library is not thread-safe.
 */
internal class KicheEndpoint(
    private val host: String,
    private val port: Int,
    private val config: KicheEngineConfig,
    private val selectorManager: io.ktor.network.selector.SelectorManager,
    parentContext: CoroutineContext,
    private val onDone: () -> Unit,
) : CoroutineScope {

    override val coroutineContext: CoroutineContext =
        parentContext + SupervisorJob(parentContext.job) + CoroutineName("kiche-endpoint-$host:$port")

    /** Serializes all quiche calls (conn + h3 are not thread-safe). */
    private val mutex = Mutex()

    // Connection state — guarded by mutex, set during ensureConnected().
    // The event loop captures these at launch and owns their lifecycle.
    // ensureConnected() only nulls out the fields (without closing) before reconnecting;
    // the old event loop's finally block closes the native objects it captured.
    private var udpSocket: BoundDatagramSocket? = null
    private var conn: KicheConnection? = null
    private var h3Conn: KicheH3Connection? = null
    private var h3Config: KicheH3Config? = null
    private var quicConfig: KicheConfig? = null
    private var peerSocketAddr: InetSocketAddress? = null
    private var local: KicheAddress? = null
    private var peer: KicheAddress? = null
    private var eventLoopJob: Job? = null

    /** Reusable send buffer for drainSend — avoids per-call 64KB allocation. */
    private val sendBuf = ByteArray(MAX_DATAGRAM_SIZE)

    /** Per-stream request/response state, keyed by H3 stream ID. */
    private val streams = mutableMapOf<Long, StreamState>()

    /**
     * Execute an HTTP/3 request on this endpoint's shared connection.
     *
     * Returns when the full response (headers + body) has been received.
     */
    suspend fun execute(
        data: HttpRequestData,
        callContext: CoroutineContext,
        requestTime: GMTDate,
    ): HttpResponseData {
        ensureConnected()

        val headers = buildH3Headers(data)
        val bodyChannel = data.body.toReadChannel(CoroutineScope(callContext))

        val responseDeferred = CompletableDeferred<HttpResponseData>()

        // Register stream and flush under mutex (sendRequest mutates h3 state)
        val streamId = mutex.withLock {
            val c = conn ?: error("Connection closed before request could be sent")
            val h3 = h3Conn ?: error("H3 connection closed before request could be sent")
            val id = h3.sendRequest(quicConn = c, headers = headers, fin = bodyChannel == null)
            streams[id] = StreamState(
                streamId = id,
                responseDeferred = responseDeferred,
                requestTime = requestTime,
                callContext = callContext,
                bodyChannel = bodyChannel,
                bodyReadBuf = if (bodyChannel != null) ByteArray(MAX_BODY_CHUNK) else null,
            )
            // Flush immediately so request headers hit the wire without waiting for event loop
            drainSendLocked()
            log("execute: registered stream $id, hasBody=${bodyChannel != null}, totalStreams=${streams.size}")
            id
        }

        try {
            return responseDeferred.await()
        } catch (e: Throwable) {
            // If the caller is cancelled, clean up the stream
            mutex.withLock { streams.remove(streamId) }
            throw e
        }
    }

    /**
     * Ensures the QUIC connection is established and the event loop is running.
     * Creates a new connection if none exists or if the previous one closed.
     *
     * Holds the mutex for the entire connect-and-handshake path to prevent two
     * concurrent callers from racing to create separate connections (one of which
     * would be leaked). The handshake is fast (1 RTT) so this is acceptable.
     */
    private suspend fun ensureConnected() = mutex.withLock {
        val c = conn
        if (c != null && !c.isClosed) return@withLock

        log("ensureConnected: starting new connection to $host:$port")

        // Detach old connection from fields — the old event loop's finally block
        // will close the native objects it captured at launch.
        detachConnectionLocked()

        // Resolve peer address
        val socketAddr = InetSocketAddress(host, port)
        val peerIp = socketAddr.resolveAddress()
            ?: error("Failed to resolve address for $host")
        val peerAddr = KicheAddress(ip = peerIp, port = port)

        // Bind local UDP socket — use the peer's host so both sides share
        // the same address family (avoids IPv4↔IPv6 dual-stack issues on macOS).
        log("ensureConnected: binding UDP socket")
        val socket = aSocket(selectorManager).udp().bind(InetSocketAddress(host, 0))
        val localSocketAddr = socket.localAddress as InetSocketAddress
        val localAddr = KicheAddress(
            ip = localSocketAddr.resolveAddress() ?: byteArrayOf(0, 0, 0, 0),
            port = localSocketAddr.port,
        )

        val scid = Random.nextBytes(16)
        val handshakeBuf = ByteArray(MAX_DATAGRAM_SIZE)

        val qConfig = KicheConfig().apply {
            setApplicationProtos(H3_ALPN)
            verifyPeer(config.verifyPeer)
            config.caCertPath?.let { loadVerifyLocationsFromFile(it) }
            setCcAlgorithm(config.ccAlgorithm)
            setMaxIdleTimeout(config.maxIdleTimeoutMs)
            setInitialMaxData(config.initialMaxData)
            setInitialMaxStreamDataBidiLocal(config.initialMaxStreamDataBidiLocal)
            setInitialMaxStreamDataBidiRemote(config.initialMaxStreamDataBidiRemote)
            setInitialMaxStreamDataUni(config.initialMaxStreamDataUni)
            setInitialMaxStreamsBidi(config.initialMaxStreamsBidi)
            setInitialMaxStreamsUni(config.initialMaxStreamsUni)
        }

        try {
            log("ensureConnected: creating QUIC connection")
            val newConn = KicheConnection.connect(host, scid, localAddr, peerAddr, qConfig)

            try {
                // Drive initial handshake packets (suspend send — no contention during handshake)
                log("ensureConnected: draining initial handshake packets")
                drainSendSuspend(newConn, handshakeBuf, socket, socketAddr)

                // Handshake loop — no other coroutine accesses newConn yet.
                // Uses the same timeout pattern as the event loop: wait for a
                // packet up to quiche's timeout, then call onTimeout() to drive
                // retransmissions. Without this, a lost initial packet would
                // hang the handshake forever.
                log("ensureConnected: entering handshake loop")
                while (!newConn.isEstablished) {
                    val timeout = newConn.timeoutAsMillis().coerceIn(1, 1000)
                    val dgram = withTimeoutOrNull(timeout) { socket.receive() }
                    if (dgram == null) {
                        newConn.onTimeout()
                        drainSendSuspend(newConn, handshakeBuf, socket, socketAddr)
                        continue
                    }
                    val bytes = dgram.packet.readByteArray()
                    newConn.recv(buf = bytes, len = bytes.size, from = peerAddr, to = localAddr)
                    drainSendSuspend(newConn, handshakeBuf, socket, socketAddr)
                }

                log("ensureConnected: handshake complete")
                // Create H3 layer
                val h3Cfg = KicheH3Config()
                val h3 = try {
                    KicheH3Connection(newConn, h3Cfg)
                } catch (e: Throwable) {
                    h3Cfg.close()
                    throw e
                }

                // Publish connection state (already under mutex)
                udpSocket = socket
                conn = newConn
                quicConfig = qConfig
                h3Config = h3Cfg
                h3Conn = h3
                peerSocketAddr = socketAddr
                local = localAddr
                peer = peerAddr

                // Start background event loop
                eventLoopJob = launch { eventLoop(newConn, h3, h3Cfg, qConfig, socket, socketAddr, localAddr, peerAddr) }
            } catch (e: Throwable) {
                newConn.close()
                throw e
            }
        } catch (e: Throwable) {
            qConfig.close()
            socket.close()
            throw e
        }
    }

    /**
     * Background coroutine: receives UDP datagrams, feeds them to quiche,
     * drives body sends, polls H3 events, and dispatches to per-stream handlers.
     *
     * Captures its own connection objects at launch. The finally block closes only
     * the objects it was started with, preventing a reconnecting ensureConnected()
     * from having its new connection destroyed by a stale event loop.
     */
    private suspend fun eventLoop(
        myConn: KicheConnection,
        myH3: KicheH3Connection,
        myH3Config: KicheH3Config,
        myQConfig: KicheConfig,
        mySocket: BoundDatagramSocket,
        myPeerSocketAddr: InetSocketAddress,
        myLocal: KicheAddress,
        myPeer: KicheAddress,
    ) {
        val recvBodyBuf = ByteArray(MAX_DATAGRAM_SIZE)
        log("eventLoop started")

        try {
            while (true) {
                if (myConn.isClosed) { log("eventLoop: conn.isClosed, breaking"); break }

                // Get quiche timeout + check connection validity under mutex
                val timeout = mutex.withLock {
                    if (conn !== myConn) { log("eventLoop: replaced by reconnect, returning"); return }
                    myConn.timeoutAsMillis().coerceIn(1, 1000)
                }

                // Wait for UDP datagram or timeout — without holding mutex.
                log("eventLoop: entering socket.receive() timeout=${timeout}ms")
                val dgram = withTimeoutOrNull(timeout) {
                    mySocket.receive()
                }
                log("eventLoop: socket.receive() returned dgram=${dgram != null}")

                mutex.withLock {
                    if (conn !== myConn) return // replaced by reconnect
                    if (myConn.isClosed) { log("eventLoop: conn closed inside mutex"); return@withLock }

                    if (dgram != null) {
                        val bytes = dgram.packet.readByteArray()
                        myConn.recv(buf = bytes, len = bytes.size, from = myPeer, to = myLocal)
                    } else {
                        myConn.onTimeout()
                    }

                    // Drive pending body sends for all active streams
                    val bodySendsBefore = streams.values.count { !it.bodyFinished }
                    driveBodySendsLocked(myConn, myH3)
                    val bodySendsAfter = streams.values.count { !it.bodyFinished }

                    // Poll and dispatch H3 events
                    var eventCount = 0
                    while (true) {
                        val event = myH3.poll(quicConn = myConn) ?: break
                        eventCount++
                        log("eventLoop: event type=${event.type} stream=${event.streamId}")
                        dispatchEventLocked(event, myConn, myH3, recvBodyBuf)
                    }

                    if (eventCount > 0 || bodySendsBefore != bodySendsAfter) {
                        log("eventLoop: events=$eventCount streams=${streams.size} pendingBodies=$bodySendsAfter")
                    }

                    // Flush outgoing packets
                    drainSendLocked()
                }
            }
        } finally {
            log("eventLoop finally: entering")
            // Fail all pending streams and close our own native objects.
            val cause = IOException("QUIC connection closed")
            log("eventLoop finally: acquiring mutex")
            val pending = mutex.withLock {
                log("eventLoop finally: mutex acquired, streams=${streams.size}")
                val copy = streams.values.toList()
                streams.clear()
                // Only null out fields if they still point to our objects
                if (conn === myConn) {
                    log("eventLoop finally: nulling fields (conn is ours)")
                    conn = null; h3Conn = null; h3Config = null
                    quicConfig = null; udpSocket = null; eventLoopJob = null
                    peerSocketAddr = null; local = null; peer = null
                } else {
                    log("eventLoop finally: fields already replaced by reconnect")
                }
                copy
            }
            log("eventLoop finally: closing native objects")
            myH3.close()
            myH3Config.close()
            myConn.close()
            myQConfig.close()
            try { mySocket.close() } catch (_: Throwable) {}
            log("eventLoop finally: native objects closed, failing ${pending.size} streams")

            for (stream in pending) {
                stream.responseDeferred.completeExceptionally(cause)
            }
            log("eventLoop finally: calling onDone()")
            onDone()
            log("eventLoop finally: done")
        }
    }

    /**
     * Dispatches an H3 event to the appropriate stream. Called under mutex.
     */
    private fun dispatchEventLocked(
        event: KicheH3Event,
        c: KicheConnection,
        h3: KicheH3Connection,
        recvBodyBuf: ByteArray,
    ) {
        val stream = streams[event.streamId] ?: return

        when (event.type) {
            KicheH3EventType.Headers -> {
                val (status, ktorHeaders) = parseResponseHeaders(event.headers ?: emptyList())
                stream.responseStatus = status
                stream.responseHeaders = ktorHeaders
            }

            KicheH3EventType.Data -> {
                while (true) {
                    val n = try {
                        h3.recvBody(quicConn = c, streamId = event.streamId, buf = recvBodyBuf)
                    } catch (_: KicheException) {
                        break
                    }
                    if (n <= 0) break
                    stream.responseBodyParts.add(recvBodyBuf.copyOf(n))
                }
            }

            KicheH3EventType.Finished -> {
                log("dispatch: stream ${event.streamId} finished, status=${stream.responseStatus}")
                streams.remove(event.streamId)
                completeStream(stream)
            }

            KicheH3EventType.Reset -> {
                streams.remove(event.streamId)
                stream.responseDeferred.completeExceptionally(
                    IOException("Stream ${event.streamId} reset by server")
                )
            }

            KicheH3EventType.GoAway -> {
                val goAwayId = event.streamId
                val affected = streams.entries.filter { it.key >= goAwayId }
                for ((id, s) in affected) {
                    streams.remove(id)
                    s.responseDeferred.completeExceptionally(
                        IOException("Server sent GOAWAY (stream $goAwayId)")
                    )
                }
            }

            KicheH3EventType.PriorityUpdate -> { /* ignore — advisory only */ }
        }
    }

    /** Assembles the response and completes the deferred. */
    private fun completeStream(stream: StreamState) {
        val totalSize = stream.responseBodyParts.sumOf { it.size }
        val responseBody = ByteArray(totalSize)
        var offset = 0
        for (part in stream.responseBodyParts) {
            part.copyInto(responseBody, offset)
            offset += part.size
        }

        val response = HttpResponseData(
            statusCode = stream.responseStatus,
            requestTime = stream.requestTime,
            headers = stream.responseHeaders,
            version = HttpProtocolVersion("HTTP", 3, 0),
            body = ByteReadChannel(responseBody),
            callContext = stream.callContext,
        )
        stream.responseDeferred.complete(response)
    }

    /**
     * Attempts to push request body data for all active streams. Called under mutex.
     * Exceptions from individual streams are routed to their deferred (not rethrown).
     *
     * Note: [trySendBodyData] calls [ByteReadChannel.readAvailable] which is suspend.
     * It should return immediately when [ByteReadChannel.availableForRead] > 0 (which
     * is checked first), but if it ever suspends, the mutex is held for the duration.
     * This is acceptable given the guard; the alternative (releasing and re-acquiring
     * the mutex per stream) would introduce complex ordering issues.
     */
    private suspend fun driveBodySendsLocked(c: KicheConnection, h3: KicheH3Connection) {
        val iterator = streams.values.iterator()
        while (iterator.hasNext()) {
            val stream = iterator.next()
            if (stream.bodyFinished) continue
            val bodyChannel = stream.bodyChannel ?: continue

            try {
                val result = trySendBodyData(
                    h3, c, stream.streamId, bodyChannel, stream.bodyReadBuf!!,
                    stream.pendingBody, stream.pendingOffset,
                )
                if (result.finished && !stream.bodyFinished) {
                    log("driveBody: stream ${stream.streamId} body finished")
                }
                stream.bodyFinished = result.finished
                stream.pendingBody = result.pending
                stream.pendingOffset = result.offset
            } catch (e: Throwable) {
                log("driveBody: stream ${stream.streamId} error: ${e.message}")
                // Body send failed (e.g. writer exception) — fail this stream only
                iterator.remove()
                stream.responseDeferred.completeExceptionally(e)
            }
        }
    }

    /**
     * Flushes all pending QUIC packets using the endpoint's reusable [sendBuf].
     * Called under mutex.
     */
    private fun drainSendLocked() {
        val c = conn ?: return
        val socket = udpSocket ?: return
        val peerAddr = peerSocketAddr ?: return
        var sent = 0
        var dropped = 0
        while (true) {
            val sendResult = c.send(sendBuf, sendBuf.size) ?: break
            val packet = Buffer().apply { write(sendBuf, 0, sendResult.written) }
            if (!socket.outgoing.trySend(Datagram(packet, peerAddr)).isSuccess) {
                dropped++
                break
            }
            sent++
        }
        if (dropped > 0) {
            log("drainSendLocked: sent=$sent DROPPED=$dropped packets!")
        }
    }

    /**
     * Detaches connection from fields without closing native objects.
     * The event loop owns the lifecycle of the native objects it captured at launch;
     * this only cancels the event loop and nulls out the fields so ensureConnected()
     * can safely publish new objects.
     */
    private fun detachConnectionLocked() {
        eventLoopJob?.cancel()
        eventLoopJob = null
        conn = null
        h3Conn = null
        h3Config = null
        quicConfig = null
        udpSocket = null
        peerSocketAddr = null
        local = null
        peer = null
    }

    /**
     * Closes this endpoint. Closes the UDP socket first to unblock any pending
     * `socket.receive()` in the event loop, then cancels the coroutine scope.
     * Returns the job so the caller can join before tearing down shared resources
     * (e.g., the selector manager).
     */
    fun close(): Job {
        log("endpoint.close() enter, socket=${udpSocket != null}")
        // Close the socket to interrupt socket.receive() immediately.
        // Coroutine cancellation alone isn't reliable — the selector may not wake
        // up promptly. Closing the socket forces an immediate exception.
        try { udpSocket?.close() } catch (_: Throwable) {}
        log("endpoint.close() socket closed, cancelling job")
        coroutineContext.job.cancel()
        log("endpoint.close() job cancelled")
        return coroutineContext.job
    }

    /**
     * Opens a WebTransport session over a dedicated QUIC + H3 connection.
     *
     * Creates a fresh QUIC connection with Extended CONNECT enabled, performs
     * the handshake, and sends the CONNECT request to establish the WT session.
     */
    suspend fun openWebTransportSession(url: Url): WebTransportSession {
        log("openWebTransportSession: $url")

        val socketAddr = InetSocketAddress(host, port)
        val peerIp = socketAddr.resolveAddress()
            ?: error("Failed to resolve address for $host")
        val peerAddr = KicheAddress(ip = peerIp, port = port)

        val socket = aSocket(selectorManager).udp().bind(InetSocketAddress(host, 0))
        val localSocketAddr = socket.localAddress as InetSocketAddress
        val localAddr = KicheAddress(
            ip = localSocketAddr.resolveAddress() ?: byteArrayOf(0, 0, 0, 0),
            port = localSocketAddr.port,
        )

        val scid = Random.nextBytes(16)
        val handshakeBuf = ByteArray(MAX_DATAGRAM_SIZE)

        val qConfig = KicheConfig().apply {
            setApplicationProtos(H3_ALPN)
            verifyPeer(config.verifyPeer)
            config.caCertPath?.let { loadVerifyLocationsFromFile(it) }
            setCcAlgorithm(config.ccAlgorithm)
            setMaxIdleTimeout(config.maxIdleTimeoutMs)
            setInitialMaxData(config.initialMaxData)
            setInitialMaxStreamDataBidiLocal(config.initialMaxStreamDataBidiLocal)
            setInitialMaxStreamDataBidiRemote(config.initialMaxStreamDataBidiRemote)
            setInitialMaxStreamDataUni(config.initialMaxStreamDataUni)
            setInitialMaxStreamsBidi(config.initialMaxStreamsBidi)
            setInitialMaxStreamsUni(config.initialMaxStreamsUni)
            enableDgram(true, 65535, 65535)
        }

        try {
            val newConn = KicheConnection.connect(host, scid, localAddr, peerAddr, qConfig)

            try {
                // Drive handshake
                drainSendSuspend(newConn, handshakeBuf, socket, socketAddr)
                while (!newConn.isEstablished) {
                    val timeout = newConn.timeoutAsMillis().coerceIn(1, 1000)
                    val dgram = withTimeoutOrNull(timeout) { socket.receive() }
                    if (dgram == null) {
                        newConn.onTimeout()
                        drainSendSuspend(newConn, handshakeBuf, socket, socketAddr)
                        continue
                    }
                    val bytes = dgram.packet.readByteArray()
                    newConn.recv(bytes, bytes.size, peerAddr, localAddr)
                    drainSendSuspend(newConn, handshakeBuf, socket, socketAddr)
                }

                log("openWebTransportSession: QUIC handshake complete")

                // Create H3 layer with Extended CONNECT enabled
                val h3Cfg = KicheH3Config().apply {
                    enableExtendedConnect(true)
                }
                val h3 = try {
                    KicheH3Connection(newConn, h3Cfg)
                } catch (e: Throwable) {
                    h3Cfg.close()
                    throw e
                }

                // Create the session and establish it
                val session = KicheWebTransportSession(
                    conn = newConn,
                    h3Conn = h3,
                    h3Config = h3Cfg,
                    quicConfig = qConfig,
                    udpSocket = socket,
                    peerSocketAddr = socketAddr,
                    localAddr = localAddr,
                    peerAddr = peerAddr,
                    parentContext = coroutineContext,
                )

                val path = url.encodedPathAndQuery.ifEmpty { "/" }
                session.establish(path, url.hostWithPort)

                return session
            } catch (e: Throwable) {
                newConn.close()
                throw e
            }
        } catch (e: Throwable) {
            qConfig.close()
            socket.close()
            throw e
        }
    }

    companion object {
        const val MAX_DATAGRAM_SIZE = 65535
        const val MAX_BODY_CHUNK = 65535
        const val DEFAULT_HTTPS_PORT = 443

        /** h3 ALPN token encoded as quiche expects: length-prefixed. */
        val H3_ALPN: ByteArray = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte())
    }
}

/**
 * Flushes pending QUIC packets using suspending socket.send(). Used during handshake
 * where there is no mutex contention and we want reliable delivery (not trySend).
 */
private suspend fun drainSendSuspend(
    conn: KicheConnection,
    buf: ByteArray,
    socket: BoundDatagramSocket,
    peerAddr: InetSocketAddress,
) {
    while (true) {
        val sendResult = conn.send(buf, buf.size) ?: break
        val packet = Buffer().apply { write(buf, 0, sendResult.written) }
        socket.send(Datagram(packet, peerAddr))
    }
}

/**
 * Mutable per-stream state tracked by the endpoint. One instance per active H3 stream.
 */
private class StreamState(
    val streamId: Long,
    val responseDeferred: CompletableDeferred<HttpResponseData>,
    val requestTime: GMTDate,
    val callContext: CoroutineContext,
    val bodyChannel: ByteReadChannel?,
    val bodyReadBuf: ByteArray?,
) {
    // Response accumulation
    var responseStatus: HttpStatusCode = HttpStatusCode.OK
    var responseHeaders: Headers = Headers.Empty
    val responseBodyParts: MutableList<ByteArray> = mutableListOf()

    // Body sending state
    var bodyFinished: Boolean = bodyChannel == null
    var pendingBody: ByteArray? = null
    var pendingOffset: Int = 0
}

/**
 * Result of a non-blocking body send attempt.
 */
private class BodySendResult(
    val finished: Boolean,
    val pending: ByteArray?,
    val offset: Int,
)

/**
 * Attempts to send request body data without suspending on the body channel.
 * Must be called under the endpoint's mutex.
 */
private suspend fun trySendBodyData(
    h3Conn: KicheH3Connection,
    conn: KicheConnection,
    streamId: Long,
    bodyChannel: ByteReadChannel,
    readBuf: ByteArray,
    pendingBody: ByteArray?,
    pendingOffset: Int,
): BodySendResult {
    bodyChannel.closedCause?.let { throw it }

    var curPending = pendingBody
    var curOffset = pendingOffset

    val available = try {
        bodyChannel.availableForRead
    } catch (_: Throwable) {
        0
    }

    if (curPending == null) {
        if (available > 0) {
            val n = bodyChannel.readAvailable(readBuf, 0, readBuf.size)
            if (n > 0) {
                curPending = readBuf.copyOf(n)
                curOffset = 0
            }
        }

        if (curPending == null && bodyChannel.isClosedForRead) {
            bodyChannel.closedCause?.let { throw it }
            // Send an empty DATA frame with FIN to close the H3 stream.
            // quiche's H3 send_body may return Done when stream capacity is
            // exhausted (no room for the 2-byte DATA frame header). In that
            // case, set the QUIC stream FIN directly via streamSend — the
            // H3 layer doesn't require a trailing DATA frame, and the
            // server's h3.poll() generates a Finished event from the QUIC FIN.
            val rc = try {
                h3Conn.sendBody(conn, streamId, ByteArray(0), fin = true)
            } catch (e: KicheException) {
                if (e.error.isRetryable) -1 else throw e
            }
            if (rc < 0) {
                // H3 couldn't send (likely Done due to flow control).
                // Fall back to raw QUIC stream FIN.
                try {
                    conn.streamSend(streamId, ByteArray(0), 0, true)
                } catch (e: KicheException) {
                    if (e.error.isRetryable) {
                        return BodySendResult(finished = false, pending = null, offset = 0)
                    }
                    throw e
                }
            }
            return BodySendResult(finished = true, pending = null, offset = 0)
        }
    }

    if (curPending != null) {
        val chunk = if (curOffset == 0) curPending else curPending.copyOfRange(curOffset, curPending.size)

        val sent = try {
            h3Conn.sendBody(conn, streamId, chunk, fin = false)
        } catch (e: KicheException) {
            if (!e.error.isRetryable) throw e
            0
        }

        if (sent > 0) {
            curOffset += sent
            if (curOffset >= curPending.size) {
                curPending = null
                curOffset = 0
            }
        }
    }

    return BodySendResult(finished = false, pending = curPending, offset = curOffset)
}

private fun buildH3Headers(data: HttpRequestData): List<KicheH3Header> {
    val headers = mutableListOf<KicheH3Header>()
    headers.add(KicheH3Header(":method", data.method.value))
    headers.add(KicheH3Header(":scheme", data.url.protocol.name))
    headers.add(KicheH3Header(":authority", data.url.hostWithPort))
    headers.add(KicheH3Header(":path", data.url.encodedPathAndQuery))

    data.headers.forEach { name, values ->
        for (value in values) {
            headers.add(KicheH3Header(name, value))
        }
    }

    data.body.contentType?.let {
        headers.add(KicheH3Header("content-type", it.toString()))
    }
    data.body.contentLength?.let {
        headers.add(KicheH3Header("content-length", it.toString()))
    }

    return headers
}

@OptIn(InternalAPI::class)
private fun parseResponseHeaders(h3Headers: List<KicheH3Header>): Pair<HttpStatusCode, Headers> {
    var status = HttpStatusCode.OK
    val builder = HeadersBuilder()

    for (header in h3Headers) {
        val name = header.nameString
        val value = header.valueString
        if (name == ":status") {
            status = HttpStatusCode.fromValue(value.toInt())
        } else if (!name.startsWith(":")) {
            builder.append(name, value)
        }
    }

    return status to builder.build()
}

@OptIn(InternalAPI::class)
private fun OutgoingContent.toReadChannel(scope: CoroutineScope): ByteReadChannel? = when (this) {
    is OutgoingContent.NoContent -> null

    is OutgoingContent.ByteArrayContent -> {
        val bytes = bytes()
        if (bytes.isEmpty()) null else ByteReadChannel(bytes)
    }

    is OutgoingContent.ReadChannelContent -> readFrom()

    is OutgoingContent.WriteChannelContent -> {
        val content = this
        scope.writer { content.writeTo(channel) }.channel
    }

    is OutgoingContent.ContentWrapper -> delegate().toReadChannel(scope)

    is OutgoingContent.ProtocolUpgrade ->
        error("Protocol upgrade is not supported by the Kiche engine")
}
