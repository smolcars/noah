package com.noahwallet.recurring

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.doOnLayout
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.LocalFoldingFeatures
import com.noahwallet.ui.WindowRegion
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.Underline
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.settings.*
import com.noahwallet.ui.usableRegion
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

internal fun limitedRecurringText(value: String, limit: Int) = value.take(limit).dropLastWhile { it.isHighSurrogate() }

/** The amount field's sats, kept as typed: only a whole, positive number counts ("1.5" or "-1000" is 0, which saving rejects). */
internal fun recurringAmount(text: String): Long = text.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull() ?: 0

internal fun recurringDefaultEvery(unit: String) = if (unit == "day") "14" else "1"

internal fun recurringEndDate(value: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).apply {
    timeInMillis = value
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The recurring payment editor (prototype `SCR.recedit`), one screen: a big amount, the recipient (its kind as a tag),
 * the name, a note for Lightning addresses, Repeats with a stepper, the first payment, Ends, and the next three dates.
 * Today's fields, rules, limits and errors.
 */
@Composable internal fun RecurringEditorPage(app: com.noahwallet.ui.AppModel, nav: SettingsNav, id: String?) {
    val context = LocalContext.current
    val executor = remember { RecurringRuntime.shared(context) }.executor
    val original = id?.let { findSchedule(executor.schedules, it) }
    RecurringPaymentEditorScreen(executor, original, app.amountUnit, nav) { nav.pop() }
}

@Composable internal fun RecurringPaymentEditorScreen(executor: RecurringExecutor, original: RecurringPayment?,
    amountUnit: com.noahwallet.formatting.BitcoinAmountUnit = com.noahwallet.formatting.BitcoinAmountUnit.bip177,
    nav: SettingsNav? = null, done: () -> Unit) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope(); val focus = LocalFocusManager.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val tomorrow = rememberSaveable(original?.id) { Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY,
        9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    val initial = RecurringFields.of(original, tomorrow)
    var label by rememberSaveable(original?.id) { mutableStateOf(initial.label) }
    var recipient by rememberSaveable(original?.id) { mutableStateOf(initial.recipient) }
    var amount by rememberSaveable(original?.id) { mutableStateOf(initial.amount) }
    var comment by rememberSaveable(original?.id) { mutableStateOf(initial.comment) }
    var unit by rememberSaveable(original?.id) { mutableStateOf(initial.unit) }
    var every by rememberSaveable(original?.id) { mutableStateOf(initial.every) }
    var start by rememberSaveable(original?.id) { mutableLongStateOf(initial.start) }
    var endMode by rememberSaveable(original?.id) { mutableStateOf(initial.endMode) }
    var end by rememberSaveable(original?.id) { mutableLongStateOf(initial.end) }
    var count by rememberSaveable(original?.id) { mutableStateOf(initial.count) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val destination = runCatching { RecurringRecipient.resolve(recipient) }.getOrNull()
    val interval = RecurringInterval(unit, every.toIntOrNull() ?: 0)
    fun draft(): RecurringPayment =
        RecurringFields(label, recipient, amount, comment, unit, every, start, endMode, end, count).draft(original, destination, interval)
    val scheduled = tr("Scheduled"); val saved = tr("Saved")
    fun save() {
        // Saving leaves the editor when it ends, so Settings' navigation waits for it (SettingsNav.save).
        focus.clearFocus(); error = null; saving = true; nav?.hold(true)
        scope.launch {
            try { RecurringRecipient.resolve(recipient); executor.save(draft(),
                original != null); haptics.success(); toasts.show(if (original == null) scheduled else saved); done() }
            catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // UI boundary: shows why the save failed
                error = e.message ?: "Couldn't save this payment. Try again."
            }
            finally { saving = false; nav?.hold(false) }
        }
    }
    SettingsFrame(if (original == null) tr("New payment") else tr("Edit payment"), nav?.back ?: done, foot = {
        FootPill(if (original == null) tr("Schedule payment") else tr("Save"), Modifier.testTag("recurring-save-button"), enabled = !saving,
            loading = saving) { save() }
    }) {
        RecurringAmountRow(amount, amountUnit, !saving) { amount = it }
        Underline(false, modifier = Modifier.padding(top = 10.dp))
        SettingsField(recipient, { recipient = it }, tr("Name, address or offer"), Modifier.testTag("recurring-recipient-input"), label = tr("To"),
            enabled = !saving,
            tag = destination?.second?.let { recipientKind(it) },
            keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
        SettingsField(label, { label = limitedRecurringText(it, 64) }, tr("e.g. Rent"), Modifier.testTag("recurring-label-input"), label = tr("Name"),
            enabled = !saving)
        if (destination?.second == "lnurl") SettingsField(comment, { comment = limitedRecurringText(it, 140) }, tr("Shared with the recipient"),
            label = tr("Note (optional)"), enabled = !saving)
        RecurringRepeats(unit, every, !saving, { unit = it; every = recurringDefaultEvery(it) }) { every = it }
        FieldLabel(tr("First payment"))
        RecurringDatePicker(start, true, !saving) { start = it }
        RecurringEnds(endMode, end, count, !saving, { endMode = it }, { end = it }) { count = it }
        if (interval.valid) {
            FieldLabel(tr("Next"))
            RecurringNextDates(draft(), locale)
        }
        RecurringEditorNotes(error, editing = original != null)
    }
}
@Composable private fun RecurringEditorNotes(error: String?, editing: Boolean) {
    error?.let { FinePrint(tr(it), failed = true) }
    FinePrint(tr("A reminder comes the day before. A missed payment is skipped, never paid twice."))
    if (editing) FinePrint(tr("Saving starts a new schedule at the first payment date. Previous attempts remain visible."))
}
/** The editor's values: a schedule's own, or a new schedule's defaults. */
private data class RecurringFields(val label: String, val recipient: String, val amount: String, val comment: String, val unit: String,
    val every: String, val start: Long, val endMode: String, val end: Long, val count: String) {
    fun draft(original: RecurringPayment?, destination: Pair<String, String>?, interval: RecurringInterval): RecurringPayment {
        val now = System.currentTimeMillis()
        val through = recurringEndDate(end)
        return RecurringPayment(original?.id ?: UUID.randomUUID().toString(), label.trim().ifEmpty { (destination?.first ?: "").take(32) },
            destination?.first ?: "", destination?.second ?: "ark", recurringAmount(amount),
            comment = if (destination?.second == "lnurl") comment.take(140).trim() else "", interval = interval, startAt = start,
            endAt = if (endMode == "date") through else null, maxOccurrences = if (endMode == "count") count.toLongOrNull() ?: 0 else null,
            runs = original?.runs.orEmpty(), createdAt = original?.createdAt ?: now, updatedAt = now)
    }

    companion object {
        fun of(original: RecurringPayment?, tomorrow: Long): RecurringFields {
            val unit = original?.interval?.unit ?: "month"
            return RecurringFields(original?.label ?: "", original?.destination ?: "", original?.amountSat?.toString() ?: "", original?.comment ?: "",
                unit, original?.interval?.every?.toString() ?: recurringDefaultEvery(unit), original?.startAt ?: tomorrow,
                if (original?.endAt != null) "date" else if (original?.maxOccurrences != null) "count" else "never",
                original?.endAt ?: tomorrow, original?.maxOccurrences?.toString() ?: "12")
        }
    }
}

/** The amount, whole sats: ₿ before it (BIP-177) or "sats" after it. */
@Composable private fun RecurringAmountRow(amount: String, amountUnit: com.noahwallet.formatting.BitcoinAmountUnit, enabled: Boolean,
    change: (String) -> Unit) {
    val palette = LocalPalette.current
    val bip177 = amountUnit == com.noahwallet.formatting.BitcoinAmountUnit.bip177
    val amountLabel = tr("Amount")
    FlowRow(Modifier.padding(top = 14.dp), itemVerticalAlignment = Alignment.Bottom) {
        val style = SettingsType.total.copy(fontSize = 44.sp, lineHeight = 50.sp)
        if (bip177) Text("₿", style = style, color = if (amount.isEmpty()) palette.muted else palette.foreground)
        BasicTextField(amount, change, Modifier.width(IntrinsicSize.Min).semantics { contentDescription = amountLabel }
            .testTag("recurring-amount-input"), enabled = enabled, textStyle = style.copy(color = palette.foreground), singleLine = true,
            cursorBrush = SolidColor(palette.orange), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            decorationBox = { inner ->
                Box {
                    if (amount.isEmpty()) Text("0", style = style, color = palette.muted)
                    inner()
                }
            })
        Text(if (bip177) " " + tr("each time") else " " + tr("sats each time"), Modifier.padding(bottom = 8.dp, start = 3.dp),
            style = HomeType.line.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = palette.muted)
    }
}

@Composable private fun RecurringRepeats(unit: String, every: String, enabled: Boolean, changeUnit: (String) -> Unit, changeEvery: (String) -> Unit) {
    FieldLabel(tr("Repeats"))
    Segmented(listOf("week" to tr("Weekly"), "month" to tr("Monthly"), "day" to tr("Custom")), unit,
        enabled = enabled) { changeUnit(it) }
    val n = every.toIntOrNull() ?: 1
    Stepper(when (unit) {
        "week" -> if (n == 1) tr("Every week") else trPlural("Every {n} weeks", n)
        "month" -> if (n == 1) tr("Every month") else trPlural("Every {n} months", n)
        else -> if (n == 1) tr("Every day") else tr("Every {n} days", n.toString())
    }, n.toLong(), 1L..(if (unit == "day") 365L else 12L), enabled = enabled) { changeEvery(it.toString()) }
}

@Composable private fun recipientKind(type: String) = when (type) {
    "ark" -> tr("Ark address")
    "lnurl" -> tr("Lightning address")
    else -> tr("BOLT12 offer")
}

@Composable private fun RecurringEnds(endMode: String, end: Long, count: String, enabled: Boolean, changeMode: (String) -> Unit,
    changeEnd: (Long) -> Unit, changeCount: (String) -> Unit) {
    FieldLabel(tr("Ends"))
    Segmented(listOf("never" to tr("Never"), "date" to tr("On a date"), "count" to tr("After")), endMode, enabled = enabled) { changeMode(it) }
    if (endMode == "date") RecurringDatePicker(end, false, enabled) { changeEnd(it) }
    if (endMode == "count") {
        // A Long: a migrated schedule may allow billions of payments, which saving keeps.
        val payments = count.toLongOrNull() ?: 1
        Stepper(trPlural("After {n} payments", payments), payments, 1L..Long.MAX_VALUE, enabled = enabled) { changeCount(it.toString()) }
    }
}

/** The next three payment dates of the draft. */
@Composable private fun RecurringNextDates(preview: RecurringPayment, locale: java.util.Locale) {
    val palette = LocalPalette.current
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (at in (0..2).mapNotNull { preview.next(it, TimeZone.getDefault()) }) {
            Box(Modifier.height(36.dp).border(1.dp, palette.hairline, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) { Text(recurringShortDay(at, locale),
                style = HomeType.line.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = palette.foreground) }
        }
    }
}

/** The first payment or end date, as a row that opens today's date (and time) pickers, kept clear of a fold. */
@Composable private fun RecurringDatePicker(value: Long, time: Boolean, enabled: Boolean, change: (Long) -> Unit) {
    val context = LocalContext.current
    val privacy by rememberUpdatedState(com.noahwallet.ui.LocalWalletPrivacy.current)
    val covered = privacy.covered
    val hinges = LocalFoldingFeatures.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val region = dialogRegion(context as android.app.Activity, hinges)
    val dialogContext = remember(context, hinges, region, configuration) {
        if (hinges.isEmpty()) context else regionContext(context, configuration, region, density)
    }
    var picker by remember { mutableStateOf<android.app.Dialog?>(null) }
    DisposableEffect(covered, picker) {
        if (covered) picker?.hide() else picker?.show()
        onDispose { }
    }
    var step by rememberSaveable { mutableStateOf<String?>(null) }
    var candidate by rememberSaveable { mutableLongStateOf(value) }
    DisposableEffect(step, dialogContext) {
        val shownStep = step
        var disposing = false
        if (shownStep != null) {
            val calendar = Calendar.getInstance().apply { timeInMillis = candidate }
            fun updateDate(year: Int, month: Int, day: Int) {
                calendar.set(year, month, day); candidate = calendar.timeInMillis
            }
            fun updateTime(hour: Int, minute: Int) {
                calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                candidate = calendar.timeInMillis
            }
            val dialog = calendar.pickerDialog(dialogContext, shownStep == "date", android.text.format.DateFormat.is24HourFormat(context), { year, month, day ->
                updateDate(year, month, day)
                if (time) step = "time" else { change(candidate); step = null }
            }) { hour, minute ->
                updateTime(hour, minute); change(candidate); step = null
            }
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnDismissListener { if (!disposing && step == shownStep) step = null }
            picker = dialog
            if (hinges.isNotEmpty()) dialog.window?.let { window -> placeInRegion(dialog, window, region, density) }
            if (!privacy.covered) dialog.show()
            calendar.followChanges(context, dialog, ::updateDate, ::updateTime)
        }
        onDispose { disposing = true; picker?.dismiss(); picker = null }
    }
    PickerRow(if (time) recurringDate(value) else recurringDay(value), icon = SettingsIcons.calendar, enabled = enabled) {
        candidate = value; step = "date"
    }
}

/** The date or time dialog, starting at this calendar's value. */
private fun Calendar.pickerDialog(context: android.content.Context, date: Boolean, is24Hour: Boolean, pickedDate: (Int, Int, Int) -> Unit,
    pickedTime: (Int, Int) -> Unit): android.app.Dialog =
    if (date) DatePickerDialog(context, { _, year, month, day -> pickedDate(year, month, day) }, get(Calendar.YEAR), get(Calendar.MONTH),
        get(Calendar.DAY_OF_MONTH))
    else TimePickerDialog(context, { _, hour, minute -> pickedTime(hour, minute) }, get(Calendar.HOUR_OF_DAY), get(Calendar.MINUTE), is24Hour)

/** The window's area clear of system bars, cutouts and [hinges], in window coordinates. */
private fun dialogRegion(activity: android.app.Activity, hinges: List<WindowRegion>): WindowRegion {
    val bounds = androidx.window.layout.WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        ?: androidx.core.graphics.Insets.NONE
    return usableRegion(bounds.width() - insets.left - insets.right, bounds.height() - insets.top - insets.bottom,
        hinges.map { WindowRegion(it.left - insets.left, it.top - insets.top, it.right - insets.left, it.bottom - insets.top) })
}

/** A context whose configuration is [region]'s size, so the platform pickers lay out for one screen half. */
private fun regionContext(context: android.content.Context, configuration: android.content.res.Configuration, region: WindowRegion,
    density: Float) = android.view.ContextThemeWrapper(context, context.theme).apply {
    applyOverrideConfiguration(android.content.res.Configuration(configuration).apply {
        screenWidthDp = (region.width / density).toInt(); screenHeightDp = (region.height / density).toInt()
        orientation = if (region.width > region.height) android.content.res.Configuration.ORIENTATION_LANDSCAPE
        else android.content.res.Configuration.ORIENTATION_PORTRAIT
    })
}

/** Centers the dialog in [region], clear of the fold. x and y are absolute from the window's left and top. */
@android.annotation.SuppressLint("RtlHardcoded") // x is an offset from the left edge in every layout direction
private fun placeInRegion(dialog: android.app.Dialog, window: android.view.Window, region: WindowRegion, density: Float) {
    val margin = (16 * density).toInt()
    val width = minOf(region.width - margin * 2, (640 * density).toInt()).coerceAtLeast(1)
    val height = (region.height - margin * 2).coerceAtLeast(1)
    window.setGravity(android.view.Gravity.TOP or android.view.Gravity.LEFT)
    window.attributes = window.attributes.apply { x = region.left + (region.width - width) / 2; y = region.top + margin }
    window.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    window.decorView.doOnLayout { view ->
        if (dialog.isShowing) window.setLayout(width, minOf(view.height, height))
    }
}

/** Live changes before OK, so the candidate keeps the wheel's value. */
private fun Calendar.followChanges(context: android.content.Context, dialog: android.app.Dialog, updateDate: (Int, Int, Int) -> Unit,
    updateTime: (Int, Int) -> Unit) {
    if (dialog is DatePickerDialog) dialog.datePicker.init(get(Calendar.YEAR), get(Calendar.MONTH), get(Calendar.DAY_OF_MONTH)) { _, year, month, day ->
        updateDate(year, month, day)
    }
    if (dialog is TimePickerDialog) followTimePicker(context, dialog, updateTime)
}

/** Live time changes before OK, so the candidate keeps the wheel's value. */
@android.annotation.SuppressLint("DiscouragedApi") // the platform TimePicker's id isn't in android.R.id
private fun followTimePicker(context: android.content.Context, dialog: TimePickerDialog, change: (Int, Int) -> Unit) {
    val id = context.resources.getIdentifier("timePicker", "id", "android")
    dialog.findViewById<android.widget.TimePicker>(id)?.setOnTimeChangedListener { _, hour, minute -> change(hour, minute) }
}
