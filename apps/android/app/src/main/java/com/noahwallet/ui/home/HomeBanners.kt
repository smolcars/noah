package com.noahwallet.ui.home

import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.models.AppVersionInfo
import com.noahwallet.services.backup.BackupCoordinator
import com.noahwallet.ui.AppModel
import io.github.niteshbalusu11.bark.PendingRoundStatus
import kotlinx.coroutines.*
import java.util.Locale

@Composable
fun HomeBanners(app: AppModel, refresh: Int) {
    val background by com.noahwallet.services.wallet.WalletBackgroundActivity.changes.collectAsState()
    val backup by app.services.backupCoordinator.statusChanges.collectAsState()
    val version by app.server.versionChanges.collectAsState()
    LaunchedEffect(app.server) { withContext(Dispatchers.IO) { app.server.checkVersionOnce() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var retrying by remember { mutableStateOf(false) }
    var rounds by remember { mutableStateOf<List<PendingRoundStatus>>(emptyList()) }
    var showRounds by remember { mutableStateOf(false) }
    val boarding = app.autoBoardSuccessBanner
    val locale = Locale.forLanguageTag(app.localeIdentifier)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(app.session, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observeHomeRounds(
                activity = com.noahwallet.services.wallet.WalletBackgroundActivity.changes,
                walletChanges = app.session.walletChanges,
                query = { withContext(Dispatchers.IO) { app.session.pendingRounds() } },
                active = { HomeRules.activeRound(it.isFinal, it.status.name.lowercase()) },
                publish = { rounds = it },
                initiallyActive = rounds.any { HomeRules.activeRound(it.isFinal, it.status.name.lowercase()) },
            )
        }
    }
    LaunchedEffect(backup, boarding, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            homeBannerExpiries(listOfNotNull(backup.lastBackupAt?.toLong(), boarding?.second), publish = { now = it })
        }
    }
    version?.takeIf { it.updateRequired }?.let { UpdateWarningBanner(it, BuildConfig.VERSION_NAME, BuildConfig.APP_VARIANT) }
    HomeRules.backup(backup, now, locale)?.let { banner -> StatusBanner(banner, retrying) {
        scope.launch {
            retrying = true
            try { withContext(Dispatchers.IO) { app.services.backupCoordinator.flush(BackupCoordinator.Reason.manual, requireEnabled = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Coordinator persists a safe error for this banner. */ }
            finally { retrying = false }
        }
    } }
    boarding?.let { (amount, created) -> HomeRules.boarding(amount, created, now, locale)?.let { StatusBanner(it) } }
    val active = rounds.filter { HomeRules.activeRound(it.isFinal, it.status.name.lowercase()) }
    if (active.isNotEmpty()) Card(onClick = { showRounds = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(tr(if (active.size == 1) "Round in progress" else "Rounds in progress"), style = MaterialTheme.typography.titleMedium)
            Text(tr(if (active.size == 1) tr("Round #{id} {status}", active[0].roundId, active[0].status.name.lowercase().replaceFirstChar(Char::uppercase)) else tr("{count} rounds pending", active.size)))
            TextButton({ showRounds = true }) { Text(tr("View")) }
        }
    }
    background.message?.let { message ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text(tr(message))
            }
        }
    }
    if (showRounds && active.isNotEmpty()) PendingRoundsSheet(active) { showRounds = false }
}

@Composable
internal fun StatusBanner(banner: HomeRules.Banner, retrying: Boolean = false, retry: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(banner.title), style = MaterialTheme.typography.titleMedium, color = if (banner.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(tr(banner.message))
            if (banner.retry) TextButton(retry, enabled = !retrying) { Text(tr(if (retrying) "Working" else "Retry")) }
        }
    }
}

@Composable
internal fun UpdateWarningBanner(info: AppVersionInfo, currentVersion: String, variant: String) {
    val uri = LocalUriHandler.current
    Card(onClick = { uri.openUri(HomeRules.updateURL(variant)) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Update Available"), style = MaterialTheme.typography.titleMedium)
            Text(tr("Please update to the latest version of the app for bug fixes and improvements."))
            Text(tr("Current Version: $currentVersion"))
            Text(tr("Latest Version: ${info.minimumRequiredVersion}"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingRoundsSheet(rounds: List<PendingRoundStatus>, close: () -> Unit) {
    WalletModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        com.noahwallet.ui.settings.SettingsColumn {
            TextButton(close) { Text(tr("Close")) }
            Text(tr("Pending Rounds"), style = MaterialTheme.typography.headlineMedium)
            rounds.forEach { round ->
                Text(tr("Round #{_gt_value_2}", round.roundId), style = MaterialTheme.typography.titleLarge)
                Text(tr("Status: ${round.status.name.lowercase().replaceFirstChar(Char::uppercase)}"))
                Text(tr("Final: ${if (round.isFinal) "Yes" else "No"}"))
                Text(tr("Successful: ${if (round.isSuccess) "Yes" else "No"}"))
                round.fundingTxid?.let { RoundTransaction("Funding tx", it, copyable = true) }
                round.unsignedFundingTxids.forEachIndexed { index, txid -> RoundTransaction("Unsigned tx ${index + 1}", txid) }
                round.error?.let { Text(tr("Error: $it")) }
            }
        }
    }
}

@Composable
private fun RoundTransaction(label: String, txid: String, copyable: Boolean = false) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    Text(tr("$label: $txid"))
    if (copyable) TextButton({
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, txid))
    }) { Text(tr("Copy $label")) }
    TextButton({
        uri.openUri("https://mempool.space/${if (BuildConfig.APP_VARIANT == "mainnet") "" else "signet/"}tx/$txid")
    }) { Text(tr("Open {value1} in browser", label)) }
}

@Composable
fun UpgradeNotices() {
    val context = LocalContext.current
    var upgradeNotices by remember { mutableStateOf<List<String>>(emptyList()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (upgradeNotices.isEmpty()) upgradeNotices = withContext(Dispatchers.IO) {
                com.noahwallet.services.storage.SettingsStorage(context).takeUpgradeNotices()
            }
            awaitCancellation()
        }
    }
    upgradeNotices.forEach { message ->
        Card(Modifier.fillMaxWidth()) { Text(tr(message), Modifier.padding(16.dp)) }
    }
}
