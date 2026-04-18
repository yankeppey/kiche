package eu.buney.kiche.ktor

import eu.buney.kiche.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext

@OptIn(InternalAPI::class)
public class KicheEngine(override val config: KicheEngineConfig) : HttpClientEngineBase("ktor-kiche") {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
        setOf(HttpTimeoutCapability)

    private val requestsJob: CoroutineContext

    override val coroutineContext: CoroutineContext

    init {
        val parent = super.coroutineContext.job
        requestsJob = SilentSupervisor(parent)
        coroutineContext = super.coroutineContext + requestsJob
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()

        val host = data.url.host
        val port = data.url.port.takeIf { it != 0 } ?: DEFAULT_HTTPS_PORT

        // Resolve server address
        val serverAddr = InetAddress.getByName(host)
        val serverBytes = serverAddr.address
        val peer = KicheAddress(serverBytes, port)

        // Bind a UDP socket to get a local address
        val udpSocket = DatagramSocket()
        udpSocket.connect(InetSocketAddress(serverAddr, port))
        val localAddr = udpSocket.localAddress.address ?: byteArrayOf(0, 0, 0, 0)
        val localPort = udpSocket.localPort
        val local = KicheAddress(localAddr, localPort)

        // Generate random SCID
        val scid = ByteArray(16).also { SecureRandom().nextBytes(it) }

        val quicConfig = KicheConfig().apply {
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
            val conn = KicheConnection.connect(host, scid, local, peer, quicConfig)
            try {
                // QUIC handshake + HTTP/3 request/response
                val responseData = executeH3Request(conn, udpSocket, local, peer, data, callContext, requestTime)
                return responseData
            } finally {
                conn.close()
            }
        } finally {
            quicConfig.close()
            udpSocket.close()
        }
    }

    private suspend fun executeH3Request(
        conn: KicheConnection,
        udpSocket: DatagramSocket,
        local: KicheAddress,
        peer: KicheAddress,
        data: HttpRequestData,
        callContext: CoroutineContext,
        requestTime: GMTDate,
    ): HttpResponseData = withContext(Dispatchers.IO) {
        val sendBuf = ByteArray(MAX_DATAGRAM_SIZE)
        val recvBuf = ByteArray(MAX_DATAGRAM_SIZE)

        // Drive initial QUIC handshake packets
        drainSend(conn, sendBuf, udpSocket)

        // Handshake loop
        while (!conn.isEstablished) {
            val len = udpSocket.receivePacket(recvBuf)
            conn.recv(recvBuf, len, peer, local)
            drainSend(conn, sendBuf, udpSocket)
        }

        // Create H3 connection
        val h3Config = KicheH3Config()
        val h3Conn = KicheH3Connection(conn, h3Config)

        try {
            // Build HTTP/3 request headers
            val headers = buildH3Headers(data)
            val hasBody = data.body !is OutgoingContent.NoContent

            val streamId = h3Conn.sendRequest(quicConn = conn, headers = headers, fin = !hasBody)

            // Send body if present
            if (hasBody) {
                val bodyBytes = when (val body = data.body) {
                    is OutgoingContent.ByteArrayContent -> body.bytes()
                    is OutgoingContent.NoContent -> ByteArray(0)
                    else -> error("Unsupported request body type: ${body::class.simpleName}. Only ByteArrayContent is supported.")
                }
                if (bodyBytes.isNotEmpty()) {
                    h3Conn.sendBody(quicConn = conn, streamId = streamId, body = bodyBytes, fin = true)
                } else {
                    h3Conn.sendBody(quicConn = conn, streamId = streamId, body = ByteArray(0), fin = true)
                }
            }

            drainSend(conn, sendBuf, udpSocket)

            // Poll for response
            var responseHeaders: Headers = Headers.Empty
            var responseStatus = HttpStatusCode.OK
            val responseBodyParts = mutableListOf<ByteArray>()
            var finished = false

            while (!finished && !conn.isClosed) {
                // Receive packets from network
                if (udpSocket.soTimeout == 0) {
                    val timeout = conn.timeoutAsMillis()
                    udpSocket.soTimeout = if (timeout > 0) timeout.toInt().coerceAtMost(1000) else 1000
                }

                val len = try {
                    udpSocket.receivePacket(recvBuf)
                } catch (_: java.net.SocketTimeoutException) {
                    conn.onTimeout()
                    drainSend(conn, sendBuf, udpSocket)
                    continue
                }

                conn.recv(recvBuf, len, peer, local)
                drainSend(conn, sendBuf, udpSocket)

                // Poll H3 events
                while (true) {
                    val event = h3Conn.poll(quicConn = conn) ?: break

                    when (event.type) {
                        KicheH3EventType.Headers -> {
                            val (status, ktorHeaders) = parseResponseHeaders(event.headers ?: emptyList())
                            responseStatus = status
                            responseHeaders = ktorHeaders
                        }

                        KicheH3EventType.Data -> {
                            val bodyBuf = ByteArray(MAX_DATAGRAM_SIZE)
                            while (true) {
                                val n = try {
                                    h3Conn.recvBody(quicConn = conn, streamId = event.streamId, buf = bodyBuf)
                                } catch (_: KicheException) {
                                    break
                                }
                                if (n <= 0) break
                                responseBodyParts.add(bodyBuf.copyOf(n))
                            }
                        }

                        KicheH3EventType.Finished -> {
                            if (event.streamId == streamId) finished = true
                        }

                        KicheH3EventType.GoAway,
                        KicheH3EventType.Reset,
                        KicheH3EventType.PriorityUpdate -> { /* ignore for now */ }
                    }
                }
            }

            // Assemble response body
            val totalSize = responseBodyParts.sumOf { it.size }
            val responseBody = ByteArray(totalSize)
            var offset = 0
            for (part in responseBodyParts) {
                part.copyInto(responseBody, offset)
                offset += part.size
            }

            val channel = ByteReadChannel(responseBody)

            HttpResponseData(
                statusCode = responseStatus,
                requestTime = requestTime,
                headers = responseHeaders,
                version = HttpProtocolVersion("HTTP", 3, 0),
                body = channel,
                callContext = callContext,
            )
        } finally {
            h3Conn.close()
            h3Config.close()
        }
    }

