package eu.buney.kiche.ktor

import eu.buney.kiche.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A minimal HTTP/3 test server using Kiche's quiche bindings.
 * Runs on a real UDP socket on localhost.
 *
 * Handles one client at a time — sufficient for sequential Ktor engine tests.
 * Routes:
 * - ANY /echo → 200 with JSON: {"method":"...","path":"...","bodySize":N,"body":"..."} + echoed body
 * - ANY /echo-method → 200 with body = the HTTP method name
 * - POST|PUT|PATCH /echo-body → 200 with request body echoed back verbatim
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
    private val port: Int = 0,
) : AutoCloseable {

    val actualPort: Int get() = socket.localPort

    private val socket = DatagramSocket(port, InetAddress.getLoopbackAddress())
    private val quicConfig: KicheConfig
    private val h3Config = KicheH3Config()

    @Volatile
    private var running = true
    private val serverThread: Thread

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

        serverThread = thread(name = "quiche-test-server", isDaemon = true) { serve() }
    }

    private fun serve() {
        val recvBuf = ByteArray(65535)
        val sendBuf = ByteArray(65535)
        var serverScid = ByteArray(16).also { SecureRandom().nextBytes(it) }

        socket.soTimeout = 100

        var conn: KicheConnection? = null
        var h3Conn: KicheH3Connection? = null
        var local = KicheAddress(byteArrayOf(127, 0, 0, 1), actualPort)
        var connPeerPort: Int = -1

        // Pending request state per stream
        val requests = mutableMapOf<Long, RequestState>()

        while (running) {
            // Receive UDP packet
            val packet = DatagramPacket(recvBuf, recvBuf.size)
            val len = try {
                socket.receive(packet)
                packet.length
            } catch (_: java.net.SocketTimeoutException) {
                conn?.let {
                    it.onTimeout()
                    drainSend(it, sendBuf, socket)
                    if (it.isClosed) {
                        h3Conn?.close()
                        it.close()
                        h3Conn = null
                        conn = null
                        requests.clear()
                        serverScid = ByteArray(16).also { s -> SecureRandom().nextBytes(s) }
                    }
                }
                continue
            }

            val fromIp = packet.address.address
            val fromPort = packet.port
            val from = KicheAddress(fromIp, fromPort)

            // If we have an existing connection but receive from a different peer port,
            // this is a new client. Close the old connection and accept a new one.
            if (conn != null && fromPort != connPeerPort) {
                h3Conn?.close()
                conn.close()
                h3Conn = null
                conn = null
                requests.clear()
                serverScid = ByteArray(16).also { s -> SecureRandom().nextBytes(s) }
            }

            // Accept new connection on first packet
            if (conn == null) {
                connPeerPort = fromPort
                local = KicheAddress(byteArrayOf(127, 0, 0, 1), actualPort)
                conn = KicheConnection.accept(
                    scid = serverScid,
                    odcid = null,
                    local = local,
                    peer = from,
                    config = quicConfig,
                )
            }

            conn.recv(recvBuf, len, from, local)
            drainSend(conn, sendBuf, socket)

            // Create H3 connection once QUIC handshake completes
            if (conn.isEstablished && h3Conn == null) {
                h3Conn = KicheH3Connection(conn, h3Config)
            }

            // Poll H3 events
            val h3 = h3Conn ?: continue
            while (true) {
                val event = h3.poll(conn) ?: break

                when (event.type) {
                    KicheH3EventType.Headers -> {
                        val state = RequestState()
                        event.headers?.forEach { header ->
                            when (header.nameString) {
                                ":method" -> state.method = header.valueString
                                ":path" -> state.path = header.valueString
                                else -> if (!header.nameString.startsWith(":")) {
                                    state.headers[header.nameString] = header.valueString
                                }
                            }
                        }
                        requests[event.streamId] = state
                    }

                    KicheH3EventType.Data -> {
                        val bodyBuf = ByteArray(65535)
                        val state = requests[event.streamId] ?: continue
                        while (true) {
                            val n = try {
                                h3.recvBody(conn, event.streamId, bodyBuf)
                            } catch (_: KicheException) {
                                break
                            }
                            if (n <= 0) break
                            state.body.addAll(bodyBuf.sliceArray(0 until n).toList())
                        }
                    }

                    KicheH3EventType.Finished -> {
                        val state = requests.remove(event.streamId) ?: continue
                        handleRequest(h3, conn, event.streamId, state)
                    }

                    else -> {}
                }
            }

            drainSend(conn, sendBuf, socket)

            // If connection is closed, reset for next client
            if (conn.isClosed) {
                h3Conn?.close()
                conn.close()
                h3Conn = null
                conn = null
                requests.clear()
                serverScid = ByteArray(16).also { s -> SecureRandom().nextBytes(s) }
            }
        }

        // Cleanup
        h3Conn?.close()
        conn?.close()
    }

    private fun handleRequest(
        h3: KicheH3Connection,
        conn: KicheConnection,
        streamId: Long,
        request: RequestState,
    ) {
        val path = request.path.substringBefore("?")
        val query = request.path.substringAfter("?", "")
        val method = request.method
        val bodyBytes = request.body.toByteArray()

        when {
            path == "/hello" -> {
                sendResponse(h3, conn, streamId, 200, "hello".encodeToByteArray())
            }

            path == "/empty" -> {
                sendResponse(h3, conn, streamId, 200, ByteArray(0))
            }

            path == "/echo-method" -> {
                sendResponse(h3, conn, streamId, 200, method.encodeToByteArray())
            }

            path == "/echo-body" -> {
                sendResponse(h3, conn, streamId, 200, bodyBytes)
            }

            path == "/echo" -> {
                sendResponse(h3, conn, streamId, 200, bodyBytes)
            }

            path.startsWith("/status/") -> {
                val status = path.removePrefix("/status/").toIntOrNull() ?: 400
                sendResponse(h3, conn, streamId, status, ByteArray(0))
            }

            path == "/headers" -> {
                val echoHeaders = request.headers.map { (k, v) ->
                    KicheH3Header("x-echo-$k", v)
                }
                val responseHeaders = mutableListOf(
                    KicheH3Header(":status", "200"),
                ) + echoHeaders
                h3.sendResponse(conn, streamId, responseHeaders, fin = true)
            }

            path == "/content-type" -> {
                val ct = request.headers["content-type"] ?: "none"
                sendResponse(h3, conn, streamId, 200, ct.encodeToByteArray())
            }

            path == "/query" -> {
                sendResponse(h3, conn, streamId, 200, query.encodeToByteArray())
            }

            path.startsWith("/large/") -> {
                val size = path.removePrefix("/large/").toIntOrNull() ?: 0
                val body = ByteArray(size) { 'A'.code.toByte() }
                sendResponse(h3, conn, streamId, 200, body)
            }

            path == "/multi-header" -> {
                val responseHeaders = listOf(
                    KicheH3Header(":status", "200"),
                    KicheH3Header("x-multi", "value1"),
                    KicheH3Header("x-multi", "value2"),
                    KicheH3Header("x-multi", "value3"),
                    KicheH3Header("x-single", "only"),
                )
                h3.sendResponse(conn, streamId, responseHeaders, fin = true)
            }

            else -> {
                sendResponse(h3, conn, streamId, 404, "not found".encodeToByteArray())
            }
        }
    }

    private fun sendResponse(
        h3: KicheH3Connection,
        conn: KicheConnection,
        streamId: Long,
        status: Int,
        body: ByteArray,
    ) {
        val headers = listOf(KicheH3Header(":status", status.toString()))
        val hasBody = body.isNotEmpty()
        h3.sendResponse(conn, streamId, headers, fin = !hasBody)
        if (hasBody) {
            h3.sendBody(conn, streamId, body, fin = true)
        }
    }

    override fun close() {
        running = false
        serverThread.join(5000)
        h3Config.close()
        quicConfig.close()
        socket.close()
    }

    private class RequestState {
        var method: String = "GET"
        var path: String = "/"
        var headers: MutableMap<String, String> = mutableMapOf()
        var body: MutableList<Byte> = mutableListOf()
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

        private fun drainSend(conn: KicheConnection, buf: ByteArray, socket: DatagramSocket) {
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
    }
}
