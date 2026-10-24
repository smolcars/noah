package com.noahwallet.ui

import androidx.compose.runtime.*
import com.noahwallet.services.backup.RestoreTransaction
import com.noahwallet.services.backup.WalletRestorer
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.storage.RecoveryPhraseBackup
import com.noahwallet.services.storage.SettingsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Post-commit errors must never offer another transaction over the new wallet. */
class RestoreCompletion(
    private val loadState: () -> PersistedWalletState,
    private val saveState: (PersistedWalletState) -> Unit,
    private val seedRestored: (String, String?) -> Unit,
    private val recover: suspend () -> RestoreTransaction.Recovery,
    private val openWallet: suspend () -> Unit,
    private val journal: RestoreCompletionJournal? = null,
    private val clearToken: suspend () -> Unit = {},
    private val settingsUnavailable: () -> Boolean = { false },
    private val acknowledgeBackup: () -> Unit = {},
) {
    class RestartRequired : Exception()

    companion object {
        /**
         * The app's wiring: wallet flags and, for a typed phrase, the backup acknowledgement land in settings
         * inside completion, before the receipt clears, so a replay after process death applies them too.
         */
        fun withSettings(settings: SettingsStorage, journal: RestoreCompletionJournal?, seedRestored: (String, String?) -> Unit,
                         recover: suspend () -> RestoreTransaction.Recovery, openWallet: suspend () -> Unit,
                         clearToken: suspend () -> Unit = {}, acknowledged: () -> Unit = {}) = RestoreCompletion(
            loadState = { settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState() },
            saveState = { if (!settings.completeRestore()) throw SettingsStorage.Unavailable() },
            seedRestored = seedRestored, recover = recover, openWallet = openWallet, journal = journal, clearToken = clearToken,
            settingsUnavailable = { runCatching { settings.requireHealthy() }.isFailure },
            acknowledgeBackup = { RecoveryPhraseBackup.set(settings, true); acknowledged() },
        )
    }
    suspend fun replay(recovery: RestoreTransaction.Recovery, hasDatabase: Boolean) {
        val outcome = journal?.read() ?: return
        if (recovery == RestoreTransaction.Recovery.waitingForSecret) throw RestartRequired()
        if (recovery == RestoreTransaction.Recovery.rolledBack || !hasDatabase) journal.clear()
        else finish(outcome)
    }

    suspend fun finish(outcome: WalletRestorer.Outcome) {
        journal?.write(outcome)
        if (outcome.cleanupPending && recover() !in setOf(RestoreTransaction.Recovery.completed, RestoreTransaction.Recovery.nothingPending)) {
            throw RestartRequired()
        }
        clearToken()
        try {
            val wallet = loadState()
            saveState(wallet.copy(isInitialized = true, isWalletLoaded = true))
            if (outcome.typedPhrase) acknowledgeBackup()
            outcome.snapshotSha256?.let { seedRestored(it, outcome.backupId) }
        } catch (failure: Exception) {
            if (failure is CancellationException || !settingsUnavailable()) throw failure
            openWallet() // The durable receipt retries preferences; the wallet is already committed.
            return
        }
        openWallet()
        journal?.clear()
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
        if (com.noahwallet.services.storage.StoredMnemonic.normalize(phrase) == null) { issue = RestoreIssue.invalidPhrase; return }
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
