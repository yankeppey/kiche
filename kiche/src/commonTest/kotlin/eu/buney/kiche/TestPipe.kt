/*
 * In-memory QUIC connection pair for testing.
 *
 * Ported from quiche's test_utils.rs:Pipe — creates a client and server
 * connection that exchange packets entirely in memory, with no network I/O.
 */
package eu.buney.kiche

/**
 * Returns the absolute path to quiche's example cert/key directory.
 * Platform-specific because working directory differs between JVM and iOS simulator.
 */
expect fun quicheCertDir(): String

class TestPipe(
    val client: KicheConnection,
    val server: KicheConnection,
    private val clientConfig: KicheConfig,
    private val serverConfig: KicheConfig,
) : AutoCloseable {

    companion object {
        val CLIENT_ADDR = KicheAddress(byteArrayOf(127, 0, 0, 1), 1234)
        val SERVER_ADDR = KicheAddress(byteArrayOf(127, 0, 0, 1), 4321)

        private val PROTOS = byteArrayOf(
            0x06, 'p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(),
            't'.code.toByte(), 'o'.code.toByte(), '1'.code.toByte()
        )

        /**
         * Creates a pipe matching quiche's test_utils::Pipe::default_config():
         * max_data=30, stream_bidi=15, stream_uni=10, max_streams=3.
         * Useful for tests that need to hit flow control / stream limits.
         */
        fun newWithSmallLimits(): TestPipe {
            val certDir = quicheCertDir()

            val serverConfig = KicheConfig().apply {
                loadCertChainFromPemFile("$certDir/cert.crt")
                loadPrivKeyFromPemFile("$certDir/cert.key")
                setApplicationProtos(PROTOS)
                setInitialMaxData(30)
                setInitialMaxStreamDataBidiLocal(15)
                setInitialMaxStreamDataBidiRemote(15)
                setInitialMaxStreamDataUni(10)
                setInitialMaxStreamsBidi(3)
                setInitialMaxStreamsUni(3)
                setMaxIdleTimeout(180_000)
                verifyPeer(false)
                setAckDelayExponent(8)
            }

            val clientConfig = KicheConfig().apply {
                setApplicationProtos(PROTOS)
                setInitialMaxData(30)
                setInitialMaxStreamDataBidiLocal(15)
                setInitialMaxStreamDataBidiRemote(15)
                setInitialMaxStreamDataUni(10)
                setInitialMaxStreamsBidi(3)
                setInitialMaxStreamsUni(3)
                setMaxIdleTimeout(180_000)
                verifyPeer(false)
                setAckDelayExponent(8)
            }

            val clientScid = ByteArray(16) { (it + 0xC0).toByte() }
            val serverScid = ByteArray(16) { (it + 0x50).toByte() }

            val client = KicheConnection.connect(
                serverName = "quic.tech", scid = clientScid,
                local = CLIENT_ADDR, peer = SERVER_ADDR, config = clientConfig,
            )
            val server = KicheConnection.accept(
                scid = serverScid, odcid = null,
                local = SERVER_ADDR, peer = CLIENT_ADDR, config = serverConfig,
            )

            return TestPipe(client, server, clientConfig, serverConfig)
        }

        fun new(
            dgramRecvQueueLen: Long = 100,
            dgramSendQueueLen: Long = 100,
        ): TestPipe {
            val certDir = quicheCertDir()

            val serverConfig = KicheConfig().apply {
                loadCertChainFromPemFile("$certDir/cert.crt")
                loadPrivKeyFromPemFile("$certDir/cert.key")
                setApplicationProtos(PROTOS)
                setInitialMaxData(10_000_000)
                setInitialMaxStreamDataBidiLocal(1_000_000)
                setInitialMaxStreamDataBidiRemote(1_000_000)
                setInitialMaxStreamDataUni(1_000_000)
                setInitialMaxStreamsBidi(100)
                setInitialMaxStreamsUni(100)
                setMaxIdleTimeout(180_000)
                verifyPeer(false)
                enableDgram(true, dgramRecvQueueLen, dgramSendQueueLen)
            }

            val clientConfig = KicheConfig().apply {
                setApplicationProtos(PROTOS)
                setInitialMaxData(10_000_000)
                setInitialMaxStreamDataBidiLocal(1_000_000)
                setInitialMaxStreamDataBidiRemote(1_000_000)
                setInitialMaxStreamDataUni(1_000_000)
                setInitialMaxStreamsBidi(100)
                setInitialMaxStreamsUni(100)
                setMaxIdleTimeout(180_000)
                verifyPeer(false)
                enableDgram(true, dgramRecvQueueLen, dgramSendQueueLen)
            }

            val clientScid = ByteArray(16) { (it + 0xC0).toByte() }
            val serverScid = ByteArray(16) { (it + 0x50).toByte() }

            val client = KicheConnection.connect(
                serverName = "quic.tech", scid = clientScid,
                local = CLIENT_ADDR, peer = SERVER_ADDR, config = clientConfig,
            )
            val server = KicheConnection.accept(
                scid = serverScid, odcid = null,
                local = SERVER_ADDR, peer = CLIENT_ADDR, config = serverConfig,
            )

            return TestPipe(client, server, clientConfig, serverConfig)
        }
    }

    private val buf = ByteArray(65535)

    /**
     * Runs the QUIC handshake to completion by exchanging packets between
     * client and server in memory.
     */
    fun handshake() {
        while (!client.isEstablished || !server.isEstablished) {
            val clientFlight = emitFlight(client)
            processFlight(server, clientFlight)

            val serverFlight = emitFlight(server)
            processFlight(client, serverFlight)
        }
    }

    /**
     * Exchanges packets in both directions until both sides are done sending.
     */
    fun advance() {
        var clientDone = false
        var serverDone = false

        while (!clientDone || !serverDone) {
            val cf = emitFlight(client)
            if (cf.isEmpty()) clientDone = true
            else processFlight(server, cf)

            val sf = emitFlight(server)
            if (sf.isEmpty()) serverDone = true
            else processFlight(client, sf)
        }
    }

    private fun emitFlight(conn: KicheConnection): List<Pair<ByteArray, KicheSendResult>> {
        val flight = mutableListOf<Pair<ByteArray, KicheSendResult>>()
        while (true) {
            val result = conn.send(buf, buf.size) ?: break
            val pkt = buf.copyOf(result.written)
            flight.add(pkt to result)
        }
        return flight
    }

    private fun processFlight(conn: KicheConnection, flight: List<Pair<ByteArray, KicheSendResult>>) {
        for ((pkt, si) in flight) {
            conn.recv(buf = pkt, len = pkt.size, from = si.from, to = si.to)
        }
    }

    override fun close() {
        client.close()
        server.close()
        clientConfig.close()
        serverConfig.close()
    }
}
