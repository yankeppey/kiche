package eu.buney.kiche.ktor.server.webtransport

import io.ktor.server.routing.*
import io.ktor.util.*

/**
 * Handler type for WebTransport sessions.
 */
public fun interface WebTransportHandler {
    public suspend fun WebTransportServerSession.handle()
}

/**
 * Attribute key used to register WebTransport route handlers in the application.
 *
 * The server engine checks this to determine which paths accept WebTransport sessions.
 */
internal val WebTransportRoutesKey: AttributeKey<MutableMap<String, WebTransportHandler>> =
    AttributeKey("WebTransportRoutes")

/**
 * Registers a WebTransport session handler at the given [path].
 *
 * When a client sends an HTTP/3 Extended CONNECT request with `:protocol = webtransport`
 * to this path, the server establishes a WebTransport session and invokes [handler].
 *
 * ```kotlin
 * routing {
 *     webTransport("/wt") {
 *         // `this` is WebTransportServerSession
 *         val stream = incomingBidirectionalStreams.receive()
 *         val data = stream.incoming.readRemaining().readByteArray()
 *         stream.outgoing.writeFully(data)
 *         stream.finish()
 *     }
 * }
 * ```
 *
 * @param path The URL path to listen on for WebTransport sessions.
 * @param handler Suspend lambda executed within the session scope. The session
 *   remains open until [handler] returns or the connection closes.
 */
public fun Routing.webTransport(path: String, handler: suspend WebTransportServerSession.() -> Unit) {
    val routes = application.attributes.computeIfAbsent(WebTransportRoutesKey) { mutableMapOf() }
    routes[path] = WebTransportHandler { handler() }
}
