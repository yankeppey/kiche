package eu.buney.kiche.ktor.server.webtransport

import eu.buney.kiche.*
import eu.buney.kiche.ktor.webtransport.*
import eu.buney.kiche.ktor.server.kicheLogStamp
import eu.buney.kiche.QuicVarint
import eu.buney.kiche.ktor.webtransport.capsule.*
import io.ktor.network.sockets.*
import io.ktor.server.application.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

private fun slog(msg: String) {
    println("[WT-SRV ${kicheLogStamp()}] $msg")
}

/**
 * Server-side WebTransport session implementation.
 *
 * Unlike the client session which owns its own QUIC connection and event loop,
 * the server session **shares** the connection and event loop with the
 * [KicheApplicationEngine]. The engine feeds events to this session via
 * [onStreamData], [onStreamFinished], [onDatagram], etc.
 */
internal class KicheWebTransportServerSession(
    override val call: ApplicationCall,
    private val sessionStreamId: Long,
    private val conn: KicheConnection,
    private val h3Conn: KicheH3Connection,
    private val mutex: Mutex,
    private val drainSend: suspend () -> Unit,
    parentContext: CoroutineContext,
) : WebTransportServerSession {

    private val job = SupervisorJob(parentContext[Job])
    override val coroutineContext: CoroutineContext =
        parentContext + job + CoroutineName("wt-server-session")

    override val ready: CompletableDeferred<Unit> = CompletableDeferred()
    private val _closed = CompletableDeferred<WebTransportCloseInfo?>()
    override val closed: Deferred<WebTransportCloseInfo?> get() = _closed

    // Incoming streams
    private val _incomingBidi = Channel<WebTransportStream>(Channel.UNLIMITED)
    private val _incomingUni = Channel<WebTransportReceiveStream>(Channel.UNLIMITED)
    override val incomingBidirectionalStreams: ReceiveChannel<WebTransportStream> get() = _incomingBidi
    override val incomingUnidirectionalStreams: ReceiveChannel<WebTransportReceiveStream> get() = _incomingUni

    // Datagrams
    private val _dgramIncoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val _dgramOutgoing = Channel<ByteArray>(Channel.UNLIMITED)
    override val datagrams: WebTransportDatagrams = object : WebTransportDatagrams {
        override val incoming: ReceiveChannel<ByteArray> get() = _dgramIncoming
        override val outgoing: SendChannel<ByteArray> get() = _dgramOutgoing
        override val maxDatagramSize: Long
            get() = try { conn.dgramMaxWritableLen() } catch (_: Throwable) { 0L }
    }

    /** Active streams keyed by QUIC stream ID — accessed from engine event loop under mutex. */
    internal val activeStreams = mutableMapOf<Long, ServerStreamState>()

    /**
     * Sends the 200 response on the CONNECT stream to accept the session.
     * Must be called under the engine mutex.
     */
    fun acceptSessionLocked() {
        val headers = listOf(
            KicheH3Header(":status", "200"),
        )
        h3Conn.sendResponse(conn, sessionStreamId, headers, fin = false)
        slog("acceptSession: sent 200 on stream $sessionStreamId")
        ready.complete(Unit)
    }

    override suspend fun createBidirectionalStream(): WebTransportStream {
        return mutex.withLock {
            val streamId = conn.streamWritableNext()
            val header = buildStreamHeader(bidi = true)
            conn.streamSend(streamId, header, header.size, false)
            drainSend()
            val state = ServerStreamState(streamId, isBidi = true)
            activeStreams[streamId] = state
            slog("createBidirectionalStream: $streamId")
            state.asStream(this)
        }
    }

    override suspend fun createUnidirectionalStream(): WebTransportSendStream {
        return mutex.withLock {
            val streamId = conn.streamWritableNext()
            val header = buildStreamHeader(bidi = false)
            conn.streamSend(streamId, header, header.size, false)
            drainSend()
            val state = ServerStreamState(streamId, isBidi = false)
            activeStreams[streamId] = state
            slog("createUnidirectionalStream: $streamId")
            state.asStream(this)
        }
    }

    override suspend fun close(info: WebTransportCloseInfo) {
        slog("close: code=${info.code}")
        mutex.withLock {
            try {
                // Send CLOSE_WEBTRANSPORT_SESSION capsule on the CONNECT stream
                val capsuleBytes = CloseWebTransportSession.encode(info)
                h3Conn.sendBody(conn, sessionStreamId, capsuleBytes, true)
                drainSend()
            } catch (_: Throwable) {
                // Best effort — connection may already be closed
            }
        }
        _closed.complete(info)
        cleanup()
    }

    //region Methods called by the engine event loop (under mutex)

    /** Called when the engine receives data on a QUIC stream belonging to this session. */
    fun onStreamData(streamId: Long, data: ByteArray) {
        val state = activeStreams[streamId]
        if (state != null) {
            state.incomingData.trySend(data)
        } else {
            // New client-initiated stream
            handleNewIncomingStream(streamId, data)
        }
    }

    /** Called when a stream receives FIN. */
    fun onStreamFinished(streamId: Long) {
        if (streamId == sessionStreamId) {
            slog("onStreamFinished: CONNECT stream → session ending")
            if (!_closed.isCompleted) _closed.complete(null)
            return
        }
        val state = activeStreams[streamId]
        state?.incomingData?.close()
    }

    /** Called when a stream is reset. */
    fun onStreamReset(streamId: Long) {
        if (streamId == sessionStreamId) {
            if (!_closed.isCompleted) _closed.complete(null)
            return
        }
        val state = activeStreams.remove(streamId)
        state?.incomingData?.close(WebTransportException("Stream $streamId reset"))
    }

    /** Called when a QUIC datagram is received. */
    fun onDatagram(data: ByteArray) {
        _dgramIncoming.trySend(data)
    }

    /** Drives outgoing datagrams and stream writes. Called by engine under mutex. */
    fun driveOutgoingLocked() {
        // Flush outgoing datagrams with Quarter Stream ID framing (RFC 9297)
        while (true) {
            val payload = _dgramOutgoing.tryReceive().getOrNull() ?: break
            try {
                val framed = HttpDatagram.encode(sessionStreamId, payload)
                conn.dgramSend(framed, framed.size)
            } catch (_: KicheException) {
                slog("driveOutgoing: dropped datagram (${payload.size} bytes)")
            }
        }

        // Flush outgoing stream writes
        for (state in activeStreams.values) {
            state.driveWriteLocked(conn, h3Conn)
        }
    }

    //endregion

    private fun handleNewIncomingStream(streamId: Long, initialData: ByteArray) {
        // Determine stream type from QUIC stream ID:
        // Client-initiated bidi: streamId % 4 == 0
        // Client-initiated uni:  streamId % 4 == 2
        val isClientBidi = (streamId % 4L) == 0L
        val isClientUni = (streamId % 4L) == 2L

        if (!isClientBidi && !isClientUni) return // Server-initiated — shouldn't arrive here

        val state = ServerStreamState(streamId, isBidi = isClientBidi)
        activeStreams[streamId] = state

        // Feed initial data (includes WT stream header which we skip for now)
        // TODO: Parse and strip WT stream header (signal byte + session ID varint)
        if (initialData.isNotEmpty()) {
            state.incomingData.trySend(initialData)
        }

        val stream = state.asStream(this)
        if (isClientBidi) {
            _incomingBidi.trySend(stream)
        } else {
            _incomingUni.trySend(stream)
        }
    }

    private fun cleanup() {
        _incomingBidi.close()
        _incomingUni.close()
        _dgramIncoming.close()
        _dgramOutgoing.close()
        for (state in activeStreams.values) {
            state.incomingData.close()
        }
        activeStreams.clear()
        job.cancel()
    }

    private fun buildStreamHeader(bidi: Boolean): ByteArray {
        val signal = if (bidi) 0x41.toByte() else 0x54.toByte()
        val sessionIdBytes = QuicVarint.encode(sessionStreamId)
        val result = ByteArray(1 + sessionIdBytes.size)
        result[0] = signal
        sessionIdBytes.copyInto(result, 1)
        return result
    }
}

