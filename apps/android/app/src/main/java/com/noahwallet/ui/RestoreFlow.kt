package com.noahwallet.ui

import androidx.compose.runtime.*
import com.noahwallet.services.backup.RestoreTransaction
import com.noahwallet.services.backup.WalletRestorer
import com.noahwallet.services.storage.PersistedWalletState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun WalletRestorer.Step?.restoreMessage(): String = when (this) {
    null -> "Starting restore…"
    WalletRestorer.Step.fetching -> "Fetching backup…"
    WalletRestorer.Step.validating -> "Validating backup…"
    WalletRestorer.Step.loading -> "Installing wallet…"
    WalletRestorer.Step.finalizing -> "Verifying recovery phrase storage…"
}

/** Post-commit errors must never offer another transaction over the new wallet. */
class RestoreCompletion(
    private val loadState: () -> PersistedWalletState,
    private val saveState: (PersistedWalletState) -> Unit,
    private val seedRestored: (String, String?) -> Unit,
    private val recover: suspend () -> RestoreTransaction.Recovery,
    private val openWallet: suspend () -> Unit,
) {
    class RestartRequired : Exception()
    suspend fun finish(outcome: WalletRestorer.Outcome) {
        val wallet = loadState()
        saveState(wallet.copy(isInitialized = true, isWalletLoaded = true))
        outcome.snapshotSha256?.let { seedRestored(it, outcome.backupId) }
        if (outcome.cleanupPending && recover() !in setOf(RestoreTransaction.Recovery.completed, RestoreTransaction.Recovery.nothingPending)) {
            throw RestartRequired()
        }
        openWallet()
    }
}

class RestoreFlow(
    private val restore: suspend (String, (WalletRestorer.Step) -> Unit) -> WalletRestorer.Outcome,
    private val complete: suspend (WalletRestorer.Outcome) -> Unit,
) {
    var running by mutableStateOf(false); private set
    var step by mutableStateOf<WalletRestorer.Step?>(null); private set
    var issue by mutableStateOf<RestoreIssue?>(null); private set
    var completed by mutableStateOf(false); private set
    val canLeave get() = !running && issue?.requiresRestart != true
    fun reset() { check(!running); issue = null; step = null; completed = false }
    fun reject(value: RestoreIssue) { if (canLeave) issue = value }
    fun clearIssue() { if (canLeave) issue = null }

    suspend fun run(phrase: String) {
        if (running || completed || issue?.requiresRestart == true) return
        if (!RecoveryPhraseInput.isValid(phrase)) { issue = RestoreIssue.invalidPhrase; return }
        running = true; issue = null
        // The screen cannot cancel; a committed engine result must reach completion even if its owner clears.
        withContext(NonCancellable) {
            var committed = false
            try {
                val outcome = restore(phrase) { step = it }
                committed = true
                complete(outcome)
                completed = true
            } catch (failure: Exception) { issue = if (committed) RestoreIssue.committed else RestoreIssue.from(failure) }
            finally { running = false; step = null }
        }
    }
}
