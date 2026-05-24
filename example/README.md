# Kiche example app

A small **Compose Multiplatform** app (Android + Desktop/JVM) that demonstrates the
`ktor-client-kiche` HTTP/3 client against public test servers. UX follows the Compose Multiplatform
sample: a menu of buttons, each opening a feature screen.

## Screens

| Screen | What it does | Endpoint |
|---|---|---|
| Connection & protocol | QUIC + TLS 1.3 handshake; shows the negotiated protocol (expect HTTP/3.0) | `nghttp2.org/httpbin/get` |
| Echo (POST body) | POSTs a body, reads it back from the response JSON | `nghttp2.org/httpbin/post` |
| Download bytes | GETs a fixed number of bytes and times it | `nghttp2.org/httpbin/bytes/{n}` |
| Streamed response | Reads a chunked, newline-delimited streaming response | `nghttp2.org/httpbin/stream/{n}` |

A single pooled `HttpClient` is shared across screens (`Http3DemoViewModel`), so you can see
Kiche's connection pooling: the first request handshakes, later ones reuse the connection.

## Running

**Desktop (works out of the box on macOS** — `:kiche` bundles a prebuilt `libquiche_jni.dylib`):

```
./gradlew :example:run
```

**Android** requires the quiche JNI `.so` built for the device ABI via cargo-ndk (through `:kiche`).
This path is not yet integration-tested (see `docs/release-readiness.md`). Once built:

```
./gradlew :example:installDebug
```

## ⚠️ TLS verification is disabled

The app sets `verifyPeer = false` in the Kiche engine **on purpose**, to avoid bundling a CA
file. Kiche has no system trust store yet (see `docs/release-readiness.md` → "TLS trust"). This
is fine for a demo against known servers — **never ship `verifyPeer = false` in a real app.** To
verify properly, bundle a CA bundle (e.g. https://curl.se/ca/cacert.pem) and set `caCertPath`.

## Notes / TODO

- **`readUTF8Line` deprecation (Streamed response screen).** `ByteReadChannel.readUTF8Line()` is
  deprecated in ktor 3.4 (use `readLine`/`readLineStrict`). We keep `bodyAsChannel()` +
  `readUTF8Line()` because demonstrating the channel-based streaming-read pattern is the point of
  that screen. Tolerated for now; migrate when convenient.
- **Streaming is not yet truly incremental.** The engine buffers the full response body before
  exposing it, so the streamed lines are counted from the assembled body. Real incremental
  delivery awaits engine-level response-body streaming.
- iOS is not wired here to keep the example lean; it's a straightforward CMP addition later.
