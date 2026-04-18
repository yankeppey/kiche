package eu.buney.kiche.ktor.webtransport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A WebTransport session over HTTP/3.
 *
 * Established via an HTTP/3 Extended CONNECT request with `:protocol = webtransport`.
 * Provides multiplexed bidirectional and unidirectional QUIC streams, plus unreliable
 * datagrams — all scoped to this session.
 *
 * Usage:
 * ```kotlin
 * client.webTransport("https://example.com/wt") {
 *     val stream = createBidirectionalStream()
 *     stream.outgoing.writeStringUtf8("hello")
 *     val response = stream.incoming.readUTF8Line()
 *
 *     datagrams.outgoing.send("ping".encodeToByteArray())
 * }
 * ```
 *
 * The session is a [CoroutineScope] — cancelling it closes the underlying QUIC
 * connection and all associated streams.
 */
public interface WebTransportSession : CoroutineScope {

    /**
     * Completes when the WebTransport session is fully established
     * (Extended CONNECT succeeded and peer accepted the session).
     */
    public val ready: Deferred<Unit>

    /**
     * Completes when the session closes, carrying the close info if available.
     */
    public val closed: Deferred<WebTransportCloseInfo?>

    /**
     * Opens a new client-initiated bidirectional stream.
     *
     * Suspends if the peer's max stream limit has been reached, resuming
     * when the peer grants additional stream credits.
     */
    public suspend fun createBidirectionalStream(): WebTransportStream

    /**
     * Opens a new client-initiated unidirectional (send-only) stream.
     *
     * Suspends if the peer's max stream limit has been reached.
     */
    public suspend fun createUnidirectionalStream(): WebTransportSendStream

    /**
     * Bidirectional streams opened by the peer, delivered in order of creation.
     *
     * The channel closes when the session ends.
     */
    public val incomingBidirectionalStreams: ReceiveChannel<WebTransportStream>

    /**
     * Unidirectional (receive-only) streams opened by the peer.
     *
     * The channel closes when the session ends.
     */
    public val incomingUnidirectionalStreams: ReceiveChannel<WebTransportReceiveStream>

    /**
     * Unreliable datagram transport for this session.
     */
    public val datagrams: WebTransportDatagrams

    /**
     * Closes the session gracefully with an optional close info.
     *
     * All open streams are reset and the underlying QUIC connection is drained.
     */
    public suspend fun close(info: WebTransportCloseInfo = WebTransportCloseInfo(0u, ""))
}
