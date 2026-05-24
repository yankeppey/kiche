package eu.buney.kiche.example

import eu.buney.kiche.ktor.h3adaptive.H3AdaptiveEngineConfig
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun H3AdaptiveEngineConfig.installTcpEngine() {
    tcp(OkHttp)
}
