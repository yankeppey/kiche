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

## Current limitations

- **JVM only** (Android and iOS support planned)
- **No connection pooling** -- opens a new QUIC connection per request
- **ByteArray bodies only** -- `ReadChannelContent` / `WriteChannelContent` streaming not yet supported
- **No redirect handling** at the engine level (use Ktor's `HttpRedirect` plugin)
- **No system trust store** -- `caCertPath` must be set explicitly for TLS verification

## How it works

The engine creates a UDP `DatagramSocket`, performs the QUIC handshake via `KicheConnection.connect()`, opens an HTTP/3 session via `KicheH3Connection`, maps Ktor's `HttpRequestData` to H3 pseudo-headers, and polls for H3 response events. All I/O runs on `Dispatchers.IO`.

Responses always report `HttpProtocolVersion("HTTP", 3, 0)`.
