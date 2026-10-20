package com.noahwallet.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
    suspend fun list() { backups = V0BackupV2ListClient(model.services.client).listBackupObjects().value() }
    LaunchedEffect(Unit) {
        while (isActive) { state = PersistedBackupState.decode(model.settings.string(PersistedBackupState.key)); delay(1000) }
    }
    SettingsColumn {
        Text("Backups are encrypted with your seed phrase and stored securely on our servers. We can never access your funds or data.")
        SettingToggle("Enable Automatic Backups", "", enabled, !model.busy) {
            model.action("Couldn't update backup settings.") {
                model.server.setBackupEnabled(it); enabled = it; model.services.reconcileBackupTriggers(foreground = true)
            }
        }
        SectionTitle("Last Backup")
        Text(state.lastBackupAt?.let { DateFormat.getDateTimeInstance().format(Date(it.toLong())) } ?: "No backups yet")
        Text(when (state.lastBackupStatus) {
            "in_progress" -> "Backing up…"; "success" -> "Backup complete"; "failed" -> "Backup failed. Please try again."
            else -> "Up to date or waiting for changes"
        })
        if (state.backupPending) Text("Backup pending")
        NoahButton("Backup Now", !model.busy && !model.isWalletSuspended) {
            model.action("Couldn't back up your wallet. Please try again.") {
                model.services.backupCoordinator.flush(BackupCoordinator.Reason.manual, requireEnabled = false)
                message = "Your wallet has been backed up successfully."
                if (backups != null) list()
            }
        }
        NoahButton("List Backups", !model.busy, secondary = true) { model.action("Couldn't list backups.") { list() } }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
        backups?.let { records ->
            SectionTitle("Available Backups")
            if (records.isEmpty()) Text("No backups found")
            records.forEach { backup ->
                SettingRow("Encrypted wallet snapshot", "${backup.createdAt} · ${backup.encryptedSize / 1024} KiB")
                TextButton({ deleting = backup }, enabled = !model.busy) { Text("Delete Backup", color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
            }
        }
    }
    deleting?.let { backup -> AlertDialog(onDismissRequest = { if (!model.busy) deleting = null }, title = { Text("Delete encrypted backup?") },
        dismissButton = { TextButton({ deleting = null }, enabled = !model.busy) { Text("Cancel") } },
        confirmButton = { TextButton({ model.action("Couldn't delete backup.") {
            V0BackupV2DeleteClient(model.services.client).deleteBackupObject(DeleteBackupObjectPayload(backup.backupId)).value()
            model.services.backupCoordinator.forgetUploadedBackup(backup.backupId)
            deleting = null; list()
        } }, enabled = !model.busy) { Text("Delete Backup") } }) }
}

/** Keep the grant and file alive while the receiver is active; clean on return or screen disposal. */
@Composable
private fun ShareFileButton(model: AppModel, title: String, filename: String, mime: String, write: suspend (File) -> Unit) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf<File?>(null) }
    var shareUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var returned by remember { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    fun discard() {
        shareUri?.let { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        folder?.let { model.services.scratch.discard(it) }
        folder = null; shareUri = null; returned = false
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // A chooser result alone can precede the receiver reading; wait until Noah resumes.
        returned = true
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) discard()
    }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && returned) discard()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); discard() }
    }
    NoahButton(title, !model.busy && !model.isWalletSuspended) {
        model.action("Couldn't prepare the file for sharing. Please try again.") {
            discard()
            val directory = model.services.scratch.makeFolder("share")
            folder = directory
            try {
                val file = File(directory, filename)
                withContext(Dispatchers.IO) { write(file) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                shareUri = uri
                val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .apply { clipData = ClipData.newRawUri(filename, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                launcher.launch(Intent.createChooser(send, title))
            } catch (failure: Exception) { discard(); throw failure }
        }
    }
}

@Composable
fun ExportScreen(model: AppModel) {
    SettingsColumn {
        Text("Create an encrypted backup file containing your wallet database.")
        Text("You will need your seed phrase to restore this backup. Keep it safe and never share it.")
        ShareFileButton(model, "Export Database", "noah-${BuildConfig.APP_VARIANT}.noahbackup", "application/octet-stream") {
            model.services.backupService.exportEncryptedBackup(it)
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
fun LogScreen(model: AppModel) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { lines = withContext(Dispatchers.IO) { LogFile.shared(context).lines() } }
    SettingsColumn {
        NoahButton("Refresh Logs", !model.busy, secondary = true) { model.action("Couldn't read logs.") { lines = withContext(Dispatchers.IO) { LogFile.shared(context).lines() } } }
        ShareFileButton(model, "Share Logs", "noah.log", "text/plain") { it.writeText(LogFile.shared(context).lines().joinToString("\n")) }
        if (lines.isEmpty()) Text("No logs found")
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        }
    }
}
