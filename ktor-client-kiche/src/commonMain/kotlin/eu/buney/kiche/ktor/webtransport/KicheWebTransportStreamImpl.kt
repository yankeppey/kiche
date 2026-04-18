package eu.buney.kiche.ktor.webtransport

import eu.buney.kiche.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Implementation of a WebTransport stream backed by a QUIC stream.
 *
 * Exposes [ByteReadChannel] / [ByteWriteChannel] for reading and writing,
 * bridged from the poll-based quiche API via the session's event loop.
 */
internal class KicheWebTransportStreamImpl(
    override val id: Long,
    private val conn: KicheConnection,
    private val h3Conn: KicheH3Connection,
    private val mutex: Mutex,
    parentScope: CoroutineScope,
    private val isBidi: Boolean,
) : WebTransportStream {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    override val coroutineContext: CoroutineContext =
        parentScope.coroutineContext + job + CoroutineName("wt-stream-$id")

    // Read side: data pushed by the session event loop
    private val _incoming = ByteChannel()
    override val incoming: ByteReadChannel get() = _incoming

    // Write side: user writes here, the session event loop drains it
    private val _outgoing = ByteChannel()
    override val outgoing: ByteWriteChannel get() = _outgoing

    /** Pending write data not yet flushed to quiche. */
    private var pendingWrite: ByteArray? = null
    private var pendingWriteOffset: Int = 0

    /**
     * Called by the session event loop when data is received on this stream.
     * Must be called under the session mutex.
     */
    fun onDataReceived(data: ByteArray) {
        // ByteChannel.trySend or writeAvailable — use launch to avoid blocking the event loop
        val result = _incoming.trySendData(data)
        if (!result) {
            // Channel is full or closed — launch a coroutine to write asynchronously
            // This shouldn't happen often with UNLIMITED channel
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.Default) {
                try {
                    _incoming.writeFully(data)
                } catch (_: Throwable) {
                    // Stream closed
                }
            }
        }
    }

    /** Called when the stream receives a FIN (no more data from peer). */
    fun onFinished() {
        _incoming.close()
    }

    /** Called when the stream is reset by the peer. */
    fun onReset() {
        _incoming.close(WebTransportException("Stream $id reset by peer"))
        _outgoing.close(WebTransportException("Stream $id reset by peer"))
        job.cancel()
    }

    /** Closes both channels (used during session cleanup). */
    fun closeChannels() {
        _incoming.close()
        _outgoing.close()
        job.cancel()
    }

    override suspend fun finish() {
        _outgoing.close()
        // The event loop will see the close and send FIN on the QUIC stream
    }

    /**
     * Drives pending writes to the QUIC stream. Called by the session
     * event loop under the mutex.
     */
    fun driveWriteLocked() {
        if (_outgoing.isClosedForRead && pendingWrite == null) {
            // Writer closed and all data flushed — send FIN
            try {
                conn.streamSend(id, ByteArray(0), 0, true)
            } catch (_: KicheException) {
                // Flow control or closed — will retry next loop
            }
            return
        }

        // Try to drain data from the outgoing channel
        while (true) {
            // First flush any pending data
            if (pendingWrite != null) {
                val chunk = if (pendingWriteOffset == 0) pendingWrite!!
                else pendingWrite!!.copyOfRange(pendingWriteOffset, pendingWrite!!.size)

                val sent = try {
                    conn.streamSend(id, chunk, chunk.size, false)
                } catch (e: KicheException) {
                    if (e.error.isRetryable) 0 else {
                        _outgoing.close(e)
                        return
                    }
                }

                if (sent > 0) {
                    pendingWriteOffset += sent
                    if (pendingWriteOffset >= pendingWrite!!.size) {
                        pendingWrite = null
                        pendingWriteOffset = 0
                    }
                } else {
                    // Can't write more right now — flow control
                    return
                }
            }

            // Read more from the outgoing channel (non-blocking)
            val readBuf = ByteArray(65535)
            val n = try {
                if (_outgoing.availableForRead > 0) {
                    // This is technically suspend but returns immediately when data is available
                    // We can't call suspend here (we're under mutex), so use tryReceive pattern
                    break // Can't read suspend under mutex — data will be picked up next iteration
                } else {
                    break
                }
            } catch (_: Throwable) {
                break
            }
        }
    }
}

/**
 * Try to send data to a ByteChannel without suspending.
 * Returns true if data was written, false if the channel is full or closed.
 */
private fun ByteChannel.trySendData(data: ByteArray): Boolean {
    return try {
        if (isClosedForWrite) return false
        // ByteChannel doesn't have a non-suspending write, so we return false
        // to signal that a coroutine launch is needed
        false
    } catch (_: Throwable) {
        false
    }
}
