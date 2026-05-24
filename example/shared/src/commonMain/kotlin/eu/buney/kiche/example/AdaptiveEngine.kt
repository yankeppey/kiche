package eu.buney.kiche.example

import eu.buney.kiche.ktor.h3adaptive.H3AdaptiveEngineConfig

/**
 * Installs the platform's TCP engine (HTTP/1.1 + HTTP/2) as the adaptive engine's TCP leg:
 * OkHttp on JVM/Android, Darwin (NSURLSession) on iOS. The QUIC/HTTP-3 leg is always Kiche.
 *
 * The adaptive engine ([eu.buney.kiche.ktor.h3adaptive.H3Adaptive]) sends the first request to an
 * origin over this TCP engine, reads its `Alt-Svc` header, and routes later requests to HTTP/3.
 */
internal expect fun H3AdaptiveEngineConfig.installTcpEngine()
