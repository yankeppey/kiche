package eu.buney.kiche

actual class KicheConnection private constructor(private var handle: Long) : AutoCloseable {
    // Fields written by JNI for send() output
    @JvmField internal var sendFromIp: ByteArray? = null
    @JvmField internal var sendFromPort: Int = 0
    @JvmField internal var sendToIp: ByteArray? = null
    @JvmField internal var sendToPort: Int = 0

    // Fields written by JNI for streamRecv() and error outputs
    @JvmField internal var lastStreamRecvFin: Boolean = false
    @JvmField internal var lastErrorReason: ByteArray? = null

    // Fields written by JNI for pathStats() address data
    @JvmField internal var lastPathStatsLocalIp: ByteArray? = null
    @JvmField internal var lastPathStatsPeerIp: ByteArray? = null

    // Fields written by JNI for pathEventNext() address data
    @JvmField internal var pathEventLocalIp: ByteArray? = null
    @JvmField internal var pathEventPeerIp: ByteArray? = null
    @JvmField internal var pathEventOldLocalIp: ByteArray? = null
    @JvmField internal var pathEventOldPeerIp: ByteArray? = null

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

    actual fun send(buf: ByteArray, len: Int): KicheSendResult? {
        val result = nativeSend(requireOpen(), buf, len) ?: return null
        val written = result[0].toInt()
        val atNanos = result[1]
        return KicheSendResult(
            written = written,
            from = KicheAddress(sendFromIp!!, sendFromPort),
            to = KicheAddress(sendToIp!!, sendToPort),
            atNanos = atNanos,
        )
    }

    actual fun sendAckEliciting(): Long = nativeSendAckEliciting(requireOpen())

    //endregion

    //region Streams

    actual fun streamRecv(streamId: Long, buf: ByteArray, len: Int): KicheStreamRecvResult {
        val rc = nativeStreamRecv(requireOpen(), streamId, buf, len)
        if (rc < 0) KicheException.check(rc.toInt())
        return KicheStreamRecvResult(read = rc.toInt(), fin = lastStreamRecvFin)
    }

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

    actual fun pathEventNext(): KichePathEvent? {
        val data = nativePathEventNext(requireOpen()) ?: return null
        val type = data[0].toInt()
        return when (type) {
            4 -> KichePathEvent.ReusedSourceConnectionId(
                id = data[1],
                oldLocal = KicheAddress(pathEventOldLocalIp!!, data[2].toInt()),
                oldPeer = KicheAddress(pathEventOldPeerIp!!, data[3].toInt()),
                local = KicheAddress(pathEventLocalIp!!, data[4].toInt()),
                peer = KicheAddress(pathEventPeerIp!!, data[5].toInt()),
            )
            else -> {
                val local = KicheAddress(pathEventLocalIp!!, data[1].toInt())
                val peer = KicheAddress(pathEventPeerIp!!, data[2].toInt())
                when (type) {
                    0 -> KichePathEvent.New(local, peer)
                    1 -> KichePathEvent.Validated(local, peer)
                    2 -> KichePathEvent.FailedValidation(local, peer)
                    3 -> KichePathEvent.Closed(local, peer)
                    5 -> KichePathEvent.PeerMigrated(local, peer)
                    else -> error("Unknown path event type: $type")
                }
            }
        }
    }

    actual fun sendOnPath(buf: ByteArray, len: Int, from: KicheAddress?, to: KicheAddress?): KicheSendResult? {
        val result = nativeSendOnPath(requireOpen(), buf, len,
            from?.ip, from?.port ?: 0, from != null,
            to?.ip, to?.port ?: 0, to != null) ?: return null
        return KicheSendResult(
            written = result[0].toInt(),
            from = KicheAddress(sendFromIp!!, sendFromPort),
            to = KicheAddress(sendToIp!!, sendToPort),
            atNanos = result[1],
        )
    }

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

    actual fun peerError(): KicheConnectionError? {
        val arr = nativePeerError(requireOpen()) ?: return null
        return KicheConnectionError(arr[0] != 0L, arr[1], lastErrorReason ?: ByteArray(0))
    }

    actual fun localError(): KicheConnectionError? {
        val arr = nativeLocalError(requireOpen()) ?: return null
        return KicheConnectionError(arr[0] != 0L, arr[1], lastErrorReason ?: ByteArray(0))
    }

    actual fun stats(): KicheStats {
        val s = nativeStats(requireOpen())
        return KicheStats(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9],
            s[10], s[11], s[12], s[13], s[14], s[15], s[16])
    }

    actual fun peerTransportParams(): KicheTransportParams? {
        val tp = nativePeerTransportParams(requireOpen()) ?: return null
        return KicheTransportParams(tp[0], tp[1], tp[2], tp[3], tp[4], tp[5], tp[6], tp[7],
            tp[8], tp[9], tp[10] != 0L, tp[11], tp[12])
    }

    actual fun pathStats(idx: Long): KichePathStats? {
        val data = nativePathStats(requireOpen(), idx) ?: return null
        // data layout: [active, recv, sent, lost, retrans, rtt, minRtt, rttvar, cwnd,
        //               sentBytes, recvBytes, lostBytes, streamRetransBytes, pmtu, deliveryRate,
        //               fromIpLen, fromPort, toIpLen, toPort]
        val fromIpLen = data[15].toInt()
        val fromPort = data[16].toInt()
        val toIpLen = data[17].toInt()
        val toPort = data[18].toInt()
        return KichePathStats(
            localAddr = KicheAddress(lastPathStatsLocalIp!!, fromPort),
            peerAddr = KicheAddress(lastPathStatsPeerIp!!, toPort),
            active = data[0] != 0L,
            recv = data[1], sent = data[2], lost = data[3], retrans = data[4],
            rtt = data[5], minRtt = data[6], rttvar = data[7], cwnd = data[8],
            sentBytes = data[9], recvBytes = data[10], lostBytes = data[11],
            streamRetransBytes = data[12], pmtu = data[13], deliveryRate = data[14],
        )
    }

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
    private external fun nativeSend(handle: Long, buf: ByteArray, len: Int): LongArray?
    private external fun nativeStreamRecv(handle: Long, streamId: Long, buf: ByteArray, len: Int): Long
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
    private external fun nativePeerError(handle: Long): LongArray?
    private external fun nativeLocalError(handle: Long): LongArray?
    private external fun nativeStats(handle: Long): LongArray
    private external fun nativePeerTransportParams(handle: Long): LongArray?
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
    private external fun nativePathStats(handle: Long, idx: Long): LongArray?
    private external fun nativeProbePath(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Long
    private external fun nativeIsPathValidated(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Int
    private external fun nativeMigrateSource(handle: Long, localIp: ByteArray, localPort: Int): Long
    private external fun nativeMigrate(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Long
    private external fun nativePathEventNext(handle: Long): LongArray?
    private external fun nativeSendOnPath(handle: Long, buf: ByteArray, len: Int,
        fromIp: ByteArray?, fromPort: Int, hasFrom: Boolean,
        toIp: ByteArray?, toPort: Int, hasTo: Boolean): LongArray?
    private external fun nativeSendQuantumOnPath(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Long
    private external fun nativeSendAckElicitingOnPath(handle: Long, localIp: ByteArray, localPort: Int, peerIp: ByteArray, peerPort: Int): Int
    private external fun nativePathsIter(handle: Long, fromIp: ByteArray, fromPort: Int): Array<KicheAddress>?
    //endregion
}
