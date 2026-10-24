package com.noahwallet.ui.home

import com.noahwallet.localization.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import com.noahwallet.ui.readableWidth
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.models.AppVersionInfo
import com.noahwallet.services.backup.BackupCoordinator
import com.noahwallet.services.storage.PersistedBackupState
import com.noahwallet.ui.AppModel
import io.github.niteshbalusu11.bark.PendingRoundStatus
import kotlinx.coroutines.*

/** Today's Home inputs: app version, backup status, pending rounds, background work and the renew check. */
internal class HomeSignals(
    val version: AppVersionInfo?, val backup: PersistedBackupState, val background: String?,
    val rounds: List<PendingRoundStatus>, val now: Long, val retrying: Boolean,
    val retry: () -> Unit,
) {
    val active get() = rounds.filter { HomeRules.activeRound(it.isFinal, it.status.name.lowercase()) }
}

@Composable
internal fun rememberHomeSignals(app: AppModel, refresh: Int, memory: HomeMemory): HomeSignals {
    val background by com.noahwallet.services.wallet.WalletBackgroundActivity.changes.collectAsState()
    val backup by app.services.backupCoordinator.statusChanges.collectAsState()
    val version by app.server.versionChanges.collectAsState()
    val walletRevision by app.session.walletChanges.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(app.server) { withContext(Dispatchers.IO) { app.server.checkVersionOnce() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var retrying by remember { mutableStateOf(false) }
    var rounds by remember { mutableStateOf<List<PendingRoundStatus>>(emptyList()) }
    var roundChecks by remember { mutableIntStateOf(0) }
    val boarding = app.autoBoardSuccessBanner
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(app.session, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observeHomeRounds(
                activity = com.noahwallet.services.wallet.WalletBackgroundActivity.changes,
                walletChanges = app.session.walletChanges,
                query = { withContext(Dispatchers.IO) { app.session.pendingRounds() } },
                active = { HomeRules.activeRound(it.isFinal, it.status.name.lowercase()) },
                publish = { rounds = it; roundChecks++ },
                initiallyActive = rounds.any { HomeRules.activeRound(it.isFinal, it.status.name.lowercase()) },
            )
        }
    }
    LaunchedEffect(backup, boarding, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            homeBannerExpiries(listOfNotNull(backup.lastBackupAt?.toLong(), boarding?.second), publish = { now = it })
        }
    }
    // The renew nudge reuses today's refresh moments: Home appearing, wallet changes, round checks and pull-to-refresh.
    LaunchedEffect(app.session, refresh, walletRevision, roundChecks, app.isWalletSuspended, lifecycle) {
        if (app.isWalletSuspended) { memory.renew = null; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (com.noahwallet.onchain.onchainReady(app)) try {
                memory.renew = HomeRules.renew(withContext(Dispatchers.IO) { app.session.onchainOverview() }.vtxos)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last known answer; a background job or a closed session is transient. */ }
            awaitCancellation()
        }
    }
    // Today's one-time upgrade notices, taken once per process and kept until dismissed.
    LaunchedEffect(lifecycle, app.isWalletSuspended) {
        if (app.isWalletSuspended) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!memory.noticesTaken) {
                memory.notices = withContext(Dispatchers.IO) { com.noahwallet.services.storage.SettingsStorage(context).takeUpgradeNotices() }
                memory.noticesTaken = true
            }
            awaitCancellation()
        }
    }
    return HomeSignals(version, backup, background.message, rounds, now, retrying) {
        scope.launch {
            retrying = true
            try { withContext(Dispatchers.IO) { app.services.backupCoordinator.flush(BackupCoordinator.Reason.manual, requireEnabled = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Coordinator persists a safe error for the nudge. */ }
            finally { retrying = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingRoundsSheet(rounds: List<PendingRoundStatus>, close: () -> Unit) {
    com.noahwallet.ui.onboarding.OnboardingSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), padded = false) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().readableWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            TextButton(close) { Text(tr("Close")) }
            Text(tr("Pending Rounds"), style = MaterialTheme.typography.headlineMedium)
            rounds.forEach { round ->
                Text(tr("Round #{_gt_value_2}", round.roundId), style = MaterialTheme.typography.titleLarge)
                Text(tr("Status") + ": " + tr(round.status.name.lowercase().replaceFirstChar(Char::uppercase)))
                Text(tr("Final") + ": " + tr(if (round.isFinal) "Yes" else "No"))
                Text(tr("Successful") + ": " + tr(if (round.isSuccess) "Yes" else "No"))
                round.fundingTxid?.let { RoundTransaction("Funding tx", it, copyable = true) }
                round.unsignedFundingTxids.forEachIndexed { index, txid -> RoundTransaction(tr("Unsigned tx {value1}", index + 1), txid) }
                round.error?.let { Text(tr("Error") + ": " + it) }
            }
        }
    }
}

@Composable
private fun RoundTransaction(label: String, txid: String, copyable: Boolean = false) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    Text(tr(label) + ": " + txid)
    if (copyable) TextButton({
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, txid))
    }) { Text(tr("Copy $label")) }
    TextButton({
        uri.openUri("https://mempool.space/${if (BuildConfig.APP_VARIANT == "mainnet") "" else "signet/"}tx/$txid")
    }) { Text(tr("Open {value1} in browser", label)) }
}
