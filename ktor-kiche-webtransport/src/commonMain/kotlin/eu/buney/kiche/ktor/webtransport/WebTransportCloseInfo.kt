package eu.buney.kiche.ktor.webtransport

/**
 * Information provided when closing a WebTransport session.
 *
 * @property code Application-defined close code (32-bit unsigned).
 * @property reason Human-readable close reason (UTF-8, max 1024 bytes).
 */
public data class WebTransportCloseInfo(
    val code: UInt,
    val reason: String,
)