    override fun close() {
        super.close()
        (requestsJob[Job] as CompletableJob).complete()
    }

    private companion object {
        const val MAX_DATAGRAM_SIZE = 65535
        const val DEFAULT_HTTPS_PORT = 443

        /** h3 ALPN token encoded as quiche expects: length-prefixed. */
        val H3_ALPN: ByteArray = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte())
    }
}

/**
 * Build H3 pseudo-headers + regular headers from a Ktor [HttpRequestData].
 */
private fun buildH3Headers(data: HttpRequestData): List<KicheH3Header> {
    val headers = mutableListOf<KicheH3Header>()

    // Pseudo-headers (must come first in HTTP/3)
    headers.add(KicheH3Header(":method", data.method.value))
    headers.add(KicheH3Header(":scheme", data.url.protocol.name))
    headers.add(KicheH3Header(":authority", data.url.hostWithPort))
    headers.add(KicheH3Header(":path", data.url.encodedPathAndQuery))

    // Regular headers
    data.headers.forEach { name, values ->
        for (value in values) {
            headers.add(KicheH3Header(name, value))
        }
    }

    // Content-Type / Content-Length from body
    data.body.contentType?.let {
        headers.add(KicheH3Header("content-type", it.toString()))
    }
    data.body.contentLength?.let {
        headers.add(KicheH3Header("content-length", it.toString()))
    }

    return headers
}

/**
 * Parse H3 response headers into a status code and Ktor [Headers].
 */
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

/**
 * Drain all pending QUIC packets from the connection and send them over the UDP socket.
 */
private fun drainSend(conn: KicheConnection, buf: ByteArray, socket: DatagramSocket) {
    while (true) {
        val sendResult = conn.send(buf, buf.size) ?: break
        val packet = java.net.DatagramPacket(
            buf, 0, sendResult.written,
            InetAddress.getByAddress(sendResult.to.ip),
            sendResult.to.port,
        )
        socket.send(packet)
    }
}

/**
 * Receive a single UDP packet, returning the number of bytes read.
 */
private fun DatagramSocket.receivePacket(buf: ByteArray): Int {
    val packet = java.net.DatagramPacket(buf, buf.size)
    receive(packet)
    return packet.length
}

