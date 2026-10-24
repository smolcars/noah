package com.noahwallet.ui.settings

import com.noahwallet.ui.WalletAlertDialog

import com.noahwallet.localization.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.noahwallet.services.logging.LogFile
import com.noahwallet.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().readableWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun SettingRow(title: String, detail: String = "", enabled: Boolean = true, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(tr(title)) }, supportingContent = if (detail.isEmpty()) null else ({ Text(tr(detail)) }),
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background,
            headlineColor = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
fun SectionTitle(title: String) {
    Text(tr(title), Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
}

@Composable
fun SettingToggle(title: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    val localizedTitle = tr(title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(tr(title), style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) Text(tr(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = localizedTitle })
    }
}

/** Today's notification rows from the old list, until Notifications is rebuilt. */
@Composable
internal fun TodaysNotifications(model: AppModel, nav: SettingsNav) {
    var server by remember { mutableStateOf(model.server.state) }
    SettingsColumn {
        SettingToggle("Mailbox Notifications", "Allow Noah to wake the app to claim incoming payments", server.isMailboxAuthorizationEnabled != false, !model.busy && !model.isWalletSuspended) {
            model.action("Couldn't update mailbox notifications.") {
                model.server.setMailboxEnabled(it)
                server = model.server.state
            }
        }
        SettingRow("Battery Optimization", "Allow background wallet notifications") { nav.open(SettingsDestination.Battery) }
        if (com.noahwallet.ui.launch.PlatformAccess.unifiedPush(LocalContext.current)) SettingRow("UnifiedPush", "Configure push notifications") { nav.open(SettingsDestination.UnifiedPush) }
    }
}

/** Today's info, debug and danger-zone rows from the old list, until Advanced is rebuilt. */
@Composable
internal fun TodaysAdvanced(model: AppModel, nav: SettingsNav, live: SettingsLive) {
    var resetting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    SettingsColumn {
        SettingRow("Ark Info", "Ark server, wallet, and explorer configuration") { nav.open(SettingsDestination.Ark) }
        SettingRow("Show Logs", "Application logs") { nav.open(SettingsDestination.Logs) }
        SettingRow("Reset Server Registration", "Register this wallet and push notifications again", !model.busy && !model.isWalletSuspended) { resetting = true }
        if (live.debug) SettingRow("Debug Screen", "Advanced developer actions") { nav.open(SettingsDestination.Debug) }
        SectionTitle("Danger Zone")
        SettingToggle("Suspend Wallet", "Close the wallet and disable all wallet operations until re-enabled", model.isWalletSuspended, !model.busy) { suspend ->
            model.action("Couldn't change wallet suspension. Please try again.") { if (suspend) model.suspendWallet() else model.resumeWallet() }
        }
        message?.let { Text(tr(it)) }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (resetting) WalletAlertDialog(onDismissRequest = { if (!model.busy) resetting = false },
        title = { Text(tr("Reset Server Registration")) },
        text = { Text(tr("Clear local server registration and register again? Your wallet will be kept.")) },
        dismissButton = { TextButton({ resetting = false }, enabled = !model.busy) { Text(tr("Cancel")) } },
        confirmButton = { TextButton({
            model.action("Couldn't register again. Please retry.") {
                model.reRegisterWithServer(); message = "Registered with the server again"
                resetting = false
            }
        }, enabled = !model.busy) { Text(tr(if (model.busy) "Please wait…" else "Reset"), color = MaterialTheme.colorScheme.error) } })
}

/** Today's log screen, until Advanced is rebuilt. */
@Composable
internal fun LogScreen(model: AppModel) {
    val context = LocalContext.current
    val snapshot = remember { LogSnapshot() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val share = rememberFileShare(model, tr("Share app logs"), "text/plain")
    suspend fun load() { snapshot.load(read = { withContext(Dispatchers.IO) { LogFile.shared(context).lines() } }) }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(snapshot.updatedAt) { if (snapshot.lines.isNotEmpty()) listState.scrollToItem(snapshot.lines.lastIndex) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoahButton("Refresh app logs", !snapshot.loading, secondary = true) { scope.launch { load() } }
        NoahButton("Share app logs", !model.busy && !model.isWalletSuspended) {
            share {
                val scratch = model.services.scratch
                val folder = scratch.makeFolder("share")
                try {
                    val file = java.io.File(folder, "noah.log").apply { writeText(LogFile.shared(context).lines().joinToString("\n")) }
                    SharedFile(file) { scratch.discard(folder) }
                } catch (failure: Exception) { scratch.discard(folder); throw failure }
            }
        }
        if (snapshot.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        snapshot.error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        snapshot.updatedAt?.let { Text(tr("Updated at {time}", java.text.DateFormat.getTimeInstance().format(java.util.Date(it)))) }
        if (!snapshot.loading && snapshot.error == null && snapshot.lines.isEmpty()) Text(tr("No logs found."))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(snapshot.lines) { line ->
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
            }
        }
    }
}

/** Today's story and links from the old list, until About is rebuilt. */
@Composable
internal fun TodaysAbout() {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { StoryScreen() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            TextButton({ uri.openUri("https://t.me/blixtwallet/605026") }) { Text(tr("Telegram support")) }
            TextButton({ uri.openUri("https://github.com/smolcars/noah") }) { Text(tr("Noah on GitHub")) }
        }
    }
}

/** Today's delete confirmation from the old list, until the delete sheet. */
@Composable
internal fun TodaysDeleteDialog(model: AppModel, dismiss: () -> Unit) {
    var deleteText by remember { mutableStateOf("") }
    WalletAlertDialog(onDismissRequest = { if (!model.busy) dismiss() },
        title = { Text(tr("Delete Wallet")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("This removes your wallet and recovery phrase from this device. Make sure you have saved your recovery phrase. Type delete to confirm."))
            OutlinedTextField(deleteText, { deleteText = it }, label = { Text(tr("delete")) }, modifier = Modifier.testTag("delete-wallet-input"), enabled = !model.busy, singleLine = true)
        } },
        dismissButton = { TextButton(dismiss, enabled = !model.busy) { Text(tr("Cancel")) } },
        confirmButton = { TextButton({
            model.action("Couldn't delete wallet. Restart Noah if a restore is pending, then try again.") {
                model.deleteWallet()
                dismiss()
            }
        }, modifier = Modifier.testTag("delete-wallet-confirm"), enabled = !model.busy && deleteText.trim().equals("delete", true)) {
            Text(tr(if (model.busy) "Please wait…" else "Delete Wallet"), color = MaterialTheme.colorScheme.error)
        } })
}
