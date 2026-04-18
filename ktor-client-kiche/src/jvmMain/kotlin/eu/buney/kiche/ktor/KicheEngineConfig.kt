package eu.buney.kiche.ktor

import eu.buney.kiche.KicheCcAlgorithm
import io.ktor.client.engine.*

/**
 * Configuration for the [Kiche] client engine.
 */
public class KicheEngineConfig : HttpClientEngineConfig() {

    /**
     * Congestion control algorithm. Defaults to BBR2.
     */
    public var ccAlgorithm: KicheCcAlgorithm = KicheCcAlgorithm.Bbr2

    /**
     * Maximum idle timeout in milliseconds. 0 means no timeout.
     */
    public var maxIdleTimeoutMs: Long = 30_000L

    /**
     * Initial maximum data the peer can send (connection-level flow control).
     */
    public var initialMaxData: Long = 10_000_000L

    /**
     * Initial maximum data per bidirectional stream (local-initiated).
     */
    public var initialMaxStreamDataBidiLocal: Long = 1_000_000L

    /**
     * Initial maximum data per bidirectional stream (remote-initiated).
     */
    public var initialMaxStreamDataBidiRemote: Long = 1_000_000L

    /**
     * Initial maximum data per unidirectional stream.
     */
    public var initialMaxStreamDataUni: Long = 1_000_000L

    /**
     * Initial maximum number of bidirectional streams the peer can open.
     */
    public var initialMaxStreamsBidi: Long = 100L

    /**
     * Initial maximum number of unidirectional streams the peer can open.
     */
    public var initialMaxStreamsUni: Long = 100L

    /**
     * Whether to verify the server's TLS certificate. Defaults to true.
     */
    public var verifyPeer: Boolean = true

    /**
     * Path to a CA certificate file for server verification (PEM format).
     * If null, the default system trust store is used (not yet implemented — set this for now).
     */
    public var caCertPath: String? = null
}
