package com.noahwallet.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.localization.tr
import com.noahwallet.services.backup.BackupCoordinator
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.PersistedBackupState
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.ActivityType
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.rememberHaptics
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A list refresh cannot change a completed upload into a failed backup. */
internal suspend fun backUpAndRefresh(backup: suspend () -> Unit, refresh: suspend () -> Unit): Boolean {
    backup()
    return try { refresh(); true }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

/** Backup's list, and whether its last read failed: only a read sets that, and a failed read keeps what was listed. */
internal class BackupList {
    var items by mutableStateOf<List<BackupObjectInfo>?>(null); private set
    var failed by mutableStateOf(false); private set
    suspend fun load(read: suspend () -> List<BackupObjectInfo>) {
        try { items = read(); failed = false }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    }
}

/** A produced file and how to remove its temporary staging folder. */
internal class SharedFile(val file: File, val discard: () -> Unit)

/**
 * Today's share flow: the model retains a bounded copy across rotation, the chooser reports the chosen target, and a
 * cleanup job removes it later. Returns a function that produces the file and opens the share sheet.
 */
@Composable
internal fun rememberFileShare(model: AppModel, title: String, mime: String, onShared: () -> Unit = {}): (suspend () -> SharedFile) -> Unit {
    val context = LocalContext.current
    val shareCompletion = rememberShareCompletion(onShared)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    return { produce ->
        model.action("Couldn't prepare the file for sharing. Please try again.") {
            val (file, displayName) = withContext(Dispatchers.IO) {
                model.services.scratch.withOperation {
                    val staged = produce()
                    try { model.settingsShares.retain(staged.file) to staged.file.name }
                    finally { staged.discard() }
                }
            }
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, displayName)
                val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .apply { clipData = ClipData.newRawUri(file.name, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val cleanup = androidx.work.OneTimeWorkRequestBuilder<com.noahwallet.services.sharing.SettingsShareCleanup>()
                    .setInitialDelay(com.noahwallet.services.sharing.SettingsShareFiles.retentionMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueue(cleanup)
                launcher.launch(Intent.createChooser(send, title, shareCompletion))
            }.onFailure { model.settingsShares.discard(file) }.getOrThrow()
        }
    }
}

/** The server's backup time, in your locale; the raw value if it isn't a timestamp. */
private fun backupDate(createdAt: String, locale: Locale): String = try {
    val instant = if (android.os.Build.VERSION.SDK_INT >= 26) Date.from(java.time.OffsetDateTime.parse(createdAt).toInstant()) else null
    instant?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(it) } ?: createdAt
} catch (_: Exception) { createdAt }

internal fun backupTime(at: Double?, locale: Locale): String? =
    at?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it.toLong())) }

/**
 * Backup (prototype `SCR.backup`): a status mark (up to date, backing up, failed with Try again, off, never), Back up
 * now, the automatic switch, "Backups N" opening in place with deletion behind a confirmation, Export file (a sheet that
 * names the file, then the share sheet) and one line on how to restore.
 */
