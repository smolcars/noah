package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SendUnresolvedOverrideTest {
    @Test fun unresolvedReceiptsRequireExplicitAuditedOverrideAcrossRestart() = runBlocking {
        for (status in listOf(SendReceipt.Status.submitted, SendReceipt.Status.unlocated)) {
            val journal = TestJournal(); val wallet = FakeSendWallet()
            journal.attempt = SendAttempt(walletIdentity = wallet.owner, payment = chainPayment, quote = chainQuote,
                receipt = SendReceipt(1000, txid = "exact", status = status, proof = SendReceipt.Proof.preparedTxid, broadcastRetryPending = true))
            repeat(2) {
                val subject = controller(wallet, journal, this); subject.reloadForWalletChange()
                subject.acknowledge(); subject.submit(chainPayment, chainQuote)
                assertTrue(subject.locked); assertTrue(subject.canOverride)
                subject.startNewPaymentAfterHistoryCheck(false); assertTrue(subject.locked); assertTrue(journal.audits.isEmpty())
            }
            val subject = controller(wallet, journal, this); subject.reloadForWalletChange()
            assertTrue(subject.overrideWarning.contains("does not cancel Bark’s stored transaction or its future broadcast retries"))
            assertTrue(subject.overrideWarning.contains("spend twice"))
            subject.startNewPaymentAfterHistoryCheck(true)
            assertFalse(subject.locked); assertNull(journal.attempt); assertEquals("exact", journal.audits.single().txid)
            assertEquals(0, wallet.calls); assertEquals(0, wallet.prepareCalls)
        }
    }
    @Test fun unreadableJournalIsPreservedAndCannotUnlockOnAnyStorageFailure() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("send-corrupt").toFile()
        try {
            for (failure in listOf("quarantine", "audit", "remove", "none")) {
                val file = java.io.File(directory, "$failure.json").apply { writeBytes(byteArrayOf(0, -1, 42)) }
                val journal = SendJournal(file) { }
                var audit: SendOverrideAudit? = null
                val subject = SendController(SendController.Dependencies(
                    read = { error("unreadable") }, write = {
                        assertNotNull(audit)
                        if (failure == "remove") error("remove")
                        check(file.delete())
                    }, quarantineUnreadable = {
                        if (failure == "quarantine") error("quarantine")
                        journal.quarantineUnreadable()
                    }, auditOverride = {
                        assertArrayEquals(file.readBytes(), directory.listFiles()!!.first { it.name.startsWith(file.name + ".unreadable-") }.readBytes())
                        if (failure == "audit") error("audit")
                        audit = it
                    }) { it(FakeSendWallet()) }, this)
                subject.reloadForWalletChange(); subject.acknowledge()
                assertTrue(subject.locked); assertTrue(subject.canOverride)
                subject.startNewPaymentAfterHistoryCheck(false); assertTrue(file.exists()); assertNull(audit)
                subject.startNewPaymentAfterHistoryCheck(true)
                assertEquals(failure, failure != "none", subject.locked)
                assertEquals(failure != "none", file.exists())
                if (failure != "none") { subject.reloadForWalletChange(); assertTrue(subject.locked) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun acceptedReceiptsAndInFlightSubmissionKeepOrdinaryLocks() = runBlocking {
        val journal = TestJournal(); val wallet = FakeSendWallet()
        journal.attempt = SendAttempt(walletIdentity = wallet.owner, payment = chainPayment,
            receipt = SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.preparedTxid))
        val subject = controller(wallet, journal, this); subject.reloadForWalletChange()
        assertFalse(subject.canOverride); subject.startNewPaymentAfterHistoryCheck(true); assertTrue(subject.locked)
        subject.acknowledge(); assertFalse(subject.locked)
        val gate = CompletableDeferred<Unit>()
        wallet.payBlock = { gate.await(); SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
        subject.submit(arkPayment, FakeSendWallet.quote); yield()
        assertFalse(subject.canOverride); subject.startNewPaymentAfterHistoryCheck(true); assertTrue(subject.locked)
        gate.complete(Unit); coroutineContext.job.children.toList().joinAll()
    }

}
