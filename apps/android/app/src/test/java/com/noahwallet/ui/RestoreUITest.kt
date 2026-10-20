package com.noahwallet.ui

import com.noahwallet.services.backup.*
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.server.ServerFailure
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.wallet.WalletSession
import com.noahwallet.fixtures.SharedFixtures
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryPhraseInputTest {
    private val phrase = List(11) { "abandon" }.plus("about").joinToString(" ")
    @Test fun normalizesWholePhrasePasteIntoAnyField() {
        repeat(12) { index ->
            val input = RecoveryPhraseInput()
            input.update(" \n${phrase.uppercase().replace(" ", "\t\n")} ", index)
            assertTrue(input.phrase == phrase); assertEquals(12, input.wordCount)
            assertTrue(input.isComplete); assertTrue(RecoveryPhraseInput.isValid(input.phrase))
            assertFalse(input.toString().contains("abandon"))
        }
    }
    @Test fun rejectsPartialAndOversizedPastesWithoutReplacingEntries() {
        val input = RecoveryPhraseInput()
        input.update(" ABOUT ", 11)
        for (paste in listOf("$phrase abandon", "abandon about")) {
            input.update(paste, 0)
            assertTrue(input.wrongWordCount); assertEquals(1, input.wordCount); assertEquals("about", input.words[11])
        }
        input.update(phrase, 0); assertTrue(input.isComplete)
        input.update("", 4); assertFalse(input.isComplete); assertEquals(11, input.wordCount)
        input.clear(); assertEquals(0, input.wordCount); assertFalse(input.wrongWordCount)
    }
    @Test fun localSuggestionsAndDictionaryIntegrity() {
        assertEquals(2048, RecoveryPhraseInput.wordList.size)
        assertEquals(2048, RecoveryPhraseInput.wordList.toSet().size)
        val input = RecoveryPhraseInput()
        assertTrue(input.suggestions(0).isEmpty())
        input.update("aban", 0); assertEquals(listOf("abandon"), input.suggestions(0))
        input.update("abandon", 0); assertTrue(input.suggestions(0).isEmpty())
        input.update("a", 0); assertEquals(5, input.suggestions(0).size)
        input.update("zzzz", 0); assertTrue(input.suggestions(0).isEmpty())
    }
    @Test fun sharedWordCountDictionaryChecksumAndNormalizationVectors() {
        for (case in SharedFixtures.load("restore-phrase")) {
            assertEquals(case.name, case.expected.jsonPrimitive.boolean,
                RecoveryPhraseInput.isValid(case.input.jsonPrimitive.content))
        }
        assertEquals(listOf("about", "abandon"), RecoveryPhraseInput.normalizedWords("ＡＢＯＵＴ\u00a0abandon"))
    }
}

class RestoreIssueTest {
    @Test fun everyEngineReasonMapsToActionableFixedCopy() {
        WalletRestorer.Reason.entries.forEach { reason ->
            val issue = RestoreIssue.from(WalletRestorer.Failure(reason))
            assertTrue(issue.message.isNotEmpty())
            assertEquals(reason in setOf(WalletRestorer.Reason.rollbackFailed, WalletRestorer.Reason.seedStateUnknown), issue.requiresRestart)
            if (issue.requiresRestart) { assertTrue(issue.message.contains("Do not retry")); assertTrue(issue.message.contains("restart Noah")) }
        }
        assertTrue(RestoreIssue.from(WalletRestorer.Failure(WalletRestorer.Reason.wrongNetwork)).message.contains("different Bitcoin network"))
        assertTrue(RestoreIssue.from(WalletRestorer.Failure(WalletRestorer.Reason.downloadMismatch)).message.contains("connection"))
        assertTrue(RestoreIssue.from(WalletRestorer.Failure(WalletRestorer.Reason.secureStorageUnavailable)).message.contains("Unlock"))
    }
    @Test fun missingBackupCryptoNetworkAndRawErrors() {
        val raw = "sensitive request data"
        val missing = RestoreIssue.from(ServerFailure(raw, 404))
        assertTrue(missing.message.contains("No backup"))
        assertEquals(missing, RestoreIssue.from(ServerFailure(raw, 401, "USER_NOT_FOUND")))
        assertTrue(RestoreIssue.from(BackupCrypto.Failure(BackupCrypto.Reason.authenticationFailed)).message.contains("unlock the backup"))
        assertTrue(RestoreIssue.from(IOException(raw)).message.contains("internet connection"))
        listOf(ServerFailure(raw, 500), IOException(raw), Exception(raw)).forEach { assertFalse(RestoreIssue.from(it).message.contains(raw)) }
    }
    @Test fun pendingOrUncertainStateBlocksRetry() {
        RestoreTransaction.Reason.entries.forEach { assertTrue(RestoreIssue.from(RestoreTransaction.Failure(it)).requiresRestart) }
        assertTrue(RestoreIssue.from(RestoreCompletion.RestartRequired()).requiresRestart)
        assertTrue(RestoreIssue.from(WalletSession.OpenError(WalletSession.OpenFailure.restorePending)).requiresRestart)
        assertTrue(RestoreIssue.from(WalletSession.OpenError(WalletSession.OpenFailure.teardownFailed)).requiresRestart)
        assertTrue(RestoreIssue.committed.requiresRestart)
    }
}

