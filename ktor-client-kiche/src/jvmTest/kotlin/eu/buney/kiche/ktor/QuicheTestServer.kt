package eu.buney.kiche.ktor

import eu.buney.kiche.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * A minimal HTTP/3 test server using Kiche's quiche bindings.
 * Runs on a real UDP socket on localhost.
 *
 * Uses coroutines to interleave sending and receiving on the UDP socket,
 * which is required for QUIC flow control to work with large bodies.
 * Architecture (modeled after Ktor CIO server):
 * - **recv coroutine**: reads UDP packets, feeds conn.recv(), polls H3 events
 * - **send coroutine**: drains conn.send() and writes UDP packets
 * - **Mutex**: serializes access to the quiche connection (not thread-safe)
 * - **Channel**: recv signals send that there may be packets to flush
 *
 * Routes:
 * - ANY /echo-method → 200 with body = the HTTP method name
 * - ANY /echo-body → 200 with request body echoed back verbatim
 * - ANY /echo → 200 with request body echoed back
 * - GET /hello → 200 "hello"
 * - ANY /status/NNN → NNN with empty body
 * - ANY /headers → 200 with response headers echoing request headers as "x-echo-NAME: VALUE"
 * - ANY /content-type → 200 with body = request content-type header value
 * - ANY /query → 200 with body = the raw query string (path after ?)
 * - GET /large/N → 200 with body of N bytes (repeating 'A')
 * - GET /multi-header → 200 with multiple values for x-multi header
 * - GET /empty → 200 with empty body
 * - anything else → 404
 */
