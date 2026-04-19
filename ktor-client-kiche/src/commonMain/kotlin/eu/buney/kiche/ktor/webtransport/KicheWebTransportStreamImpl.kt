package eu.buney.kiche.ktor.webtransport

import eu.buney.kiche.KicheConnection
import eu.buney.kiche.KicheH3Connection
import eu.buney.kiche.KicheH3Exception
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/**
 * Implementation of a WebTransport stream backed by a QUIC stream.
 *
 * Bridges Ktor's [ByteReadChannel]/[ByteWriteChannel] to quiche's poll-based API
 * via intermediate [Channel]s that the session event loop can drain without suspending.
 *
 * Write path: user → [_outgoing] (ByteWriteChannel) → bridging coroutine → [outgoingData] (Channel) → event loop → quiche streamSend
 * Read path:  quiche streamRecv → event loop → [incomingData] (Channel) → bridging coroutine → [_incoming] (ByteReadChannel) → user
 */
internal class KicheWebTransportStreamImpl(
    override val id: Long,
    private val conn: KicheConnection,
    private val h3Conn: KicheH3Connection,
    parentScope: CoroutineScope,
    private val isBidi: Boolean,
) : WebTransportStream {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    override val coroutineContext: CoroutineContext =
        parentScope.coroutineContext + job + CoroutineName("wt-stream-$id")

    // Public read/write channels exposed to the user
    private val _incoming = ByteChannel()
    override val incoming: ByteReadChannel get() = _incoming

    private val _outgoing = ByteChannel()
    override val outgoing: ByteWriteChannel get() = _outgoing

    // Internal channels bridged by coroutines — drainable without suspending
    internal val incomingData = Channel<ByteArray>(Channel.UNLIMITED)
    internal val outgoingData = Channel<ByteArray>(Channel.UNLIMITED)

    /** Set to true when the user calls [finish] or closes [outgoing]. */
    @Volatile
    internal var finRequested: Boolean = false

    /** Set to true once FIN has been sent on the QUIC stream. */
    @Volatile
    internal var finSent: Boolean = false

    /** Pending write data not yet flushed to quiche. */
    internal var pendingWrite: ByteArray? = null
    internal var pendingWriteOffset: Int = 0

    init {
        // Bridge incomingData channel → ByteReadChannel
        launch {
            try {
                for (chunk in incomingData) {
                    _incoming.writeFully(chunk)
                    _incoming.flush()
                }
            } catch (_: Throwable) { }
            _incoming.close()
        }

        // Bridge ByteWriteChannel → outgoingData channel
        launch {
            val buf = ByteArray(65535)
            try {
                while (!_outgoing.isClosedForRead) {
                    val n = _outgoing.readAvailable(buf)
                    if (n > 0) {
                        outgoingData.send(buf.copyOf(n))
                    }
                }
            } catch (_: Throwable) { }
            // Writer closed — request FIN
            if (!finRequested) {
                finRequested = true
            }
        }
    }

    /**
     * Called by the session event loop when data is received on this stream.
     * Must be called under the session mutex.
     */
    fun onDataReceived(data: ByteArray) {
        incomingData.trySend(data)
    }

    /** Called when the stream receives a FIN (no more data from peer). */
    fun onFinished() {
        incomingData.close()
    }

    /** Called when the stream is reset by the peer. */
    fun onReset() {
        val cause = WebTransportException("Stream $id reset by peer")
        incomingData.close(cause)
        _incoming.close(cause)
        _outgoing.close(cause)
        job.cancel()
    }

    /** Closes both channels (used during session cleanup). */
    fun closeChannels() {
        incomingData.close()
        _incoming.close()
        _outgoing.close()
        job.cancel()
    }

    override suspend fun finish() {
        _outgoing.close()
        finRequested = true
    }

    /**
     * Drives pending writes to the QUIC stream. Called by the session
     * event loop under the mutex.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun driveWriteLocked() {
        // Flush pending data first
        while (pendingWrite != null) {
            val chunk = if (pendingWriteOffset == 0) pendingWrite!!
            else pendingWrite!!.copyOfRange(pendingWriteOffset, pendingWrite!!.size)

            val sent = try {
                h3Conn.sendBody(conn, id, chunk, false)
            } catch (e: KicheH3Exception) {
                if (e.isRetryable) 0 else return
            }

            if (sent > 0) {
                pendingWriteOffset += sent
                if (pendingWriteOffset >= pendingWrite!!.size) {
                    pendingWrite = null
                    pendingWriteOffset = 0
                }
            } else break
        }

        // Dequeue new data from the bridging channel (non-blocking)
        if (pendingWrite == null) {
            while (true) {
                val data = outgoingData.tryReceive().getOrNull() ?: break
                val sent = try {
                    h3Conn.sendBody(conn, id, data, false)
                } catch (e: KicheH3Exception) {
                    if (e.isRetryable) {
                        pendingWrite = data
                        pendingWriteOffset = 0
                        break
                    }
                    return
                }
                if (sent < data.size) {
                    pendingWrite = data
                    pendingWriteOffset = sent
                    break
                }
            }
        }

        // Send FIN if requested and all data flushed.
        // Also verify _outgoing is closed for read — this means the bridging
        // coroutine has finished reading all data and won't queue more.
        if (finRequested && !finSent && pendingWrite == null && outgoingData.isEmpty
            && _outgoing.isClosedForRead
        ) {
            try {
                h3Conn.sendBody(conn, id, ByteArray(0), true)
                finSent = true
            } catch (_: KicheH3Exception) { }
        }
    }
}
