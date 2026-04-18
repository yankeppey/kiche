package eu.buney.kiche

actual class KicheH3Connection actual constructor(
    quicConn: KicheConnection,
    config: KicheH3Config
) : AutoCloseable {
    private var handle: Long

    init {
        KicheLoader.load()
        handle = nativeNew(quicConn.getHandle(), config.getHandle())
        if (handle == 0L) error("Failed to create HTTP/3 connection")
    }

    private fun requireOpen(): Long {
        val h = handle
        check(h != 0L) { "KicheH3Connection is closed" }
        return h
    }

    actual fun poll(quicConn: KicheConnection): KicheH3Event? {
        // Returns [streamId, eventType] or null if DONE
        val result = nativePoll(requireOpen(), quicConn.getHandle()) ?: return null
        val streamId = result[0]
        val eventType = KicheH3EventType.fromValue(result[1].toInt()) ?: return null
        // Headers are collected by nativePoll into lastHeaders field
        val headers = if (eventType == KicheH3EventType.Headers) lastHeaders else null
        return KicheH3Event(eventType, streamId, headers)
    }

    actual fun sendRequest(
        quicConn: KicheConnection,
        headers: List<KicheH3Header>,
        fin: Boolean
    ): Long {
        val names = Array(headers.size) { headers[it].name }
        val values = Array(headers.size) { headers[it].value }
        val rc = nativeSendRequest(requireOpen(), quicConn.getHandle(), names, values, fin)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc
    }

    actual fun sendBody(quicConn: KicheConnection, streamId: Long, body: ByteArray, fin: Boolean): Int {
        val rc = nativeSendBody(requireOpen(), quicConn.getHandle(), streamId, body, fin)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc.toInt()
    }

    actual fun recvBody(quicConn: KicheConnection, streamId: Long, buf: ByteArray): Int {
        val rc = nativeRecvBody(requireOpen(), quicConn.getHandle(), streamId, buf)
        if (rc < 0) KicheException.check(rc.toInt())
        return rc.toInt()
    }

    actual fun sendGoaway(quicConn: KicheConnection, id: Long) {
        KicheException.check(nativeSendGoaway(requireOpen(), quicConn.getHandle(), id))
    }

    actual fun dgramEnabledByPeer(quicConn: KicheConnection): Boolean =
        nativeDgramEnabledByPeer(requireOpen(), quicConn.getHandle())

    actual override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeFree(h)
        }
    }

    // Field written by JNI during poll() for header events
    @JvmField internal var lastHeaders: List<KicheH3Header>? = null

    private external fun nativeNew(quicConnHandle: Long, configHandle: Long): Long
    private external fun nativeFree(handle: Long)
    private external fun nativePoll(handle: Long, quicConnHandle: Long): LongArray?
    private external fun nativeSendRequest(handle: Long, quicConnHandle: Long,
        names: Array<ByteArray>, values: Array<ByteArray>, fin: Boolean): Long
    private external fun nativeSendBody(handle: Long, quicConnHandle: Long,
        streamId: Long, body: ByteArray, fin: Boolean): Long
    private external fun nativeRecvBody(handle: Long, quicConnHandle: Long,
        streamId: Long, buf: ByteArray): Long
    private external fun nativeSendGoaway(handle: Long, quicConnHandle: Long, id: Long): Int
    private external fun nativeDgramEnabledByPeer(handle: Long, quicConnHandle: Long): Boolean
}