/**
 * Per-stream state tracked by the server session.
 */
internal class ServerStreamState(
    val streamId: Long,
    val isBidi: Boolean,
) {
    /** Incoming data chunks pushed by the engine event loop. */
    val incomingData = Channel<ByteArray>(Channel.UNLIMITED)

    /** Outgoing data queued by the handler, drained by the event loop. */
    val outgoingData = Channel<ByteArray>(Channel.UNLIMITED)

    /** Set to true when the handler calls finish(). */
    @Volatile var finRequested: Boolean = false

    /** Set to true when the bridging coroutine has drained all data from the ByteWriteChannel. */
    @Volatile var bridgeDrained: Boolean = false

    /** Set to true once FIN has been sent on the QUIC stream. */
    @Volatile var finSent: Boolean = false

    /** Pending write data not yet flushed. */
    var pendingWrite: ByteArray? = null
    var pendingWriteOffset: Int = 0

    fun driveWriteLocked(conn: KicheConnection, h3Conn: KicheH3Connection) {
        // Flush pending first
        while (pendingWrite != null) {
            val chunk = if (pendingWriteOffset == 0) pendingWrite!!
            else pendingWrite!!.copyOfRange(pendingWriteOffset, pendingWrite!!.size)

            val sent = try {
                h3Conn.sendBody(conn, streamId, chunk, false)
            } catch (e: KicheH3Exception) {
                if (e.isRetryable) 0 else { incomingData.close(e); return }
            }

            if (sent > 0) {
                pendingWriteOffset += sent
                if (pendingWriteOffset >= pendingWrite!!.size) {
                    pendingWrite = null
                    pendingWriteOffset = 0
                }
            } else break
        }

        // Dequeue new data
        if (pendingWrite == null) {
            while (true) {
                val data = outgoingData.tryReceive().getOrNull() ?: break
                val sent = try {
                    h3Conn.sendBody(conn, streamId, data, false)
                } catch (e: KicheH3Exception) {
                    if (e.isRetryable) {
                        pendingWrite = data
                        pendingWriteOffset = 0
                        break
                    }
                    incomingData.close(e)
                    return
                }
                if (sent < data.size) {
                    pendingWrite = data
                    pendingWriteOffset = sent
                    break
                }
            }
        }

        // Send FIN if requested, all data flushed, and bridging coroutine is done
        @OptIn(ExperimentalCoroutinesApi::class)
        if (finRequested && !finSent && pendingWrite == null && outgoingData.isEmpty && bridgeDrained) {
            try {
                h3Conn.sendBody(conn, streamId, ByteArray(0), true)
                finSent = true
            } catch (e: KicheH3Exception) {
                if (!e.isRetryable) incomingData.close(e)
                // Retryable: will retry on next driveWriteLocked() call
            }
        }
    }

    fun asStream(session: KicheWebTransportServerSession): ServerWebTransportStream {
        return ServerWebTransportStream(this, session)
    }
}

