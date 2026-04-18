package eu.buney.kiche.ktor.h3adaptive

import io.ktor.client.engine.*

/**
 * Configuration for [H3AdaptiveEngine].
 *
 * Users must provide both a TCP engine (for HTTP/1.1 and HTTP/2) and a QUIC engine
 * (for HTTP/3). The adaptive engine routes requests between them based on Alt-Svc
 * discovery.
 *
 * ```kotlin
 * val client = HttpClient(H3Adaptive) {
 *     engine {
 *         tcp(CIO) {
 *             // CIO-specific config
 *         }
 *         quic(Kiche) {
 *             // Kiche-specific config
 *         }
 *     }
 * }
 * ```
 */
public class H3AdaptiveEngineConfig : HttpClientEngineConfig() {

    internal var tcpEngineCreator: (() -> HttpClientEngine)? = null
    internal var quicEngineCreator: (() -> HttpClientEngine)? = null

    /**
     * Configure the TCP engine used for HTTP/1.1 and HTTP/2 requests.
     *
     * @param factory The ktor engine factory (e.g. `CIO`, `OkHttp`, `Darwin`).
     * @param block Engine-specific configuration block.
     */
    public fun <T : HttpClientEngineConfig> tcp(
        factory: HttpClientEngineFactory<T>,
        block: T.() -> Unit = {},
    ) {
        tcpEngineCreator = { factory.create(block) }
    }

    /**
     * Configure the QUIC engine used for HTTP/3 requests.
     *
     * @param factory The ktor engine factory (e.g. `Kiche`).
     * @param block Engine-specific configuration block.
     */
    public fun <T : HttpClientEngineConfig> quic(
        factory: HttpClientEngineFactory<T>,
        block: T.() -> Unit = {},
    ) {
        quicEngineCreator = { factory.create(block) }
    }
}
