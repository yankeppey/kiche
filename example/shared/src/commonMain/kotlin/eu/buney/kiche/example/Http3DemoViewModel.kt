package eu.buney.kiche.example

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import eu.buney.kiche.ktor.Kiche
import eu.buney.kiche.ktor.h3adaptive.H3Adaptive
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/** The result of one demo operation, rendered by [ResultCard]. */
sealed interface OpState {
    data object Idle : OpState
    data object Loading : OpState
    data class Success(val text: String) : OpState
    data class Failure(val message: String) : OpState
}

/** The HTTP engine the demo operations run on, toggled from the menu. */
enum class EngineMode(val label: String) {
    /** Every request goes over QUIC/HTTP-3 (Kiche). */
    PureHttp3("Pure HTTP/3"),

    /** First request over TCP/HTTP-2, then upgraded to HTTP/3 once Alt-Svc advertises it. */
    Adaptive("Adaptive HTTP/2 ⇄ HTTP/3"),
}

/**
 * Owns the [HttpClient]s that drive the demo operations and exposes an [EngineMode] toggle.
 *
 * Two clients are held: a pure HTTP/3 client (Kiche) and an adaptive one that starts on TCP/HTTP-2
 * and upgrades to HTTP/3 via Alt-Svc. The menu switch picks which one the operations use. Each
 * client is reused across every screen on purpose — it shows connection pooling: the first request
 * to a host performs the handshake, later requests reuse the connection.
 *
 * State is exposed as Compose [mutableStateOf] (the Paragem `@Stable` state-holder idiom),
 * so screens read it directly without flows. This is a plain holder, not an androidx ViewModel,
 * to avoid platform `ViewModelStoreOwner` wiring — it is created in [App] and closed on dispose.
 */
@Stable
class Http3DemoViewModel {

    private val log = Logger.withTag("KicheDemo")

    init {
        // Turn on quiche's internal QUIC/HTTP3 trace logging (to stderr). Verbose, but useful
        // for the demo. ktor-network's own logs surface via the slf4j-simple desktop dependency.
        val enabled = eu.buney.kiche.Kiche.enableDebugLogging()
        log.i { "quiche debug logging ${if (enabled) "enabled" else "already enabled"}" }
    }

    /** Which engine the demo operations run on; toggled from the menu. */
    var engineMode by mutableStateOf(EngineMode.PureHttp3)
        private set

    // Pure HTTP/3: every request goes over QUIC (Kiche).
    private val pureClient: HttpClient = HttpClient(Kiche) {
        engine {
            // DEMO ONLY: skip TLS verification so we don't have to bundle a CA bundle.
            // quiche has no system trust store (see docs/release-readiness.md → "TLS trust").
            // Never ship verifyPeer = false in a real app.
            verifyPeer = false
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    // Adaptive: first request over TCP/HTTP-2 (OkHttp on JVM/Android, Darwin on iOS), then upgraded
    // to HTTP/3 (Kiche) once the origin's Alt-Svc advertises it (RFC 7838).
    private val adaptiveClient: HttpClient = HttpClient(H3Adaptive) {
        engine {
            installTcpEngine()
            quic(Kiche) {
                verifyPeer = false // DEMO ONLY — see above. The TCP leg validates TLS normally.
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    private val client: HttpClient
        get() = when (engineMode) {
            EngineMode.PureHttp3 -> pureClient
            EngineMode.Adaptive -> adaptiveClient
        }

    var connectionInfo by mutableStateOf<OpState>(OpState.Idle)
        private set
    var echo by mutableStateOf<OpState>(OpState.Idle)
        private set
    var download by mutableStateOf<OpState>(OpState.Idle)
        private set
    var streaming by mutableStateOf<OpState>(OpState.Idle)
        private set

    /** GET /get — confirms the handshake worked and reports the negotiated protocol. */
    suspend fun loadConnectionInfo() {
        connectionInfo = OpState.Loading
        connectionInfo = runOp("GET /get") {
            val response = client.get("${Endpoints.HTTPBIN}/get")
            buildString {
                appendLine("Status:   ${response.status}")
                appendLine("Protocol: ${response.version}")   // expect HTTP/3.0
                appendLine()
                appendLine(response.bodyAsText().take(600))
            }
        }
    }

    /** POST /post — echoes the request body back inside the response JSON. */
    suspend fun runEcho(message: String) {
        echo = OpState.Loading
        echo = runOp("POST /post") {
            val response = client.post("${Endpoints.HTTPBIN}/post") { setBody(message) }
            response.bodyAsText().take(1200)
        }
    }

    /**
     * GET /stream-bytes/{n} — downloads n random bytes, streamed in chunks.
     *
     * We use /stream-bytes rather than /bytes: nghttp2.org's /bytes endpoint is broken — it sends
     * the response headers (200, Content-Length) but then aborts the body (an empty body over
     * HTTP/2, or RESET_STREAM with H3_INTERNAL_ERROR=0x102 over HTTP/3). /stream-bytes returns the
     * same n random bytes and works correctly.
     */
    suspend fun runDownload(numBytes: Int) {
        download = OpState.Loading
        download = runOp("GET /stream-bytes/$numBytes") {
            val mark = TimeSource.Monotonic.markNow()
            val bytes = client.get("${Endpoints.HTTPBIN}/stream-bytes/$numBytes").readRawBytes()
            val elapsed = mark.elapsedNow()
            "Requested: $numBytes bytes\nReceived:  ${bytes.size} bytes\nElapsed:   $elapsed"
        }
    }

    /**
     * GET /stream/{n} — the server emits n newline-delimited JSON objects, chunked.
     *
     * Reads the response body as a [io.ktor.utils.io.ByteReadChannel] line-by-line — the
     * idiomatic streaming-read pattern. NOTE: the current engine buffers the full body before
     * exposing it (see docs/release-readiness.md), so lines are not yet delivered truly
     * incrementally — only the API usage is streaming.
     */
    suspend fun runStreaming(lines: Int) {
        streaming = OpState.Loading
        streaming = runOp("GET /stream/$lines") {
            val channel = client.get("${Endpoints.HTTPBIN}/stream/$lines").bodyAsChannel()
            var count = 0
            val preview = StringBuilder()
            while (true) {
                // TODO: readUTF8Line is deprecated in ktor 3.4 (use readLine/readLineStrict).
                // Tolerated for now; tracked in example/README.md. See KICHE notes.
                val line = channel.readUTF8Line() ?: break
                count++
                if (count <= 5) preview.appendLine(line.take(120))
            }
            "Received $count streamed JSON line(s).\n\nFirst lines:\n$preview"
        }
    }

    /** Switch the active engine and clear stale results produced by the previous one. */
    fun selectEngineMode(mode: EngineMode) {
        if (mode == engineMode) return
        engineMode = mode
        connectionInfo = OpState.Idle
        echo = OpState.Idle
        download = OpState.Idle
        streaming = OpState.Idle
    }

    fun close() {
        pureClient.close()
        adaptiveClient.close()
    }

    private suspend fun runOp(name: String, block: suspend () -> String): OpState {
        log.i { "$name — starting" }
        return try {
            val text = block()
            log.i { "$name — ok" }
            OpState.Success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "$name — failed" }
            OpState.Failure(e.message ?: e.toString())
        }
    }
}
