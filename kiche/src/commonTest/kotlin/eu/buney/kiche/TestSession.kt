/*
 * In-memory HTTP/3 session for testing.
 *
 * Ported from quiche's h3/mod.rs:testing::Session — wraps a TestPipe
 * with HTTP/3 client and server connections.
 */
package eu.buney.kiche

class TestSession(
    val pipe: TestPipe,
    val client: KicheH3Connection,
    val server: KicheH3Connection,
    private val clientH3Config: KicheH3Config,
    private val serverH3Config: KicheH3Config,
) : AutoCloseable {

    companion object {
        private val H3_PROTOS = byteArrayOf(0x02, 'h'.code.toByte(), '3'.code.toByte())

        val DEFAULT_REQUEST_HEADERS = listOf(
            KicheH3Header(":method", "GET"),
            KicheH3Header(":scheme", "https"),
            KicheH3Header(":authority", "quic.tech"),
            KicheH3Header(":path", "/test"),
            KicheH3Header("user-agent", "quiche-test"),
        )

        val DEFAULT_RESPONSE_HEADERS = listOf(
            KicheH3Header(":status", "200"),
            KicheH3Header("server", "quiche-test"),
        )

        val DEFAULT_BODY = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        /** Default H3 DATAGRAM payload: 10 bytes [1..10]. */
        val DEFAULT_DGRAM_DATA = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        fun new(): TestSession = newWithQuicConfig()

        /**
         * Creates a session with custom QUIC-level config. The [configure]
         * lambda is called on both client and server configs (server also
         * gets cert/key loaded). Defaults match quiche's Session::new().
         */
        fun newWithQuicConfig(
            configure: KicheConfig.() -> Unit = {
                setInitialMaxData(1500)
                setInitialMaxStreamDataBidiLocal(150)
                setInitialMaxStreamDataBidiRemote(150)
                setInitialMaxStreamDataUni(150)
                setInitialMaxStreamsBidi(5)
                setInitialMaxStreamsUni(5)
                enableDgram(true, 3, 3)
                setAckDelayExponent(8)
            },
        ): TestSession {
            val certDir = quicheCertDir()

            val serverQuicConfig = KicheConfig().apply {
                loadCertChainFromPemFile("$certDir/cert.crt")
                loadPrivKeyFromPemFile("$certDir/cert.key")
                setApplicationProtos(H3_PROTOS)
                verifyPeer(false)
                configure()
            }

            val clientQuicConfig = KicheConfig().apply {
                setApplicationProtos(H3_PROTOS)
                verifyPeer(false)
                configure()
            }

            val clientScid = ByteArray(16) { (it + 0xC0).toByte() }
            val serverScid = ByteArray(16) { (it + 0x50).toByte() }

            val quicClient = KicheConnection.connect(
                serverName = "quic.tech", scid = clientScid,
                local = TestPipe.CLIENT_ADDR, peer = TestPipe.SERVER_ADDR, config = clientQuicConfig,
            )
            val quicServer = KicheConnection.accept(
                scid = serverScid, odcid = null,
                local = TestPipe.SERVER_ADDR, peer = TestPipe.CLIENT_ADDR, config = serverQuicConfig,
            )

            val pipe = TestPipe(quicClient, quicServer, clientQuicConfig, serverQuicConfig)

            // QUIC handshake
            pipe.handshake()

            // Create H3 connections (opens control + QPACK streams automatically)
            val h3ClientConfig = KicheH3Config()
            val h3ServerConfig = KicheH3Config()
            val h3Client = KicheH3Connection(pipe.client, h3ClientConfig)
            val h3Server = KicheH3Connection(pipe.server, h3ServerConfig)

            // Exchange H3 setup frames
            pipe.advance()

            // Drain initial settings/QPACK events
            while (h3Client.poll(pipe.client) != null) { /* drain */ }
            while (h3Server.poll(pipe.server) != null) { /* drain */ }

            return TestSession(pipe, h3Client, h3Server, h3ClientConfig, h3ServerConfig)
        }
    }

    fun advance() = pipe.advance()

    fun pollClient(): KicheH3Event? = client.poll(pipe.client)
    fun pollServer(): KicheH3Event? = server.poll(pipe.server)

    fun sendRequest(fin: Boolean): Long {
        val stream = client.sendRequest(pipe.client, DEFAULT_REQUEST_HEADERS, fin)
        advance()
        return stream
    }

    fun sendResponse(streamId: Long, fin: Boolean) {
        server.sendResponse(pipe.server, streamId, DEFAULT_RESPONSE_HEADERS, fin)
        advance()
    }

    fun sendBodyClient(streamId: Long, fin: Boolean): ByteArray {
        client.sendBody(pipe.client, streamId, DEFAULT_BODY, fin)
        advance()
        return DEFAULT_BODY
    }

    fun sendBodyServer(streamId: Long, fin: Boolean): ByteArray {
        server.sendBody(pipe.server, streamId, DEFAULT_BODY, fin)
        advance()
        return DEFAULT_BODY
    }

    fun recvBodyClient(streamId: Long, buf: ByteArray): Int =
        client.recvBody(pipe.client, streamId, buf)

    fun recvBodyServer(streamId: Long, buf: ByteArray): Int =
        server.recvBody(pipe.server, streamId, buf)

    // --- H3 Datagram helpers ---
    // H3 datagrams are QUIC datagrams with a varint flow_id prefix.

    /** Sends an H3 datagram from client: varint(flowId) + DEFAULT_DGRAM_DATA. */
    fun sendDgramClient(flowId: Long) {
        pipe.client.dgramSend(encodeDgram(flowId), encodeDgram(flowId).size)
        advance()
    }

    /** Sends an H3 datagram from server: varint(flowId) + DEFAULT_DGRAM_DATA. */
    fun sendDgramServer(flowId: Long) {
        pipe.server.dgramSend(encodeDgram(flowId), encodeDgram(flowId).size)
        advance()
    }

    /** Receives an H3 datagram on client. Returns (totalLen, flowId, flowIdLen). */
    fun recvDgramClient(buf: ByteArray): Triple<Int, Long, Int> {
        val len = pipe.client.dgramRecv(buf, buf.size)
        val (flowId, flowIdLen) = decodeVarint(buf)
        return Triple(len, flowId, flowIdLen)
    }

    /** Receives an H3 datagram on server. Returns (totalLen, flowId, flowIdLen). */
    fun recvDgramServer(buf: ByteArray): Triple<Int, Long, Int> {
        val len = pipe.server.dgramRecv(buf, buf.size)
        val (flowId, flowIdLen) = decodeVarint(buf)
        return Triple(len, flowId, flowIdLen)
    }

    private fun encodeDgram(flowId: Long): ByteArray =
        QuicVarint.encode(flowId) + DEFAULT_DGRAM_DATA

    private fun decodeVarint(buf: ByteArray): Pair<Long, Int> =
        QuicVarint.decode(buf, 0) ?: error("empty buffer")

    override fun close() {
        client.close()
        server.close()
        clientH3Config.close()
        serverH3Config.close()
        pipe.close()
    }
}
