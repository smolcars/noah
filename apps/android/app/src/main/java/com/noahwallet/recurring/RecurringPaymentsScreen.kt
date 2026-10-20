package com.noahwallet.recurring

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.*
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal fun recurringDate(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
internal fun recurringDay(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RecurringPaymentsScreen(app: AppModel) {
    val context = LocalContext.current
    val executor = remember { RecurringRuntime.shared(context).executor }
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecurringPayment?>(null) }
    var cancelling by remember { mutableStateOf<RecurringPayment?>(null) }
    var overriding by remember { mutableStateOf<RecurringPayment?>(null) }
    var overrideStep by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<RecurringExecutor.Review?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    fun act(work: suspend () -> Unit) { actionError = null; scope.launch { try { work() } catch (e: Exception) { actionError = e.message ?: "Couldn't update recurring payments. Try again." } } }
    LaunchedEffect(Unit) { executor.reload(); executor.execute() }
    BackHandler(editor) { editor = false }
    if (editor) { RecurringPaymentEditorScreen(executor, editing) { editor = false }; return }
    SettingsColumn {
        Text("Automatic payments from your Ark balance. Lightning fees must fit both the quoted fee and the server's worst-case fee limit. Ark payments must have no fee. Other payments need manual approval. Keep notifications enabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        NoahButton("New recurring payment", !app.isWalletSuspended) { editing = null; editor = true }
        if (executor.schedules.isEmpty()) Text("No recurring payments yet. Schedule rent, subscriptions, allowances or donations to an Ark address, Lightning address or BOLT12 offer.")
        executor.schedules.forEach { s ->
            HorizontalDivider(Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text(if (s.inFlight != null && executor.running) "Sending" else when (s.status) {
                    "active" -> "Active"; "paused" -> "Paused"; "completed" -> "Completed"; "needs_approval" -> "Needs approval"; else -> "Needs attention"
                }, color = if (s.status.startsWith("needs_")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer { Text(s.destination, style = MaterialTheme.typography.bodySmall) }
            Text("${s.amountSat} sats", style = MaterialTheme.typography.headlineMedium)
            if (!s.unsupportedStorage) Text("${s.interval.description} · ${s.occurrencesPaid} paid" + (s.maxOccurrences?.let { " of $it" } ?: ""))
            if (s.status == "active") s.nextRunAt?.let { Text("Next payment: ${recurringDate(it)}") }
            s.endAt?.let { Text("Ends: ${recurringDay(it)}") }
            s.runs.firstOrNull()?.let { Text("Last attempt: ${recurringDate(it.attemptedAt)} (${when (it.status) { "success" -> "Successful"; "skipped" -> "Skipped"; else -> "Failed" }})", style = MaterialTheme.typography.bodySmall) }
            s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (s.status == "needs_approval") NoahButton("Review and pay manually", !executor.running && !executor.controller.locked && !app.isWalletSuspended) { act { review = executor.review(s.id) } }
            if (s.status == "needs_attention" || s.inFlight != null) {
                TextButton({ act { executor.checkStatus(s.id) } }, enabled = !executor.running) { Text("Check payment status") }
                TextButton({ overriding = s; overrideStep = 1 }, enabled = !executor.running) { Text("Resume after checking history…", color = MaterialTheme.colorScheme.error) }
            }
            if (s.status != "completed" && s.status != "needs_attention") {
                if (s.inFlight == null) TextButton({ editing = s; editor = true }, enabled = !app.isWalletSuspended) { Text("Edit payment") }
                TextButton({ act { if (s.status == "active") executor.pause(s.id) else executor.resume(s.id) } }, enabled = s.status == "active" || s.inFlight == null && !app.isWalletSuspended) { Text(if (s.status == "active") "Pause" else "Resume") }
            }
            TextButton({ cancelling = s }) { Text(if (s.status == "completed") "Remove" else "Cancel", color = MaterialTheme.colorScheme.error) }
        }
        if (executor.schedules.any { it.status == "active" }) NoahButton(if (executor.running) "Sending…" else "Run due payments now", !executor.running && !app.isWalletSuspended) { act { executor.execute(manual = true) } }
        if (executor.syncPending) Text("Wake-up schedule hasn't synced yet. Noah will retry when opened.", style = MaterialTheme.typography.bodySmall)
        (actionError ?: executor.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    cancelling?.let { s -> AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("Cancel recurring payment?") },
        text = { Text("No more payments will be sent. Payments already sent can't be undone. An in-progress payment may still complete.") },
        confirmButton = { TextButton({ cancelling = null; act { executor.cancel(s.id) } }) { Text("Cancel payment", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ cancelling = null }) { Text("Keep payment") } }) }
    overriding?.let { s -> AlertDialog(onDismissRequest = { overriding = null }, title = { Text(if (overrideStep == 1) "Have you checked?" else "You could pay twice") },
        text = { Text(if (overrideStep == 1) "Check your history and confirm with the recipient. This payment may already have been sent." else RecurringExecutor.overrideWarning) },
        confirmButton = { TextButton({ if (overrideStep == 1) overrideStep = 2 else { overriding = null; act { executor.overrideAndResume(s.id, true) } } }) { Text(if (overrideStep == 1) "I checked history" else RecurringExecutor.overrideConfirmation) } },
        dismissButton = { TextButton({ overriding = null }) { Text("Keep stopped") } }) }
    review?.let { item ->
        ModalBottomSheet(onDismissRequest = { if (!executor.controller.locked) review = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            if (executor.controller.locked) SendOutcome(executor.controller) {
                executor.controller.acknowledge()
                if (!executor.controller.locked) { review = null; executor.reload() }
            }
            else SendConfirmation(item.payment, item.quote, executor.controller, confirm = { act {
                executor.execute(manual = true, approval = item)
                if (!executor.controller.locked) review = null
            } }, cancel = { review = null })
        }
    }
}
