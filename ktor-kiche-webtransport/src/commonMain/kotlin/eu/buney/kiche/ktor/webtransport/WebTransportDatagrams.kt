package eu.buney.kiche.ktor.webtransport

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

/**
 * Unreliable datagram transport for a WebTransport session.
 *
 * Datagrams are sent via QUIC DATAGRAM frames (RFC 9221). They are:
 * - **Unreliable**: may be dropped by the network.
 * - **Unordered**: may arrive out of order.
 * - **Size-limited**: constrained by [maxDatagramSize].
 *
 * Usage:
 * ```kotlin
 * session.datagrams.outgoing.send("ping".encodeToByteArray())
 * val response = session.datagrams.incoming.receive()
 * ```
 */
public interface WebTransportDatagrams {

    /**
     * Channel of incoming datagrams from the peer.
     *
     * Each element is a single datagram payload (without QUIC framing).
     * The channel closes when the session ends.
     */
    public val incoming: ReceiveChannel<ByteArray>

    /**
     * Channel to send outgoing datagrams to the peer.
     *
     * Each element is a single datagram payload. Sending suspends if the
     * send queue is full. The datagram must not exceed [maxDatagramSize].
     */
    public val outgoing: SendChannel<ByteArray>

    /**
     * Maximum payload size (in bytes) for a single datagram on this session.
     *
     * Determined by the QUIC connection's path MTU and the peer's
     * `max_datagram_frame_size` transport parameter.
     */
    public val maxDatagramSize: Long
}
