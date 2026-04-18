package eu.buney.kiche

expect class KicheConnection : AutoCloseable {
    companion object {
        fun connect(
            serverName: String,
            scid: ByteArray,
            local: KicheAddress,
            peer: KicheAddress,
            config: KicheConfig,
        ): KicheConnection

        fun accept(
            scid: ByteArray,
            odcid: ByteArray?,
            local: KicheAddress,
            peer: KicheAddress,
            config: KicheConfig,
        ): KicheConnection
    }

    //region Core I/O
    fun recv(buf: ByteArray, len: Int, from: KicheAddress, to: KicheAddress): Int
    fun send(buf: ByteArray, len: Int): KicheSendResult?
    fun sendAckEliciting(): Long
    //endregion

    //region Streams
    fun streamRecv(streamId: Long, buf: ByteArray, len: Int): KicheStreamRecvResult
    fun streamSend(streamId: Long, buf: ByteArray, len: Int, fin: Boolean): Int
    fun streamShutdown(streamId: Long, direction: KicheShutdown, err: Long)
    fun streamCapacity(streamId: Long): Int
    fun streamReadableNext(): Long
    fun streamWritableNext(): Long
    fun streamReadable(streamId: Long): Boolean
    fun streamWritable(streamId: Long, len: Int): Boolean
    fun streamFinished(streamId: Long): Boolean
    fun streamPriority(streamId: Long, urgency: Int, incremental: Boolean)
    //endregion

    //region Datagrams
    fun dgramSend(buf: ByteArray, len: Int): Int
    fun dgramRecv(buf: ByteArray, len: Int): Int
    fun dgramMaxWritableLen(): Long
    fun dgramRecvFrontLen(): Long
    fun dgramRecvQueueLen(): Long
    fun dgramRecvQueueByteSize(): Long
    fun dgramSendQueueLen(): Long
    fun dgramSendQueueByteSize(): Long
    fun isDgramSendQueueFull(): Boolean
    fun isDgramRecvQueueFull(): Boolean
    //endregion

    //region Timer
    fun timeoutAsMillis(): Long
    fun timeoutAsNanos(): Long
    fun onTimeout()
    //endregion

    //region Connection state
    val isEstablished: Boolean
    val isClosed: Boolean
    val isTimedOut: Boolean
    val isResumed: Boolean
    val isInEarlyData: Boolean
    val isReadable: Boolean
    val isDraining: Boolean
    val isServer: Boolean
    fun peerStreamsLeftBidi(): Long
    fun peerStreamsLeftUni(): Long
    fun maxSendUdpPayloadSize(): Long
    fun sendQuantum(): Long
    //endregion

    //region Close
    fun closeConnection(app: Boolean, err: Long, reason: ByteArray)
    //endregion

    //region Connection ID management
    fun retiredScids(): Long
    fun availableDcids(): Long
    fun scidsLeft(): Long
    fun activeScids(): Long
    fun newScid(scid: ByteArray, resetToken: ByteArray, retireIfNeeded: Boolean): Long
    fun retireDcid(dcidSeq: Long)
    fun retiredScidNext(): ByteArray?
    //endregion

    //region TLS / session
    fun setSession(session: ByteArray)
    fun session(): ByteArray?
    fun setMaxIdleTimeout(v: Long)
    fun setKeylogPath(path: String): Boolean
    //endregion

    //region Info
    fun applicationProto(): ByteArray?
    fun peerCert(): ByteArray?
    fun sourceId(): ByteArray?
    fun destinationId(): ByteArray?
    fun traceId(): ByteArray?
    fun serverName(): ByteArray?
    fun peerError(): KicheConnectionError?
    fun localError(): KicheConnectionError?
    fun stats(): KicheStats
    fun pathStats(idx: Long): KichePathStats?
    fun peerTransportParams(): KicheTransportParams?
    //endregion

    override fun close()
}
