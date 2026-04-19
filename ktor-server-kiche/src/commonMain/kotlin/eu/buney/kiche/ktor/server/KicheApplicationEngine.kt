package eu.buney.kiche.ktor.server

import eu.buney.kiche.*
import eu.buney.kiche.ktor.server.webtransport.*
import io.ktor.events.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.*
import kotlin.random.Random

private fun slog(msg: String) {
    val t = System.currentTimeMillis() % 100_000
    val thread = Thread.currentThread().name.takeLast(30)
    println("[KICHE-SRV $t $thread] $msg")
}

/**
 * Ktor server engine that serves HTTP/3 over QUIC using Cloudflare quiche.
 *
 * Architecture (modeled after Ktor CIO server):
 * - Binds a UDP socket on the configured host:port via ktor-network
 * - Accepts QUIC connections and creates H3 sessions
 * - For each H3 request, creates a [KicheApplicationCall] and executes the Ktor pipeline
 * - Separate recv/send coroutines handle QUIC packet I/O with flow control
 */
public class KicheApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    public val configuration: Configuration,
    private val applicationProvider: () -> Application,
) : BaseApplicationEngine(environment, monitor, developmentMode) {

    /**
     * Configuration for the Kiche QUIC server engine.
     */
    public class Configuration : BaseApplicationEngine.Configuration() {
        /** Path to the TLS certificate chain file (PEM format). Required. */
        public var certChainPath: String? = null

        /** Path to the TLS private key file (PEM format). Required. */
        public var privateKeyPath: String? = null

        /** Congestion control algorithm. */
        public var ccAlgorithm: KicheCcAlgorithm = KicheCcAlgorithm.Bbr2

        /** Maximum idle timeout in milliseconds. 0 = no timeout. */
        public var maxIdleTimeoutMs: Long = 30_000L

        /** Connection-level flow control window. */
        public var initialMaxData: Long = 10_000_000L

        /** Per-stream flow control window (bidirectional, local-initiated). */
        public var initialMaxStreamDataBidiLocal: Long = 1_000_000L

        /** Per-stream flow control window (bidirectional, remote-initiated). */
        public var initialMaxStreamDataBidiRemote: Long = 1_000_000L

        /** Per-stream flow control window (unidirectional). */
        public var initialMaxStreamDataUni: Long = 1_000_000L

        /** Maximum concurrent bidirectional streams. */
        public var initialMaxStreamsBidi: Long = 100L

        /** Maximum concurrent unidirectional streams. */
        public var initialMaxStreamsUni: Long = 100L
    }

    private val serverJob = CompletableDeferred<Unit>()
    private val selectorManager = SelectorManager(Dispatchers.Default)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("kiche-server")
    )

    override fun start(wait: Boolean): ApplicationEngine {
        scope.launch { serve() }

        if (wait) {
            runBlocking { serverJob.join() }
        }

        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        scope.cancel()
        selectorManager.close()
    }

    private suspend fun serve() {
        val certChainPath = configuration.certChainPath
            ?: error("certChainPath must be set for KicheQuic server")
        val privateKeyPath = configuration.privateKeyPath
            ?: error("privateKeyPath must be set for KicheQuic server")

        val connector = configuration.connectors.firstOrNull()
        val host = connector?.host ?: "0.0.0.0"
        val port = connector?.port ?: 4433

        val quicConfig = KicheConfig().apply {
            loadCertChainFromPemFile(certChainPath)
            loadPrivKeyFromPemFile(privateKeyPath)
            setApplicationProtos(H3_ALPN)
            verifyPeer(false)
            setCcAlgorithm(configuration.ccAlgorithm)
            setMaxIdleTimeout(configuration.maxIdleTimeoutMs)
            setInitialMaxData(configuration.initialMaxData)
            setInitialMaxStreamDataBidiLocal(configuration.initialMaxStreamDataBidiLocal)
            setInitialMaxStreamDataBidiRemote(configuration.initialMaxStreamDataBidiRemote)
            setInitialMaxStreamDataUni(configuration.initialMaxStreamDataUni)
            setInitialMaxStreamsBidi(configuration.initialMaxStreamsBidi)
            setInitialMaxStreamsUni(configuration.initialMaxStreamsUni)
            enableDgram(true, 65535, 65535)
        }

        val udpSocket = aSocket(selectorManager).udp().bind(InetSocketAddress(host, port))
        val boundAddr = udpSocket.localAddress as InetSocketAddress

        // Report the actual resolved connector
        resolvedConnectorsDeferred.complete(
            listOf(
                EngineConnectorBuilder().apply {
                    this.host = host
                    this.port = boundAddr.port
                }
            )
        )

        val localAddr = KicheAddress(
            ip = boundAddr.resolveAddress() ?: byteArrayOf(0, 0, 0, 0),
            port = boundAddr.port,
        )

        slog("serve: socket bound to ${boundAddr.port}, resolvedConnectors published")
        monitor.raiseCatching(ServerReady, environment, environment.log)

        try {
            serveLoop(udpSocket, quicConfig, localAddr)
        } finally {
            quicConfig.close()
            udpSocket.close()
        }
    }

    private suspend fun serveLoop(
        udpSocket: BoundDatagramSocket,
        quicConfig: KicheConfig,
        localAddr: KicheAddress,
    ) {
        val mutex = Mutex()
        val sendSignal = Channel<Unit>(Channel.CONFLATED)

        var conn: KicheConnection? = null
        var h3Conn: KicheH3Connection? = null
        var h3Config: KicheH3Config? = null
        var connPeerPort = -1
        var connPeerSocketAddr: InetSocketAddress? = null // kept for drainSend (avoids ip→string round-trip)
        var connScope: CoroutineScope? = null // per-connection scope for response coroutines

        val sendBuf = ByteArray(65535)

        // Send coroutine: drains outgoing QUIC packets and drives pending response bodies
        val sendJob = scope.launch {
            while (isActive) {
                withTimeoutOrNull(10) { sendSignal.receive() }
                mutex.withLock {
                    val c = conn ?: return@withLock
                    val peerAddr = connPeerSocketAddr ?: return@withLock
                    drainSend(c, sendBuf, udpSocket, peerAddr)
                }
            }
        }

        // Recv loop
        slog("serveLoop: entering recv loop on ${udpSocket.localAddress}")
        try {
            while (scope.isActive) {
                val dgram = withTimeoutOrNull(50) { udpSocket.receive() }

                if (dgram == null) {
                    // Timeout — drive QUIC timers and poll for pending H3 events.
                    mutex.withLock {
                        val c = conn ?: return@withLock
                        c.onTimeout()
                        val h3 = h3Conn
                        if (h3 != null && connScope != null) {
                            pollAndDispatch(h3, c, udpSocket, connPeerSocketAddr!!, mutex, connScope!!)
                            driveWtSessionsLocked(h3, c, udpSocket, connPeerSocketAddr!!, sendBuf)
                            drainSend(c, sendBuf, udpSocket, connPeerSocketAddr!!)
                        }
                        if (requests.isNotEmpty()) {
                            slog("timeout path: ${requests.size} pending requests (streams: ${requests.keys})")
                        }
                        sendSignal.trySend(Unit)
                    }
                    continue
                }

                val peerAddr = dgram.address as InetSocketAddress
                val fromPort = peerAddr.port
                val fromIp = peerAddr.resolveAddress() ?: continue
                val from = KicheAddress(fromIp, fromPort)
                val bytes = dgram.packet.readByteArray()

                mutex.withLock {
                    // New client from different port → reset
                    conn?.let { c ->
                        if (fromPort != connPeerPort) {
                            // Cancel in-flight response coroutines before freeing native objects
                            wtSessions.clear()
                            connScope?.coroutineContext?.job?.cancelAndJoin()
                            connScope = null
                            h3Conn?.close()
                            c.close()
                            h3Config?.close()
                            h3Conn = null
                            h3Config = null
                            conn = null
                            connPeerSocketAddr = null
                        }
                    }

                    // Accept new connection
                    if (conn == null) {
                        slog("serveLoop: accepting new connection from port $fromPort")
                        connPeerPort = fromPort
                        connPeerSocketAddr = peerAddr
                        connScope = CoroutineScope(
                            scope.coroutineContext + SupervisorJob(scope.coroutineContext.job)
                        )
                        val scid = Random.nextBytes(16)
                        conn = KicheConnection.accept(
                            scid = scid, odcid = null,
                            local = localAddr, peer = from, config = quicConfig,
                        )
                    }

                    val c = conn ?: return@withLock
                    c.recv(buf = bytes, len = bytes.size, from = from, to = localAddr)

                    // Create H3 connection once handshake completes
                    if (c.isEstablished && h3Conn == null) {
                        h3Config = KicheH3Config().apply {
                            enableExtendedConnect(true)
                        }
                        h3Conn = KicheH3Connection(c, h3Config!!)
                    }

                    // Poll H3 events and dispatch to Ktor pipeline
                    val h3 = h3Conn
                    if (h3 != null) {
                        pollAndDispatch(h3, c, udpSocket, peerAddr, mutex, connScope!!)
                        driveWtSessionsLocked(h3, c, udpSocket, peerAddr, sendBuf)
                    }

                    // Flush outgoing packets (flow control updates, ACKs) immediately.
                    // Without this, recvBody() queues MAX_STREAM_DATA frames in quiche's
                    // output buffer but they aren't sent until the send coroutine runs,
                    // which starves the client's flow control window on large bodies.
                    drainSend(c, sendBuf, udpSocket, peerAddr)

                    sendSignal.trySend(Unit)

                    // Connection closed → cleanup
                    if (c.isClosed) {
                        wtSessions.clear()
                        connScope?.coroutineContext?.job?.cancelAndJoin()
                        connScope = null
                        h3Conn?.close()
                        c.close()
                        h3Config?.close()
                        h3Conn = null
                        h3Config = null
                        conn = null
                        connPeerSocketAddr = null
                    }
                }
            }
        } finally {
            sendJob.cancel()
            wtSessions.clear()
            connScope?.coroutineContext?.job?.cancelAndJoin()
            h3Conn?.close()
            conn?.close()
            h3Config?.close()
        }
    }

    /** Tracks in-flight request state before the Finished event. */
    private class RequestState {
        var method: String = "GET"
        var path: String = "/"
        var protocol: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        val bodyParts = mutableListOf<ByteArray>()
    }

    private val requests = mutableMapOf<Long, RequestState>()

    /** Active WebTransport sessions keyed by CONNECT stream ID. */
    private val wtSessions = mutableMapOf<Long, KicheWebTransportServerSession>()

    /** Sub-streams waiting for a 200 response (sendResponse hit StreamBlocked). */
    private data class PendingSubStream(val streamId: Long, val sessionId: Long, val isUni: Boolean)
    private val pendingSubStreams = mutableListOf<PendingSubStream>()

    private fun pollAndDispatch(
        h3: KicheH3Connection,
        conn: KicheConnection,
        udpSocket: BoundDatagramSocket,
        peerSocketAddr: InetSocketAddress,
        mutex: Mutex,
        connScope: CoroutineScope,
    ) {
        while (true) {
            val event = h3.poll(quicConn = conn) ?: break

            when (event.type) {
                KicheH3EventType.Headers -> {
                    // Check if this stream belongs to an active WT session
                    val wtSession = findWtSessionForStream(event.streamId)
                    if (wtSession != null) {
                        // WT sub-stream headers — pass initial data to session
                        slog("Headers stream=${event.streamId} → WT session")
                        continue
                    }

                    val state = RequestState()
                    event.headers?.forEach { header ->
                        val name = header.nameString
                        val value = header.valueString
                        when (name) {
                            ":method" -> state.method = value
                            ":path" -> state.path = value
                            ":protocol" -> state.protocol = value
                        }
                        state.headers.add(name to value)
                    }
                    requests[event.streamId] = state
                    slog("Headers stream=${event.streamId} ${state.method} ${state.path} protocol=${state.protocol}")

                    // If this is a WebTransport CONNECT, handle immediately (no body expected)
                    if (state.method == "CONNECT" && state.protocol == "webtransport") {
                        requests.remove(event.streamId)
                        handleWebTransportConnect(
                            h3, conn, event.streamId, state,
                            udpSocket, peerSocketAddr, mutex, connScope,
                        )
                    } else if (state.method == "CONNECT" && state.protocol == "webtransport-stream") {
                        // Sub-stream belonging to a WT session — route to the session
                        requests.remove(event.streamId)
                        val sessionIdStr = state.headers.find { it.first == "wt-session-id" }?.second
                        val sessionId = sessionIdStr?.toLongOrNull()
                        val session = if (sessionId != null) wtSessions[sessionId] else null
                        if (session != null) {
                            val isUni = state.headers.any { it.first == "wt-uni" && it.second == "true" }
                            acceptSubStreamLocked(h3, conn, event.streamId, sessionId!!, session, isUni)
                        } else {
                            slog("Headers stream=${event.streamId} → WT sub-stream but no session found for $sessionId")
                        }
                    }
                }

                KicheH3EventType.Data -> {
                    // Check if this is data on a WT session's CONNECT stream or sub-stream
                    val wtSession = wtSessions[event.streamId]
                    if (wtSession != null) {
                        // Data on the CONNECT stream (capsule protocol / datagrams)
                        val bodyBuf = ByteArray(65535)
                        while (true) {
                            val n = try {
                                h3.recvBody(quicConn = conn, streamId = event.streamId, buf = bodyBuf)
                            } catch (_: KicheH3Exception) { break }
                            if (n <= 0) break
                            wtSession.onDatagram(bodyBuf.copyOf(n))
                        }
                        continue
                    }

                    // Check if this is data on a WT sub-stream
                    val wtSubSession = findWtSessionForStream(event.streamId)
                    if (wtSubSession != null) {
                        val bodyBuf = ByteArray(65535)
                        while (true) {
                            val n = try {
                                h3.recvBody(quicConn = conn, streamId = event.streamId, buf = bodyBuf)
                            } catch (_: KicheH3Exception) { break }
                            if (n <= 0) break
                            wtSubSession.onStreamData(event.streamId, bodyBuf.copyOf(n))
                        }
                        continue
                    }

                    val bodyBuf = ByteArray(65535)
                    val state = requests[event.streamId] ?: continue
                    var totalRead = 0
                    while (true) {
                        val n = try {
                            h3.recvBody(quicConn = conn, streamId = event.streamId, buf = bodyBuf)
                        } catch (_: KicheH3Exception) {
                            break
                        }
                        if (n <= 0) break
                        state.bodyParts.add(bodyBuf.copyOf(n))
                        totalRead += n
                    }
                    val totalBody = state.bodyParts.sumOf { it.size }
                    slog("Data stream=${event.streamId} read=$totalRead totalBody=$totalBody")
                }

                KicheH3EventType.Finished -> {
                    // Check WT sessions
                    val wtSession = wtSessions[event.streamId] ?: findWtSessionForStream(event.streamId)
                    if (wtSession != null) {
                        wtSession.onStreamFinished(event.streamId)
                        if (event.streamId in wtSessions) {
                            slog("Finished: WT CONNECT stream ${event.streamId} → removing session")
                            wtSessions.remove(event.streamId)
                        }
                        continue
                    }

                    val state = requests.remove(event.streamId) ?: continue
                    val bodySize = state.bodyParts.sumOf { it.size }
                    slog("Finished stream=${event.streamId} bodySize=$bodySize → handleH3Request")
                    handleH3Request(h3, conn, event.streamId, state, udpSocket, peerSocketAddr, mutex, connScope)
                }

                KicheH3EventType.Reset -> {
                    val wtSession = wtSessions[event.streamId] ?: findWtSessionForStream(event.streamId)
                    if (wtSession != null) {
                        wtSession.onStreamReset(event.streamId)
                        if (event.streamId in wtSessions) {
                            wtSessions.remove(event.streamId)
                        }
                        continue
                    }
                    slog("Reset stream=${event.streamId}")
                }

                else -> {
                    slog("Other event type=${event.type} stream=${event.streamId}")
                }
            }
        }
    }

    /**
     * Drives outgoing writes and datagram sends for all active WT sessions,
     * and reads incoming QUIC stream data for WT sub-streams.
     * Called under mutex.
     */
    private fun driveWtSessionsLocked(
        h3: KicheH3Connection,
        conn: KicheConnection,
        udpSocket: BoundDatagramSocket,
        peerAddr: InetSocketAddress,
        sendBuf: ByteArray,
    ) {
        // Retry any sub-stream responses that were blocked
        if (pendingSubStreams.isNotEmpty()) {
            retryPendingSubStreamsLocked(h3, conn)
        }

        for (session in wtSessions.values) {
            // Drive outgoing datagrams and stream writes
            session.driveOutgoingLocked()

            // NOTE: Do NOT read from QUIC streams here. Client sub-streams are
            // H3 request streams — their data arrives via H3 Data events in
            // pollAndDispatch(). Using conn.streamReadableNext()/streamRecv()
            // would steal bytes from H3 internal streams (control, QPACK) and
            // corrupt the H3 connection state.

            // Drain incoming QUIC datagrams — strip Quarter Stream ID (RFC 9297)
            val dgramBuf = ByteArray(conn.dgramMaxWritableLen().toInt().coerceAtLeast(1))
            while (conn.dgramRecvQueueLen() > 0) {
                val n = try {
                    conn.dgramRecv(dgramBuf, dgramBuf.size)
                } catch (_: KicheException) { break }
                if (n > 0) {
                    val decoded = eu.buney.kiche.ktor.webtransport.capsule.HttpDatagram.decode(
                        dgramBuf.copyOf(n)
                    )
                    if (decoded != null) {
                        val (_, payload) = decoded
                        session.onDatagram(payload)
                    }
                }
            }
        }
    }

    /**
     * Accepts a WT sub-stream by sending a 200 response. If the H3 layer is
     * temporarily blocked (StreamBlocked), defers to [pendingSubStreams] for
     * retry on the next event loop iteration. Called under mutex.
     */
    private fun acceptSubStreamLocked(
        h3: KicheH3Connection, conn: KicheConnection,
        streamId: Long, sessionId: Long,
        session: KicheWebTransportServerSession, isUni: Boolean,
    ) {
        try {
            h3.sendResponse(conn, streamId, listOf(KicheH3Header(":status", "200")), fin = false)
        } catch (e: KicheH3Exception) {
            if (e.isRetryable) {
                pendingSubStreams.add(PendingSubStream(streamId, sessionId, isUni))
                slog("Headers stream=$streamId → WT sub-stream blocked, queued for retry")
                return
            }
            throw e
        }
        session.onStreamData(streamId, ByteArray(0))
        slog("Headers stream=$streamId → WT sub-stream (session=$sessionId, uni=$isUni)")
    }

    /** Retries pending sub-stream 200 responses. Called under mutex. */
    private fun retryPendingSubStreamsLocked(h3: KicheH3Connection, conn: KicheConnection) {
        val iter = pendingSubStreams.iterator()
        while (iter.hasNext()) {
            val pending = iter.next()
            val session = wtSessions[pending.sessionId] ?: run { iter.remove(); continue }
            try {
                h3.sendResponse(conn, pending.streamId, listOf(KicheH3Header(":status", "200")), fin = false)
            } catch (e: KicheH3Exception) {
                if (e.isRetryable) continue // still blocked, try next iteration
                iter.remove()
                continue
            }
            iter.remove()
            session.onStreamData(pending.streamId, ByteArray(0))
            slog("Retried sub-stream ${pending.streamId} → accepted (session=${pending.sessionId})")
        }
    }

    /** Finds a WT session that owns the given sub-stream ID, or null. */
    private fun findWtSessionForStream(streamId: Long): KicheWebTransportServerSession? {
        for (session in wtSessions.values) {
            if (session.activeStreams.containsKey(streamId)) return session
        }
        return null
    }

    /**
     * Handles an HTTP/3 Extended CONNECT with `:protocol = webtransport`.
     * Creates a [KicheWebTransportServerSession] and dispatches the registered handler.
     */
    private fun handleWebTransportConnect(
        h3: KicheH3Connection,
        conn: KicheConnection,
        streamId: Long,
        request: RequestState,
        udpSocket: BoundDatagramSocket,
        peerSocketAddr: InetSocketAddress,
        mutex: Mutex,
        connScope: CoroutineScope,
    ) {
        val path = request.path
        val app = applicationProvider()
        val routes = app.attributes.getOrNull(WebTransportRoutesKey)
        val handler = routes?.get(path)

        if (handler == null) {
            // No handler registered for this path — reject with 404
            slog("handleWebTransportConnect: no handler for $path, rejecting")
            val headers = listOf(KicheH3Header(":status", "404"))
            h3.sendResponse(conn, streamId, headers, fin = true)
            return
        }

        slog("handleWebTransportConnect: accepting WT session on $path (stream $streamId)")

        val localSocketAddr = udpSocket.localAddress as InetSocketAddress
        val call = KicheApplicationCall(
            application = app,
            method = "CONNECT",
            path = path,
            h3Headers = request.headers,
            requestBody = ByteArray(0),
            remoteAddress = peerSocketAddr,
            localAddress = localSocketAddr,
        )

        val session = KicheWebTransportServerSession(
            call = call,
            sessionStreamId = streamId,
            conn = conn,
            h3Conn = h3,
            mutex = mutex,
            drainSend = { drainSend(conn, ByteArray(65535), udpSocket, peerSocketAddr) },
            parentContext = connScope.coroutineContext,
        )

        // Accept: send 200 and register the session
        session.acceptSessionLocked()
        wtSessions[streamId] = session

        // Launch the handler in the connection scope
        connScope.launch {
            try {
                with(handler) { session.handle() }
            } catch (e: Throwable) {
                slog("WT handler error: ${e.message}")
            } finally {
                // Handler returned — close the session if still open
                if (!session.closed.isCompleted) {
                    session.close()
                }
                wtSessions.remove(streamId)
            }
        }
    }

    private fun handleH3Request(
        h3: KicheH3Connection,
        conn: KicheConnection,
        streamId: Long,
        request: RequestState,
        udpSocket: BoundDatagramSocket,
        peerSocketAddr: InetSocketAddress,
        mutex: Mutex,
        connScope: CoroutineScope,
    ) {
        val application = applicationProvider()

        // Assemble request body
        val bodySize = request.bodyParts.sumOf { it.size }
        val bodyBytes = ByteArray(bodySize)
        var offset = 0
        for (part in request.bodyParts) {
            part.copyInto(bodyBytes, offset)
            offset += part.size
        }

        // Use InetSocketAddress for Ktor's RequestConnectionPoint (uses hostname directly)
        val localSocketAddr = udpSocket.localAddress as InetSocketAddress

        val call = KicheApplicationCall(
            application = application,
            method = request.method,
            path = request.path,
            h3Headers = request.headers,
            requestBody = bodyBytes,
            remoteAddress = peerSocketAddr,
            localAddress = localSocketAddr,
        )

        connScope.launch {
            try {
                pipeline.execute(call, Unit)
            } catch (e: Throwable) {
                environment.log.error("Unhandled exception in Ktor pipeline", e)
            }

            // Send H3 response
            val responseData = call.responseData()
            val responseHeaders = mutableListOf<KicheH3Header>()
            responseHeaders.add(KicheH3Header(":status", responseData.statusCode.toString()))
            for ((name, value) in responseData.headers) {
                responseHeaders.add(KicheH3Header(name, value))
            }

            val responseBody = responseData.body
            val sendBuf = ByteArray(65535)
            val hasBody = responseBody.isNotEmpty()

            // Send response headers under the mutex. The H3 layer may return
            // StreamBlocked (mapped as error code -13 in quiche's C API) when the
            // connection-level send capacity is temporarily exhausted. Retry until
            // the congestion window opens via incoming ACKs from the recv loop.
            var headersSent = false
            while (!headersSent && !conn.isClosed) {
                mutex.withLock {
                    try {
                        h3.sendResponse(
                            quicConn = conn, streamId = streamId,
                            headers = responseHeaders, fin = !hasBody,
                        )
                        headersSent = true
                    } catch (_: KicheH3Exception) {
                        // StreamBlocked or other retryable H3 error — retry after yield
                    }
                    drainSend(conn, sendBuf, udpSocket, peerSocketAddr)
                }
                if (!headersSent) yield()
            }

            // Send body in a loop, acquiring the mutex for each attempt.
            // Between attempts we yield so the recv loop can process ACKs
            // (which opens the QUIC flow control window for more data).
            if (hasBody) {
                slog("sendBody: stream=$streamId bodySize=${responseBody.size}")
                var offset = 0
                while (offset < responseBody.size && !conn.isClosed) {
                    mutex.withLock {
                        val chunk = responseBody.copyOfRange(offset, responseBody.size)
                        val sent = try {
                            h3.sendBody(quicConn = conn, streamId = streamId, body = chunk, fin = true)
                        } catch (_: KicheH3Exception) {
                            0
                        }
                        if (sent > 0) offset += sent
                        drainSend(conn, sendBuf, udpSocket, peerSocketAddr)
                    }
                    if (offset < responseBody.size) yield()
                }
                slog("sendBody: stream=$streamId complete offset=$offset")
            }
        }
    }

    private companion object {
        val H3_ALPN: ByteArray = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte())

        suspend fun drainSend(conn: KicheConnection, buf: ByteArray, socket: BoundDatagramSocket, peerAddr: InetSocketAddress) {
            while (true) {
                val result = conn.send(buf, buf.size) ?: break
                val packet = Buffer().apply { write(buf, 0, result.written) }
                socket.send(Datagram(packet, peerAddr))
            }
        }
    }
}
