package eu.buney.kiche.ktor

import io.ktor.client.engine.*

/**
 * A Ktor client engine backed by Cloudflare quiche (QUIC + HTTP/3).
 *
 * Usage:
 * ```kotlin
 * val client = HttpClient(Kiche) {
 *     engine {
 *         // KicheEngineConfig options
 *     }
 * }
 * ```
 */
public data object Kiche : HttpClientEngineFactory<KicheEngineConfig> {
    override fun create(block: KicheEngineConfig.() -> Unit): HttpClientEngine =
        KicheEngine(KicheEngineConfig().apply(block))
}
