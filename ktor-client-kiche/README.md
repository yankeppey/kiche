# ktor-client-kiche

A [Ktor](https://ktor.io/) HTTP client engine that speaks **HTTP/3 over QUIC**, backed by [Cloudflare quiche](https://github.com/cloudflare/quiche) via [Kiche](../README.md) bindings.

## Usage

Add the dependency:

```kotlin
dependencies {
    implementation("eu.buney.kiche:ktor-client-kiche:$kiche_version")
}
```

Create a client — the minimum is a CA bundle for TLS verification (quiche has no system trust store):

```kotlin
import eu.buney.kiche.ktor.Kiche
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

val client = HttpClient(Kiche) {
    engine {
        caCertPath = "/path/to/ca-cert.pem"   // PEM CA bundle, e.g. https://curl.se/ca/cacert.pem
        // verifyPeer = false                 // DEMO ONLY — skips TLS verification; never ship this
    }
}

val response = client.get("https://example.com/api")
println(response.version)        // HTTP/3.0
println(response.bodyAsText())
```

More advanced configuration (every option, with its default):

```kotlin
import eu.buney.kiche.ktor.Kiche
import eu.buney.kiche.KicheCcAlgorithm

val client = HttpClient(Kiche) {
    engine {
        // TLS
        verifyPeer = true                              // verify the server certificate
        caCertPath = "/path/to/ca-cert.pem"            // PEM CA bundle; required when verifyPeer = true

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
    }
}
```

A WebTransport client is also available (`install(WebTransport)` + `client.webTransport(url) { … }`);
see the root [README](../README.md#usage).

## How it works

The engine opens a UDP socket, performs the QUIC handshake via `KicheConnection.connect()`, runs an
HTTP/3 session over `KicheH3Connection`, maps Ktor's `HttpRequestData` to H3 pseudo-headers, and
polls for H3 response events on `Dispatchers.IO`. One long-lived QUIC connection is pooled per
`host:port`, multiplexing requests as independent HTTP/3 streams. Responses report
`HttpProtocolVersion("HTTP", 3, 0)`.
