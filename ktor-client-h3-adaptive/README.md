# ktor-client-h3-adaptive

A [Ktor](https://ktor.io/) `HttpClient` engine that routes requests between a **TCP** engine
(HTTP/1.1, HTTP/2) and a **QUIC** engine (HTTP/3), upgrading an origin to HTTP/3 once it advertises
support via `Alt-Svc` ([RFC 7838](https://www.rfc-editor.org/rfc/rfc7838.html)).

It is independent of [`:kiche`](../README.md) — it composes any TCP + QUIC engine pair. Use
[`ktor-client-kiche`](../ktor-client-kiche/README.md) as the QUIC engine for HTTP/3.

## Usage

```kotlin
dependencies {
    implementation("eu.buney.kiche:ktor-client-h3-adaptive:$kiche_version")
}
```

```kotlin
import eu.buney.kiche.ktor.Kiche
import eu.buney.kiche.ktor.h3adaptive.H3Adaptive
import io.ktor.client.*
import io.ktor.client.engine.cio.*   // any Ktor TCP engine: CIO, OkHttp, Darwin…
import io.ktor.client.request.*

val client = HttpClient(H3Adaptive) {
    engine {
        tcp(CIO)                 // HTTP/1.1 + HTTP/2
        quic(Kiche) {            // HTTP/3
            caCertPath = "/path/to/ca-cert.pem"
        }
    }
}

// First request to an origin goes over TCP. If the response carries `Alt-Svc: h3`,
// later requests to that origin are sent over HTTP/3.
val response = client.get("https://example.com/")
```

## How it works

The first request to an origin uses the TCP engine. The engine parses the `Alt-Svc` response header;
once an origin advertises an `h3` endpoint, subsequent requests to it are routed through the QUIC
engine until the advertisement's `ma` (max-age) expires. Both engines are user-supplied, so this
module has no dependency on `:kiche` itself.
