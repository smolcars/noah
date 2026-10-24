package com.noahwallet.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.*
import com.noahwallet.ui.activity.ActivityType
import com.noahwallet.ui.activity.DetailRow
import com.noahwallet.ui.activity.ValueKind
import com.noahwallet.ui.activity.short
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal fun recurringDate(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
internal fun recurringDay(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
/** "Nov 20": a short day for rows and the editor's next dates. */
internal fun recurringShortDay(at: Long, locale: java.util.Locale): String =
    java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(Date(at))

/** The schedule Activity's "Repeats ›" opens; null once it's gone (canceled), so the page falls back to the list. */
internal fun findSchedule(schedules: List<RecurringPayment>, id: String): RecurringPayment? = schedules.firstOrNull { it.id == id }

/** How often a schedule repeats, in words. */
@Composable
internal fun intervalText(interval: RecurringInterval): String = when {
    interval.every == 1 -> when (interval.unit) { "day" -> tr("Daily"); "week" -> tr("Weekly"); else -> tr("Monthly") }
    interval.unit == "day" -> tr("Every {n} days", interval.every.toString())
    interval.unit == "week" -> trPlural("Every {n} weeks", interval.every)
    else -> trPlural("Every {n} months", interval.every)
}

/** A schedule's line in the list: "Monthly · next Nov 20", or its status word; failures read in red. */
@Composable
private fun scheduleLine(s: RecurringPayment, running: Boolean, locale: java.util.Locale): Pair<String, Boolean> = when {
    s.inFlight != null && running -> tr("Sending") to false
    s.status == "active" -> (s.nextRunAt?.let { tr("{interval} · next {date}", intervalText(s.interval), recurringShortDay(it, locale)) } ?: intervalText(s.interval)) to false
    s.status == "paused" -> tr("Paused") to false
    s.status == "completed" -> tr("Completed") to false
    s.status == "needs_approval" -> tr("Needs approval") to true
    else -> tr("Needs attention") to true
}

/** The recipient's mark: an initial for Lightning addresses, Ark's or Lightning's glyph otherwise. */
@Composable
private fun ScheduleAvatar(s: RecurringPayment, large: Boolean) {
    val needsYou = s.status.startsWith("needs_")
    SettingsAvatar(if (large) 60.dp else 42.dp, initial = if (s.destinationType == "lnurl") s.destination.take(1) else null,
        icon = if (s.destinationType == "ark") SettingsIcons.ark else SettingsIcons.lightning, badge = needsYou)
}

/**
 * Recurring payments (prototype `SCR.recurring`): Activity-style rows, "Needs approval" with the red badge, Run due
 * payments, and + for a new one. Shown on every device.
 */
@Composable
internal fun RecurringListPage(app: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val locale = java.util.Locale.forLanguageTag(app.localeIdentifier)
    val runtime = remember { RecurringRuntime.shared(context) }
    val executor = runtime.executor
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { executor.reload(); runtime.executeInForeground() }
    val shown = executor.schedules
    SettingsFrame(tr("Recurring payments"), nav.back, line = if (shown.isEmpty()) null else tr("Paid from your Ark balance."), actions = {
        com.noahwallet.ui.activity.BarButton(SettingsIcons.plus, tr("New payment"), Modifier.testTag("recurring-new-button")) {
            if (!app.isWalletSuspended) nav.open(EditorPage(null))
        }
    }) {
        if (shown.isEmpty()) EmptyRecurring(!app.isWalletSuspended) { nav.open(EditorPage(null)) }
        else Column(Modifier.padding(top = 8.dp)) {
            shown.forEachIndexed { index, s ->
                val (line, failed) = scheduleLine(s, executor.running, locale)
                Row(Modifier.fillMaxWidth().height(66.dp)
                    .drawBehind { if (index < shown.lastIndex) drawRect(palette.hairline, Offset(56.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 56.dp.toPx(), 1.dp.toPx())) }
                    .clickable(role = Role.Button) { nav.open(SchedulePage(s.id)) }.testTag("recurring-row-${s.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ScheduleAvatar(s, large = false)
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = HomeType.rowTitle, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(line, style = HomeType.small, color = if (failed) palette.failure else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(s.formattedAmount(app.amountUnit, locale).let { if (app.hidden) "••••" else it }, style = HomeType.amount,
                        color = if (s.status == "active") palette.foreground else palette.muted, maxLines = 1)
                }
            }
        }
        if (shown.any { it.status == "active" }) SettingsLink(if (executor.running) tr("Sending…") else tr("Run due payments now"),
            Modifier.padding(top = 6.dp).testTag("recurring-run-due"), enabled = !executor.running && !app.isWalletSuspended) {
            actionError = null
            scope.launch { try { runtime.executeInForeground(manual = true) } catch (e: Exception) { actionError = e.message ?: "Couldn't update recurring payments. Try again." } }
        }
        if (shown.isNotEmpty()) FinePrint(tr("Keep notifications on so payments can go through while Noah is closed."))
        if (executor.syncPending) FinePrint(tr("Wake-up schedule hasn't synced yet. Noah will retry when opened."))
        (actionError ?: executor.error)?.let { FinePrint(tr(it), failed = true) }
    }
}

/** No payments yet: ghost rows, a line and New payment. */
@Composable
private fun EmptyRecurring(enabled: Boolean, create: () -> Unit) {
    val palette = LocalPalette.current
    val initial = "r" // a recipient's initial in the ghost rows; not copy
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 300.dp).fillMaxWidth().clearAndSetSemantics { }) {
            listOf<@Composable () -> Unit>(
                { Text(initial, style = HomeType.avatar, color = palette.muted) },
                { Icon(painterResource(SettingsIcons.lightning), null, Modifier.size(20.dp), tint = palette.muted) },
                { Icon(painterResource(SettingsIcons.repeat), null, Modifier.size(20.dp), tint = palette.muted) },
            ).forEachIndexed { i, glyph ->
                Row(Modifier.fillMaxWidth().height(60.dp).graphicsLayer { alpha = 1 - i * .28f }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(42.dp).border(1.5.dp, palette.hairline, CircleShape), contentAlignment = Alignment.Center) { glyph() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.fillMaxWidth(listOf(.56f, .44f, .60f)[i]).height(10.dp).background(palette.soft, RoundedCornerShape(5.dp)))
                        Box(Modifier.fillMaxWidth(listOf(.38f, .30f, .34f)[i]).height(10.dp).background(palette.soft, RoundedCornerShape(5.dp)))
                    }
                    Box(Modifier.size(54.dp, 12.dp).background(palette.soft, RoundedCornerShape(6.dp)))
                }
            }
        }
        Text(tr("No recurring payments"), Modifier.padding(top = 26.dp, bottom = 6.dp), style = HomeType.listTitle, color = palette.foreground)
        Text(tr("Rent, allowances or donations, paid on time."), Modifier.widthIn(max = 280.dp).padding(bottom = 18.dp), style = HomeType.line,
            color = palette.muted, textAlign = TextAlign.Center)
        com.noahwallet.ui.home.Pill(tr("New payment"), height = 40.dp, style = ActivityType.link, enabled = enabled, onClick = create)
    }
}

