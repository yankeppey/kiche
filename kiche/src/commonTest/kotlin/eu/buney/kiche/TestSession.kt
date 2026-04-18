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
    private val clientConfig: KicheH3Config,
    private val serverConfig: KicheH3Config,
) : AutoCloseable {

    companion object {
        private val H3_PROTOS = byteArrayOf(0x02, 'h'.code.toByte(), '3'.code.toByte())

        /**
         * Default request headers matching quiche's Session::send_request().
         */
        val DEFAULT_REQUEST_HEADERS = listOf(
            KicheH3Header(":method", "GET"),
            KicheH3Header(":scheme", "https"),
            KicheH3Header(":authority", "quic.tech"),
            KicheH3Header(":path", "/test"),
            KicheH3Header("user-agent", "quiche-test"),
        )

        /**
         * Default response headers matching quiche's Session::send_response().
         */
        val DEFAULT_RESPONSE_HEADERS = listOf(
            KicheH3Header(":status", "200"),
            KicheH3Header("server", "quiche-test"),
        )

        /**
         * Default body payload matching quiche's Session::send_body_client/server().
         */
        val DEFAULT_BODY = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        fun new(): TestSession {
            val certDir = quicheCertDir()

            val quicConfig = KicheConfig().apply {
                loadCertChainFromPemFile("$certDir/cert.crt")
                loadPrivKeyFromPemFile("$certDir/cert.key")
                setApplicationProtos(H3_PROTOS)
                setInitialMaxData(1500)
                setInitialMaxStreamDataBidiLocal(150)
                setInitialMaxStreamDataBidiRemote(150)
                setInitialMaxStreamDataUni(150)
                setInitialMaxStreamsBidi(5)
                setInitialMaxStreamsUni(5)
                verifyPeer(false)
                enableDgram(true, 3, 3)
                setAckDelayExponent(8)
            }

            val clientScid = ByteArray(16) { (it + 0xC0).toByte() }
            val serverScid = ByteArray(16) { (it + 0x50).toByte() }

            val quicClient = KicheConnection.connect(
                "quic.tech", clientScid, TestPipe.CLIENT_ADDR, TestPipe.SERVER_ADDR, quicConfig
            )
            val quicServer = KicheConnection.accept(
                serverScid, null, TestPipe.SERVER_ADDR, TestPipe.CLIENT_ADDR, quicConfig
            )

            val pipe = TestPipe(quicClient, quicServer, quicConfig, quicConfig)

            // QUIC handshake
            pipe.handshake()

            // Create H3 connections (this opens control + QPACK streams automatically)
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

    /**
     * Sends a request from client with default headers.
     * Returns the stream ID.
     */
    fun sendRequest(fin: Boolean): Long {
        val stream = client.sendRequest(pipe.client, DEFAULT_REQUEST_HEADERS, fin)
        advance()
        return stream
    }

    /**
     * Sends a response from server with default headers.
     */
    fun sendResponse(streamId: Long, fin: Boolean) {
        server.sendResponse(pipe.server, streamId, DEFAULT_RESPONSE_HEADERS, fin)
        advance()
    }

    /**
     * Sends default body from client.
     */
    fun sendBodyClient(streamId: Long, fin: Boolean): ByteArray {
        client.sendBody(pipe.client, streamId, DEFAULT_BODY, fin)
        advance()
        return DEFAULT_BODY
    }

    /**
     * Sends default body from server.
     */
    fun sendBodyServer(streamId: Long, fin: Boolean): ByteArray {
        server.sendBody(pipe.server, streamId, DEFAULT_BODY, fin)
        advance()
        return DEFAULT_BODY
    }

    /**
     * Receives body on client side.
     */
    fun recvBodyClient(streamId: Long, buf: ByteArray): Int =
        client.recvBody(pipe.client, streamId, buf)

    /**
     * Receives body on server side.
     */
    fun recvBodyServer(streamId: Long, buf: ByteArray): Int =
        server.recvBody(pipe.server, streamId, buf)

    override fun close() {
        client.close()
        server.close()
        clientConfig.close()
        serverConfig.close()
        pipe.close()
    }
}
