package com.noahwallet.services.wallet

import com.noahwallet.services.storage.WalletDataLocation
import io.github.niteshbalusu11.bark.Network
import io.github.niteshbalusu11.bark.WalletSnapshotInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The fake native handles and data directory every WalletSession test runs against. */
internal abstract class WalletSessionFixture {
    protected class Boom : Exception()
    protected class Handles : WalletSession.Handles {
        var exitsRead = false
        override val m6 = object : com.noahwallet.onchain.OnchainWalletAccess {
            override val walletID = "wallet"
            override suspend fun exits(): List<io.github.niteshbalusu11.bark.ExitVtxo> {
                exitsRead = true; throw io.github.niteshbalusu11.bark.BarkError("offline", io.github.niteshbalusu11.bark.BarkErrorCode.OTHER)
            }
        }
        override val send = com.noahwallet.send.FakeSendWallet()
        var preparations: com.noahwallet.send.SendPreparations? = null
        var beforeRetry: suspend () -> Unit = {}
        override suspend fun retrySendDiscards() { beforeRetry(); preparations?.retryDiscards() }
        var sendReviewsDiscarded = false
        override suspend fun discardSendReviews() { preparations?.discardAll(); sendReviewsDiscarded = true }
        val subscribed = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var holderClosed = false
        var beforeNativeWait: CompletableDeferred<Unit>? = null
        var waitRegistered = false
        val changeWaiting = CompletableDeferred<Unit>()
        val changeReleased = CompletableDeferred<Unit>()
        var changeClosed = false
        override fun stateChanges(): WalletSession.Changes = object : WalletSession.Changes {
            override suspend fun next(): io.github.niteshbalusu11.bark.StateChangeEvent? {
                changeWaiting.complete(Unit); changeReleased.await(); return null
            }
            override fun stop() { changeReleased.complete(Unit) }
            override fun close() { changeClosed = true }
        }
        override fun notifications(): WalletSession.Notifications {
            subscribed.complete(Unit)
            return object : WalletSession.Notifications {
                override suspend fun next(): io.github.niteshbalusu11.bark.WalletNotification? {
                    waiting.complete(Unit)
                    beforeNativeWait?.await()
                    waitRegistered = true
                    released.await(); return null
                }
                override fun cancelWait() {
                    check(!holderClosed)
                    // Real SDK cancellation has no effect before nextNotification registers its wait.
                    if (beforeNativeWait == null || waitRegistered) released.complete(Unit)
                }
                override fun close() { holderClosed = true }
            }
        }
        var historyEntered: CompletableDeferred<Unit>? = null
        var historyGate: CompletableDeferred<Unit>? = null
        var chainRead = false
        override suspend fun history(): List<io.github.niteshbalusu11.bark.Movement> {
            historyEntered?.complete(Unit); historyGate?.await(); assertFalse(closed)
            return emptyList()
        }
        override suspend fun transactions(): List<io.github.niteshbalusu11.bark.WalletTransaction> {
            assertFalse(closed); chainRead = true
            return emptyList()
        }
        override suspend fun receiveRequest() = ReceiveRequest("ark-test", "chain-test")
        override suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String) =
            request.copy(amountSat = amountSat, description = description, lightningInvoice = "invoice", paymentHash = "hash")
        var initialized = false
        var initializationFails = false
        override suspend fun initializeAddress() {
            if (initializationFails) throw Boom()
            initialized = true
        }
        override suspend fun peekPublicKey(index: UInt): String { check(initialized); return "public-key-0" }
        override suspend fun peekAddress(index: UInt) = "ark-test"
        var closed = false
        var stopFails = false
        var stopEntered: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var summaryGate: CompletableDeferred<Unit>? = null
        var summaryEntered: CompletableDeferred<Unit>? = null
        override suspend fun summary(): WalletSession.Summary {
            summaryEntered?.complete(Unit); summaryGate?.await()
            assertFalse(closed)
            return WalletSession.Summary(Network.REGTEST, "abcd",
                WalletBalance(WalletBalance.Ark(1u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u)), 2)
        }
        override suspend fun sync() { assertFalse(closed) }
        override suspend fun maintenanceDelegated() { assertFalse(closed) }
        override suspend fun bolt11Invoice(amountSats: ULong): String { check(!closed); return "invoice-$amountSats" }
        override suspend fun claimLightningReceives() { assertFalse(closed) }
        override suspend fun closestExpiryBlocks(): Long? { check(!closed); return -1L }
        override suspend fun createSnapshot(destination: File): WalletSnapshotInfo = throw UnsupportedOperationException()
        override suspend fun stopDaemonWait() {
            stopEntered?.complete(Unit); stopGate?.await()
            if (stopFails) throw Boom()
        }
        override fun close() { closed = true }
    }
    protected suspend fun withLocation(block: suspend (WalletDataLocation) -> Unit) {
        val root = Files.createTempDirectory("session").toFile()
        try { withTimeout(10_000) { block(WalletDataLocation(root, "regtest")) } }
        finally { root.deleteRecursively() }
    }
    protected fun bark(location: WalletDataLocation) { location.directory.mkdirs(); location.database.writeText("fake") }
    protected fun assertFailure(expected: WalletSession.OpenFailure, result: Result<Unit>) {
        assertEquals(expected, (result.exceptionOrNull() as? WalletSession.OpenError)?.reason)
    }
}
