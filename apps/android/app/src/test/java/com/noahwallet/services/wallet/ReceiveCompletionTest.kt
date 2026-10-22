package com.noahwallet.services.wallet

import io.github.niteshbalusu11.bark.*
import org.junit.Assert.*
import org.junit.Test

class ReceiveCompletionTest {
    private val request = ReceiveRequest("ark-mine", "chain-mine", 500, lightningInvoice = "invoice", paymentHash = "hash")
    private fun movement(subsystem: String = "bark.arkoor", status: String = "successful", kind: String = "receive",
        destinations: List<MovementDestination> = listOf(destination("ark-mine", 200u), destination("ark-mine", 300u)),
        hash: String? = null) = Movement(1u, status, subsystem, kind, "{}", 500, 500, 0u,
            emptyList(), destinations, emptyList(), emptyList(), emptyList(), "now", "now", null, hash, null, null)
    private fun destination(address: String, sats: ULong) = MovementDestination(PaymentMethod("ark", address), sats)

    @Test fun sumsOnlyMatchingArkDestinations() {
        val event = movement(destinations = listOf(destination("other", 800u), destination("ark-mine", 200u), destination("ark-mine", 300u)))
        assertEquals(ReceiveCompletion.Received(500, ReceiveCompletion.Rail.ark), ReceiveCompletion.matching(event, request))
        assertNull(ReceiveCompletion.matching(movement(destinations = listOf(destination("other", 500u))), request))
        assertNull(ReceiveCompletion.matching(movement(status = "pending"), request))
        assertNull(ReceiveCompletion.matching(movement(kind = "send"), request))
    }
    @Test fun lightningMustMatchHashAndTerminalStatus() {
        assertNull(ReceiveCompletion.matching(movement("bark.lightning_receive", hash = "other"), request))
        assertNull(ReceiveCompletion.matching(movement("bark.lightning_receive", "pending", hash = "hash"), request))
        assertEquals(ReceiveCompletion.Received(500, ReceiveCompletion.Rail.lightning), ReceiveCompletion.matching(movement("bark.lightning_receive", hash = "hash"), request))
        assertEquals(ReceiveCompletion.LightningFailed(false), ReceiveCompletion.matching(movement("bark.lightning_receive", "failed", hash = "hash"), request))
        assertEquals(ReceiveCompletion.LightningFailed(true), ReceiveCompletion.matching(movement("bark.lightning_receive", "canceled", hash = "hash"), request))
    }
    @Test fun avoidsUnsignedOverflowAndValidatesUtf16Description() {
        assertNull(ReceiveCompletion.matching(movement(destinations = listOf(destination("ark-mine", ULong.MAX_VALUE))), request))
        ReceiveRequest.validate(1, "x".repeat(639))
        assertTrue(runCatching { ReceiveRequest.validate(1, "x".repeat(640)) }.isFailure)
        assertTrue(runCatching { ReceiveRequest.validate(1, "🚀".repeat(320)) }.isFailure)
        assertTrue(runCatching { ReceiveRequest("ark", "chain", 1).uri() }.isFailure)
    }
}