class RestoreCompletionTest {
    private class Fake {
        var state = PersistedWalletState(isInitialized = false, isWalletLoaded = false, staticVtxoPubkey = "keep")
        val events = mutableListOf<String>()
        var seeded: Pair<String, String?>? = null
        var recovery = RestoreTransaction.Recovery.completed
        var failSave = false
        var failRecovery = false
        val completion get() = RestoreCompletion(
            { state }, { if (failSave) error("save"); state = it; events += "flags" },
            { sha, id -> seeded = sha to id; events += "seed" },
            { events += "recover"; if (failRecovery) error("recovery"); recovery }, { events += "open" })
    }
    @Test fun savesFlagsSeedsBackupRecoversThenOpens() = runBlocking {
        val fake = Fake()
        fake.completion.finish(WalletRestorer.Outcome("b1", "hash", true))
        assertEquals(listOf("flags", "seed", "recover", "open"), fake.events)
        assertEquals(true, fake.state.isInitialized); assertEquals(true, fake.state.isWalletLoaded)
        assertEquals("keep", fake.state.staticVtxoPubkey); assertEquals("hash" to "b1", fake.seeded)
    }
    @Test fun legacyDoesNotSeedOrRecover() = runBlocking {
        val fake = Fake(); fake.completion.finish(WalletRestorer.Outcome(null, null))
        assertEquals(listOf("flags", "open"), fake.events); assertNull(fake.seeded)
    }
    @Test fun snapshotWithoutBackupIdSeeds() = runBlocking {
        val fake = Fake(); fake.completion.finish(WalletRestorer.Outcome(null, "hash"))
        assertEquals("hash" to null, fake.seeded)
        assertEquals(listOf("flags", "seed", "open"), fake.events)
    }
    @Test fun unresolvedCleanupDoesNotOpen() = runBlocking {
        for (result in listOf(RestoreTransaction.Recovery.waitingForSecret, RestoreTransaction.Recovery.rolledBack)) {
            val fake = Fake(); fake.recovery = result
            try { fake.completion.finish(WalletRestorer.Outcome("b1", "hash", true)); fail("Must require restart") }
            catch (_: RestoreCompletion.RestartRequired) { }
            assertFalse(fake.events.contains("open"))
        }
    }
    @Test fun thrownRecoveryAndSaveFailureDoNotOpen() = runBlocking {
        for (save in listOf(true, false)) {
            val fake = Fake(); fake.failSave = save; fake.failRecovery = !save
            try { fake.completion.finish(WalletRestorer.Outcome("b1", "hash", true)); fail("Must stop") }
            catch (_: IllegalStateException) { }
            assertFalse(fake.events.contains("open"))
            if (save) assertTrue(fake.events.isEmpty())
        }
    }
    @Test fun alreadySettledCleanupOpens() = runBlocking {
        val fake = Fake(); fake.recovery = RestoreTransaction.Recovery.nothingPending
        fake.completion.finish(WalletRestorer.Outcome(null, null, true))
        assertEquals(listOf("flags", "recover", "open"), fake.events)
    }
}

