package eu.buney.kiche.ktor.h3adaptive

import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginStateTest {

    @Test
    fun `unknown origin does not use quic`() = runTest {
        val state = OriginState("example.com:443")
        assertFalse(state.shouldUseQuic())
    }

    @Test
    fun `available after alt-svc received`() = runTest {
        val state = OriginState("example.com:443")
        state.onAltSvcReceived(altSvcEntry())
        assertTrue(state.shouldUseQuic())
    }

    @Test
    fun `confirmed after quic success`() = runTest {
        val state = OriginState("example.com:443")
        state.onAltSvcReceived(altSvcEntry())
        state.onQuicSuccess()
        assertTrue(state.shouldUseQuic())
        assertTrue(state.status == OriginState.Status.CONFIRMED)
    }

    @Test
    fun `broken after quic failure`() = runTest {
        val state = OriginState("example.com:443")
        state.onAltSvcReceived(altSvcEntry())
        state.onQuicFailure()
        // Immediately after failure, backoff is active
        assertFalse(state.shouldUseQuic())
        assertTrue(state.consecutiveFailures == 1)
    }

    @Test
    fun `clear resets to unknown`() = runTest {
        val state = OriginState("example.com:443")
        state.onAltSvcReceived(altSvcEntry())
        state.onQuicSuccess()
        state.onAltSvcCleared()
        assertFalse(state.shouldUseQuic())
        assertTrue(state.status == OriginState.Status.UNKNOWN)
    }

    @Test
    fun `expired alt-svc reverts a confirmed origin to unknown`() = runTest {
        val state = OriginState("example.com:443")
        state.onAltSvcReceived(altSvcEntry())
        state.onQuicSuccess()
        assertTrue(state.shouldUseQuic()) // CONFIRMED → uses QUIC

        // The advertisement's max-age elapses (received in the past, short ma).
        state.onAltSvcReceived(expiredAltSvcEntry())
        assertFalse(state.shouldUseQuic()) // stale → forgotten, back to TCP
        assertTrue(state.status == OriginState.Status.UNKNOWN)
    }

    private fun altSvcEntry() = AltSvcEntry(
        protocolId = "h3",
        host = "",
        port = 443,
        maxAge = 86400L,
    )

    private fun expiredAltSvcEntry() = AltSvcEntry(
        protocolId = "h3",
        host = "",
        port = 443,
        maxAge = 60L,
        receivedAt = GMTDate().timestamp - 120_000L, // received 120 s ago, ma=60 s → expired
    )
}
