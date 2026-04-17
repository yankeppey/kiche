package eu.buney.kiche.ktor

import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.network.selector.*
import io.ktor.util.*
import io.ktor.util.collections.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Ktor HTTP client engine backed by Cloudflare quiche (QUIC + HTTP/3).
 *
 * Maintains a pool of [KicheEndpoint]s — one per host:port. Each endpoint owns
 * a long-lived QUIC connection and multiplexes requests as independent H3 streams.
 */
@OptIn(InternalAPI::class)
public class KicheEngine(override val config: KicheEngineConfig) : HttpClientEngineBase("ktor-kiche") {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> =
        setOf(HttpTimeoutCapability)

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
                requestJob.join()
            } finally {
                selector.close()
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

        return endpoint.execute(data, callContext, requestTime)
    }

    override fun close() {
        super.close()
        endpoints.forEach { (_, endpoint) -> endpoint.close() }
        // Completing requestsJob triggers the deferred selector closure set up in init.
        (requestsJob[Job] as CompletableJob).complete()
    }
}
