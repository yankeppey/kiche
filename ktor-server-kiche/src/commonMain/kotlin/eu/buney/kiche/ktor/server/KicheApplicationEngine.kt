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

    //region ConnectionState

    /** Tracks in-flight request state before the Finished event. */
    private class RequestState {
        var method: String = "GET"
        var path: String = "/"
        var protocol: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        val bodyParts = mutableListOf<ByteArray>()
    }

    /** Sub-streams waiting for a 200 response (sendResponse hit StreamBlocked). */
    private data class PendingSubStream(val streamId: Long, val sessionId: Long, val isUni: Boolean)

    /** Wraps a ByteArray for use as a HashMap key (contentEquals/contentHashCode). */
    private class ConnectionId(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ConnectionId && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** All per-connection state: QUIC transport, H3, requests, WebTransport sessions. */
    private class ConnectionState(
        val conn: KicheConnection,
        val peerAddr: InetSocketAddress,
        val peerKicheAddr: KicheAddress,
        val connScope: CoroutineScope,
        val dcid: ByteArray,
    ) {
        var h3Conn: KicheH3Connection? = null
        var h3Config: KicheH3Config? = null
        val requests = mutableMapOf<Long, RequestState>()
        val wtSessions = mutableMapOf<Long, KicheWebTransportServerSession>()
        val pendingSubStreams = mutableListOf<PendingSubStream>()

        suspend fun cleanup() {
            wtSessions.clear()
            connScope.coroutineContext.job.cancelAndJoin()
            h3Conn?.close()
            conn.close()
            h3Config?.close()
        }
    }

    //endregion

    private suspend fun serveLoop(
        udpSocket: BoundDatagramSocket,
        quicConfig: KicheConfig,
        localAddr: KicheAddress,
    ) {
        val mutex = Mutex()
        val sendSignal = Channel<Unit>(Channel.CONFLATED)

        val connections = HashMap<ConnectionId, ConnectionState>()

        val sendBuf = ByteArray(65535)

        // Send coroutine: drains outgoing QUIC packets and drives pending response bodies
        val sendJob = scope.launch {
            while (isActive) {
                withTimeoutOrNull(10) { sendSignal.receive() }
                mutex.withLock {
                    for (cs in connections.values) {
                        drainSend(cs.conn, sendBuf, udpSocket, cs.peerAddr)
                    }
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
                        val iter = connections.values.iterator()
                        while (iter.hasNext()) {
                            val cs = iter.next()
                            cs.conn.onTimeout()
                            val h3 = cs.h3Conn
                            if (h3 != null) {
                                pollAndDispatch(h3, cs, udpSocket, mutex)
                                driveWtSessionsLocked(h3, cs, udpSocket, sendBuf)
                                drainSend(cs.conn, sendBuf, udpSocket, cs.peerAddr)
                            }
                            if (cs.conn.isClosed) {
                                cs.cleanup()
                                iter.remove()
                            }
                        }
                        sendSignal.trySend(Unit)
                    }
                    continue
                }

                val peerAddr = dgram.address as InetSocketAddress
                val fromIp = peerAddr.resolveAddress() ?: continue
                val from = KicheAddress(fromIp, peerAddr.port)
                val bytes = dgram.packet.readByteArray()

                mutex.withLock {
                    // Parse QUIC packet header to route by DCID
                    val headerInfo = try {
                        Kiche.headerInfo(buf = bytes, len = bytes.size, dcil = DCID_LEN)
                    } catch (e: KicheException) {
                        slog("serveLoop: failed to parse header: ${e.message}")
                        return@withLock
                    }

                    val connId = ConnectionId(headerInfo.dcid)
                    var cs = connections[connId]

                    // New connection
                    if (cs == null) {
                        // Reject unsupported QUIC versions with a Version Negotiation packet
                        if (!Kiche.versionIsSupported(headerInfo.version)) {
                            slog("serveLoop: unsupported version ${headerInfo.version}, sending version negotiation")
                            val written = Kiche.negotiateVersion(
                                scid = headerInfo.scid,
                                dcid = headerInfo.dcid,
                                out = sendBuf,
                            )
                            val packet = Buffer().apply { write(sendBuf, 0, written) }
                            udpSocket.send(Datagram(packet, peerAddr))
                            return@withLock
                        }

                        // Address validation via stateless retry tokens
                        if (headerInfo.token.isEmpty()) {
                            slog("serveLoop: no token, sending retry to ${peerAddr.port}")
                            val token = mintToken(headerInfo.dcid, from)
                            val newScid = Random.nextBytes(DCID_LEN)
                            val written = Kiche.retry(
                                scid = headerInfo.scid,
                                dcid = headerInfo.dcid,
                                newScid = newScid,
                                token = token,
                                version = headerInfo.version,
                                out = sendBuf,
                            )
                            val packet = Buffer().apply { write(sendBuf, 0, written) }
                            udpSocket.send(Datagram(packet, peerAddr))
                            return@withLock
                        }

                        val odcid = validateToken(headerInfo.token, from)
                        if (odcid == null) {
                            slog("serveLoop: invalid token from ${peerAddr.port}, dropping")
                            return@withLock
                        }

                        slog("serveLoop: accepting new connection from ${peerAddr.port} (active=${connections.size})")
                        val connScope = CoroutineScope(
                            scope.coroutineContext + SupervisorJob(scope.coroutineContext.job)
                        )
                        // Use headerInfo.dcid as SCID — this is the newScid from the
                        // Retry packet, which the client used as its DCID in the retried
                        // Initial. The server must accept with this CID for the client's
                        // transport parameters to validate correctly.
                        val scid = headerInfo.dcid
                        val conn = KicheConnection.accept(
                            scid = scid, odcid = odcid,
                            local = localAddr, peer = from, config = quicConfig,
                        )
                        cs = ConnectionState(
                            conn = conn,
                            peerAddr = peerAddr,
                            peerKicheAddr = from,
                            connScope = connScope,
                            dcid = scid,
                        )
                        connections[ConnectionId(scid)] = cs
                    }

                    cs.conn.recv(buf = bytes, len = bytes.size, from = from, to = localAddr)

                    // Create H3 connection once handshake completes
                    if (cs.conn.isEstablished && cs.h3Conn == null) {
                        cs.h3Config = KicheH3Config().apply {
                            enableExtendedConnect(true)
                        }
                        cs.h3Conn = KicheH3Connection(cs.conn, cs.h3Config!!)
                    }

                    // Poll H3 events and dispatch to Ktor pipeline
                    val h3 = cs.h3Conn
                    if (h3 != null) {
                        pollAndDispatch(h3, cs, udpSocket, mutex)
                        driveWtSessionsLocked(h3, cs, udpSocket, sendBuf)
                    }

                    // Flush outgoing packets (flow control updates, ACKs) immediately.
                    drainSend(cs.conn, sendBuf, udpSocket, cs.peerAddr)

                    sendSignal.trySend(Unit)

                    // Connection closed → cleanup
                    if (cs.conn.isClosed) {
                        cs.cleanup()
                        connections.remove(connId)
                    }
                }
            }
        } finally {
            sendJob.cancel()
            for (cs in connections.values) cs.cleanup()
            connections.clear()
        }
    }

    //region H3 event polling & dispatch

    private fun pollAndDispatch(
        h3: KicheH3Connection,
        cs: ConnectionState,
        udpSocket: BoundDatagramSocket,
        mutex: Mutex,
    ) {
        while (true) {
            val event = try {
                h3.poll(quicConn = cs.conn) ?: break
            } catch (e: KicheH3Exception) {
                if (e.isRetryable) break
                throw e
            }

            when (event.type) {
                KicheH3EventType.Headers -> {
                    // Check if this stream belongs to an active WT session
                    val wtSession = findWtSessionForStream(cs, event.streamId)
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
                    cs.requests[event.streamId] = state
                    slog("Headers stream=${event.streamId} ${state.method} ${state.path} protocol=${state.protocol}")

                    // If this is a WebTransport CONNECT, handle immediately (no body expected)
                    if (state.method == "CONNECT" && state.protocol == "webtransport") {
                        cs.requests.remove(event.streamId)
                        handleWebTransportConnect(h3, cs, event.streamId, state, udpSocket, mutex)
                    } else if (state.method == "CONNECT" && state.protocol == "webtransport-stream") {
                        // Sub-stream belonging to a WT session — route to the session
                        cs.requests.remove(event.streamId)
                        val sessionIdStr = state.headers.find { it.first == "wt-session-id" }?.second
                        val sessionId = sessionIdStr?.toLongOrNull()
                        val session = if (sessionId != null) cs.wtSessions[sessionId] else null
                        if (session != null) {
                            val isUni = state.headers.any { it.first == "wt-uni" && it.second == "true" }
                            acceptSubStreamLocked(h3, cs, event.streamId, sessionId!!, session, isUni)
                        } else {
                            slog("Headers stream=${event.streamId} → WT sub-stream but no session found for $sessionId")
                        }
                    }
                }

                KicheH3EventType.Data -> {
                    // Check if this is data on a WT session's CONNECT stream or sub-stream
                    val wtSession = cs.wtSessions[event.streamId]
                    if (wtSession != null) {
                        // Data on the CONNECT stream (capsule protocol / datagrams)
                        val bodyBuf = ByteArray(65535)
                        while (true) {
                            val n = try {
                                h3.recvBody(quicConn = cs.conn, streamId = event.streamId, buf = bodyBuf)
                            } catch (e: KicheH3Exception) {
                                if (e.isRetryable) break
                                throw e
                            }
                            if (n <= 0) break
                            wtSession.onDatagram(bodyBuf.copyOf(n))
                        }
                        continue
                    }

                    // Check if this is data on a WT sub-stream
                    val wtSubSession = findWtSessionForStream(cs, event.streamId)
                    if (wtSubSession != null) {
                        val bodyBuf = ByteArray(65535)
                        while (true) {
                            val n = try {
                                h3.recvBody(quicConn = cs.conn, streamId = event.streamId, buf = bodyBuf)
                            } catch (e: KicheH3Exception) {
                                if (e.isRetryable) break
                                throw e
                            }
                            if (n <= 0) break
                            wtSubSession.onStreamData(event.streamId, bodyBuf.copyOf(n))
                        }
                        continue
                    }

                    val bodyBuf = ByteArray(65535)
                    val state = cs.requests[event.streamId] ?: continue
                    var totalRead = 0
                    while (true) {
                        val n = try {
                            h3.recvBody(quicConn = cs.conn, streamId = event.streamId, buf = bodyBuf)
                        } catch (e: KicheH3Exception) {
                            if (e.isRetryable) break
                            throw e
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
                    val wtSession = cs.wtSessions[event.streamId] ?: findWtSessionForStream(cs, event.streamId)
                    if (wtSession != null) {
                        wtSession.onStreamFinished(event.streamId)
                        if (event.streamId in cs.wtSessions) {
                            slog("Finished: WT CONNECT stream ${event.streamId} → removing session")
                            cs.wtSessions.remove(event.streamId)
                        }
                        continue
                    }

                    val state = cs.requests.remove(event.streamId) ?: continue
                    val bodySize = state.bodyParts.sumOf { it.size }
                    slog("Finished stream=${event.streamId} bodySize=$bodySize → handleH3Request")
                    handleH3Request(h3, cs, event.streamId, state, udpSocket, mutex)
                }

                KicheH3EventType.Reset -> {
                    val wtSession = cs.wtSessions[event.streamId] ?: findWtSessionForStream(cs, event.streamId)
                    if (wtSession != null) {
                        wtSession.onStreamReset(event.streamId)
                        if (event.streamId in cs.wtSessions) {
                            cs.wtSessions.remove(event.streamId)
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

    //endregion

    //region WebTransport

    /**
     * Drives outgoing writes and datagram sends for all active WT sessions,
     * and reads incoming QUIC stream data for WT sub-streams.
     * Called under mutex.
     */
    private fun driveWtSessionsLocked(
        h3: KicheH3Connection,
        cs: ConnectionState,
        udpSocket: BoundDatagramSocket,
        sendBuf: ByteArray,
    ) {
        // Retry any sub-stream responses that were blocked
        if (cs.pendingSubStreams.isNotEmpty()) {
            retryPendingSubStreamsLocked(h3, cs)
        }

        for (session in cs.wtSessions.values) {
            // Drive outgoing datagrams and stream writes
            session.driveOutgoingLocked()

            // NOTE: Do NOT read from QUIC streams here. Client sub-streams are
            // H3 request streams — their data arrives via H3 Data events in
            // pollAndDispatch(). Using conn.streamReadableNext()/streamRecv()
            // would steal bytes from H3 internal streams (control, QPACK) and
            // corrupt the H3 connection state.

            // Drain incoming QUIC datagrams — strip Quarter Stream ID (RFC 9297)
            val dgramBuf = ByteArray(cs.conn.dgramMaxWritableLen().toInt().coerceAtLeast(1))
            while (cs.conn.dgramRecvQueueLen() > 0) {
                val n = try {
                    cs.conn.dgramRecv(dgramBuf, dgramBuf.size)
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
     * temporarily blocked (StreamBlocked), defers to [ConnectionState.pendingSubStreams] for
     * retry on the next event loop iteration. Called under mutex.
     */
    private fun acceptSubStreamLocked(
        h3: KicheH3Connection, cs: ConnectionState,
        streamId: Long, sessionId: Long,
        session: KicheWebTransportServerSession, isUni: Boolean,
    ) {
        try {
            h3.sendResponse(cs.conn, streamId, listOf(KicheH3Header(":status", "200")), fin = false)
        } catch (e: KicheH3Exception) {
            if (e.isRetryable) {
                cs.pendingSubStreams.add(PendingSubStream(streamId, sessionId, isUni))
                slog("Headers stream=$streamId → WT sub-stream blocked, queued for retry")
                return
            }
            throw e
        }
        session.onStreamData(streamId, ByteArray(0))
        slog("Headers stream=$streamId → WT sub-stream (session=$sessionId, uni=$isUni)")
    }

    /** Retries pending sub-stream 200 responses. Called under mutex. */
    private fun retryPendingSubStreamsLocked(h3: KicheH3Connection, cs: ConnectionState) {
        val iter = cs.pendingSubStreams.iterator()
        while (iter.hasNext()) {
            val pending = iter.next()
            val session = cs.wtSessions[pending.sessionId] ?: run { iter.remove(); continue }
            try {
                h3.sendResponse(cs.conn, pending.streamId, listOf(KicheH3Header(":status", "200")), fin = false)
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
    private fun findWtSessionForStream(cs: ConnectionState, streamId: Long): KicheWebTransportServerSession? {
        for (session in cs.wtSessions.values) {
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
        cs: ConnectionState,
        streamId: Long,
        request: RequestState,
        udpSocket: BoundDatagramSocket,
        mutex: Mutex,
    ) {
        val path = request.path
        val app = applicationProvider()
        val routes = app.attributes.getOrNull(WebTransportRoutesKey)
        val handler = routes?.get(path)

        if (handler == null) {
            // No handler registered for this path — reject with 404
            slog("handleWebTransportConnect: no handler for $path, rejecting")
            val headers = listOf(KicheH3Header(":status", "404"))
            h3.sendResponse(cs.conn, streamId, headers, fin = true)
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
            remoteAddress = cs.peerAddr,
            localAddress = localSocketAddr,
        )

        val session = KicheWebTransportServerSession(
            call = call,
            sessionStreamId = streamId,
            conn = cs.conn,
            h3Conn = h3,
            mutex = mutex,
            drainSend = { drainSend(cs.conn, ByteArray(65535), udpSocket, cs.peerAddr) },
            parentContext = cs.connScope.coroutineContext,
        )

        // Accept: send 200 and register the session
        session.acceptSessionLocked()
        cs.wtSessions[streamId] = session

        // Launch the handler in the connection scope
        cs.connScope.launch {
            try {
                with(handler) { session.handle() }
            } catch (e: Throwable) {
                slog("WT handler error: ${e.message}")
            } finally {
                // Handler returned — close the session if still open
                if (!session.closed.isCompleted) {
                    session.close()
                }
                cs.wtSessions.remove(streamId)
            }
        }
    }

    //endregion

    //region H3 request handling

    private fun handleH3Request(
        h3: KicheH3Connection,
        cs: ConnectionState,
        streamId: Long,
        request: RequestState,
        udpSocket: BoundDatagramSocket,
        mutex: Mutex,
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
            remoteAddress = cs.peerAddr,
            localAddress = localSocketAddr,
        )

        cs.connScope.launch {
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
            while (!headersSent && !cs.conn.isClosed) {
                mutex.withLock {
                    try {
                        h3.sendResponse(
                            quicConn = cs.conn, streamId = streamId,
                            headers = responseHeaders, fin = !hasBody,
                        )
                        headersSent = true
                    } catch (e: KicheH3Exception) {
                        if (!e.isRetryable) throw e
                    }
                    drainSend(cs.conn, sendBuf, udpSocket, cs.peerAddr)
                }
                if (!headersSent) yield()
            }

            // Send body in a loop, acquiring the mutex for each attempt.
            // Between attempts we yield so the recv loop can process ACKs
            // (which opens the QUIC flow control window for more data).
            if (hasBody) {
                slog("sendBody: stream=$streamId bodySize=${responseBody.size}")
                var bodyOffset = 0
                while (bodyOffset < responseBody.size && !cs.conn.isClosed) {
                    mutex.withLock {
                        val chunk = responseBody.copyOfRange(bodyOffset, responseBody.size)
                        val sent = try {
                            h3.sendBody(quicConn = cs.conn, streamId = streamId, body = chunk, fin = true)
                        } catch (e: KicheH3Exception) {
                            if (!e.isRetryable) throw e
                            0
                        }
                        if (sent > 0) bodyOffset += sent
                        drainSend(cs.conn, sendBuf, udpSocket, cs.peerAddr)
                    }
                    if (bodyOffset < responseBody.size) yield()
                }
                slog("sendBody: stream=$streamId complete offset=$bodyOffset")
            }
        }
    }

    //endregion

    internal companion object {
        const val DCID_LEN = 16
        val H3_ALPN: ByteArray = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte())

        private val TOKEN_PREFIX = "quiche".encodeToByteArray() // 6 bytes

        /**
         * Mints a stateless retry token: `"quiche" + ip + port(2B BE) + dcid`.
         * Same scheme as quiche's C example http3-server.c.
         */
        internal fun mintToken(dcid: ByteArray, peerAddr: KicheAddress): ByteArray {
            val portBytes = byteArrayOf(
                (peerAddr.port shr 8).toByte(),
                (peerAddr.port and 0xFF).toByte(),
            )
            return TOKEN_PREFIX + peerAddr.ip + portBytes + dcid
        }

        /**
         * Validates a retry token and extracts the original DCID.
         * Returns the original DCID, or null if the token is invalid.
         */
        internal fun validateToken(token: ByteArray, peerAddr: KicheAddress): ByteArray? {
            if (token.size < TOKEN_PREFIX.size) return null
            // Check prefix
            for (i in TOKEN_PREFIX.indices) {
                if (token[i] != TOKEN_PREFIX[i]) return null
            }
            var offset = TOKEN_PREFIX.size
            // Check peer IP
            val ipLen = peerAddr.ip.size
            if (token.size < offset + ipLen) return null
            for (i in 0 until ipLen) {
                if (token[offset + i] != peerAddr.ip[i]) return null
            }
            offset += ipLen
            // Check peer port (2 bytes big-endian)
            if (token.size < offset + 2) return null
            val tokenPort = ((token[offset].toInt() and 0xFF) shl 8) or (token[offset + 1].toInt() and 0xFF)
            if (tokenPort != peerAddr.port) return null
            offset += 2
            // Remainder is the original DCID
            if (offset >= token.size) return null
            return token.copyOfRange(offset, token.size)
        }

        suspend fun drainSend(conn: KicheConnection, buf: ByteArray, socket: BoundDatagramSocket, peerAddr: InetSocketAddress) {
            while (true) {
                val result = conn.send(buf, buf.size) ?: break
                val packet = Buffer().apply { write(buf, 0, result.written) }
                // TODO: use result.to for multi-path / connection migration support
                socket.send(Datagram(packet, peerAddr))
            }
        }
    }
}
