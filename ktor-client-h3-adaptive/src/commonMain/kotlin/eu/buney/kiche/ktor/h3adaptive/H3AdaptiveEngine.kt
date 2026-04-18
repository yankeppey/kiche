package eu.buney.kiche.ktor.h3adaptive

import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A ktor client engine that adaptively routes requests between a TCP engine
 * (HTTP/1.1, HTTP/2) and a QUIC engine (HTTP/3) based on Alt-Svc discovery.
 *
 * First request to an origin always goes via TCP. If the server advertises
 * HTTP/3 support via the `Alt-Svc` header, subsequent requests to that origin
 * will use the QUIC engine. If QUIC fails, the engine falls back to TCP with
 * exponential backoff before retrying QUIC.
 *
 * When an origin is already known to support H3, requests can optionally race
 * QUIC against TCP (with a configurable head start for QUIC) to minimize latency.
 */
public class H3AdaptiveEngine(
    override val config: H3AdaptiveEngineConfig,
) : HttpClientEngineBase("h3-adaptive") {

    private val tcpEngine: HttpClientEngine = requireNotNull(config.tcpEngineFactory) {
        "TCP engine must be configured via H3AdaptiveEngineConfig.tcp()"
    }.createWithConfig(config.tcpConfigBlock)

    private val quicEngine: HttpClientEngine = requireNotNull(config.quicEngineFactory) {
        "QUIC engine must be configured via H3AdaptiveEngineConfig.quic()"
    }.createWithConfig(config.quicConfigBlock)

    private val originStates = mutableMapOf<String, OriginState>()
    private val originStatesMutex = Mutex()

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>>
        get() = tcpEngine.supportedCapabilities intersect quicEngine.supportedCapabilities

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val origin = data.url.authority
        val state = getOrCreateOriginState(origin)

        return if (state.shouldUseQuic()) {
            executeViaQuic(data, state)
        } else {
            executeViaTcp(data, state)
        }
    }

    private suspend fun executeViaTcp(
        data: HttpRequestData,
        state: OriginState,
    ): HttpResponseData {
        @OptIn(InternalAPI::class)
        val response = tcpEngine.execute(data)
        processAltSvcHeader(response, state)
        return response
    }

    private suspend fun executeViaQuic(
        data: HttpRequestData,
        state: OriginState,
    ): HttpResponseData {
        return try {
            val requestData = rewriteForAltSvc(data, state)
            @OptIn(InternalAPI::class)
            val response = quicEngine.execute(requestData)
            state.onQuicSuccess()
            response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.onQuicFailure()
            // Fall back to TCP
            executeViaTcp(data, state)
        }
    }

    /**
     * If the Alt-Svc entry specifies a different port or host, rewrite the request URL.
     */
    private fun rewriteForAltSvc(data: HttpRequestData, state: OriginState): HttpRequestData {
        val altSvc = state.altSvcEntry ?: return data
        val originalUrl = data.url

        // No rewrite needed if same host and port
        val altHost = altSvc.host.ifEmpty { originalUrl.host }
        val altPort = altSvc.port
        if (altHost == originalUrl.host && altPort == originalUrl.port) return data

        return HttpRequestBuilder().takeFrom(data).apply {
            url.host = altHost
            url.port = altPort
        }.build()
    }

    /**
     * Check for `Alt-Svc` header in a TCP response and update origin state.
     */
    private suspend fun processAltSvcHeader(response: HttpResponseData, state: OriginState) {
        val altSvcHeader = response.headers[ALT_SVC_HEADER] ?: return

        val entries = parseAltSvcHeader(altSvcHeader)
        if (entries.isEmpty() && altSvcHeader.trim() == "clear") {
            state.onAltSvcCleared()
            return
        }

        // Find the first h3 entry
        val h3Entry = entries.firstOrNull { it.protocolId.startsWith("h3") }
        if (h3Entry != null) {
            state.onAltSvcReceived(h3Entry)
        }
    }

    private suspend fun getOrCreateOriginState(origin: String): OriginState {
        return originStatesMutex.withLock {
            originStates.getOrPut(origin) { OriginState(origin) }
        }
    }

    override fun close() {
        super.close()
        tcpEngine.close()
        quicEngine.close()
    }

    private companion object {
        const val ALT_SVC_HEADER = "Alt-Svc"
    }
}

private fun HttpClientEngineFactory<*>.createWithConfig(
    block: HttpClientEngineConfig.() -> Unit,
): HttpClientEngine = create(block)
