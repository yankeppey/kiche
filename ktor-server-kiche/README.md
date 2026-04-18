# ktor-server-kiche

A [Ktor](https://ktor.io/) server engine that serves **HTTP/3 over QUIC**, backed by [Cloudflare quiche](https://github.com/cloudflare/quiche) via [Kiche](../README.md) bindings.

## Usage

Add the dependency:

```kotlin
dependencies {
    implementation("eu.buney.kiche:ktor-server-kiche:$kiche_version")
}
```

Create a server:

```kotlin
import eu.buney.kiche.ktor.server.KicheQuic
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(KicheQuic, port = 4433) {
        routing {
            get("/") {
                call.respondText("Hello over HTTP/3!")
            }
        }
    }.start(wait = true)
}
```

TLS certificate and key are required (QUIC mandates TLS 1.3):

```kotlin
embeddedServer(KicheQuic, port = 4433) {
    routing { /* ... */ }
}.apply {
    engine.configuration.certChainPath = "/path/to/cert.pem"
    engine.configuration.privateKeyPath = "/path/to/key.pem"
}.start(wait = true)
```

## Configuration

| Property | Default | Description |
|---|---|---|
| `certChainPath` | **required** | TLS certificate chain file (PEM) |
| `privateKeyPath` | **required** | TLS private key file (PEM) |
| `ccAlgorithm` | `Bbr2` | Congestion control: `Reno`, `Cubic`, or `Bbr2` |
| `maxIdleTimeoutMs` | `30000` | Connection idle timeout (ms). 0 = no timeout |
| `initialMaxData` | `10000000` | Connection-level flow control window (bytes) |
| `initialMaxStreamDataBidiLocal` | `1000000` | Per-stream flow control (bidirectional, local-initiated) |
| `initialMaxStreamDataBidiRemote` | `1000000` | Per-stream flow control (bidirectional, remote-initiated) |
| `initialMaxStreamDataUni` | `1000000` | Per-stream flow control (unidirectional) |
| `initialMaxStreamsBidi` | `100` | Max concurrent bidirectional streams |
| `initialMaxStreamsUni` | `100` | Max concurrent unidirectional streams |

## Architecture

Modeled after Ktor's CIO server engine, using coroutines for non-blocking I/O:

- **Recv coroutine** -- reads UDP packets, feeds `KicheConnection.recv()`, polls H3 events
- **Send coroutine** -- drains `KicheConnection.send()`, writes UDP packets, drives pending response bodies
- **Mutex** -- serializes access to the quiche connection (not thread-safe)
- **Channel** -- recv signals send that there are outgoing packets to flush

For each H3 request, the engine creates a `KicheApplicationCall` and executes the standard Ktor `ApplicationCallPipeline`, so all Ktor plugins (routing, content negotiation, authentication, etc.) work normally.

## Current limitations

- **JVM only** (Android and iOS support planned)
- **Single connection** -- handles one QUIC client at a time (sufficient for testing and development)
- **No concurrent streams** -- requests within a connection are processed sequentially
- **No WebSocket / SSE** -- HTTP/3 request-response only
- **Response bodies buffered in memory** -- no streaming response channel yet
