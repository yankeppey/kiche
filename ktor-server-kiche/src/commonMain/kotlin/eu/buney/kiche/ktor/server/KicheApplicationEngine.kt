package eu.buney.kiche.ktor.server

import eu.buney.kiche.*
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
        val pendingResponses = mutableListOf<PendingResponse>()

        val sendBuf = ByteArray(65535)

        // Send coroutine: drains outgoing QUIC packets and drives pending response bodies
        val sendJob = scope.launch {
            while (isActive) {
                withTimeoutOrNull(10) { sendSignal.receive() }
                mutex.withLock {
                    val c = conn ?: return@withLock
                    val peerAddr = connPeerSocketAddr ?: return@withLock
                    val h3 = h3Conn
                    if (h3 != null) {
                        drivePendingResponses(h3, c, pendingResponses)
                    }
                    drainSend(c, sendBuf, udpSocket, peerAddr)
                }
            }
        }

        // Recv loop
        try {
            while (scope.isActive) {
                val dgram = withTimeoutOrNull(50) { udpSocket.receive() }

                if (dgram == null) {
                    // Timeout — drive QUIC timers
                    mutex.withLock {
                        conn?.onTimeout()
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
                            // Wait for any in-flight response coroutines before freeing native objects
                            joinResponseJobs()
                            h3Conn?.close()
                            c.close()
                            h3Config?.close()
                            h3Conn = null
                            h3Config = null
                            conn = null
                            connPeerSocketAddr = null
                            pendingResponses.clear()
                        }
                    }

                    // Accept new connection
                    if (conn == null) {
                        connPeerPort = fromPort
                        connPeerSocketAddr = peerAddr
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
                        h3Config = KicheH3Config()
                        h3Conn = KicheH3Connection(c, h3Config!!)
                    }

                    // Poll H3 events and dispatch to Ktor pipeline
                    val h3 = h3Conn
                    if (h3 != null) {
                        pollAndDispatch(h3, c, from, localAddr, pendingResponses, udpSocket, peerAddr, mutex)
                    }

                    sendSignal.trySend(Unit)

                    // Connection closed → cleanup
                    if (c.isClosed) {
                        joinResponseJobs()
                        h3Conn?.close()
                        c.close()
                        h3Config?.close()
                        h3Conn = null
                        h3Config = null
                        conn = null
                        connPeerSocketAddr = null
                        pendingResponses.clear()
                    }
                }
            }
        } finally {
            sendJob.cancel()
            h3Conn?.close()
            conn?.close()
            h3Config?.close()
        }
    }

    /** Tracks in-flight request state before the Finished event. */
    private class RequestState {
        var method: String = "GET"
        var path: String = "/"
        val headers = mutableListOf<Pair<String, String>>()
        val bodyParts = mutableListOf<ByteArray>()
    }

    /** Tracks a response body that couldn't be fully sent due to flow control. */
    private class PendingResponse(
        val streamId: Long,
        val body: ByteArray,
        var offset: Int = 0,
    )

    private val requests = mutableMapOf<Long, RequestState>()

    /** Outstanding response coroutines that hold references to the current h3/conn objects. */
    private val responseJobs = mutableListOf<Job>()

    /**
     * Waits for all in-flight response coroutines to complete before freeing native objects.
     * Must be called BEFORE closing h3Conn/conn to avoid use-after-free.
     */
    private suspend fun joinResponseJobs() {
        responseJobs.forEach { it.join() }
        responseJobs.clear()
    }

    private fun pollAndDispatch(
        h3: KicheH3Connection,
        conn: KicheConnection,
        remoteAddr: KicheAddress,
        localAddr: KicheAddress,
        pendingResponses: MutableList<PendingResponse>,
        udpSocket: BoundDatagramSocket,
        peerSocketAddr: InetSocketAddress,
        mutex: Mutex,
    ) {
        while (true) {
            val event = h3.poll(quicConn = conn) ?: break

            when (event.type) {
                KicheH3EventType.Headers -> {
                    val state = RequestState()
                    event.headers?.forEach { header ->
                        val name = header.nameString
                        val value = header.valueString
                        when (name) {
                            ":method" -> state.method = value
                            ":path" -> state.path = value
                        }
                        state.headers.add(name to value)
                    }
                    requests[event.streamId] = state
                }

                KicheH3EventType.Data -> {
                    val bodyBuf = ByteArray(65535)
                    val state = requests[event.streamId] ?: continue
                    while (true) {
                        val n = try {
                            h3.recvBody(quicConn = conn, streamId = event.streamId, buf = bodyBuf)
                        } catch (_: KicheException) {
                            break
                        }
                        if (n <= 0) break
                        state.bodyParts.add(bodyBuf.copyOf(n))
                    }
                }

                KicheH3EventType.Finished -> {
                    val state = requests.remove(event.streamId) ?: continue
                    handleH3Request(h3, conn, event.streamId, state, remoteAddr, localAddr, pendingResponses, udpSocket, peerSocketAddr, mutex)
                }

                else -> {}
            }
        }
    }

    private fun handleH3Request(
        h3: KicheH3Connection,
        conn: KicheConnection,
        streamId: Long,
        request: RequestState,
        remoteAddr: KicheAddress,
        localAddr: KicheAddress,
        pendingResponses: MutableList<PendingResponse>,
        udpSocket: BoundDatagramSocket,
        peerSocketAddr: InetSocketAddress,
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
            remoteAddress = peerSocketAddr,
            localAddress = localSocketAddr,
        )

        val job = scope.launch {
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

            // Send headers (and possibly small bodies) under the mutex
            mutex.withLock {
                h3.sendResponse(
                    quicConn = conn, streamId = streamId,
                    headers = responseHeaders, fin = !hasBody,
                )
                drainSend(conn, sendBuf, udpSocket, peerSocketAddr)
            }

            // Send body in a loop, acquiring the mutex for each attempt.
            // Between attempts we yield so the recv loop can process ACKs
            // (which opens the QUIC flow control window for more data).
            if (hasBody) {
                var offset = 0
                while (offset < responseBody.size && !conn.isClosed) {
                    mutex.withLock {
                        val chunk = responseBody.copyOfRange(offset, responseBody.size)
                        val sent = try {
                            h3.sendBody(quicConn = conn, streamId = streamId, body = chunk, fin = true)
                        } catch (_: KicheException) {
                            0
                        }
                        if (sent > 0) offset += sent
                        drainSend(conn, sendBuf, udpSocket, peerSocketAddr)
                    }
                    if (offset < responseBody.size) yield()
                }
            }
        }
        responseJobs.add(job)
        job.invokeOnCompletion { responseJobs.remove(job) }
    }

    private companion object {
        val H3_ALPN: ByteArray = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte())

        fun drivePendingResponses(
            h3: KicheH3Connection,
            conn: KicheConnection,
            pendingResponses: MutableList<PendingResponse>,
        ) {
            val iter = pendingResponses.iterator()
            while (iter.hasNext()) {
                val pending = iter.next()
                while (pending.offset < pending.body.size) {
                    val chunk = pending.body.copyOfRange(pending.offset, pending.body.size)
                    val sent = try {
                        h3.sendBody(quicConn = conn, streamId = pending.streamId, body = chunk, fin = true)
                    } catch (_: KicheException) {
                        break
                    }
                    if (sent <= 0) break
                    pending.offset += sent
                }
                if (pending.offset >= pending.body.size) {
                    iter.remove()
                }
            }
        }

        suspend fun drainSend(conn: KicheConnection, buf: ByteArray, socket: BoundDatagramSocket, peerAddr: InetSocketAddress) {
            while (true) {
                val result = conn.send(buf, buf.size) ?: break
                val packet = Buffer().apply { write(buf, 0, result.written) }
                socket.send(Datagram(packet, peerAddr))
            }
        }
    }
}