@Composable
internal fun BackupPage(model: AppModel, nav: SettingsNav, live: SettingsLive, initialSheet: String? = null) {
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    var enabled by remember { mutableStateOf(model.server.state.isBackupEnabled == true) }
    val backups = remember { BackupList() }
    var deleting by remember { mutableStateOf<BackupObjectInfo?>(null) }
    var exporting by rememberSaveable { mutableStateOf(initialSheet == "export") }
    suspend fun list() = backups.load { V0BackupV2ListClient(model.services.client).listBackupObjects().value() }
    LaunchedEffect(Unit) { list() }
    val state = live.backup
    val kind = SettingsRules.backup(enabled, state.lastBackupStatus)
    val check = rememberBackupCheck(kind)
    val backedUp = tr("Backed up")
    val exported = tr("Exported")
    val share = rememberFileShare(model, tr("Export file"), "application/octet-stream") { toasts.show(exported) }
    SettingsFrame(tr("Backup"), nav.back) {
        BackupStatus(kind, state, backupTime(state.lastBackupAt, locale), check.value)
        SettingsGroup(rows = listOf(
            { d -> SettingsRow(if (kind == SettingsRules.Backup.failed) tr("Try again") else tr("Back up now"), SettingsIcons.refresh, bold = true,
                end = RowEnd.None, enabled = kind != SettingsRules.Backup.running && !model.busy && !model.isWalletSuspended, divider = d,
                modifier = Modifier.testTag("backup-now")) {
                model.action("Couldn't back up your wallet. Please try again.") {
                    backUpAndRefresh(backup = {
                        model.services.backupCoordinator.flush(BackupCoordinator.Reason.manual, requireEnabled = false)
                    }, refresh = { list() })
                    haptics.success(); toasts.show(backedUp)
                }
            } },
            { d -> SettingsRow(tr("Automatic backups"), SettingsIcons.cloud, divider = d, modifier = Modifier.testTag("backup-automatic"),
                end = RowEnd.Toggle(enabled, !model.busy) { value ->
                    model.action("Couldn't update backup settings.") {
                        model.server.setBackupEnabled(value); enabled = value; model.services.reconcileBackupTriggers(foreground = true)
                    }
                }) },
            { d -> BackupsRow(model, backups, d) { deleting = it } },
            { d -> SettingsRow(tr("Export file"), SettingsIcons.push, divider = d, enabled = !model.isWalletSuspended,
                modifier = Modifier.testTag("backup-export")) { exporting = true } },
        ))
        FinePrint(tr(
            "Encrypted with your recovery phrase before it leaves this phone. To restore, enter your recovery phrase when you set up Noah on a phone.",
        ))
    }
    deleting?.let { backup -> DeleteBackupSheet(model, backup, { deleting = null }) { list() } }
    if (exporting) ExportSheet(model, share) { exporting = false }
}

/** The check draws itself when a running backup finishes. */
@Composable
private fun rememberBackupCheck(kind: SettingsRules.Backup): Animatable<Float, AnimationVector1D> {
    val reduceMotion = LocalReduceMotion.current
    val check = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(kind) }
    LaunchedEffect(kind) {
        if (previous == SettingsRules.Backup.running && kind == SettingsRules.Backup.on && !reduceMotion) { check.snapTo(0f)
            check.animateTo(1f, NoahTokens.Motion.soft()) }
        previous = kind
    }
    return check
}

/** The page's header: backing up, failed, off, waiting for the first backup or backed up. */
@Composable
private fun BackupStatus(kind: SettingsRules.Backup, state: PersistedBackupState, last: String?, check: Float) {
    val never = state.lastBackupAt == null
    when (kind) {
        SettingsRules.Backup.running -> StatusHeader(tr("Backing up…"), tr("Encrypting and uploading")) { StatusMark(MarkKind.running) }
        SettingsRules.Backup.failed -> StatusHeader(tr("Backup failed"),
            last?.let { tr("Last good backup {date}", it) } ?: tr("Not backed up yet"), failed = true) { StatusMark(MarkKind.failed) }
        SettingsRules.Backup.off -> StatusHeader(
            if (never) tr("Not backed up yet") else tr("Automatic backups off"),
            if (never) tr("Turn it on or back up now") else tr("Last backup {date}", last!!)) { StatusMark(MarkKind.idle, SettingsIcons.cloud) }
        SettingsRules.Backup.on -> if (never) StatusHeader(tr("Not backed up yet"),
            if (state.backupPending) tr("Backup pending") else tr("Backs up when your wallet changes")) { StatusMark(MarkKind.idle, SettingsIcons.cloud) }
        else StatusHeader(tr("Backed up"), if (state.backupPending) tr("Backup pending") else last) { StatusMark(MarkKind.ok, check = check) }
    }
}

/** "Backups", expanding to the server's list; [delete] asks about one. */
@Composable
private fun BackupsRow(model: AppModel, backups: BackupList, divider: Boolean, delete: (BackupObjectInfo) -> Unit) {
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        SettingsRow(tr("Backups"), SettingsIcons.doc, value = backups.items?.size?.toString(), end = RowEnd.Expand(open), divider = divider || open,
            modifier = Modifier.testTag("backup-list")) { open = !open; haptics.selection() }
        AnimatedVisibility(open,
            enter = if (reduceMotion) fadeIn(tween(180)) else expandVertically(spring(1f, 157.9137f)) + fadeIn(spring(1f, 157.9137f)),
            exit = if (reduceMotion) fadeOut(tween(180)) else shrinkVertically(spring(1f, 157.9137f)) + fadeOut(spring(1f, 157.9137f))) {
            BackupRecords(model, backups, delete)
        }
    }
}

