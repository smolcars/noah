package com.noahwallet.recurring

import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.*
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal fun recurringDate(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
internal fun recurringDay(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
/** The schedules to show: just [focus] when Activity opened it and it still exists, else all of them. */
internal fun focusedSchedules(schedules: List<RecurringPayment>, focus: String?): List<RecurringPayment> =
    schedules.filter { it.id == focus }.ifEmpty { schedules }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RecurringPaymentsScreen(app: AppModel) {
    val context = LocalContext.current
    val runtime = remember { RecurringRuntime.shared(context) }
    val executor = runtime.executor
    val scope = rememberCoroutineScope()
    var editor by rememberSaveable { mutableStateOf(false) }
    var editingID by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = executor.schedules.firstOrNull { it.id == editingID }
    var cancelling by remember { mutableStateOf<RecurringPayment?>(null) }
    var overriding by remember { mutableStateOf<RecurringPayment?>(null) }
    var overrideStep by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<RecurringExecutor.Review?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    // Activity's Repeats › opens one schedule; "Show all" returns to the list.
    var focus by rememberSaveable { mutableStateOf(app.recurringSchedule.also { app.recurringSchedule = null }) }
    val shown = focusedSchedules(executor.schedules, focus)
    fun act(work: suspend () -> Unit) { actionError = null; scope.launch { try { work() } catch (e: Exception) { actionError = e.message ?: "Couldn't update recurring payments. Try again." } } }
    LaunchedEffect(Unit) { executor.reload(); runtime.executeInForeground() }
    BackHandler(editor) { editor = false }
    if (editor) { RecurringPaymentEditorScreen(executor, editing, app.amountUnit) { editor = false }; return }
    SettingsColumn {
        Text(tr("Automatic payments from your Ark balance. Lightning fees must fit both the quoted fee and the server's worst-case fee limit. Ark payments must have no fee. Other payments need manual approval. Keep notifications enabled."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        NoahButton("New recurring payment", !app.isWalletSuspended, modifier = Modifier.testTag("recurring-new-button")) { editingID = null; editor = true }
        if (executor.schedules.isEmpty()) Text(tr("No recurring payments yet. Schedule rent, subscriptions, allowances or donations to an Ark address, Lightning address or BOLT12 offer."))
        if (shown.size < executor.schedules.size) TextButton({ focus = null }, Modifier.testTag("recurring-show-all")) { Text(tr("Show all")) }
        shown.forEach { s ->
            HorizontalDivider(Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text(tr(if (s.inFlight != null && executor.running) "Sending" else when (s.status) {
                    "active" -> "Active"; "paused" -> "Paused"; "completed" -> "Completed"; "needs_approval" -> "Needs approval"; else -> "Needs attention"
                }), color = if (s.status.startsWith("needs_")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer { Text(s.destination, style = MaterialTheme.typography.bodySmall) }
            Text(s.formattedAmount(app.amountUnit, java.util.Locale.forLanguageTag(app.localeIdentifier)), style = MaterialTheme.typography.headlineMedium)
            if (!s.unsupportedStorage) Text(tr("${s.interval.description} · ${s.occurrencesPaid} paid" + (s.maxOccurrences?.let { " of $it" } ?: "")))
            if (s.status == "active") s.nextRunAt?.let { Text(tr("Next payment: {_gt_value_2}", recurringDate(it))) }
            s.endAt?.let { Text(tr("Ends: {_gt_value_2}", recurringDay(it))) }
            s.runs.firstOrNull()?.let { Text(tr("Last attempt: {_gt_value_2} ({_gt_value_3})", recurringDate(it.attemptedAt), when (it.status) { "success" -> "Successful"; "skipped" -> "Skipped"; else -> "Failed" }), style = MaterialTheme.typography.bodySmall) }
            s.lastError?.let { Text(s.issue?.message(androidx.compose.ui.platform.LocalContext.current) ?: tr(it), color = MaterialTheme.colorScheme.error) }
            if (s.status == "needs_approval") NoahButton("Review and pay manually", !executor.running && !executor.controller.locked && !app.isWalletSuspended) { act { review = executor.review(s.id) } }
            if (s.status == "needs_attention" || s.inFlight != null) {
                TextButton({ act { executor.checkStatus(s.id) } }, enabled = !executor.running) { Text(tr("Check payment status")) }
                if (!s.requiresPolicyApproval) TextButton({ overriding = s; overrideStep = 1 }, enabled = !executor.running) { Text(tr("Resume after checking history…"), color = MaterialTheme.colorScheme.error) }
            }
            if (s.status != "completed" && (s.status != "needs_attention" || s.requiresPolicyApproval)) {
                if (s.inFlight == null || s.requiresPolicyApproval) TextButton({ editingID = s.id; editor = true }, enabled = !app.isWalletSuspended) { Text(tr("Edit payment")) }
                TextButton({ act { if (s.status == "active") executor.pause(s.id) else executor.resume(s.id) } }, enabled = s.status == "active" || (s.inFlight == null || s.requiresPolicyApproval) && !app.isWalletSuspended) { Text(tr(if (s.status == "active") "Pause" else if (s.requiresPolicyApproval) "Approve and resume" else "Resume")) }
            }
            TextButton({ cancelling = s }) { Text(tr(if (s.status == "completed") "Remove" else "Cancel"), color = MaterialTheme.colorScheme.error) }
        }
        if (executor.schedules.any { it.status == "active" }) NoahButton(if (executor.running) "Sending…" else "Run due payments now", !executor.running && !app.isWalletSuspended) { act { runtime.executeInForeground(manual = true) } }
        if (executor.syncPending) Text(tr("Wake-up schedule hasn't synced yet. Noah will retry when opened."), style = MaterialTheme.typography.bodySmall)
        (actionError ?: executor.error)?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
    }
    cancelling?.let { s -> WalletAlertDialog(onDismissRequest = { cancelling = null }, title = { Text(tr("Cancel recurring payment?")) },
        text = { Text(tr("No more payments will be sent. Payments already sent can't be undone. An in-progress payment may still complete.")) },
        confirmButton = { TextButton({ cancelling = null; act { executor.cancel(s.id) } }) { Text(tr("Cancel payment"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ cancelling = null }) { Text(tr("Keep payment")) } }) }
    overriding?.let { s -> WalletAlertDialog(onDismissRequest = { overriding = null }, title = { Text(tr(if (overrideStep == 1) "Have you checked?" else "You could pay twice")) },
        text = { Text(tr(if (overrideStep == 1) "Check your history and confirm with the recipient. This payment may already have been sent." else RecurringExecutor.overrideWarning)) },
        confirmButton = { TextButton({ if (overrideStep == 1) overrideStep = 2 else { overriding = null; act { executor.overrideAndResume(s.id, true) } } }) { Text(tr(if (overrideStep == 1) "I checked history" else RecurringExecutor.overrideConfirmation)) } },
        dismissButton = { TextButton({ overriding = null }) { Text(tr("Keep stopped")) } }) }
    review?.let { item ->
        WalletModalBottomSheet(onDismissRequest = { if (!executor.controller.locked) review = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            if (executor.controller.locked) SendOutcome(executor.controller, app) { act {
                executor.controller.acknowledge()
                if (!executor.controller.locked) { review = null; executor.reload() }
            } }
            else SendConfirmation(item.payment, item.quote, executor.controller, confirm = { act {
                runtime.executeInForeground(manual = true, approval = item)
                if (!executor.controller.locked) review = null
            } }, cancel = { review = null })
        }
    }
}
