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

## Dual-stack HTTP/2 + HTTP/3

In production, clients discover HTTP/3 via the `Alt-Svc` header served over HTTP/2.
Run both a standard HTTP/2 engine and KicheQuic side by side, sharing the same application module:

```kotlin
import eu.buney.kiche.ktor.server.KicheQuic
import io.ktor.server.engine.*
import io.ktor.server.netty.*          // or CIO, Jetty, etc.
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.defaultheaders.*

/** Application logic — defined once, used by both engines. */
fun Application.appModule() {
    routing {
        get("/") {
            call.respondText("Hello from HTTP/2 or HTTP/3!")
        }
    }
}

fun main() {
    // HTTP/2 (TCP, port 443) — advertises HTTP/3 via Alt-Svc
    embeddedServer(Netty, port = 443) {
        appModule()
        install(DefaultHeaders) {
            header("Alt-Svc", """h3=":443"; ma=86400""")
        }
    }.start(wait = false)

    // HTTP/3 (QUIC/UDP, port 443)
    embeddedServer(KicheQuic, port = 443) {
        appModule()
    }.apply {
        engine.configuration.certChainPath = "/path/to/cert.pem"
        engine.configuration.privateKeyPath = "/path/to/key.pem"
    }.start(wait = true)
}
```

Flow:
1. Client connects over TCP → Netty serves the response with `Alt-Svc: h3=":443"; ma=86400`
2. Client learns HTTP/3 is available and switches to QUIC on subsequent requests
3. Both engines execute the same `appModule()` routing and handlers

> **Note:** Each engine creates its own `Application` instance, so in-memory plugin state
> (e.g., in-memory sessions, rate-limit counters) is not shared between the two.
> Externalize such state (Redis, database) if you need it consistent across both stacks.

## What works

- **Multiple concurrent QUIC connections** -- connections are tracked in a DCID-keyed map with a
  configurable `maxConnections` limit; includes version negotiation and stateless retry tokens.
- **Concurrent request handling** -- each H3 request stream is dispatched to the Ktor pipeline on
  its own coroutine, so requests within a connection run concurrently.
- **WebTransport server** -- `Routing.webTransport(path) { session -> ... }` accepts WebTransport
  sessions over HTTP/3 (Extended CONNECT) with streams and datagrams.

## Current limitations

- **JVM is the tested target.** Android and iOS targets are declared in `build.gradle.kts` and the
  code compiles, but they are not yet integration-tested.
- **Response bodies buffered in memory** -- the response body is serialized to a `ByteArray` before
  H3 transmission; there is no streaming response channel yet.
- **No WebSocket / SSE** -- plain HTTP/3 request-response (plus WebTransport, above).
- **No HTTP/3 priority or trailers** (those quiche APIs are not wrapped — see `docs/quiche-coverage.md`).
- Subject to known flaky concurrency issues during handshake under load (see
  `docs/connection-pooling-bugs-report.md`).