@Composable
private fun BackupRecords(model: AppModel, backups: BackupList, delete: (BackupObjectInfo) -> Unit) {
    val palette = LocalPalette.current
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    Column(Modifier.padding(start = 48.dp)) {
        val records = backups.items
        when {
            backups.failed && records == null -> Text(tr("Couldn't list backups."), Modifier.padding(vertical = 14.dp), style = HomeType.line,
                color = palette.failure)
            records == null -> Text(tr("Loading…"), Modifier.padding(vertical = 14.dp), style = HomeType.line, color = palette.muted)
            records.isEmpty() -> Text(tr("No backups found"), Modifier.padding(vertical = 14.dp), style = HomeType.line, color = palette.muted)
            else -> records.forEach { backup ->
                BackupLine(backupDate(backup.createdAt, locale), "${backup.encryptedSize / 1024} KB", divider = true,
                    enabled = !model.busy) { delete(backup) }
            }
        }
        if (backups.failed && records != null) Text(tr("Couldn't list backups."), Modifier.padding(vertical = 10.dp), style = HomeType.small,
            color = palette.failure)
    }
}

@Composable
private fun DeleteBackupSheet(model: AppModel, backup: BackupObjectInfo, dismiss: () -> Unit, reload: suspend () -> Unit) {
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val removed = tr("Backup deleted")
    SettingsSheet({ if (!model.busy) dismiss() }, tr("Delete this backup?"),
        tr("{date} · {size}. Your other backups stay.", backupDate(backup.createdAt, locale), "${backup.encryptedSize / 1024} KB")) { close ->
        SheetActions(tr("Cancel"), tr("Delete"), onCancel = close, danger = true, enabled = !model.busy, loading = model.busy, onConfirm = {
            model.action("Couldn't delete backup.") {
                V0BackupV2DeleteClient(model.services.client).deleteBackupObject(DeleteBackupObjectPayload(backup.backupId)).value()
                model.services.backupCoordinator.forgetUploadedBackup(backup.backupId)
                haptics.success(); toasts.show(removed); close(); reload()
            }
        })
    }
}

@Composable
private fun ExportSheet(model: AppModel, share: (suspend () -> SharedFile) -> Unit, dismiss: () -> Unit) {
    val palette = LocalPalette.current
    val filename = rememberSaveable { ExportFilename.database() }
    SettingsSheet(dismiss, tr("Export wallet file"),
        tr("An encrypted copy of this wallet. Restoring it needs your recovery phrase.")) { close ->
        Row(Modifier.padding(top = 20.dp).fillMaxWidth().tonal(RoundedCornerShape(20.dp), palette.soft).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(SettingsIcons.doc), null, Modifier.size(26.dp), tint = palette.foreground)
            Column {
                Text(filename, style = ActivityType.fileName.copy(fontSize = 13.5.sp), color = palette.foreground)
                Text(tr("Wallet database, encrypted"), style = HomeType.small, color = palette.muted)
            }
        }
        SheetActions(tr("Cancel"), tr("Export"), onCancel = close, enabled = !model.busy && !model.isWalletSuspended, loading = model.busy,
            confirmTag = "backup-export-confirm", onConfirm = {
                close()
                share {
                    // Only the encrypted archive is retained; the service's staging folder is discarded immediately.
                    val export = model.services.backupService.exportEncryptedBackup(filename)
                    SharedFile(export.archive.file) { export.discard() }
                }
            })
    }
}

/** A backup in the open list: its date, size and a delete button. */
@Composable
private fun BackupLine(date: String, weight: String, divider: Boolean, enabled: Boolean, delete: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .drawBehind { if (divider) drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(date, Modifier.weight(1f), style = NoahTokens.Typography.body, color = palette.foreground, maxLines = 1)
        Text(weight, style = HomeType.line, color = palette.muted)
        val label = tr("Delete backup")
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = delete)
            .semantics { contentDescription = label }.testTag("backup-delete"), contentAlignment = Alignment.Center) {
            Icon(painterResource(SettingsIcons.trash), null, Modifier.size(17.dp), tint = palette.muted)
        }
    }
}