/**
 * WebTransport stream backed by [ServerStreamState].
 *
 * Read and write operations go through channels that are driven by the engine event loop.
 */
internal class ServerWebTransportStream(
    private val state: ServerStreamState,
    parentScope: CoroutineScope,
) : WebTransportStream {

    override val id: Long get() = state.streamId

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    override val coroutineContext: CoroutineContext =
        parentScope.coroutineContext + job + CoroutineName("wt-srv-stream-${state.streamId}")

    // Read side — data is pushed by the engine via state.incomingData channel.
    // We bridge it to a ByteReadChannel for the user.
    private val _incoming: ByteChannel = ByteChannel()
    override val incoming: ByteReadChannel get() = _incoming

    // Write side — user writes here, data is queued and flushed by engine.
    private val _outgoing: ByteChannel = ByteChannel()
    override val outgoing: ByteWriteChannel get() = _outgoing

    init {
        // Bridge incoming data channel → ByteReadChannel
        launch {
            try {
                for (chunk in state.incomingData) {
                    _incoming.writeFully(chunk)
                    _incoming.flush()
                }
            } catch (_: Throwable) { }
            _incoming.close()
        }

        // Bridge ByteWriteChannel → outgoing data channel
        launch {
            val buf = ByteArray(65535)
            try {
                while (!_outgoing.isClosedForRead) {
                    val n = _outgoing.readAvailable(buf)
                    if (n > 0) {
                        state.outgoingData.send(buf.copyOf(n))
                    }
                }
            } catch (_: Throwable) { }
            // Bridging coroutine is done — all data from ByteWriteChannel has been queued
            state.bridgeDrained = true
            if (!state.finRequested) {
                state.finRequested = true
            }
        }
    }

    override suspend fun finish() {
        _outgoing.close()
        state.finRequested = true
    }
}

