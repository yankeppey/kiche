package eu.buney.kiche.ktor.webtransport

import io.ktor.client.*
import io.ktor.client.plugins.api.*
import io.ktor.util.*

/**
 * Configuration for the WebTransport plugin.
 */
public class WebTransportConfig {
    /**
     * Whether to enable HTTP/3 datagram support for WebTransport sessions.
     * When true, the QUIC connection advertises datagram capability to the peer.
     * Defaults to true.
     */
    public var datagramsEnabled: Boolean = true
}

/**
 * Ktor client plugin that enables WebTransport session creation.
 *
 * Installation:
 * ```kotlin
 * val client = HttpClient(Kiche) {
 *     install(WebTransport)
 * }
 * ```
 *
 * This plugin does not intercept normal HTTP requests. It stores configuration
 * and provides the [WebTransport] attribute key used by the engine to identify
 * WebTransport session requests.
 */
public val WebTransport: ClientPlugin<WebTransportConfig> = createClientPlugin(
    "WebTransport",
    ::WebTransportConfig,
) {
    // Store config for engine access
    client.attributes.put(WebTransportConfigKey, pluginConfig)
}

internal val WebTransportConfigKey: AttributeKey<WebTransportConfig> =
    AttributeKey("WebTransportConfig")
