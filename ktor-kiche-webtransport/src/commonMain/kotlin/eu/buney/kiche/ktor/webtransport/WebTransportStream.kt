package eu.buney.kiche.ktor.webtransport

import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope

/**
 * A receive-only (unidirectional) WebTransport stream opened by the peer.
 *
 * Data arrives reliably and in order within this stream.
 */
public interface WebTransportReceiveStream : CoroutineScope {

    /** The QUIC stream ID. */
    public val id: Long

    /**
     * Byte channel to read incoming data from.
     *
     * Closes when the peer finishes the stream (FIN) or resets it.
     */
    public val incoming: ByteReadChannel
}

/**
 * A send-only (unidirectional) WebTransport stream opened by the client.
 *
 * Data is delivered reliably and in order within this stream.
 */
public interface WebTransportSendStream : CoroutineScope {

    /** The QUIC stream ID. */
    public val id: Long

    /**
     * Byte channel to write outgoing data to.
     *
     * Close the channel (or call [finish]) to send a FIN, signalling
     * the end of the stream.
     */
    public val outgoing: ByteWriteChannel

    /**
     * Sends a FIN on this stream, indicating no more data will be written.
     *
     * Equivalent to closing [outgoing].
     */
    public suspend fun finish()
}

/**
 * A bidirectional WebTransport stream.
 *
 * Can be opened by either side. Combines both read and write capabilities.
 */
public interface WebTransportStream : WebTransportReceiveStream, WebTransportSendStream
