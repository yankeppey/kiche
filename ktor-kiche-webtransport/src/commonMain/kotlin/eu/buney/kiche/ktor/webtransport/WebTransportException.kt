package eu.buney.kiche.ktor.webtransport

/**
 * Exception thrown when a WebTransport operation fails.
 */
public class WebTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