class QuicheTestServer(
    port: Int = 0,
) : AutoCloseable {

    val actualPort: Int get() = socket.localPort

    private val socket = DatagramSocket(port, InetAddress.getLoopbackAddress())
    private val quicConfig: KicheConfig

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        val certDir = findCertDir()
        quicConfig = KicheConfig().apply {
            loadCertChainFromPemFile("$certDir/cert.crt")
            loadPrivKeyFromPemFile("$certDir/cert.key")
            setApplicationProtos(H3_ALPN)
            verifyPeer(false)
            setInitialMaxData(10_000_000)
            setInitialMaxStreamDataBidiLocal(1_000_000)
            setInitialMaxStreamDataBidiRemote(1_000_000)
            setInitialMaxStreamDataUni(1_000_000)
            setInitialMaxStreamsBidi(100)
            setInitialMaxStreamsUni(100)
            setMaxIdleTimeout(30_000)
        }

        socket.soTimeout = 50
        scope.launch { serve() }
    }

    /**
     * Holds all mutable state for one QUIC+H3 connection.
     * Access must be serialized via [mutex].
     */
    private class ConnectionState(
        val conn: KicheConnection,
        val peerPort: Int,
    ) {
        var h3Conn: KicheH3Connection? = null
        var h3Config: KicheH3Config? = null
        val requests = mutableMapOf<Long, RequestState>()
        /** Streams that have a complete request and pending response body to send. */
        val pendingResponses = mutableListOf<PendingResponse>()

        fun close() {
            h3Conn?.close()
            h3Config?.close()
            conn.close()
        }
    }

    private class PendingResponse(
        val streamId: Long,
        val body: ByteArray,
        var offset: Int = 0,
    )

    private class RequestState {
        var method: String = "GET"
        var path: String = "/"
        var headers: MutableMap<String, String> = mutableMapOf()
        var body: MutableList<Byte> = mutableListOf()
    }

    /** Mutex serializing all access to quiche connection state (quiche is not thread-safe). */
    private val mutex = Mutex()

    /** Signal channel: recv coroutine notifies send coroutine that there may be outgoing packets. */
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)

    private suspend fun serve() {
        var state: ConnectionState? = null
        val local = KicheAddress(byteArrayOf(127, 0, 0, 1), actualPort)

        // Send coroutine: drains outgoing QUIC packets whenever signaled,
        // and also periodically flushes + drives pending large response bodies.
        val sendJob = scope.launch {
            val sendBuf = ByteArray(65535)
            while (isActive) {
                // Wait for signal or periodic tick (for timer-driven retransmits)
                withTimeoutOrNull(10) { sendSignal.receive() }

                mutex.withLock {
                    val s = state ?: return@withLock
                    // Drive pending large response bodies
                    drivePendingResponses(s)
                    // Flush outgoing packets
                    drainSend(s.conn, sendBuf)
                }
            }
        }

        // Recv coroutine: reads UDP packets, processes H3 events.
        val recvBuf = ByteArray(65535)
        try {
            while (scope.isActive) {
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                val len = try {
                    socket.receive(packet)
                    packet.length
                } catch (_: java.net.SocketTimeoutException) {
                    // Drive timeouts
                    mutex.withLock {
                        val s = state ?: return@withLock
                        s.conn.onTimeout()
                        sendSignal.trySend(Unit)
                    }
                    continue
                }

                val fromPort = packet.port
                val from = KicheAddress(packet.address.address, fromPort)

                mutex.withLock {
                    // New client from different port → reset connection
                    state?.let { current ->
                        if (fromPort != current.peerPort) {
                            current.close()
                            state = null
                        }
                    }

                    // Accept new connection
                    if (state == null) {
                        val scid = ByteArray(16).also { SecureRandom().nextBytes(it) }
                        val conn = KicheConnection.accept(
                            scid = scid, odcid = null,
                            local = local, peer = from, config = quicConfig,
                        )
                        state = ConnectionState(conn, fromPort)
                    }

                    val s = state ?: return@withLock
                    s.conn.recv(buf = recvBuf, len = len, from = from, to = local)

                    // Create H3 connection once handshake completes
                    if (s.conn.isEstablished && s.h3Conn == null) {
                        s.h3Config = KicheH3Config()
                        s.h3Conn = KicheH3Connection(s.conn, s.h3Config!!)
                    }

                    // Poll H3 events
                    val h3 = s.h3Conn
                    if (h3 != null) {
                        pollH3Events(s, h3)
                    }

                    // Signal send coroutine
                    sendSignal.trySend(Unit)

                    // Connection closed → cleanup
                    if (s.conn.isClosed) {
                        s.close()
                        state = null
                    }
                }
            }
        } finally {
            sendJob.cancel()
            mutex.withLock { state?.close() }
        }
    }

    private fun pollH3Events(s: ConnectionState, h3: KicheH3Connection) {
        while (true) {
            val event = h3.poll(quicConn = s.conn) ?: break

            when (event.type) {
                KicheH3EventType.Headers -> {
                    val req = RequestState()
                    event.headers?.forEach { header ->
                        when (header.nameString) {
                            ":method" -> req.method = header.valueString
                            ":path" -> req.path = header.valueString
                            else -> if (!header.nameString.startsWith(":")) {
                                req.headers[header.nameString] = header.valueString
                            }
                        }
                    }
                    s.requests[event.streamId] = req
                }

                KicheH3EventType.Data -> {
                    val bodyBuf = ByteArray(65535)
                    val req = s.requests[event.streamId] ?: continue
                    while (true) {
                        val n = try {
                            h3.recvBody(quicConn = s.conn, streamId = event.streamId, buf = bodyBuf)
                        } catch (_: KicheException) {
                            break
                        }
                        if (n <= 0) break
                        req.body.addAll(bodyBuf.copyOf(n).toList())
                    }
                }

                KicheH3EventType.Finished -> {
                    val req = s.requests.remove(event.streamId) ?: continue
                    handleRequest(s, h3, event.streamId, req)
                }

                else -> {}
            }
        }
    }

    /**
     * Drives pending large response bodies by sending as much as flow control allows.
     * Called from the send coroutine on each tick.
     */
    private fun drivePendingResponses(s: ConnectionState) {
        val h3 = s.h3Conn ?: return
        val iter = s.pendingResponses.iterator()
        while (iter.hasNext()) {
            val pending = iter.next()
            while (pending.offset < pending.body.size) {
                val chunk = pending.body.copyOfRange(pending.offset, pending.body.size)
                val sent = try {
                    h3.sendBody(quicConn = s.conn, streamId = pending.streamId, body = chunk, fin = true)
                } catch (_: KicheException) {
                    break // stream blocked, will retry next tick
                }
                if (sent <= 0) break
                pending.offset += sent
            }
            if (pending.offset >= pending.body.size) {
                iter.remove()
            }
        }
    }

    private fun handleRequest(
        s: ConnectionState,
        h3: KicheH3Connection,
        streamId: Long,
        request: RequestState,
    ) {
        val path = request.path.substringBefore("?")
        val query = request.path.substringAfter("?", "")
        val method = request.method
        val bodyBytes = request.body.toByteArray()

        when {
            path == "/hello" ->
                sendResponse(s, h3, streamId, 200, "hello".encodeToByteArray())

            path == "/empty" ->
                sendResponse(s, h3, streamId, 200, ByteArray(0))

            path == "/echo-method" ->
                sendResponse(s, h3, streamId, 200, method.encodeToByteArray())

            path == "/echo-body" ->
                sendResponse(s, h3, streamId, 200, bodyBytes)

            path == "/echo" ->
                sendResponse(s, h3, streamId, 200, bodyBytes)

            path.startsWith("/status/") -> {
                val status = path.removePrefix("/status/").toIntOrNull() ?: 400
                sendResponse(s, h3, streamId, status, ByteArray(0))
            }

            path == "/headers" -> {
                val echoHeaders = request.headers.map { (k, v) ->
                    KicheH3Header("x-echo-$k", v)
                }
                val responseHeaders = mutableListOf(
                    KicheH3Header(":status", "200"),
                ) + echoHeaders
                h3.sendResponse(quicConn = s.conn, streamId = streamId, headers = responseHeaders, fin = true)
            }

            path == "/content-type" -> {
                val ct = request.headers["content-type"] ?: "none"
                sendResponse(s, h3, streamId, 200, ct.encodeToByteArray())
            }

            path == "/query" ->
                sendResponse(s, h3, streamId, 200, query.encodeToByteArray())

            path.startsWith("/large/") -> {
                val size = path.removePrefix("/large/").toIntOrNull() ?: 0
                val body = ByteArray(size) { 'A'.code.toByte() }
                sendResponse(s, h3, streamId, 200, body)
            }

            path == "/multi-header" -> {
                val responseHeaders = listOf(
                    KicheH3Header(":status", "200"),
                    KicheH3Header("x-multi", "value1"),
                    KicheH3Header("x-multi", "value2"),
                    KicheH3Header("x-multi", "value3"),
                    KicheH3Header("x-single", "only"),
                )
                h3.sendResponse(quicConn = s.conn, streamId = streamId, headers = responseHeaders, fin = true)
            }

            else ->
                sendResponse(s, h3, streamId, 404, "not found".encodeToByteArray())
        }
    }

    /**
     * Sends H3 response headers and begins sending body.
     * If the full body can't be sent immediately (flow control), queues it
     * as a [PendingResponse] to be driven incrementally by the send coroutine.
     */
    private fun sendResponse(
        s: ConnectionState,
        h3: KicheH3Connection,
        streamId: Long,
        status: Int,
        body: ByteArray,
    ) {
        val headers = listOf(KicheH3Header(":status", status.toString()))
        val hasBody = body.isNotEmpty()
        h3.sendResponse(quicConn = s.conn, streamId = streamId, headers = headers, fin = !hasBody)
        if (hasBody) {
            val sent = try {
                h3.sendBody(quicConn = s.conn, streamId = streamId, body = body, fin = true)
            } catch (_: KicheException) {
                0
            }
            if (sent < body.size) {
                // Couldn't send everything — queue remainder for incremental sending
                s.pendingResponses.add(PendingResponse(streamId, body, offset = sent))
            }
        }
    }

    private fun drainSend(conn: KicheConnection, buf: ByteArray) {
        while (true) {
            val result = conn.send(buf, buf.size) ?: break
            val packet = DatagramPacket(
                buf, 0, result.written,
                InetAddress.getByAddress(result.to.ip),
                result.to.port,
            )
            socket.send(packet)
        }
    }

    override fun close() {
        scope.cancel()
        quicConfig.close()
        socket.close()
    }

    companion object {
        private val H3_ALPN = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte())

        private fun findCertDir(): String {
            val candidates = listOf(
                "third_party/quiche/quiche/examples",
                "../third_party/quiche/quiche/examples",
            )
            for (path in candidates) {
                if (File(path, "cert.crt").exists()) return path
            }
            error("Cannot find quiche example certs. Searched: $candidates")
        }
    }
}
