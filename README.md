# Kiche

**Kiche** is a [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) library that
wraps [Cloudflare quiche](https://github.com/cloudflare/quiche) — a Rust implementation of the
**QUIC** transport protocol and **HTTP/3** — behind a Kotlin-idiomatic API. It exposes QUIC
connections, streams, datagrams ([RFC 9221](https://www.rfc-editor.org/rfc/rfc9221.html)), and
HTTP/3 on **JVM, Android, and iOS**.

The name is *quiche*, but in Kotlin.

> ## ⚠️ Experimental — `0.1.0-alpha.1`
>
> This is an early alpha. **There are no API-stability guarantees of any kind** — anything may
> change or be removed without notice or deprecation between releases. It is **not** recommended for
> production use yet. Feedback and issues are very welcome.

## Why

No maintained JNI/cinterop binding for quiche exists. The only prior attempt,
[quiche4j](https://github.com/kachayev/quiche4j), is pinned to a very old quiche, has no datagram
support, no Android builds, and is unmaintained. Kiche targets a current quiche and exposes the raw
QUIC primitives (streams, datagrams, congestion control) that high-level HTTP clients (OkHttp,
Cronet) don't — while leaving **all** QUIC/HTTP3 protocol logic (QPACK, frame parsing, TLS 1.3 via
BoringSSL) inside quiche itself.

## Modules

| Module | Coordinates (`eu.buney.kiche:…`) | What it is |
|---|---|---|
| **`:kiche`** | `kiche` | Core KMP binding. QUIC transport **and** HTTP/3: streams, datagrams, timers, stats, connection-ID management, path migration, and the server-side helpers. ~73% of quiche's C surface. |
| **`:ktor-kiche-webtransport`** | `ktor-kiche-webtransport` | WebTransport-over-HTTP/3 protocol layer ([RFC 9297](https://www.rfc-editor.org/rfc/rfc9297.html) capsules, HTTP Datagram framing). Shared by client + server. |
| **`:ktor-client-kiche`** | `ktor-client-kiche` | [Ktor](https://ktor.io/) `HttpClient` engine over HTTP/3, with connection pooling and streaming request bodies (+ WebTransport client). See [its README](ktor-client-kiche/README.md). |
| **`:ktor-server-kiche`** | `ktor-server-kiche` | Ktor `ApplicationEngine` over HTTP/3, with concurrent connections + per-stream dispatch (+ WebTransport server). See [its README](ktor-server-kiche/README.md). |
| **`:ktor-client-h3-adaptive`** | `ktor-client-h3-adaptive` | Alt-Svc–driven adaptive engine that routes between a TCP engine and a QUIC engine ([RFC 7838](https://www.rfc-editor.org/rfc/rfc7838.html)). Independent of `:kiche`. |

The authoritative map of which `quiche_*` functions are wrapped (and the known gaps — HTTP/3
priority & trailers, qlog, datagram purge) lives in [`docs/quiche-coverage.md`](docs/quiche-coverage.md).

## Platform status

| Target | Status |
|---|---|
| **JVM (desktop)** | ✅ Tested/proven. The published JAR currently bundles the macOS native (`arm64`/`x86_64`); Linux/Windows natives are not yet packaged. |
| **Android** | ⚠️ Declared and compiles (cargo-ndk wired); **not yet integration-tested**. |
| **iOS** | ⚠️ Declared and compiles (Kotlin/Native cinterop); **not yet integration-tested**. |

## Install

```kotlin
// build.gradle.kts
repositories { mavenCentral() }

dependencies {
    implementation("eu.buney.kiche:kiche:0.1.0-alpha.1")
    // Ktor integrations (optional):
    implementation("eu.buney.kiche:ktor-client-kiche:0.1.0-alpha.1")
    implementation("eu.buney.kiche:ktor-server-kiche:0.1.0-alpha.1")
}
```

## Quick start (Ktor HTTP/3 client)

```kotlin
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import eu.buney.kiche.ktor.Kiche

val client = HttpClient(Kiche) {
    engine {
        // For TLS verification, point at a CA bundle: caCertPath = "/path/to/cacert.pem"
    }
}

val response = client.get("https://cloudflare-quic.com/")
println(response.version)        // HTTP/3.0
println(response.bodyAsText())
```

A runnable Compose Multiplatform demo (Android + Desktop + iOS) lives in
[`example/`](example/README.md).

## Building from source

quiche (Rust + BoringSSL) is vendored as a git submodule, so clone recursively:

```
git clone --recurse-submodules https://github.com/yankeppey/kiche.git
./gradlew :example:shared:run        # runs the desktop demo on macOS
```

Native build scripts for Android/iOS/desktop are under [`scripts/`](scripts/), orchestrated by
Gradle tasks; cross-compilation for Linux/Windows uses the [`Dockerfile`](Dockerfile).

## License

Apache-2.0 — see [`LICENSE`](LICENSE). Kiche bundles quiche and BoringSSL; their attributions are in
[`NOTICE`](NOTICE) and [`THIRD-PARTY-LICENSES.txt`](THIRD-PARTY-LICENSES.txt).
