package com.noahwallet.send

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SendOverrideAuditTest {
    @Test fun confirmationMustBeDurableBeforeRemovingLegacyEvidence() = runBlocking {
        val directory = Files.createTempDirectory("send-audit").toFile()
        try {
            val file = File(directory, "overrides.json")
            var saved: SendAttempt? = SendAttempt(payment = chainPayment, broadcastingTxid = "legacy-txid",
                possibleTxids = listOf("possible"), candidates = listOf(7u), quote = chainQuote)
            val original = saved
            val corrupt = "unreadable audit bytes".toByteArray()
            file.writeBytes(corrupt)
            val store = SendOverrideAuditStore(file) { }
            val wallet = FakeSendWallet()
            val subject = SendController(SendController.Dependencies({ saved }, { value ->
                if (value == null) {
                    val audit = Json.decodeFromString<List<SendOverrideAudit>>(file.readText()).last()
                    assertEquals(original, audit.originalEvidence)
                    assertEquals("legacy-txid", audit.txid); assertTrue(audit.ownershipUnverified)
                    assertNull(audit.walletIdentity); assertEquals(listOf(7u), audit.candidates)
                    assertEquals(listOf("possible"), audit.possibleTxids)
                    assertArrayEquals(corrupt, directory.listFiles()!!.single { ".unreadable-" in it.name }.readBytes())
                }
                saved = value
            }, auditOverride = store::append) { it(wallet) }, this)
            subject.reloadForWalletChange()
            subject.startNewPaymentAfterHistoryCheck(false)
            assertTrue(subject.locked); assertArrayEquals(corrupt, file.readBytes())
            subject.startNewPaymentAfterHistoryCheck(true)
            assertFalse("Confirmed override must save audit before removal", subject.locked)
            assertNotNull(original)
            val audit = Json.decodeFromString<List<SendOverrideAudit>>(file.readText()).single()
            assertTrue(audit.overriddenAt <= System.currentTimeMillis())
            assertEquals(SendOverrideAudit.warningText, audit.warning)
            assertEquals(SendOverrideAudit.titleText, audit.title)
            assertEquals(SendOverrideAudit.confirmationText, audit.confirmation)
            assertEquals("uncertain", audit.status)
            subject.reloadForWalletChange(); assertFalse(subject.locked)
            saved = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment)
            assertEquals(audit, Json.decodeFromString<List<SendOverrideAudit>>(file.readText()).single())
        } finally { directory.deleteRecursively() }
    }
    @Test fun auditQuarantineWriteAndRemovalFailuresKeepLockedAcrossRestart() = runBlocking {
        val directory = Files.createTempDirectory("send-audit-failure").toFile()
        try {
            for (failure in listOf("quarantine", "write", "sync", "remove")) {
                val file = when (failure) {
                    "quarantine" -> File(directory, "a".repeat(240)).apply { writeText("corrupt") }
                    "write" -> File(directory, "missing/audit.json")
                    else -> File(directory, "$failure.json")
                }
                val store = SendOverrideAuditStore(file) { if (failure == "sync") error("fsync") }
                val original = SendAttempt(payment = chainPayment, broadcastingTxid = "original")
                var saved: SendAttempt? = original
                repeat(2) {
                    val subject = SendController(SendController.Dependencies({ saved }, {
                        if (failure == "remove") error("removal")
                        saved = it
                    }, auditOverride = store::append) { it(FakeSendWallet()) }, this)
                    subject.reloadForWalletChange(); subject.startNewPaymentAfterHistoryCheck(true)
                    assertTrue(failure, subject.locked); assertEquals(original, saved)
                    if (failure == "quarantine") assertEquals("corrupt", file.readText())
                    assertTrue(subject.message!!.startsWith(if (failure == "remove") "Unable to save payment status" else "Unable to save the override confirmation"))
                }
            }
        } finally { directory.deleteRecursively() }
    }

}
