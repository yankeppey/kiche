# Kiche

[![Maven Central](https://img.shields.io/maven-central/v/eu.buney.kiche/kiche?label=Maven%20Central&color=blue)](https://central.sonatype.com/artifact/eu.buney.kiche/kiche)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Stability: Experimental](https://img.shields.io/badge/stability-experimental-orange.svg)

**Kiche** is a [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) library that
wraps [Cloudflare quiche](https://github.com/cloudflare/quiche) — a Rust implementation of the
**QUIC** transport protocol and **HTTP/3** — behind a Kotlin-idiomatic API. It exposes QUIC
connections, streams, datagrams ([RFC 9221](https://www.rfc-editor.org/rfc/rfc9221.html)), and
HTTP/3 on **JVM, Android, and iOS**.

> ## ⚠️ Experimental — `0.1.0-alpha.3`
>
> This is an early alpha. **There are no API-stability guarantees of any kind** — anything may
> change or be removed without notice or deprecation between releases. It is **not** recommended for
> production use yet.

## Modules

- `:kiche` - core KMP binding to Quiche. QUIC transport **and** HTTP/3: streams, datagrams, timers, stats, connection-ID management, path migration, and the server-side helpers.

### Ktor 
- `:ktor-client-kiche` - [Ktor](https://ktor.io/) `HttpClient` engine over HTTP/3, with connection pooling and streaming request bodies (+ WebTransport client). See [its README](ktor-client-kiche/README.md).
- `:ktor-server-kiche` - Ktor `ApplicationEngine` over HTTP/3, with concurrent connections + per-stream dispatch (+ WebTransport server). See [its README](ktor-server-kiche/README.md).
- `:ktor-kiche-webtransport` - WebTransport-over-HTTP/3 protocol layer ([RFC 9297](https://www.rfc-editor.org/rfc/rfc9297.html) capsules, HTTP Datagram framing). Shared by client + server. 
- `ktor-client-h3-adaptive` - Alt-Svc–driven adaptive engine that routes between a TCP engine and a QUIC engine ([RFC 7838](https://www.rfc-editor.org/rfc/rfc7838.html)). Independent of `:kiche`.

## Install

```kotlin
// build.gradle.kts
repositories { mavenCentral() }

dependencies {
    implementation("eu.buney.kiche:kiche:0.1.0-alpha.3")                   // core QUIC + HTTP/3
    // Ktor integrations — all optional:
    implementation("eu.buney.kiche:ktor-client-kiche:0.1.0-alpha.3")       // HTTP/3 client engine (+ WebTransport)
    implementation("eu.buney.kiche:ktor-server-kiche:0.1.0-alpha.3")       // HTTP/3 server engine (+ WebTransport)
    implementation("eu.buney.kiche:ktor-client-h3-adaptive:0.1.0-alpha.3") // Alt-Svc TCP↔QUIC adaptive router
}
```

## Usage

A runnable Compose Multiplatform demo (Android + Desktop + iOS) lives in [`example/`](example/README.md).
The snippets below show the common entry points.

> **Note on TLS:** quiche has no system trust store, so for real verification you must point it at a
> CA bundle via `caCertPath` (e.g. [`cacert.pem`](https://curl.se/ca/cacert.pem)). The snippets that
> use `verifyPeer = false` are demo-only — never ship that.

<details>
<summary><b>HTTP/3 request (Ktor client)</b></summary>

The `Kiche` engine plugs into a Ktor `HttpClient`. Requests to the same `host:port` reuse one pooled
QUIC connection, multiplexed as independent HTTP/3 streams. See
[`ktor-client-kiche`](ktor-client-kiche/README.md) for all configuration options.

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
println(response.version)        // HTTP/3.0
println(response.bodyAsText())
```

</details>

<details>
<summary><b>Adaptive HTTP/2 ⇄ HTTP/3 (Alt-Svc)</b></summary>

`:ktor-client-h3-adaptive` composes a TCP engine (HTTP/1.1 + HTTP/2) with a QUIC engine (HTTP/3).
The first request to an origin goes over TCP; if the origin advertises `Alt-Svc: h3`, subsequent
requests to it upgrade to HTTP/3 automatically ([RFC 7838](https://www.rfc-editor.org/rfc/rfc7838.html)).
This module is independent of `:kiche` — it works with any TCP + QUIC engine pair.

```kotlin
import eu.buney.kiche.ktor.Kiche
import eu.buney.kiche.ktor.h3adaptive.H3Adaptive
import io.ktor.client.*
import io.ktor.client.engine.cio.*   // any Ktor TCP engine: CIO, OkHttp, Darwin…
import io.ktor.client.request.*

val client = HttpClient(H3Adaptive) {
    engine {
        tcp(CIO)                       // HTTP/1.1 + HTTP/2
        quic(Kiche) {                  // HTTP/3
            caCertPath = "/path/to/ca-cert.pem"
        }
    }
}

// First call uses TCP; later calls to an h3-advertising origin switch to HTTP/3.
val response = client.get("https://example.com/")
```

</details>

<details>
<summary><b>WebTransport</b></summary>

Install the `WebTransport` plugin on a `Kiche` client, then open a session. Inside the session you
can exchange datagrams ([RFC 9221](https://www.rfc-editor.org/rfc/rfc9221.html)) and open
bidirectional/unidirectional streams; the session closes automatically when the block returns.

```kotlin
import eu.buney.kiche.ktor.Kiche
import eu.buney.kiche.ktor.webtransport.*
import io.ktor.client.*
import io.ktor.utils.io.*

val client = HttpClient(Kiche) {
    engine { caCertPath = "/path/to/ca-cert.pem" }
    install(WebTransport)
}

client.webTransport("https://example.com:4433/wt") {
    // Datagram round-trip
    datagrams.outgoing.send("ping".encodeToByteArray())
    val echoed = datagrams.incoming.receive()

    // Bidirectional stream
    val stream = createBidirectionalStream()
    stream.outgoing.writeStringUtf8("hello")
    stream.outgoing.flush()
    val reply = stream.incoming.readUTF8Line()
    stream.finish()
}
```

On the server, accept WebTransport sessions with `routing { webTransport(path) { … } }` (`:ktor-server-kiche`).

</details>

<details>
<summary><b>HTTP/3 server</b></summary>

`KicheQuic` is a Ktor `ApplicationEngineFactory`, so routing and plugins work as usual. QUIC mandates
TLS 1.3, so a certificate chain and private key are required. A dual-stack HTTP/2 + HTTP/3 (Alt-Svc)
setup is documented in [`ktor-server-kiche`](ktor-server-kiche/README.md).

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

</details>

## Building from source

quiche (Rust + BoringSSL) is vendored as a git submodule, pinned to quiche **`0.28.0`**, so clone
recursively:

```
git clone --recurse-submodules https://github.com/yankeppey/kiche.git
./gradlew :example:shared:run        # runs the desktop demo on macOS
```

Native build scripts are under [`scripts/`](scripts/), orchestrated by Gradle tasks:

- `build_quiche_apple.sh` / `build_quiche_jni.sh` — Apple (iOS/macOS) + the macOS desktop JNI.
- `build_quiche_android.sh` — Android ABIs via `cargo-ndk` (needs `ANDROID_NDK_HOME`).
- `build_quiche_jni_linux.sh` / `build_quiche_jni_windows.ps1` — the Linux/Windows desktop JNI
  (`libquiche_jni.so` / `.dll`). These run natively on a Linux / Windows host; CI builds them on
  GitHub-hosted runners and the published JVM jar bundles every platform's native under
  `native/<os>/<arch>/`, extracted at runtime by `KicheLoader`.

## License

Apache-2.0 — see [`LICENSE`](LICENSE). Kiche bundles quiche and BoringSSL; their attributions are in
[`NOTICE`](NOTICE) and [`THIRD-PARTY-LICENSES.txt`](THIRD-PARTY-LICENSES.txt).
