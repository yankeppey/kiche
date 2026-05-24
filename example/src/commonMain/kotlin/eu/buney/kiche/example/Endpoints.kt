package eu.buney.kiche.example

/**
 * Public HTTP/3 test endpoints used by the demo.
 *
 * The demo connects directly to UDP :443 with ALPN `h3` — it does NOT do Alt-Svc discovery
 * (that is what `ktor-client-h3-adaptive` is for). All requests run with `verifyPeer = false`
 * (see [Http3DemoViewModel]) so we don't need to bundle a CA file.
 */
object Endpoints {
    /**
     * nghttp2.org serves httpbin over HTTP/3 (ngtcp2 / nghttp3) — a rich echo / download /
     * streaming API, and a *different* QUIC stack than quiche, so it doubles as interop.
     */
    const val HTTPBIN: String = "https://nghttp2.org/httpbin"

    /** Cloudflare's quiche-backed HTTP/3 test site — the same library Kiche wraps. */
    const val CLOUDFLARE_QUIC: String = "https://cloudflare-quic.com"
}
