package eu.buney.kiche

/**
 * Exception thrown by HTTP/3 operations ([KicheH3Connection]).
 *
 * quiche's C API uses a separate error code space for H3 functions
 * (`QUICHE_H3_ERR_*`, range -1..-20) vs QUIC transport functions
 * (`QUICHE_ERR_*`, range -1..-23). When a QUIC transport error bubbles
 * up through an H3 function, quiche shifts it by -1000 to avoid collision
 * (see `h3::Error::to_c()` in quiche).
 *
 * This exception carries either an [h3Error] (H3-native) or a
 * [transportError] (QUIC transport error surfaced through the H3 layer).
 */
class KicheH3Exception(
    val h3Error: KicheH3Error? = null,
    val transportError: KicheError? = null,
    message: String,
) : Exception(message) {

    /** True for errors that indicate temporary backpressure (retry after draining). */
    val isRetryable: Boolean
        get() = h3Error?.isRetryable == true || transportError?.isRetryable == true

    companion object {
        /**
         * Checks an H3 return code and throws [KicheH3Exception] on error.
         * Silently ignores `Done` (-1), same as [KicheException.check].
         */
        @JvmStatic
        fun check(code: Int) {
            if (code < 0) {
                // Try H3-native error codes first (-1..-20)
                val h3 = KicheH3Error.fromCode(code)
                if (h3 != null) {
                    if (h3 != KicheH3Error.Done) {
                        throw KicheH3Exception(h3Error = h3, message = "H3 error: ${h3.name} (${h3.code})")
                    }
                    return
                }
                // quiche shifts QUIC transport errors by -1000 in the H3 C API
                // (see quiche/src/h3/mod.rs Error::to_c → TransportError offset)
                val transport = KicheError.fromCode(code + 1000)
                if (transport != null) {
                    throw KicheH3Exception(
                        transportError = transport,
                        message = "H3 transport error: ${transport.name} (${transport.code})",
                    )
                }
                throw KicheH3Exception(message = "Unknown H3 error code: $code")
            }
        }
    }
}
