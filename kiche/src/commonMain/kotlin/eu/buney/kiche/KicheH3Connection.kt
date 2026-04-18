package eu.buney.kiche

expect class KicheH3Connection(quicConn: KicheConnection, config: KicheH3Config) : AutoCloseable {
    fun poll(quicConn: KicheConnection): KicheH3Event?
    fun sendRequest(quicConn: KicheConnection, headers: List<KicheH3Header>, fin: Boolean): Long
    fun sendBody(quicConn: KicheConnection, streamId: Long, body: ByteArray, fin: Boolean): Int
    fun recvBody(quicConn: KicheConnection, streamId: Long, buf: ByteArray): Int
    fun sendGoaway(quicConn: KicheConnection, id: Long)
    fun dgramEnabledByPeer(quicConn: KicheConnection): Boolean
    override fun close()
}
