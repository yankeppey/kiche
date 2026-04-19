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

    actual fun poll(quicConn: KicheConnection): KicheH3Event? =
        nativePoll(requireOpen(), quicConn.getHandle())

    actual fun sendRequest(
        quicConn: KicheConnection,
        headers: List<KicheH3Header>,
        fin: Boolean
    ): Long {
        val names = Array(headers.size) { headers[it].name }
        val values = Array(headers.size) { headers[it].value }
        val rc = nativeSendRequest(requireOpen(), quicConn.getHandle(), names, values, fin)
        if (rc < 0) KicheH3Exception.check(rc.toInt())
        return rc
    }

    actual fun sendBody(quicConn: KicheConnection, streamId: Long, body: ByteArray, fin: Boolean): Int {
        val rc = nativeSendBody(requireOpen(), quicConn.getHandle(), streamId, body, fin)
        if (rc < 0) {
            KicheH3Exception.check(rc.toInt())
            return 0 // check() didn't throw (Done) → no bytes sent
        }
        return rc.toInt()
    }

    actual fun recvBody(quicConn: KicheConnection, streamId: Long, buf: ByteArray): Int {
        val rc = nativeRecvBody(requireOpen(), quicConn.getHandle(), streamId, buf)
        if (rc < 0) KicheH3Exception.check(rc.toInt())
        return rc.toInt()
    }

    actual fun sendResponse(quicConn: KicheConnection, streamId: Long, headers: List<KicheH3Header>, fin: Boolean) {
        val names = Array(headers.size) { headers[it].name }
        val values = Array(headers.size) { headers[it].value }
        KicheH3Exception.check(nativeSendResponse(requireOpen(), quicConn.getHandle(), streamId, names, values, fin))
    }

    actual fun sendGoaway(quicConn: KicheConnection, id: Long) {
        KicheH3Exception.check(nativeSendGoaway(requireOpen(), quicConn.getHandle(), id))
    }

    actual fun dgramEnabledByPeer(quicConn: KicheConnection): Boolean =
        nativeDgramEnabledByPeer(requireOpen(), quicConn.getHandle())

    actual fun extendedConnectEnabledByPeer(): Boolean =
        nativeExtendedConnectEnabledByPeer(requireOpen())

    actual fun stats(): KicheH3Stats = nativeStats(requireOpen())

    actual override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeFree(h)
        }
    }

    private external fun nativeNew(quicConnHandle: Long, configHandle: Long): Long
    private external fun nativeFree(handle: Long)
    private external fun nativePoll(handle: Long, quicConnHandle: Long): KicheH3Event?
    private external fun nativeSendRequest(handle: Long, quicConnHandle: Long,
        names: Array<ByteArray>, values: Array<ByteArray>, fin: Boolean): Long
    private external fun nativeSendBody(handle: Long, quicConnHandle: Long,
        streamId: Long, body: ByteArray, fin: Boolean): Long
    private external fun nativeRecvBody(handle: Long, quicConnHandle: Long,
        streamId: Long, buf: ByteArray): Long
    private external fun nativeSendGoaway(handle: Long, quicConnHandle: Long, id: Long): Int
    private external fun nativeDgramEnabledByPeer(handle: Long, quicConnHandle: Long): Boolean
    private external fun nativeExtendedConnectEnabledByPeer(handle: Long): Boolean
    private external fun nativeSendResponse(handle: Long, quicConnHandle: Long,
        streamId: Long, names: Array<ByteArray>, values: Array<ByteArray>, fin: Boolean): Int
    private external fun nativeStats(handle: Long): KicheH3Stats
}
