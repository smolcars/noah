package com.noahwallet.ui.settings

import com.noahwallet.ui.WalletAlertDialog

import com.noahwallet.localization.tr
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.backup.BackupCoordinator
import com.noahwallet.services.logging.LogFile
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.PersistedBackupState
import com.noahwallet.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupSettingsScreen(model: AppModel) {
    var state by remember { mutableStateOf(PersistedBackupState()) }
    var enabled by remember { mutableStateOf(model.server.state.isBackupEnabled == true) }
    var backups by remember { mutableStateOf<List<BackupObjectInfo>?>(null) }
    var deleting by remember { mutableStateOf<BackupObjectInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var listError by remember { mutableStateOf(false) }
    suspend fun list() { backups = V0BackupV2ListClient(model.services.client).listBackupObjects().value(); listError = false }
    LaunchedEffect(Unit) {
        while (isActive) { state = PersistedBackupState.decode(model.settings.string(PersistedBackupState.key)); delay(1000) }
    }
    SettingsColumn {
        Text(tr("Backups are encrypted with your seed phrase and stored securely on our servers. We can never access your funds or data."))
        SettingToggle("Enable Automatic Backups", "", enabled, !model.busy) {
            model.action("Couldn't update backup settings.") {
                model.server.setBackupEnabled(it); enabled = it; model.services.reconcileBackupTriggers(foreground = true)
            }
        }
        SectionTitle("Last Backup")
        Text(tr(state.lastBackupAt?.let { DateFormat.getDateTimeInstance().format(Date(it.toLong())) } ?: "No backups yet"))
        Text(tr(when (state.lastBackupStatus) {
            "in_progress" -> "Backing up wallet"; "success" -> "Backup completed"; "failed" -> "Backup failed. Please try again."
            else -> "Up to date or waiting for changes"
        }))
        if (state.backupPending) Text(tr("Backup pending"))
        NoahButton("Backup Now", !model.busy && !model.isWalletSuspended) {
            model.action("Couldn't back up your wallet. Please try again.") {
                val refreshed = backUpAndRefresh(backup = {
                    model.services.backupCoordinator.flush(BackupCoordinator.Reason.manual, requireEnabled = false)
                }, refresh = { if (backups != null) list() })
                message = "Your wallet has been backed up successfully."
                listError = !refreshed
            }
        }
        NoahButton("List Backups", !model.busy, secondary = true) { model.action("Couldn't list backups.") { list() } }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(tr(it)) }
        if (listError) Text(tr("Couldn't list backups."))
        backups?.let { records ->
            SectionTitle("Available Backups")
            if (records.isEmpty()) Text(tr("No backups found"))
            records.forEach { backup ->
                SettingRow("Encrypted wallet snapshot", "${backup.createdAt} · ${backup.encryptedSize / 1024} KiB")
                TextButton({ deleting = backup }, enabled = !model.busy) { Text(tr("Delete Backup"), color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
            }
        }
    }
    deleting?.let { backup -> WalletAlertDialog(onDismissRequest = { if (!model.busy) deleting = null }, title = { Text(tr("Delete encrypted backup?")) },
        dismissButton = { TextButton({ deleting = null }, enabled = !model.busy) { Text(tr("Cancel")) } },
        confirmButton = { TextButton({ model.action("Couldn't delete backup.") {
            V0BackupV2DeleteClient(model.services.client).deleteBackupObject(DeleteBackupObjectPayload(backup.backupId)).value()
            model.services.backupCoordinator.forgetUploadedBackup(backup.backupId)
            deleting = null; list()
        } }, enabled = !model.busy) { Text(tr("Delete Backup")) } }) }
}

/** A list refresh cannot change a completed upload into a failed backup. */
internal suspend fun backUpAndRefresh(backup: suspend () -> Unit, refresh: suspend () -> Unit): Boolean {
    backup()
    return try { refresh(); true }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

/** A produced file and how to remove its temporary staging folder. */
private class SharedFile(val file: File, val discard: () -> Unit)

/** The model retains bounded exports across rotation; chooser return does not end a receiver's read. */
@Composable
private fun ShareFileButton(model: AppModel, title: String, mime: String, onShared: () -> Unit = {}, produce: suspend () -> SharedFile) {
    val context = LocalContext.current
    val shareCompletion = rememberShareCompletion(onShared)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    NoahButton(title, !model.busy && !model.isWalletSuspended) {
        model.action("Couldn't prepare the file for sharing. Please try again.") {
            val (file, displayName) = withContext(Dispatchers.IO) {
                model.services.scratch.withOperation {
                    val staged = produce()
                    try { model.settingsShares.retain(staged.file) to staged.file.name }
                    finally { staged.discard() }
                }
            }
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, displayName)
                val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .apply { clipData = ClipData.newRawUri(file.name, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                androidx.work.WorkManager.getInstance(context).enqueue(
                    androidx.work.OneTimeWorkRequestBuilder<com.noahwallet.services.sharing.SettingsShareCleanup>()
                        .setInitialDelay(com.noahwallet.services.sharing.SettingsShareFiles.retentionMillis,
                            java.util.concurrent.TimeUnit.MILLISECONDS).build())
                launcher.launch(Intent.createChooser(send, title, shareCompletion))
            } catch (failure: Exception) { model.settingsShares.discard(file); throw failure }
        }
    }
}

@Composable
fun ExportScreen(model: AppModel) {
    var exported by remember { mutableStateOf(false) }
    SettingsColumn {
        Text(tr("Create an encrypted backup file containing your wallet database."))
        Text(tr("You will need your seed phrase to restore this backup. Keep it safe and never share it."))
        ShareFileButton(model, "Export Database", "application/octet-stream", onShared = { exported = true }) {
            // Only the encrypted archive is retained; the service's staging folder is discarded immediately.
            val export = model.services.backupService.exportEncryptedBackup(ExportFilename.database())
            SharedFile(export.archive.file) { export.discard() }
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (exported) WalletAlertDialog(onDismissRequest = { exported = false }, title = { Text(tr("Export Complete")) },
        text = { Text(tr("Your encrypted database backup was exported.")) },
        confirmButton = { TextButton({ exported = false }) { Text(tr("OK")) } })
}

@Composable
fun LogScreen(model: AppModel) {
    val context = LocalContext.current
    val snapshot = remember { LogSnapshot() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    suspend fun load() { snapshot.load(read = { withContext(Dispatchers.IO) { LogFile.shared(context).lines() } }) }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(snapshot.updatedAt) { if (snapshot.lines.isNotEmpty()) listState.scrollToItem(snapshot.lines.lastIndex) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoahButton("Refresh app logs", !snapshot.loading, secondary = true) { scope.launch { load() } }
        ShareFileButton(model, "Share app logs", "text/plain") {
            val scratch = model.services.scratch
            val folder = scratch.makeFolder("share")
            try {
                val file = File(folder, "noah.log").apply { writeText(LogFile.shared(context).lines().joinToString("\n")) }
                SharedFile(file) { scratch.discard(folder) }
            } catch (failure: Exception) { scratch.discard(folder); throw failure }
        }
        if (snapshot.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        snapshot.error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        snapshot.updatedAt?.let { Text(tr("Updated at {time}", DateFormat.getTimeInstance().format(Date(it)))) }
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
