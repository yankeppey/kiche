# ktor-server-kiche

A [Ktor](https://ktor.io/) server engine that serves **HTTP/3 over QUIC**, backed by [Cloudflare quiche](https://github.com/cloudflare/quiche) via [Kiche](../README.md) bindings.

## Usage

Add the dependency:

```kotlin
dependencies {
    implementation("eu.buney.kiche:ktor-server-kiche:$kiche_version")
}
```

Create a server. QUIC mandates TLS 1.3, so a certificate chain and private key are required:

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
    }.apply {
        engine.configuration.certChainPath = "/path/to/cert.pem"
        engine.configuration.privateKeyPath = "/path/to/key.pem"
    }.start(wait = true)
}
```

More advanced configuration (every option, with its default):

```kotlin
import eu.buney.kiche.KicheCcAlgorithm

embeddedServer(KicheQuic, port = 4433) {
    routing { /* ... */ }
}.apply {
    engine.configuration.apply {
        // TLS (required)
        certChainPath = "/path/to/cert.pem"
        privateKeyPath = "/path/to/key.pem"

        // Congestion control
        ccAlgorithm = KicheCcAlgorithm.Bbr2            // Reno | Cubic | Bbr2

        // Timeouts
        maxIdleTimeoutMs = 30_000                      // idle timeout (ms); 0 = no timeout

        // Flow control (bytes)
        initialMaxData = 10_000_000                    // connection-level receive window
        initialMaxStreamDataBidiLocal = 1_000_000      // per bidi stream, locally initiated
        initialMaxStreamDataBidiRemote = 1_000_000     // per bidi stream, remotely initiated
        initialMaxStreamDataUni = 1_000_000            // per unidirectional stream

        // Concurrency
        initialMaxStreamsBidi = 100                    // max concurrent bidirectional streams
        initialMaxStreamsUni = 100                     // max concurrent unidirectional streams
        maxConnections = 1000                          // max concurrent QUIC connections
    }
}.start(wait = true)
```

WebTransport sessions are accepted with `routing { webTransport(path) { … } }` (Extended CONNECT).

## Architecture

Modeled after Ktor's CIO server engine, using coroutines for non-blocking I/O:

- **Recv coroutine** -- reads UDP packets, feeds `KicheConnection.recv()`, polls H3 events
- **Send coroutine** -- drains `KicheConnection.send()`, writes UDP packets, drives pending response bodies
- **Mutex** -- serializes access to the quiche connection (not thread-safe)
- **Channel** -- recv signals send that there are outgoing packets to flush

For each H3 request, the engine creates a `KicheApplicationCall` and executes the standard Ktor `ApplicationCallPipeline`, so all Ktor plugins (routing, content negotiation, authentication, etc.) work normally.

## Dual-stack HTTP/2 + HTTP/3

QUIC is reached over UDP, which browsers won't try first — clients discover HTTP/3 from the
**`Alt-Svc`** header on a regular HTTP/2 (TCP) response, then switch to QUIC for later requests
([RFC 7838](https://www.rfc-editor.org/rfc/rfc7838.html)). So in production you run a TCP engine
(Netty/CIO/…) that advertises `h3` next to `KicheQuic` on the same port:

```kotlin
install(DefaultHeaders) {
    header("Alt-Svc", """h3=":443"; ma=86400""")   // advertise HTTP/3 on the HTTP/2 responses
}
```

The client-side counterpart that reads `Alt-Svc` and routes between TCP and QUIC is
[`ktor-client-h3-adaptive`](../ktor-client-h3-adaptive/README.md).
