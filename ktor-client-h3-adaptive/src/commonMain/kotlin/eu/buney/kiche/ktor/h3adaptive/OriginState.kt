package eu.buney.kiche.ktor.h3adaptive

import io.ktor.util.date.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks per-origin HTTP/3 capability and health state.
 *
 * Lifecycle:
 * 1. Origin starts as [Status.UNKNOWN] — all requests go to TCP engine.
 * 2. TCP response contains `Alt-Svc: h3=...` → state becomes [Status.AVAILABLE].
 * 3. QUIC engine succeeds → state becomes [Status.CONFIRMED].
 * 4. QUIC engine fails → state becomes [Status.BROKEN] with exponential backoff.
 * 5. After backoff expires, state returns to [Status.AVAILABLE] for retry.
 * 6. `Alt-Svc: clear` → state resets to [Status.UNKNOWN].
 */
internal class OriginState(
    val origin: String,
) {
    enum class Status {
        /** No Alt-Svc seen yet. Use TCP engine. */
        UNKNOWN,
        /** Alt-Svc header advertised H3, but not yet attempted. */
        AVAILABLE,
        /** QUIC connection succeeded at least once. */
        CONFIRMED,
        /** QUIC connection failed. Back off before retrying. */
        BROKEN,
    }

    private val mutex = Mutex()

    var status: Status = Status.UNKNOWN
        private set

    /** The Alt-Svc entry that advertised H3, if any. */
    var altSvcEntry: AltSvcEntry? = null
        private set

    /** When the origin was last marked broken (epoch millis). */
    private var brokenAt: Long = 0L

    /** Current backoff duration in millis. Doubles on each consecutive failure. */
    private var backoffMs: Long = INITIAL_BACKOFF_MS

    /** Number of consecutive QUIC failures. */
    var consecutiveFailures: Int = 0
        private set

    /**
     * Whether QUIC should be attempted for this origin right now.
     */
    suspend fun shouldUseQuic(): Boolean = mutex.withLock {
        // RFC 7838: an Alt-Svc advertisement is only valid for `ma` seconds. Once it expires,
        // forget it and fall back to TCP — the next TCP response re-advertises and refreshes it.
        // This is purely in-memory; persisting across restarts (as browsers do) would belong
        // behind an optional, consumer-supplied store, not baked in here.
        if (altSvcEntry?.isExpired() == true) {
            status = Status.UNKNOWN
            altSvcEntry = null
            resetBackoff()
        }
        when (status) {
            Status.UNKNOWN -> false
            Status.AVAILABLE, Status.CONFIRMED -> true
            Status.BROKEN -> {
                val elapsed = GMTDate().timestamp - brokenAt
                if (elapsed >= backoffMs) {
                    // Backoff expired — allow a retry
                    status = Status.AVAILABLE
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Record that an Alt-Svc header was received advertising H3 for this origin.
     */
    suspend fun onAltSvcReceived(entry: AltSvcEntry) = mutex.withLock {
        altSvcEntry = entry
        if (status == Status.UNKNOWN) {
            status = Status.AVAILABLE
        }
    }

    /**
     * Record that the Alt-Svc `clear` directive was received.
     */
    suspend fun onAltSvcCleared() = mutex.withLock {
        status = Status.UNKNOWN
        altSvcEntry = null
        resetBackoff()
    }

    /**
     * Record a successful QUIC request.
     */
    suspend fun onQuicSuccess() = mutex.withLock {
        status = Status.CONFIRMED
        resetBackoff()
    }

    /**
     * Record a failed QUIC request. Marks origin as broken with exponential backoff.
     */
    suspend fun onQuicFailure() = mutex.withLock {
        status = Status.BROKEN
        brokenAt = GMTDate().timestamp
        consecutiveFailures++
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun resetBackoff() {
        consecutiveFailures = 0
        backoffMs = INITIAL_BACKOFF_MS
    }

    internal companion object {
        const val INITIAL_BACKOFF_MS = 5_000L        // 5 seconds
        const val MAX_BACKOFF_MS = 5 * 60 * 1_000L   // 5 minutes
    }
}
