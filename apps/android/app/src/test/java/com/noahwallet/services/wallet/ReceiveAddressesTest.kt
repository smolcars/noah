package com.noahwallet.services.wallet

import com.noahwallet.send.PaymentEncoding
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Receive reuses each address until it's paid, and never an Ark key this database didn't derive. */
internal class ReceiveAddressesTest : WalletSessionFixture() {
    private fun paying(address: String, sats: Long): String {
        val script = PaymentEncoding.bitcoinScript(address)!!
        val value = ByteArray(8) { i -> (sats ushr (i * 8)).toByte() }
        return "0200000001" + "00".repeat(36) + "00ffffffff01" + PaymentEncoding.hex(value) +
            "%02x".format(script.size) + PaymentEncoding.hex(script) + "00000000"
    }
    private fun receivedOn(address: String, status: String) = Movement(1u, status, "bark.arkoor", "receive", "{}", 500, 500, 0u,
        emptyList(), listOf(MovementDestination(PaymentMethod("ark", address), 500u)), emptyList(), emptyList(), emptyList(),
        "now", "now", null, null, null, null)

    @Test fun eachAddressIsReusedUntilPaid() = runBlocking {
        withLocation { location ->
            bark(location)
            val handles = Handles()
            val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _ -> handles })
            session.openExisting("unused", "test")
            val first = session.receiveRequest()
            assertEquals(first, session.receiveRequest())
            // Paid on-chain: only that address moves on.
            handles.chainTransactions = listOf(WalletTransaction("paid", paying(first.onchainAddress, 1_000), null, 1_000, null, false))
            val second = session.receiveRequest()
            assertEquals(first.arkAddress, second.arkAddress)
            assertNotEquals(first.onchainAddress, second.onchainAddress)
            // Any movement received on the Ark address, even one still pending, moves it on.
            handles.movements = listOf(receivedOn(second.arkAddress, "pending"))
            val third = session.receiveRequest()
            assertNotEquals(second.arkAddress, third.arkAddress)
            assertEquals(second.onchainAddress, third.onchainAddress)
            // A database that never derived the saved key: a new one.
            handles.arkDerived = 0u
            assertNotEquals(third.arkAddress, session.receiveRequest().arkAddress)
            session.close()
        }
    }
}
