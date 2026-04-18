package eu.buney.kiche.ktor.h3adaptive

import io.ktor.client.engine.*

/**
 * A Ktor client engine factory that creates an [H3AdaptiveEngine].
 *
 * The adaptive engine routes requests between a user-supplied TCP engine
 * (HTTP/1.1, HTTP/2) and a user-supplied QUIC engine (HTTP/3), using
 * Alt-Svc discovery to determine when HTTP/3 is available.
 *
 * Usage:
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
public data object H3Adaptive : HttpClientEngineFactory<H3AdaptiveEngineConfig> {
    override fun create(block: H3AdaptiveEngineConfig.() -> Unit): HttpClientEngine =
        H3AdaptiveEngine(H3AdaptiveEngineConfig().apply(block))
}
