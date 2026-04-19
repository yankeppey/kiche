package eu.buney.kiche.ktor.server.webtransport

import eu.buney.kiche.ktor.webtransport.*
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A server-side WebTransport session.
 *
 * Mirrors [WebTransportSession] but adds access to the originating [ApplicationCall],
 * following the same pattern as Ktor's `WebSocketServerSession`.
 *
 * Usage in routing:
 * ```kotlin
 * routing {
 *     webTransport("/wt") {
 *         // `this` is WebTransportServerSession
 *         val stream = incomingBidirectionalStreams.receive()
 *         val data = stream.incoming.readRemaining().readByteArray()
 *         stream.outgoing.writeFully(data)
 *         stream.finish()
 *     }
 * }
 * ```
 */
public interface WebTransportServerSession : CoroutineScope {

    /** The Ktor application call that initiated this session (the CONNECT request). */
    public val call: ApplicationCall

    /** Completes when the session is fully established. */
    public val ready: Deferred<Unit>

    /** Completes when the session closes. */
    public val closed: Deferred<WebTransportCloseInfo?>

    /** Open a new server-initiated bidirectional stream. */
    public suspend fun createBidirectionalStream(): WebTransportStream

    /** Open a new server-initiated unidirectional (send-only) stream. */
    public suspend fun createUnidirectionalStream(): WebTransportSendStream

    /** Bidirectional streams opened by the client. */
    public val incomingBidirectionalStreams: ReceiveChannel<WebTransportStream>

    /** Unidirectional (receive-only) streams opened by the client. */
    public val incomingUnidirectionalStreams: ReceiveChannel<WebTransportReceiveStream>

    /** Unreliable datagram transport. */
    public val datagrams: WebTransportDatagrams

    /** Close the session. */
    public suspend fun close(info: WebTransportCloseInfo = WebTransportCloseInfo(0u, ""))
}
