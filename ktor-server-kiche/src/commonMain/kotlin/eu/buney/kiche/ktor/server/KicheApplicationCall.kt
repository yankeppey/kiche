package eu.buney.kiche.ktor.server

import eu.buney.kiche.KicheAddress
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Represents a single HTTP/3 request/response pair within the Ktor pipeline.
 */
internal class KicheApplicationCall(
    application: Application,
    method: String,
    path: String,
    h3Headers: List<Pair<String, String>>,
    requestBody: ByteArray,
    remoteAddress: KicheAddress,
    localAddress: KicheAddress,
    override val coroutineContext: CoroutineContext = Dispatchers.Default,
) : BaseApplicationCall(application), CoroutineScope {

    override val request: KicheApplicationRequest = KicheApplicationRequest(
        call = this,
        method = method,
        path = path,
        h3Headers = h3Headers,
        body = requestBody,
        remoteAddress = remoteAddress,
        localAddress = localAddress,
    )

    override val response: KicheApplicationResponse = KicheApplicationResponse(this)

    init {
        putResponseAttribute()
    }

    /** Collect the final response data after the pipeline has executed. */
    fun responseData(): ResponseData = response.toResponseData()

    internal class ResponseData(
        val statusCode: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )
}

/**
 * Ktor request backed by H3 pseudo-headers and regular headers.
 */
internal class KicheApplicationRequest(
    call: KicheApplicationCall,
    private val method: String,
    private val path: String,
    h3Headers: List<Pair<String, String>>,
    body: ByteArray,
    private val remoteAddress: KicheAddress,
    private val localAddress: KicheAddress,
) : BaseApplicationRequest(call) {

    override val engineReceiveChannel: ByteReadChannel = ByteReadChannel(body)

    override val engineHeaders: Headers = Headers.build {
        for ((name, value) in h3Headers) {
            if (!name.startsWith(":")) {
                append(name, value)
            }
        }
    }

    override val cookies: RequestCookies by lazy { RequestCookies(this) }

    override val queryParameters: Parameters by lazy {
        val queryString = path.substringAfter("?", "")
        if (queryString.isEmpty()) Parameters.Empty
        else parseQueryString(queryString)
    }

    override val rawQueryParameters: Parameters by lazy {
        val queryString = path.substringAfter("?", "")
        if (queryString.isEmpty()) Parameters.Empty
        else parseQueryString(queryString, decode = false)
    }

    private val scheme: String = h3Headers.firstOrNull { it.first == ":scheme" }?.second ?: "https"
    private val authority: String? = h3Headers.firstOrNull { it.first == ":authority" }?.second

    override val local: RequestConnectionPoint = object : RequestConnectionPoint {
        override val uri: String = path
        override val method: HttpMethod = HttpMethod.parse(this@KicheApplicationRequest.method)
        override val scheme: String = this@KicheApplicationRequest.scheme
        override val version: String = "HTTP/3"

        private val localAddrStr = formatAddress(this@KicheApplicationRequest.localAddress)
        private val remoteAddrStr = formatAddress(this@KicheApplicationRequest.remoteAddress)

        override val localPort: Int get() = this@KicheApplicationRequest.localAddress.port
        override val serverPort: Int get() = authority?.substringAfterLast(":", localPort.toString())?.toIntOrNull() ?: localPort
        override val localHost: String get() = localAddrStr
        override val serverHost: String get() = authority?.substringBeforeLast(":") ?: localHost
        override val localAddress: String get() = localAddrStr

        override val remotePort: Int get() = this@KicheApplicationRequest.remoteAddress.port
        override val remoteHost: String get() = remoteAddrStr
        override val remoteAddress: String get() = remoteAddrStr

        @Deprecated("Use localPort or serverPort instead")
        override val host: String get() = serverHost
        @Deprecated("Use localPort or serverPort instead")
        override val port: Int get() = serverPort
    }

    companion object {
        private fun formatAddress(addr: KicheAddress): String {
            return if (addr.isIpv6) {
                addr.ip.toList().chunked(2).joinToString(":") { (hi, lo) ->
                    val v = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
                    v.toString(16)
                }
            } else {
                addr.ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
        }
    }
}

/**
 * Ktor response that buffers status, headers, and body for later H3 serialization.
 */
internal class KicheApplicationResponse(
    call: KicheApplicationCall,
) : BaseApplicationResponse(call) {

    private var statusCode: HttpStatusCode = HttpStatusCode.OK
    private val headersBuilder = HeadersBuilder()
    private var bodyBytes: ByteArray = ByteArray(0)

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        override fun engineAppendHeader(name: String, value: String) {
            headersBuilder.append(name, value)
        }

        override fun getEngineHeaderNames(): List<String> {
            return headersBuilder.names().toList()
        }

        override fun getEngineHeaderValues(name: String): List<String> {
            return headersBuilder.getAll(name).orEmpty()
        }
    }

    override fun setStatus(statusCode: HttpStatusCode) {
        this.statusCode = statusCode
    }

    override suspend fun responseChannel(): ByteWriteChannel {
        // Return a channel that collects all written bytes
        val channel = ByteChannel(true)
        // Launch a coroutine to collect bytes when the channel is closed
        // For now, this is a simplification — we collect synchronously after pipeline
        return channel
    }

    override suspend fun respondUpgrade(upgrade: io.ktor.http.content.OutgoingContent.ProtocolUpgrade) {
        throw UnsupportedOperationException("Protocol upgrade is not supported over HTTP/3")
    }

    override suspend fun respondFromBytes(bytes: ByteArray) {
        bodyBytes = bytes
    }

    override suspend fun respondNoContent(content: io.ktor.http.content.OutgoingContent.NoContent) {
        bodyBytes = ByteArray(0)
    }

    override suspend fun respondOutgoingContent(content: io.ktor.http.content.OutgoingContent) {
        super.respondOutgoingContent(content)
    }

    fun toResponseData(): KicheApplicationCall.ResponseData {
        val headersList = mutableListOf<Pair<String, String>>()
        for (name in headersBuilder.names()) {
            for (value in headersBuilder.getAll(name)!!) {
                headersList.add(name to value)
            }
        }
        return KicheApplicationCall.ResponseData(
            statusCode = statusCode.value,
            headers = headersList,
            body = bodyBytes,
        )
    }
}
