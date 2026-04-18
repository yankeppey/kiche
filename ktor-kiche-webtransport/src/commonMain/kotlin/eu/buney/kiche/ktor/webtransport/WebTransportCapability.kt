package eu.buney.kiche.ktor.webtransport

import io.ktor.client.engine.*

/**
 * Engine capability marker for WebTransport support.
 *
 * Engines that support WebTransport (HTTP/3 Extended CONNECT) should include
 * this in their [HttpClientEngine.supportedCapabilities] set.
 */
public data object WebTransportCapability : HttpClientEngineCapability<Unit>