class RestoreFlowTest {
    private val phrase = List(11) { "abandon" }.plus("about").joinToString(" ")
    @Test fun invalidPhraseNeverCallsEngine() = runBlocking {
        val flow = RestoreFlow({ _, _ -> error("Unexpected engine call") }, { error("Unexpected completion") })
        flow.run("wrong"); assertEquals(RestoreIssue.invalidPhrase, flow.issue); assertTrue(flow.canLeave)
    }
    @Test fun progressBlocksLeavingAndDuplicateSubmission() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val flow = RestoreFlow({ _, progress ->
            calls++; progress(WalletRestorer.Step.validating); entered.complete(Unit); release.await(); WalletRestorer.Outcome(null, null)
        }, {})
        val job = launch { flow.run(phrase) }; entered.await()
        assertFalse(flow.canLeave); assertEquals(WalletRestorer.Step.validating, flow.step)
        flow.run(phrase); assertEquals(1, calls)
        release.complete(Unit); job.join()
        assertTrue(flow.completed); assertNull(flow.step); assertFalse(flow.running)
        flow.run(phrase); assertEquals(1, calls)
    }
    @Test fun precommitFailureAllowsRetry() = runBlocking {
        var calls = 0
        val flow = RestoreFlow({ _, _ -> calls++; throw ServerFailure("secret", 404) }, {})
        flow.run(phrase); assertTrue(flow.canLeave); assertFalse(flow.issue!!.requiresRestart)
        flow.run(phrase); assertEquals(2, calls)
    }
    @Test fun everyPostcommitErrorRequiresRestartAndNeverRetries() = runBlocking {
        for (failure in listOf(Exception("save"), RestoreCompletion.RestartRequired(), IOException("open"))) {
            var calls = 0
            val flow = RestoreFlow({ _, _ -> calls++; WalletRestorer.Outcome("id", "hash", true) }, { throw failure })
            flow.run(phrase)
            assertEquals(RestoreIssue.committed, flow.issue); assertFalse(flow.canLeave)
            flow.clearIssue(); flow.run(phrase); assertEquals(1, calls)
        }
    }
    @Test fun uncertainSeedNeverOffersRetry() = runBlocking {
        val flow = RestoreFlow({ _, _ -> throw WalletRestorer.Failure(WalletRestorer.Reason.seedStateUnknown) }, {})
        flow.run(phrase); assertFalse(flow.canLeave); flow.clearIssue(); assertTrue(flow.issue!!.requiresRestart)
    }
    @Test fun cancellationCannotDiscardCommittedOutcome() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var finished = false
        val flow = RestoreFlow({ _, _ -> entered.complete(Unit); release.await(); WalletRestorer.Outcome(null, null) }, { finished = true })
        val job = launch { flow.run(phrase) }; entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(finished); assertTrue(flow.completed)
    }
    @Test fun successfulDeletionResetsFlowForAnotherWallet() = runBlocking {
        var calls = 0
        val flow = RestoreFlow({ _, _ -> calls++; WalletRestorer.Outcome(null, null) }, {})
        flow.run(phrase); assertTrue(flow.completed)
        flow.reset(); assertFalse(flow.completed); assertNull(flow.issue)
        flow.run(phrase); assertEquals(2, calls)
    }
    @Test fun stepLabelsCoverEngineStages() {
        assertEquals("Starting restore…", null.restoreMessage())
        assertEquals(listOf("Fetching backup…", "Validating backup…", "Installing wallet…", "Verifying recovery phrase storage…"),
            WalletRestorer.Step.entries.map { it.restoreMessage() })
    }
    @Test fun journalOrDeletionAlwaysBlocksOnboarding() {
        for (seed in LaunchState.SeedStatus.entries) for (database in listOf(false, true)) {
            assertEquals(LaunchState.restoreIncomplete, LaunchState.resolve(seed, database, restorePending = true))
            assertEquals(LaunchState.restoreIncomplete, LaunchState.resolve(seed, database, deletionPending = true))
        }
    }
}

class RestoreClipboardTest {
    @Test fun clearsOnlyTheAcceptedContentIncludingOnBackgroundOrLeave() {
        var content: String? = "accepted phrase"
        var clears = 0
        val clipboard = RestoreClipboard({ content }, { content = null; clears++ })
        clipboard.accepted("accepted phrase")
        assertNull(content); assertEquals(1, clears)
        content = "unrelated replacement"
        clipboard.clearIfUnchanged(); assertEquals("unrelated replacement", content)
        content = "accepted phrase"
        clipboard.clearIfUnchanged(); assertNull(content); assertEquals(2, clears)
    }
    @Test fun clipboardReplacedBeforeAcceptanceIsPreserved() {
        var content: String? = "replacement"
        val clipboard = RestoreClipboard({ content }, { content = null })
        clipboard.accepted("accepted phrase"); assertEquals("replacement", content)
        content = null; clipboard.clearIfUnchanged(); assertNull(content)
    }
}

