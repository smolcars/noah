package com.noahwallet.ui.settings

import com.noahwallet.ui.WalletAlertDialog

import com.noahwallet.localization.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.*

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
