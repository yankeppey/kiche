package eu.buney.kiche

actual class KicheConnection private constructor(private var handle: Long) : AutoCloseable {
    init {
        KicheLoader.load()
    }

    private fun requireOpen(): Long {
        val h = handle
        check(h != 0L) { "KicheConnection is closed" }
        return h
    }

    actual companion object {
        @JvmStatic
        actual fun connect(
            serverName: String,
            scid: ByteArray,
            local: KicheAddress,
            peer: KicheAddress,
            config: KicheConfig,
        ): KicheConnection {
            KicheLoader.load()
            val h = nativeConnect(serverName, scid,
                local.ip, local.port, peer.ip, peer.port,
                config.getHandle())
            if (h == 0L) error("Failed to create QUIC connection")
            return KicheConnection(h)
        }

        @JvmStatic
        actual fun accept(
            scid: ByteArray,
            odcid: ByteArray?,
            local: KicheAddress,
            peer: KicheAddress,
            config: KicheConfig,
        ): KicheConnection {
            KicheLoader.load()
            val h = nativeAccept(scid, odcid,
                local.ip, local.port, peer.ip, peer.port,
                config.getHandle())
            if (h == 0L) error("Failed to accept QUIC connection")
            return KicheConnection(h)
        }

        @JvmStatic
        private external fun nativeConnect(
            serverName: String, scid: ByteArray,
            localIp: ByteArray, localPort: Int,
            peerIp: ByteArray, peerPort: Int,
            configHandle: Long
        ): Long

        @JvmStatic
        private external fun nativeAccept(
            scid: ByteArray, odcid: ByteArray?,
            localIp: ByteArray, localPort: Int,
            peerIp: ByteArray, peerPort: Int,
            configHandle: Long
        ): Long
    }

    //region Core I/O

    actual fun recv(buf: ByteArray, len: Int, from: KicheAddress, to: KicheAddress): Int {
        val rc = nativeRecv(requireOpen(), buf, len, from.ip, from.port, to.ip, to.port)
        if (rc < 0) KicheException.check(rc)
        return rc
    }

    actual fun send(buf: ByteArray, len: Int): KicheSendResult? =
        nativeSend(requireOpen(), buf, len)

    actual fun sendAckEliciting(): Long = nativeSendAckEliciting(requireOpen())

    //endregion

    //region Streams

    actual fun streamRecv(streamId: Long, buf: ByteArray, len: Int): KicheStreamRecvResult =
        nativeStreamRecv(requireOpen(), streamId, buf, len)

    actual fun streamSend(streamId: Long, buf: ByteArray, len: Int, fin: Boolean): Int {
        val rc = nativeStreamSend(requireOpen(), streamId, buf, len, fin)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc.toInt()
    }

    actual fun streamShutdown(streamId: Long, direction: KicheShutdown, err: Long) {
        KicheException.check(nativeStreamShutdown(requireOpen(), streamId, direction.value, err))
    }

    actual fun streamCapacity(streamId: Long): Int {
        val rc = nativeStreamCapacity(requireOpen(), streamId)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc.toInt()
    }

    actual fun streamReadableNext(): Long = nativeStreamReadableNext(requireOpen())
    actual fun streamWritableNext(): Long = nativeStreamWritableNext(requireOpen())
    actual fun streamReadable(streamId: Long): Boolean = nativeStreamReadable(requireOpen(), streamId)

    actual fun streamWritable(streamId: Long, len: Int): Boolean {
        val rc = nativeStreamWritable(requireOpen(), streamId, len)
        if (rc < 0) KicheException.check(rc)
        return rc == 1
    }

    actual fun streamFinished(streamId: Long): Boolean = nativeStreamFinished(requireOpen(), streamId)

    actual fun streamPriority(streamId: Long, urgency: Int, incremental: Boolean) {
        KicheException.check(nativeStreamPriority(requireOpen(), streamId, urgency, incremental))
    }

    //endregion

    //region Datagrams

    actual fun dgramSend(buf: ByteArray, len: Int): Int {
        val rc = nativeDgramSend(requireOpen(), buf, len)
        if (rc < 0) KicheException.checkStrict(rc.toInt())
        return rc.toInt()
    }

    actual fun dgramRecv(buf: ByteArray, len: Int): Int {
        val rc = nativeDgramRecv(requireOpen(), buf, len)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc.toInt()
    }

    actual fun dgramRecvFrontLen(): Long = nativeDgramRecvFrontLen(requireOpen())
    actual fun dgramMaxWritableLen(): Long = nativeDgramMaxWritableLen(requireOpen())
    actual fun dgramRecvQueueLen(): Long = nativeDgramRecvQueueLen(requireOpen())
    actual fun dgramRecvQueueByteSize(): Long = nativeDgramRecvQueueByteSize(requireOpen())
    actual fun dgramSendQueueLen(): Long = nativeDgramSendQueueLen(requireOpen())
    actual fun dgramSendQueueByteSize(): Long = nativeDgramSendQueueByteSize(requireOpen())
    actual fun isDgramSendQueueFull(): Boolean = nativeIsDgramSendQueueFull(requireOpen())
    actual fun isDgramRecvQueueFull(): Boolean = nativeIsDgramRecvQueueFull(requireOpen())

    //endregion

    //region Timer
    actual fun timeoutAsMillis(): Long = nativeTimeoutAsMillis(requireOpen())
    actual fun timeoutAsNanos(): Long = nativeTimeoutAsNanos(requireOpen())
    actual fun onTimeout() = nativeOnTimeout(requireOpen())

    //endregion

    //region State
    actual val isEstablished: Boolean get() = nativeIsEstablished(requireOpen())
    actual val isClosed: Boolean get() = nativeIsClosed(requireOpen())
    actual val isTimedOut: Boolean get() = nativeIsTimedOut(requireOpen())
    actual val isResumed: Boolean get() = nativeIsResumed(requireOpen())
    actual val isInEarlyData: Boolean get() = nativeIsInEarlyData(requireOpen())
    actual val isReadable: Boolean get() = nativeIsReadable(requireOpen())
    actual val isDraining: Boolean get() = nativeIsDraining(requireOpen())
    actual val isServer: Boolean get() = nativeIsServer(requireOpen())
    actual fun peerStreamsLeftBidi(): Long = nativePeerStreamsLeftBidi(requireOpen())
    actual fun peerStreamsLeftUni(): Long = nativePeerStreamsLeftUni(requireOpen())
    actual fun maxSendUdpPayloadSize(): Long = nativeMaxSendUdpPayloadSize(requireOpen())
    actual fun sendQuantum(): Long = nativeSendQuantum(requireOpen())

    //endregion

    //region Connection ID management
    actual fun retiredScids(): Long = nativeRetiredScids(requireOpen())
    actual fun availableDcids(): Long = nativeAvailableDcids(requireOpen())
    actual fun scidsLeft(): Long = nativeScidsLeft(requireOpen())
    actual fun activeScids(): Long = nativeActiveScids(requireOpen())

    actual fun newScid(scid: ByteArray, resetToken: ByteArray, retireIfNeeded: Boolean): Long =
        nativeNewScid(requireOpen(), scid, resetToken, retireIfNeeded)

    actual fun retireDcid(dcidSeq: Long) {
        KicheException.check(nativeRetireDcid(requireOpen(), dcidSeq))
    }

    actual fun retiredScidNext(): ByteArray? = nativeRetiredScidNext(requireOpen())
    //endregion

    //region Path migration & multi-path

    actual fun probePath(local: KicheAddress, peer: KicheAddress): Long {
        val rc = nativeProbePath(requireOpen(), local.ip, local.port, peer.ip, peer.port)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc
    }

    actual fun isPathValidated(local: KicheAddress, peer: KicheAddress): Boolean {
        val rc = nativeIsPathValidated(requireOpen(), local.ip, local.port, peer.ip, peer.port)
        if (rc < 0) KicheException.check(rc)
        return rc == 1
    }

    actual fun migrateSource(local: KicheAddress): Long {
        val rc = nativeMigrateSource(requireOpen(), local.ip, local.port)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc
    }

    actual fun migrate(local: KicheAddress, peer: KicheAddress): Long {
        val rc = nativeMigrate(requireOpen(), local.ip, local.port, peer.ip, peer.port)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc
    }

    actual fun pathEventNext(): KichePathEvent? =
        nativePathEventNext(requireOpen())

    actual fun sendOnPath(buf: ByteArray, len: Int, from: KicheAddress?, to: KicheAddress?): KicheSendResult? =
        nativeSendOnPath(requireOpen(), buf, len,
            from?.ip, from?.port ?: 0, from != null,
            to?.ip, to?.port ?: 0, to != null)

    actual fun sendQuantumOnPath(local: KicheAddress, peer: KicheAddress): Long =
        nativeSendQuantumOnPath(requireOpen(), local.ip, local.port, peer.ip, peer.port)

    actual fun sendAckElicitingOnPath(local: KicheAddress, peer: KicheAddress): Boolean {
        val rc = nativeSendAckElicitingOnPath(requireOpen(), local.ip, local.port, peer.ip, peer.port)
        if (rc < 0) KicheException.check(rc)
        return rc == 0
    }

    actual fun pathsIter(from: KicheAddress): List<KicheAddress> =
        nativePathsIter(requireOpen(), from.ip, from.port)?.toList() ?: emptyList()

    //endregion

    //region TLS / session
    actual fun setSession(session: ByteArray) {
        KicheException.check(nativeSetSession(requireOpen(), session))
    }

    actual fun session(): ByteArray? = nativeSession(requireOpen())

    actual fun setMaxIdleTimeout(v: Long) {
        KicheException.check(nativeSetMaxIdleTimeout(requireOpen(), v))
    }

    actual fun setKeylogPath(path: String): Boolean = nativeSetKeylogPath(requireOpen(), path)

    //endregion

    //region Close
    actual fun closeConnection(app: Boolean, err: Long, reason: ByteArray) {
        KicheException.checkStrict(nativeClose(requireOpen(), app, err, reason))
    }

    //endregion

    //region Info
    actual fun applicationProto(): ByteArray? = nativeApplicationProto(requireOpen())
    actual fun peerCert(): ByteArray? = nativePeerCert(requireOpen())
    actual fun sourceId(): ByteArray? = nativeSourceId(requireOpen())
    actual fun destinationId(): ByteArray? = nativeDestinationId(requireOpen())
    actual fun traceId(): ByteArray? = nativeTraceId(requireOpen())
    actual fun serverName(): ByteArray? = nativeServerName(requireOpen())

    actual fun peerError(): KicheConnectionError? =
        nativePeerError(requireOpen())

    actual fun localError(): KicheConnectionError? =
        nativeLocalError(requireOpen())

    actual fun stats(): KicheStats = nativeStats(requireOpen())

    actual fun peerTransportParams(): KicheTransportParams? =
        nativePeerTransportParams(requireOpen())

    actual fun pathStats(idx: Long): KichePathStats? =
        nativePathStats(requireOpen(), idx)

    actual override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeFree(h)
        }
    }

    // Expose handle for H3 to use
    internal fun getHandle(): Long = requireOpen()

    //endregion

    //region Native methods
    private external fun nativeFree(handle: Long)
    private external fun nativeRecv(handle: Long, buf: ByteArray, len: Int,
        fromIp: ByteArray, fromPort: Int, toIp: ByteArray, toPort: Int): Int
    private external fun nativeSend(handle: Long, buf: ByteArray, len: Int): KicheSendResult?
    private external fun nativeStreamRecv(handle: Long, streamId: Long, buf: ByteArray, len: Int): KicheStreamRecvResult
    private external fun nativeStreamSend(handle: Long, streamId: Long, buf: ByteArray, len: Int, fin: Boolean): Long
    private external fun nativeStreamShutdown(handle: Long, streamId: Long, direction: Int, err: Long): Int
    private external fun nativeStreamCapacity(handle: Long, streamId: Long): Long
    private external fun nativeStreamReadableNext(handle: Long): Long
    private external fun nativeStreamWritableNext(handle: Long): Long
    private external fun nativeStreamReadable(handle: Long, streamId: Long): Boolean
    private external fun nativeStreamWritable(handle: Long, streamId: Long, len: Int): Int
    private external fun nativeStreamFinished(handle: Long, streamId: Long): Boolean
    private external fun nativeDgramSend(handle: Long, buf: ByteArray, len: Int): Long
    private external fun nativeDgramRecv(handle: Long, buf: ByteArray, len: Int): Long
    private external fun nativeDgramMaxWritableLen(handle: Long): Long
    private external fun nativeDgramRecvQueueLen(handle: Long): Long
    private external fun nativeDgramSendQueueLen(handle: Long): Long
    private external fun nativeIsDgramSendQueueFull(handle: Long): Boolean
    private external fun nativeIsDgramRecvQueueFull(handle: Long): Boolean
    private external fun nativeTimeoutAsMillis(handle: Long): Long
    private external fun nativeTimeoutAsNanos(handle: Long): Long
    private external fun nativeOnTimeout(handle: Long)
    private external fun nativeIsEstablished(handle: Long): Boolean
    private external fun nativeIsClosed(handle: Long): Boolean
    private external fun nativeIsTimedOut(handle: Long): Boolean
    private external fun nativeIsResumed(handle: Long): Boolean
    private external fun nativeIsInEarlyData(handle: Long): Boolean
    private external fun nativeIsReadable(handle: Long): Boolean
    private external fun nativeIsDraining(handle: Long): Boolean
    private external fun nativeIsServer(handle: Long): Boolean
    private external fun nativePeerStreamsLeftBidi(handle: Long): Long
    private external fun nativePeerStreamsLeftUni(handle: Long): Long
    private external fun nativeMaxSendUdpPayloadSize(handle: Long): Long
    private external fun nativeSendQuantum(handle: Long): Long
    private external fun nativeClose(handle: Long, app: Boolean, err: Long, reason: ByteArray): Int
    private external fun nativeApplicationProto(handle: Long): ByteArray?
    private external fun nativePeerCert(handle: Long): ByteArray?
    private external fun nativeSourceId(handle: Long): ByteArray?
    private external fun nativeDestinationId(handle: Long): ByteArray?
    private external fun nativePeerError(handle: Long): KicheConnectionError?
    private external fun nativeLocalError(handle: Long): KicheConnectionError?
    private external fun nativeStats(handle: Long): KicheStats
    private external fun nativePeerTransportParams(handle: Long): KicheTransportParams?
    private external fun nativeSendAckEliciting(handle: Long): Long
    private external fun nativeStreamPriority(handle: Long, streamId: Long, urgency: Int, incremental: Boolean): Int
    private external fun nativeDgramRecvFrontLen(handle: Long): Long
    private external fun nativeDgramRecvQueueByteSize(handle: Long): Long
    private external fun nativeDgramSendQueueByteSize(handle: Long): Long
    private external fun nativeRetiredScids(handle: Long): Long
    private external fun nativeAvailableDcids(handle: Long): Long
    private external fun nativeScidsLeft(handle: Long): Long
    private external fun nativeActiveScids(handle: Long): Long
    private external fun nativeNewScid(handle: Long, scid: ByteArray, resetToken: ByteArray, retireIfNeeded: Boolean): Long
    private external fun nativeRetireDcid(handle: Long, dcidSeq: Long): Int
    private external fun nativeRetiredScidNext(handle: Long): ByteArray?
    private external fun nativeSetSession(handle: Long, session: ByteArray): Int
    private external fun nativeSession(handle: Long): ByteArray?
    private external fun nativeSetMaxIdleTimeout(handle: Long, v: Long): Int
    private external fun nativeSetKeylogPath(handle: Long, path: String): Boolean
    private external fun nativeTraceId(handle: Long): ByteArray?
    private external fun nativeServerName(handle: Long): ByteArray?
    private external fun nativePathStats(handle: Long, idx: Long): KichePathStats?
    private external fun nativeProbePath(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Long
    private external fun nativeIsPathValidated(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Int
    private external fun nativeMigrateSource(handle: Long, localIp: ByteArray, localPort: Int): Long
    private external fun nativeMigrate(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Long
    private external fun nativePathEventNext(handle: Long): KichePathEvent?
    private external fun nativeSendOnPath(handle: Long, buf: ByteArray, len: Int,
        fromIp: ByteArray?, fromPort: Int, hasFrom: Boolean,
        toIp: ByteArray?, toPort: Int, hasTo: Boolean): KicheSendResult?
    private external fun nativeSendQuantumOnPath(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Long
    private external fun nativeSendAckElicitingOnPath(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Int
    private external fun nativePathsIter(handle: Long, fromIp: ByteArray, fromPort: Int): Array<KicheAddress>?
    //endregion
}
