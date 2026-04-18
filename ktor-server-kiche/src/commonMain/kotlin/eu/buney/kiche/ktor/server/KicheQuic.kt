package eu.buney.kiche.ktor.server

import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*

/**
 * An [ApplicationEngineFactory] providing a QUIC/HTTP3-based [ApplicationEngine]
 * backed by Cloudflare quiche via Kiche bindings.
 *
 * Usage:
 * ```kotlin
 * embeddedServer(KicheQuic, port = 4433) {
 *     routing {
 *         get("/hello") { call.respondText("hello") }
 *     }
 * }.start(wait = true)
 * ```
 */
public object KicheQuic : ApplicationEngineFactory<KicheApplicationEngine, KicheApplicationEngine.Configuration> {

    override fun configuration(
        configure: KicheApplicationEngine.Configuration.() -> Unit,
    ): KicheApplicationEngine.Configuration {
        return KicheApplicationEngine.Configuration().apply(configure)
    }

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: KicheApplicationEngine.Configuration,
        applicationProvider: () -> Application,
    ): KicheApplicationEngine {
        return KicheApplicationEngine(environment, monitor, developmentMode, configuration, applicationProvider)
    }
}
