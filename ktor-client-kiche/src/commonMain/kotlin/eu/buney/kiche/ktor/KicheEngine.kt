package eu.buney.kiche.ktor

import eu.buney.kiche.ktor.webtransport.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.util.*
import io.ktor.util.collections.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

private fun log(msg: String) {
    val t = System.currentTimeMillis() % 100_000
    val thread = Thread.currentThread().name.takeLast(30)
    println("[KICHE $t $thread] $msg")
}

/**
 * Ktor HTTP client engine backed by Cloudflare quiche (QUIC + HTTP/3).
 *
 * Maintains a pool of [KicheEndpoint]s — one per host:port. Each endpoint owns
 * a long-lived QUIC connection and multiplexes requests as independent H3 streams.
 */
@OptIn(InternalAPI::class)
public class KicheEngine(override val config: KicheEngineConfig) : HttpClientEngineBase("ktor-kiche"),
    KicheWebTransportEngine {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
        setOf(HttpTimeoutCapability, WebTransportCapability)

    private val requestsJob: CoroutineContext

    override val coroutineContext: CoroutineContext

    private val selectorManager = SelectorManager(Dispatchers.Default)

    private val endpoints = ConcurrentMap<String, KicheEndpoint>()

    init {
        val parentContext = super.coroutineContext
        val parent = parentContext[Job]!!

        requestsJob = SilentSupervisor(parent)
        coroutineContext = parentContext + requestsJob

        // Defer selector closure until all endpoint work completes — same pattern as CIO.
        // When close() completes requestsJob, the join() below unblocks and the selector
        // is closed in the finally block, ensuring no I/O is pulled from under active endpoints.
        val requestJob = requestsJob[Job]!!
        val selector = selectorManager
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(parentContext, start = CoroutineStart.ATOMIC) {
            try {
                log("engine: deferred selector closure waiting for requestJob")
                requestJob.join()
                log("engine: requestJob completed")
            } finally {
                log("engine: closing selector")
                selector.close()
                log("engine: selector closed")
            }
        }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        val requestTime = GMTDate()

        val host = data.url.host
        val port = data.url.port.takeIf { it != 0 } ?: KicheEndpoint.DEFAULT_HTTPS_PORT
        val endpointId = "$host:$port"

        val endpoint = endpoints.computeIfAbsent(endpointId) {
            KicheEndpoint(
                host = host,
                port = port,
                config = config,
                selectorManager = selectorManager,
                parentContext = coroutineContext,
                onDone = { endpoints.remove(endpointId) },
            )
        }

        log("engine.execute: ${data.method.value} ${data.url} → endpoint $endpointId")
        return endpoint.execute(data, callContext, requestTime)
    }

    override suspend fun openWebTransportSession(url: Url): WebTransportSession {
        val host = url.host
        val port = url.port.takeIf { it != 0 } ?: KicheEndpoint.DEFAULT_HTTPS_PORT
        val endpointId = "wt:$host:$port"

        val endpoint = endpoints.computeIfAbsent(endpointId) {
            KicheEndpoint(
                host = host,
                port = port,
                config = config,
                selectorManager = selectorManager,
                parentContext = coroutineContext,
                onDone = { endpoints.remove(endpointId) },
            )
        }

        log("engine.openWebTransportSession: $url → endpoint $endpointId")
        return endpoint.openWebTransportSession(url)
    }

    override fun close() {
        log("engine.close() enter, endpoints=${endpoints.size}")
        super.close()
        endpoints.forEach { (id, endpoint) ->
            log("engine.close() closing endpoint $id")
            endpoint.close()
        }
        log("engine.close() completing requestsJob")
        (requestsJob[Job] as CompletableJob).complete()
        log("engine.close() done")
    }
}
