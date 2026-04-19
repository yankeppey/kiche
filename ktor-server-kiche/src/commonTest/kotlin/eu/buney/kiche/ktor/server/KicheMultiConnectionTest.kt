package eu.buney.kiche.ktor.server

import eu.buney.kiche.ktor.Kiche
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlin.test.*

/**
 * Tests for multi-connection support: multiple clients connecting
 * to the same server concurrently over separate QUIC connections.
 */
class KicheMultiConnectionTest {

    private lateinit var server: EmbeddedServer<KicheApplicationEngine, KicheApplicationEngine.Configuration>
    private var serverPort: Int = 0

    @BeforeTest
    fun setUp() {
        val certDir = quicheCertDir()

        server = embeddedServer(KicheQuic, port = 0) {
            routing {
                get("/hello") {
                    call.respondText("hello")
                }
                get("/slow") {
                    delay(500)
                    call.respondText("slow response")
                }
            }
        }
        server.engine.configuration.certChainPath = "$certDir/cert.crt"
        server.engine.configuration.privateKeyPath = "$certDir/cert.key"

        server.start(wait = false)

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
    }

    @AfterTest
    fun tearDown() {
        server.stop(0, 1000)
    }

    private fun createClient(): HttpClient = HttpClient(Kiche) {
        engine { verifyPeer = false }
    }

    @Test
    fun `sequential clients both succeed`() = runBlocking {
        // Client A makes a request, then closes
        val clientA = createClient()
        val responseA = clientA.get("https://127.0.0.1:$serverPort/hello")
        assertEquals(HttpStatusCode.OK, responseA.status)
        assertEquals("hello", responseA.bodyAsText())
        clientA.close()

        // Client B connects after A is done — different ephemeral port, new QUIC connection
        val clientB = createClient()
        val responseB = clientB.get("https://127.0.0.1:$serverPort/hello")
        assertEquals(HttpStatusCode.OK, responseB.status)
        assertEquals("hello", responseB.bodyAsText())
        clientB.close()
    }

    @Test
    fun `concurrent clients both get correct responses`() = runBlocking {
        val clientA = createClient()
        val clientB = createClient()

        // Launch both requests concurrently
        val deferredA = async {
            clientA.get("https://127.0.0.1:$serverPort/hello")
        }
        val deferredB = async {
            clientB.get("https://127.0.0.1:$serverPort/hello")
        }

        val responseA = deferredA.await()
        val responseB = deferredB.await()

        assertEquals(HttpStatusCode.OK, responseA.status)
        assertEquals("hello", responseA.bodyAsText())
        assertEquals(HttpStatusCode.OK, responseB.status)
        assertEquals("hello", responseB.bodyAsText())

        clientA.close()
        clientB.close()
    }

    @Test
    fun `new client connects while existing client has slow request`() = runBlocking {
        val clientA = createClient()
        val clientB = createClient()

        // Client A makes a slow request
        val deferredA = async {
            clientA.get("https://127.0.0.1:$serverPort/slow")
        }

        // Give A time to start the handshake
        delay(50)

        // Client B connects while A's request is still in-flight
        val responseB = clientB.get("https://127.0.0.1:$serverPort/hello")
        assertEquals(HttpStatusCode.OK, responseB.status)
        assertEquals("hello", responseB.bodyAsText())

        // A should also complete successfully
        val responseA = deferredA.await()
        assertEquals(HttpStatusCode.OK, responseA.status)
        assertEquals("slow response", responseA.bodyAsText())

        clientA.close()
        clientB.close()
    }
}