class RestoreCompletionReplayTest {
    @Test fun restartReplaysEveryPostcommitCrashPointIncludingBeforeEngineReturn() = runBlocking {
        for (boundary in listOf("engineReturn", "flags", "seed", "recover", "token", "open", "clear")) {
            val root = java.nio.file.Files.createTempDirectory("restore-completion").toFile()
            try {
                val location = com.noahwallet.services.storage.WalletDataLocation(root, "regtest")
                location.directory.mkdirs(); location.database.writeText("committed wallet")
                var fail = boundary
                var armed = false
                val journal = RestoreCompletionJournal(location) { if (armed && fail == "clear" && !java.io.File(location.restoreDirectory, "completion.json").exists()) error("crash") }
                // Engine callback persists the metadata before the seed commit.
                journal.write(WalletRestorer.Outcome("backup", "snapshot"))
                armed = true
                var state = PersistedWalletState()
                var seeded: Pair<String, String?>? = null
                var invalidated = false
                var opened = false
                fun crash(at: String) { if (fail == at) error("crash") }
                fun completion() = RestoreCompletion(
                    { state }, { crash("flags"); state = it },
                    { hash, id -> crash("seed"); seeded = hash to id },
                    { crash("recover"); RestoreTransaction.Recovery.nothingPending },
                    { crash("open"); opened = true }, journal,
                    { crash("token"); invalidated = true },
                )
                if (boundary != "engineReturn") assertTrue(runCatching {
                    completion().finish(WalletRestorer.Outcome("backup", "snapshot", true))
                }.isFailure)
                fail = ""
                // Construct a fresh journal, as after process death. No in-memory outcome is reused.
                val relaunched = RestoreCompletionJournal(location) {}
                val replay = RestoreCompletion({ state }, { state = it }, { hash, id -> seeded = hash to id },
                    { RestoreTransaction.Recovery.nothingPending }, { opened = true }, relaunched, { invalidated = true })
                replay.replay(RestoreTransaction.Recovery.nothingPending, location.databaseExists)
                assertTrue(state.isInitialized == true && state.isWalletLoaded == true)
                assertEquals("snapshot" to "backup", seeded)
                assertTrue(invalidated); assertTrue(opened); assertFalse(relaunched.pending)
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun precommitRollbackAndNoInstallationDiscardReceiptButWaitingSecretKeepsIt() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("restore-completion").toFile()
        try {
            val location = com.noahwallet.services.storage.WalletDataLocation(root, "regtest")
            val journal = RestoreCompletionJournal(location) {}
            val completion = RestoreCompletion({ error("No flags") }, {}, { _, _ -> error("No seed") },
                { error("No recovery") }, { error("No open") }, journal)
            for ((recovery, database) in listOf(RestoreTransaction.Recovery.rolledBack to true,
                RestoreTransaction.Recovery.nothingPending to false)) {
                journal.write(WalletRestorer.Outcome("backup", "hash"))
                completion.replay(recovery, database); assertFalse(journal.pending)
            }
            journal.write(WalletRestorer.Outcome(null, null))
            assertTrue(runCatching { completion.replay(RestoreTransaction.Recovery.waitingForSecret, true) }.isFailure)
            assertTrue(journal.pending)
        } finally { root.deleteRecursively() }
    }
    @Test fun unreadableReceiptFailsClosed() {
        val root = java.nio.file.Files.createTempDirectory("restore-completion").toFile()
        try {
            val location = com.noahwallet.services.storage.WalletDataLocation(root, "regtest")
            location.restoreDirectory.mkdirs()
            java.io.File(location.restoreDirectory, "completion.json").writeText("{}")
            val journal = RestoreCompletionJournal(location) {}
            assertTrue(runCatching { journal.read() }.isFailure); assertTrue(journal.pending)
        } finally { root.deleteRecursively() }
    }
}
