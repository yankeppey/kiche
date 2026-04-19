package eu.buney.kiche.ktor.webtransport

import eu.buney.kiche.KicheConnection
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implementation of [WebTransportDatagrams] backed by QUIC DATAGRAM frames (RFC 9221).
 *
 * The session event loop drives:
 * - Incoming: `conn.dgramRecv()` → [incoming] channel
 * - Outgoing: [outgoing] channel → `conn.dgramSend()`
 */
internal class KicheWebTransportDatagrams(
    override val incoming: ReceiveChannel<ByteArray>,
    override val outgoing: SendChannel<ByteArray>,
    private val conn: KicheConnection,
    private val mutex: Mutex,
) : WebTransportDatagrams {

    override val maxDatagramSize: Long
        get() = try {
            conn.dgramMaxWritableLen()
        } catch (_: Throwable) {
            0L
        }
}
