package eu.buney.kiche.ktor.webtransport

import io.ktor.client.*
import io.ktor.http.*

/**
 * Opens a WebTransport session and executes [block] within it.
 *
 * The session is closed automatically when [block] returns or throws.
 *
 * ```kotlin
 * client.webTransport("https://example.com:4433/wt") {
 *     val bidi = createBidirectionalStream()
 *     bidi.outgoing.writeStringUtf8("hello")
 * }
 * ```
 *
 * @param urlString The HTTPS URL for the WebTransport endpoint.
 * @param block Suspend lambda executed within the session scope.
 */
public suspend fun HttpClient.webTransport(
    urlString: String,
    block: suspend WebTransportSession.() -> Unit,
) {
    val session = webTransportSession(urlString)
    try {
        session.ready.await()
        session.block()
    } finally {
        session.close()
    }
}

/**
 * Opens a WebTransport session and executes [block] within it.
 *
 * @param url The URL for the WebTransport endpoint.
 * @param block Suspend lambda executed within the session scope.
 */
public suspend fun HttpClient.webTransport(
    url: Url,
    block: suspend WebTransportSession.() -> Unit,
) {
    webTransport(url.toString(), block)
}

/**
 * Opens a WebTransport session and returns it for manual lifecycle management.
 *
 * The caller is responsible for closing the session when done.
 *
 * ```kotlin
 * val session = client.webTransportSession("https://example.com:4433/wt")
 * session.ready.await()
 * // ... use session ...
 * session.close()
 * ```
 *
 * @param urlString The HTTPS URL for the WebTransport endpoint.
 * @return A [WebTransportSession] that is connecting or already connected.
 */
public suspend fun HttpClient.webTransportSession(
    urlString: String,
): WebTransportSession {
    val url = Url(urlString)
    require(url.protocol == URLProtocol.HTTPS || url.protocol.name == "h3") {
        "WebTransport requires HTTPS (got ${url.protocol.name})"
    }

    val engine = engineConfig()
    return engine.openWebTransportSession(url)
}

/**
 * Extension point for engines to implement WebTransport session creation.
 *
 * This is internal — engines provide the implementation via [KicheWebTransportEngine].
 */
internal fun HttpClient.engineConfig(): KicheWebTransportEngine {
    val engine = engine
    require(engine is KicheWebTransportEngine) {
        "WebTransport is only supported by the Kiche engine. Current engine: ${engine::class.simpleName}"
    }
    return engine
}

/**
 * Internal interface that the Kiche engine implements to support WebTransport.
 */
internal interface KicheWebTransportEngine {
    suspend fun openWebTransportSession(url: Url): WebTransportSession
}
