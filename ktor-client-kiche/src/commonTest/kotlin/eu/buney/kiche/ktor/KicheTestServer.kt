package eu.buney.kiche.ktor

import eu.buney.kiche.ktor.server.KicheApplicationEngine
import eu.buney.kiche.ktor.server.KicheQuic
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Shared test server for Kiche HTTP/3 integration tests.
 *
 * Provides standard endpoints and lifecycle helpers so individual test classes
 * don't duplicate server setup and routing.
 */
class KicheTestServer {

    lateinit var server: EmbeddedServer<KicheApplicationEngine, KicheApplicationEngine.Configuration>
        private set
    var port: Int = 0
        private set

    val baseUrl: String get() = "https://127.0.0.1:$port"

    /**
     * Starts the test server with the [standard routes][standardRoutes] plus any
     * additional routes supplied via [extraRoutes].
     */
    fun start(extraRoutes: (Routing.() -> Unit)? = null) {
        val certDir = quicheCertDir()

        server = embeddedServer(KicheQuic, host = "127.0.0.1", port = 0) {
            routing {
                standardRoutes()
                extraRoutes?.invoke(this)
            }
        }
        server.engine.configuration.certChainPath = "$certDir/cert.crt"
        server.engine.configuration.privateKeyPath = "$certDir/cert.key"

        server.start(wait = false)

        runBlocking {
            var attempts = 0
            while (attempts < 50) {
                val connectors = try {
                    server.engine.resolvedConnectors()
                } catch (_: Exception) {
                    emptyList()
                }
                if (connectors.isNotEmpty()) {
                    port = connectors.first().port
                    break
                }
                delay(100)
                attempts++
            }
            require(port > 0) { "Server did not start" }
        }
    }

    fun stop() {
        server.stop(0, 1000)
    }

    /** Creates a Kiche HTTP/3 client configured for localhost testing (no peer verification). */
    fun createClient(): HttpClient = HttpClient(Kiche) {
        engine { verifyPeer = false }
    }

    companion object {
        /** Standard test endpoints available on every KicheTestServer instance. */
        fun Routing.standardRoutes() {
            get("/hello") {
                call.respondText("hello")
            }
            get("/empty") {
                call.respondText("")
            }
            route("/echo-method") {
                handle {
                    call.respondText(call.request.httpMethod.value)
                }
            }
            route("/echo-body") {
                handle {
                    val body = call.receive<ByteArray>()
                    call.respondBytes(body)
                }
            }
            route("/echo") {
                handle {
                    val body = call.receive<ByteArray>()
                    call.respondBytes(body)
                }
            }
            route("/status/{code}") {
                handle {
                    val code = call.parameters["code"]?.toIntOrNull() ?: 400
                    call.respond(HttpStatusCode.fromValue(code), "")
                }
            }
            route("/headers") {
                handle {
                    val echoHeaders = HeadersBuilder()
                    call.request.headers.forEach { name, values ->
                        for (value in values) {
                            echoHeaders.append("x-echo-$name", value)
                        }
                    }
                    call.response.headers.apply {
                        echoHeaders.build().forEach { name, values ->
                            for (value in values) {
                                append(name, value)
                            }
                        }
                    }
                    call.respondText("")
                }
            }
            route("/content-type") {
                handle {
                    val ct = call.request.contentType().toString()
                    call.respondText(ct)
                }
            }
            route("/query") {
                handle {
                    val queryString = call.request.queryString()
                    call.respondText(queryString)
                }
            }
            get("/large/{size}") {
                val size = call.parameters["size"]?.toIntOrNull() ?: 0
                val body = ByteArray(size) { 'A'.code.toByte() }
                call.respondBytes(body)
            }
            get("/multi-header") {
                call.response.headers.append("x-multi", "value1")
                call.response.headers.append("x-multi", "value2")
                call.response.headers.append("x-multi", "value3")
                call.response.headers.append("x-single", "only")
                call.respondText("")
            }
        }
    }
}
