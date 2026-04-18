package eu.buney.kiche.ktor.server

import eu.buney.kiche.ktor.Kiche
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Integration tests: Kiche Ktor server (HTTP/3) + Kiche Ktor client.
 * Full round-trip over real QUIC + UDP on localhost.
 */
class KicheServerTest {

    private lateinit var server: EmbeddedServer<KicheApplicationEngine, KicheApplicationEngine.Configuration>
    private lateinit var client: HttpClient
    private var serverPort: Int = 0

    @BeforeTest
    fun setUp() {
        val certDir = findCertDir()

        server = embeddedServer(KicheQuic, port = 0) {
            routing {
                get("/hello") {
                    call.respondText("hello from kiche server")
                }
                post("/echo") {
                    val body = call.receive<String>()
                    call.respondText(body)
                }
                get("/status/{code}") {
                    val code = call.parameters["code"]?.toIntOrNull() ?: 400
                    call.respond(HttpStatusCode.fromValue(code), "")
                }
            }
        }
        server.engine.configuration.certChainPath = "$certDir/cert.crt"
        server.engine.configuration.privateKeyPath = "$certDir/cert.key"

        server.start(wait = false)

        // Wait for server to be ready and get the actual port
        runBlocking {
            var attempts = 0
            while (attempts < 50) {
                val connectors = try { server.engine.resolvedConnectors() } catch (_: Exception) { emptyList() }
                if (connectors.isNotEmpty()) {
                    serverPort = connectors.first().port
                    break
                }
                delay(100)
                attempts++
            }
            require(serverPort > 0) { "Server did not start" }
        }

        client = HttpClient(Kiche) {
            engine {
                verifyPeer = false
            }
        }
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.stop(0, 1000)
    }

    @Test
    fun `GET hello from Kiche server`() = runBlocking {
        val response = client.get("https://localhost:$serverPort/hello")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello from kiche server", response.bodyAsText())
    }

    @Test
    fun `POST echo through Kiche server`() = runBlocking {
        val payload = "round trip test"
        val response = client.post("https://localhost:$serverPort/echo") {
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(payload, response.bodyAsText())
    }

    @Test
    fun `response reports HTTP 3`() = runBlocking {
        val response = client.get("https://localhost:$serverPort/hello")
        assertEquals("HTTP", response.version.name)
        assertEquals(3, response.version.major)
    }

    companion object {
        private fun findCertDir(): String = quicheCertDir()
    }
}
