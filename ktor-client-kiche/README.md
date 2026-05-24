# ktor-client-kiche

A [Ktor](https://ktor.io/) HTTP client engine that speaks **HTTP/3 over QUIC**, backed by [Cloudflare quiche](https://github.com/cloudflare/quiche) via [Kiche](../README.md) bindings.

## Usage

Add the dependency:

```kotlin
dependencies {
    implementation("eu.buney.kiche:ktor-client-kiche:$kiche_version")
}
```

Create a client:

```kotlin
import eu.buney.kiche.ktor.Kiche
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

val client = HttpClient(Kiche) {
    engine {
        verifyPeer = true
        caCertPath = "/path/to/ca-cert.pem"
    }
}

val response = client.get("https://example.com/api")
println(response.bodyAsText())
```

## Configuration

| Property | Default | Description |
|---|---|---|
| `verifyPeer` | `true` | Verify server TLS certificate |
| `caCertPath` | `null` | Path to CA certificate file (PEM). Required when `verifyPeer = true` |
| `ccAlgorithm` | `Bbr2` | Congestion control: `Reno`, `Cubic`, or `Bbr2` |
| `maxIdleTimeoutMs` | `30000` | Connection idle timeout (ms). 0 = no timeout |
| `initialMaxData` | `10000000` | Connection-level flow control window (bytes) |
| `initialMaxStreamDataBidiLocal` | `1000000` | Per-stream flow control (bidirectional, local-initiated) |
| `initialMaxStreamDataBidiRemote` | `1000000` | Per-stream flow control (bidirectional, remote-initiated) |
| `initialMaxStreamDataUni` | `1000000` | Per-stream flow control (unidirectional) |
| `initialMaxStreamsBidi` | `100` | Max concurrent bidirectional streams |
| `initialMaxStreamsUni` | `100` | Max concurrent unidirectional streams |

## What works

- **Connection pooling** -- one long-lived QUIC connection per `host:port`; requests are
  multiplexed as independent HTTP/3 streams (see `KicheEndpoint`).
- **Streaming request bodies** -- `ReadChannelContent` / `WriteChannelContent` are streamed
  to the server chunk-by-chunk alongside QUIC flow control (tested up to 8 MB).
- **WebTransport client** -- `HttpClient.webTransportSession(url)` opens a WebTransport session
  over HTTP/3 (bidirectional/unidirectional streams + datagrams).

## Current limitations

- **JVM is the tested target.** Android and iOS targets are declared in `build.gradle.kts` and the
  code compiles, but they are not yet integration-tested.
- **Response bodies are buffered in memory** before being exposed as a `ByteReadChannel`
  (the body is reassembled from H3 `Data` events, then handed to Ktor).
- **No redirect handling** at the engine level (use Ktor's `HttpRedirect` plugin).
- **No system trust store** -- `caCertPath` must be set explicitly for TLS verification.
- **No HTTP/3 priority** (quiche's priority API is not wrapped — see `docs/quiche-coverage.md`).

## How it works

The engine creates a UDP `DatagramSocket`, performs the QUIC handshake via `KicheConnection.connect()`, opens an HTTP/3 session via `KicheH3Connection`, maps Ktor's `HttpRequestData` to H3 pseudo-headers, and polls for H3 response events. All I/O runs on `Dispatchers.IO`.

Responses always report `HttpProtocolVersion("HTTP", 3, 0)`.
