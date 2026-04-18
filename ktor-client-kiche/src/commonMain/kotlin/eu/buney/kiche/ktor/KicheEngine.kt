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
     * Body sending and response receiving happen in a single unified event loop so that
     * QUIC flow control ACKs (which open the send window) are processed between body chunks.
     * This supports streaming request bodies (ReadChannelContent, WriteChannelContent) in
     * addition to ByteArrayContent.
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
            // Build HTTP/3 request headers and resolve body channel
            val headers = buildH3Headers(data)
            val bodyChannel = data.body.toReadChannel(CoroutineScope(callContext))

            val streamId = h3Conn.sendRequest(
                quicConn = conn, headers = headers, fin = bodyChannel == null,
            )

            // Body sending state
            var bodyFinished = bodyChannel == null
            var pendingBody: ByteArray? = null
            var pendingOffset = 0

            // Response receiving state
            var responseHeaders: Headers = Headers.Empty
            var responseStatus = HttpStatusCode.OK
            val responseBodyParts = mutableListOf<ByteArray>()
            var responseFinished = false

            while (!responseFinished && !conn.isClosed) {
                // Try to push body data (non-blocking: only reads what's already buffered)
                if (!bodyFinished) {
                    val result = trySendBodyData(
                        h3Conn, conn, streamId, bodyChannel!!,
                        pendingBody, pendingOffset,
                    )
                    bodyFinished = result.finished
                    pendingBody = result.pending
                    pendingOffset = result.offset
                }
                drainSend(conn, sendBuf, udpSocket, peerSocketAddr)

                // Wait for incoming QUIC packet (or timeout for timer-driven events)
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
                            if (event.streamId == streamId) responseFinished = true
                        }

                        KicheH3EventType.GoAway,
                        KicheH3EventType.Reset,
                        KicheH3EventType.PriorityUpdate -> { /* ignore for now */ }
                    }
                }

                // After processing ACKs, flow control window may have opened — retry body send
                if (!bodyFinished) {
                    val result = trySendBodyData(
                        h3Conn, conn, streamId, bodyChannel!!,
                        pendingBody, pendingOffset,
                    )
                    bodyFinished = result.finished
                    pendingBody = result.pending
                    pendingOffset = result.offset
                    drainSend(conn, sendBuf, udpSocket, peerSocketAddr)
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

/**
 * Result of a non-blocking body send attempt, carrying the mutable state
 * back to the caller to avoid allocating a state holder object per request.
 */
private class BodySendResult(
    val finished: Boolean,
    val pending: ByteArray?,
    val offset: Int,
)

/**
 * Attempts to send request body data without suspending on the body channel.
 *
 * Reads from [bodyChannel] only if bytes are already buffered ([availableForRead] > 0),
 * then pushes as much as quiche will accept via [KicheH3Connection.sendBody].
 * Partial writes (flow control backpressure) are tracked via [pendingBody]/[pendingOffset].
 */
private suspend fun trySendBodyData(
    h3Conn: KicheH3Connection,
    conn: KicheConnection,
    streamId: Long,
    bodyChannel: ByteReadChannel,
    pendingBody: ByteArray?,
    pendingOffset: Int,
): BodySendResult {
    // If the writer coroutine failed, propagate immediately — don't send partial data.
    bodyChannel.closedCause?.let { throw it }

    var curPending = pendingBody
    var curOffset = pendingOffset

    // availableForRead can throw if the channel was closed with an error (e.g. writer exception).
    // The closedCause check above should catch this, but guard defensively.
    val available = try {
        bodyChannel.availableForRead
    } catch (_: Throwable) {
        0
    }

    // Fill pending buffer from channel if empty
    if (curPending == null) {
        if (available > 0) {
            val buf = ByteArray(MAX_BODY_CHUNK)
            val n = bodyChannel.readAvailable(buf, 0, buf.size)
            if (n > 0) {
                curPending = buf.copyOf(n)
                curOffset = 0
            }
        }

        // If still nothing pending and channel is done, send fin
        if (curPending == null && bodyChannel.isClosedForRead) {
            bodyChannel.closedCause?.let { throw it }
            try {
                h3Conn.sendBody(conn, streamId, ByteArray(0), fin = true)
            } catch (_: KicheException) {
                // Flow control blocked — retry next iteration
                return BodySendResult(finished = false, pending = null, offset = 0)
            }
            return BodySendResult(finished = true, pending = null, offset = 0)
        }
    }

    // Try to send pending data
    if (curPending != null) {
        val availableAfterSend = try {
            bodyChannel.availableForRead
        } catch (_: Throwable) {
            0
        }
        val isLast = bodyChannel.isClosedForRead && availableAfterSend == 0

        val chunk = if (curOffset == 0) curPending else curPending.copyOfRange(curOffset, curPending.size)

        val sent = try {
            h3Conn.sendBody(conn, streamId, chunk, fin = isLast)
        } catch (_: KicheException) {
            0
        }

        if (sent > 0) {
            curOffset += sent
            if (curOffset >= curPending.size) {
                curPending = null
                curOffset = 0
                if (isLast) return BodySendResult(finished = true, pending = null, offset = 0)
            }
        }
    }

    return BodySendResult(finished = false, pending = curPending, offset = curOffset)
}

private const val MAX_BODY_CHUNK = 65535

/**
 * Normalizes any [OutgoingContent] to a [ByteReadChannel], or null for no-body requests.
 * Mirrors CIO's `processOutgoingContent` but produces a read channel instead of writing
 * to a TCP stream, since quiche requires explicit [KicheH3Connection.sendBody] calls.
 */
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
