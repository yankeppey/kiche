package eu.buney.kiche.ktor

import eu.buney.kiche.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.io.*
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

@OptIn(InternalAPI::class)
public class KicheEngine(override val config: KicheEngineConfig) : HttpClientEngineBase("ktor-kiche") {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
        setOf(HttpTimeoutCapability)

    private val requestsJob: CoroutineContext

    override val coroutineContext: CoroutineContext

    private val selectorManager = SelectorManager(Dispatchers.Default)

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

        // Resolve peer address via ktor-network (handles DNS + platform differences)
        val peerSocketAddr = InetSocketAddress(host, port)
        val peerIp = peerSocketAddr.resolveAddress()
            ?: error("Failed to resolve address for $host")
        val peer = KicheAddress(ip = peerIp, port = port)

        // Bind a local UDP socket
        val udpSocket = aSocket(selectorManager).udp().bind(InetSocketAddress("0.0.0.0", 0))
        val localSocketAddr = udpSocket.localAddress as InetSocketAddress
        val local = KicheAddress(
            ip = localSocketAddr.resolveAddress() ?: byteArrayOf(0, 0, 0, 0),
            port = localSocketAddr.port,
        )

        val scid = Random.nextBytes(16)

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
                return executeH3Request(
                    conn, udpSocket, peerSocketAddr, local, peer, data, callContext, requestTime,
                )
            } finally {
                conn.close()
            }
        } finally {
            quicConfig.close()
            udpSocket.close()
        }
    }

    /**
     * Drives the QUIC handshake, sends an H3 request, and polls for the response.
     *
     * All quiche calls within a single request are sequential (receive → process → send loop),
     * so no additional synchronization is needed — each request owns its own connection.
     */
    private suspend fun executeH3Request(
        conn: KicheConnection,
        udpSocket: BoundDatagramSocket,
        peerSocketAddr: InetSocketAddress,
        local: KicheAddress,
        peer: KicheAddress,
        data: HttpRequestData,
        callContext: CoroutineContext,
        requestTime: GMTDate,
    ): HttpResponseData {
        val sendBuf = ByteArray(MAX_DATAGRAM_SIZE)

        // Drive initial QUIC handshake packets
        drainSend(conn, sendBuf, udpSocket, peerSocketAddr)

        // Handshake loop
        while (!conn.isEstablished) {
            val dgram = udpSocket.receive()
            val bytes = dgram.packet.readByteArray()
            conn.recv(buf = bytes, len = bytes.size, from = peer, to = local)
            drainSend(conn, sendBuf, udpSocket, peerSocketAddr)
        }

        // Create H3 connection
        val h3Config = KicheH3Config()
        val h3Conn = KicheH3Connection(conn, h3Config)

        try {
            // Build HTTP/3 request headers and resolve body bytes
            val headers = buildH3Headers(data)
            val bodyBytes = when (val body = data.body) {
                is OutgoingContent.NoContent -> null
                is OutgoingContent.ByteArrayContent -> body.bytes().takeIf { it.isNotEmpty() }
                else -> error("Unsupported request body type: ${body::class.simpleName}. Only ByteArrayContent is supported.")
            }

            val streamId = h3Conn.sendRequest(quicConn = conn, headers = headers, fin = bodyBytes == null)

            if (bodyBytes != null) {
                h3Conn.sendBody(quicConn = conn, streamId = streamId, body = bodyBytes, fin = true)
            }

            drainSend(conn, sendBuf, udpSocket, peerSocketAddr)

            // Poll for response
            var responseHeaders: Headers = Headers.Empty
            var responseStatus = HttpStatusCode.OK
            val responseBodyParts = mutableListOf<ByteArray>()
            var finished = false

            while (!finished && !conn.isClosed) {
                val dgram = withTimeoutOrNull(
                    conn.timeoutAsMillis().coerceIn(1, 1000)
                ) {
                    udpSocket.receive()
                }

                if (dgram == null) {
                    conn.onTimeout()
                    drainSend(conn, sendBuf, udpSocket, peerSocketAddr)
                    continue
                }

                val bytes = dgram.packet.readByteArray()
                conn.recv(buf = bytes, len = bytes.size, from = peer, to = local)
                drainSend(conn, sendBuf, udpSocket, peerSocketAddr)

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

            return HttpResponseData(
                statusCode = responseStatus,
                requestTime = requestTime,
                headers = responseHeaders,
                version = HttpProtocolVersion("HTTP", 3, 0),
                body = ByteReadChannel(responseBody),
                callContext = callContext,
            )
        } finally {
            h3Conn.close()
            h3Config.close()
        }
    }

    override fun close() {
        super.close()
        selectorManager.close()
        (requestsJob[Job] as CompletableJob).complete()
    }

    private companion object {
        const val MAX_DATAGRAM_SIZE = 65535
        const val DEFAULT_HTTPS_PORT = 443

        /** h3 ALPN token encoded as quiche expects: length-prefixed. */
        val H3_ALPN: ByteArray = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte())
    }
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

private suspend fun drainSend(
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