/**
 * One recurring payment (prototype `SCR.recdetail`): what Activity's "Repeats ›" opens. Pause, Resume or "Review and pay"
 * at the bottom; Edit, Check status, the two-step resume after checking, and Cancel as rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleScreen(app: AppModel, nav: SettingsNav, id: String, executor: RecurringExecutor = RecurringRuntime.shared(LocalContext.current).executor) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val locale = java.util.Locale.forLanguageTag(app.localeIdentifier)
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var cancelling by rememberSaveable { mutableStateOf(false) }
    var overrideStep by rememberSaveable { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<RecurringExecutor.Review?>(null) }
    var preparing by remember { mutableStateOf(false) }
    // An approval being prepared or open: an OS link can't replace it.
    com.noahwallet.ui.ReviewOpen(app.openReviews, review != null || preparing)
    var actionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { executor.reload(); loaded = true }
    val s = findSchedule(executor.schedules, id)
    // A canceled schedule falls back to the list.
    LaunchedEffect(s == null, loaded) { if (loaded && s == null) nav.pop() }
    if (s == null) { SettingsFrame(tr("Recurring payment"), nav.back, hero = true) {}; return }
    fun act(done: String? = null, work: suspend () -> Unit) {
        actionError = null
        scope.launch {
            try { work(); done?.let { haptics.success(); toasts.show(it) } }
            catch (e: Exception) { actionError = e.message ?: "Couldn't update recurring payments. Try again." }
        }
    }
    val paused = tr("Paused"); val resumed = tr("Resumed"); val canceled = tr("Canceled")
    val attention = s.status == "needs_attention" || s.inFlight != null
    val adjustable = s.status != "completed" && (s.status != "needs_attention" || s.requiresPolicyApproval)
    val canResume = s.status == "active" || (s.inFlight == null || s.requiresPolicyApproval) && !app.isWalletSuspended
    val resumeTitle = if (s.status == "active") tr("Pause") else if (s.requiresPolicyApproval) tr("Approve and resume") else tr("Resume")
    fun pauseOrResume() = act(if (s.status == "active") paused else resumed) { if (s.status == "active") executor.pause(s.id) else executor.resume(s.id) }
    SettingsFrame(tr("Recurring payment"), nav.back, hero = true, foot = when {
        s.status == "needs_approval" -> ({
            FootPill(tr("Review and pay"), Modifier.testTag("recurring-review-pay"), enabled = !executor.running && !executor.controller.locked && !app.isWalletSuspended) {
                act { preparing = true; try { review = executor.review(s.id) } finally { preparing = false } }
            }
        })
        adjustable -> ({ FootPill(resumeTitle, Modifier.testTag("recurring-pause-resume"), outlined = s.status == "active", enabled = canResume) { pauseOrResume() } })
        else -> null
    }) {
        Column(Modifier.padding(top = 6.dp)) {
            ScheduleAvatar(s, large = true)
            Text(if (app.hidden) "••••" else s.formattedAmount(app.amountUnit, locale), Modifier.padding(top = 18.dp), style = ActivityType.big, color = palette.foreground, maxLines = 1)
            val (line, failed) = scheduleLine(s, executor.running, locale)
            Text(line, Modifier.padding(top = 10.dp), style = ActivityType.status, color = if (failed) palette.failure else if (s.status == "paused") palette.muted else palette.foreground)
            Text(s.label, Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted)
        }
        Column(Modifier.padding(top = 22.dp)) {
            if (s.destinationType == "lnurl") DetailRow(tr("To"), s.destination, copy = s.destination, toasts = toasts)
            else DetailRow(tr("To"), short(s.destination, 10, 4), ValueKind.mono, copy = s.destination, toasts = toasts)
            DetailRow(tr("Via"), if (s.destinationType == "ark") tr("Ark") else tr("Lightning"),
                glyph = if (s.destinationType == "ark") SettingsIcons.ark else SettingsIcons.lightning, toasts = toasts)
            if (!s.unsupportedStorage) DetailRow(tr("Repeats"), intervalText(s.interval), toasts = toasts)
            if (s.status == "active") s.nextRunAt?.let { DetailRow(tr("Next"), recurringDate(it), toasts = toasts) }
            DetailRow(tr("Ends"), s.endAt?.let { recurringDay(it) } ?: s.maxOccurrences?.let { trPlural("After {n} payments", it) } ?: tr("Never"), toasts = toasts)
            DetailRow(tr("Paid"), trPlural("{n} payments", s.occurrencesPaid), toasts = toasts, last = s.runs.isEmpty())
            s.runs.firstOrNull()?.let { run ->
                DetailRow(tr("Last"), recurringDate(run.attemptedAt) + " · " + when (run.status) { "success" -> tr("Sent"); "skipped" -> tr("Skipped"); else -> tr("Failed") },
                    toasts = toasts, failed = run.status != "success" && run.status != "skipped", last = true)
            }
        }
        s.lastError?.let { FinePrint(s.issue?.message(context) ?: tr(it), failed = true) }
        val rows = mutableListOf<@Composable (Boolean) -> Unit>()
        if (adjustable && (s.inFlight == null || s.requiresPolicyApproval)) rows += { d -> SettingsRow(tr("Edit"), divider = d, enabled = !app.isWalletSuspended,
            modifier = Modifier.testTag("recurring-edit")) { nav.open(EditorPage(s.id)) } }
        if (s.status == "needs_approval") rows += { d -> SettingsRow(resumeTitle, end = RowEnd.None, divider = d, enabled = canResume) { pauseOrResume() } }
        if (attention) rows += { d -> SettingsRow(tr("Check status"), end = RowEnd.None, divider = d, enabled = !executor.running,
            modifier = Modifier.testTag("recurring-check")) { act { executor.checkStatus(s.id) } } }
        if (attention && !s.requiresPolicyApproval) rows += { d -> SettingsRow(tr("Resume after checking history"), divider = d, enabled = !executor.running,
            modifier = Modifier.testTag("recurring-override")) { overrideStep = 1 } }
        rows += { d -> SettingsRow(if (s.status == "completed") tr("Remove payment") else tr("Cancel payment"), danger = true, divider = d,
            modifier = Modifier.testTag("recurring-cancel")) { cancelling = true } }
        SettingsGroup(rows = rows)
        actionError?.let { FinePrint(tr(it), failed = true) }
    }
    if (cancelling) SettingsSheet({ cancelling = false }, tr("Cancel {name}?", s.label),
        tr("No more payments will be sent. Payments already sent can't be undone. An in-progress payment may still complete.")) { close ->
        SheetActions(tr("Keep payment"), if (s.status == "completed") tr("Remove payment") else tr("Cancel payment"), onCancel = close, danger = true,
            confirmTag = "recurring-cancel-confirm", onConfirm = { close(); act(canceled) { executor.cancel(s.id) } })
    }
    // Both resume steps stay: "Have you checked?", then "You could pay twice".
    if (overrideStep == 1) SettingsSheet({ overrideStep = 0 }, tr("Have you checked?"),
        tr("Check Activity for this payment's last attempt and ask the recipient whether it arrived.")) { close ->
        SheetActions(tr("Keep stopped"), tr("I checked"), onCancel = close, confirmTag = "recurring-override-checked", onConfirm = { overrideStep = 2 })
    }
    if (overrideStep == 2) SettingsSheet({ overrideStep = 0 }, tr("You could pay twice"), tr(RecurringExecutor.overrideWarning)) { close ->
        SheetActions(tr("Keep stopped"), tr(RecurringExecutor.overrideConfirmation), onCancel = close, danger = true, confirmTag = "recurring-override-confirm",
            onConfirm = { close(); act(resumed) { executor.overrideAndResume(s.id, true) } })
    }
    review?.let { item ->
        // Today's manual approval: Send's confirmation and outcome, unchanged.
        WalletModalBottomSheet(onDismissRequest = { if (!executor.controller.locked) review = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            if (executor.controller.locked) SendOutcome(executor.controller, app) { act {
                executor.controller.acknowledge()
                if (!executor.controller.locked) { review = null; executor.reload() }
            } }
            else SendConfirmation(item.payment, item.quote, executor.controller, confirm = { act {
                RecurringRuntime.shared(context).executeInForeground(manual = true, approval = item)
                if (!executor.controller.locked) review = null
            } }, cancel = { review = null })
        }
    }
}
