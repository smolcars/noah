package com.noahwallet.recurring

import com.noahwallet.localization.tr
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.LocalFoldingFeatures
import com.noahwallet.ui.WindowRegion
import com.noahwallet.ui.usableRegion
import androidx.core.view.doOnLayout
import com.noahwallet.ui.settings.*
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.Underline
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.localization.trPlural
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
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

@Composable internal fun RecurringPaymentEditorScreen(executor: RecurringExecutor, original: RecurringPayment?, amountUnit: com.noahwallet.formatting.BitcoinAmountUnit = com.noahwallet.formatting.BitcoinAmountUnit.bip177,
                                             nav: SettingsNav? = null, done: () -> Unit) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope(); val focus = LocalFocusManager.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var label by rememberSaveable(original?.id) { mutableStateOf(original?.label ?: "") }
    var recipient by rememberSaveable(original?.id) { mutableStateOf(original?.destination ?: "") }
    var amount by rememberSaveable(original?.id) { mutableStateOf(original?.amountSat?.toString() ?: "") }
    var comment by rememberSaveable(original?.id) { mutableStateOf(original?.comment ?: "") }
    var unit by rememberSaveable(original?.id) { mutableStateOf(original?.interval?.unit ?: "month") }
    var every by rememberSaveable(original?.id) { mutableStateOf(original?.interval?.every?.toString() ?: recurringDefaultEvery(unit)) }
    val tomorrow = rememberSaveable(original?.id) { Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    var start by rememberSaveable(original?.id) { mutableLongStateOf(original?.startAt ?: tomorrow) }
    var endMode by rememberSaveable(original?.id) { mutableStateOf(if (original?.endAt != null) "date" else if (original?.maxOccurrences != null) "count" else "never") }
    var end by rememberSaveable(original?.id) { mutableLongStateOf(original?.endAt ?: tomorrow) }
    var count by rememberSaveable(original?.id) { mutableStateOf(original?.maxOccurrences?.toString() ?: "12") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val destination = runCatching { RecurringRecipient.resolve(recipient) }.getOrNull()
    val interval = RecurringInterval(unit, every.toIntOrNull() ?: 0)
    fun draft(): RecurringPayment {
        val now = System.currentTimeMillis()
        val through = recurringEndDate(end)
        return RecurringPayment(original?.id ?: UUID.randomUUID().toString(), label.trim().ifEmpty { (destination?.first ?: "").take(32) },
            destination?.first ?: "", destination?.second ?: "ark", recurringAmount(amount),
            comment = if (destination?.second == "lnurl") comment.take(140).trim() else "", interval = interval, startAt = start,
            endAt = if (endMode == "date") through else null, maxOccurrences = if (endMode == "count") count.toLongOrNull() ?: 0 else null,
            runs = original?.runs.orEmpty(), createdAt = original?.createdAt ?: now, updatedAt = now)
    }
    val scheduled = tr("Scheduled"); val saved = tr("Saved")
    fun save() {
        // Saving leaves the editor when it ends, so Settings' navigation waits for it (SettingsNav.save).
        focus.clearFocus(); error = null; saving = true; nav?.hold(true)
        scope.launch {
            try { RecurringRecipient.resolve(recipient); executor.save(draft(), original != null); haptics.success(); toasts.show(if (original == null) scheduled else saved); done() }
            catch (e: Exception) { error = e.message ?: "Couldn't save this payment. Try again." }
            finally { saving = false; nav?.hold(false) }
        }
    }
    SettingsFrame(if (original == null) tr("New payment") else tr("Edit payment"), nav?.back ?: done, foot = {
        FootPill(if (original == null) tr("Schedule payment") else tr("Save"), Modifier.testTag("recurring-save-button"), enabled = !saving, loading = saving) { save() }
    }) {
        // The amount, whole sats: ₿ before it (BIP-177) or "sats" after it.
        val bip177 = amountUnit == com.noahwallet.formatting.BitcoinAmountUnit.bip177
        val amountLabel = tr("Amount")
        FlowRow(Modifier.padding(top = 14.dp), itemVerticalAlignment = Alignment.Bottom) {
            val style = SettingsType.total.copy(fontSize = 44.sp, lineHeight = 50.sp)
            if (bip177) Text("₿", style = style, color = if (amount.isEmpty()) palette.muted else palette.foreground)
            BasicTextField(amount, { amount = it }, Modifier.width(IntrinsicSize.Min).semantics { contentDescription = amountLabel }
                .testTag("recurring-amount-input"), enabled = !saving, textStyle = style.copy(color = palette.foreground), singleLine = true,
                cursorBrush = SolidColor(palette.orange), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                decorationBox = { inner -> Box { if (amount.isEmpty()) Text("0", style = style, color = palette.muted); inner() } })
            Text(if (bip177) " " + tr("each time") else " " + tr("sats each time"), Modifier.padding(bottom = 8.dp, start = 3.dp), style = HomeType.line.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = palette.muted)
        }
        Underline(false, modifier = Modifier.padding(top = 10.dp))
        SettingsField(recipient, { recipient = it }, tr("Name, address or offer"), Modifier.testTag("recurring-recipient-input"), label = tr("To"), enabled = !saving,
            tag = destination?.second?.let { when (it) { "ark" -> tr("Ark address"); "lnurl" -> tr("Lightning address"); else -> tr("BOLT12 offer") } },
            keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
        SettingsField(label, { label = limitedRecurringText(it, 64) }, tr("e.g. Rent"), Modifier.testTag("recurring-label-input"), label = tr("Name"), enabled = !saving)
        if (destination?.second == "lnurl") SettingsField(comment, { comment = limitedRecurringText(it, 140) }, tr("Shared with the recipient"), label = tr("Note (optional)"), enabled = !saving)
        FieldLabel(tr("Repeats"))
        Segmented(listOf("week" to tr("Weekly"), "month" to tr("Monthly"), "day" to tr("Custom")), unit, enabled = !saving) { unit = it; every = recurringDefaultEvery(it) }
        val n = every.toIntOrNull() ?: 1
        Stepper(when (unit) {
            "week" -> if (n == 1) tr("Every week") else trPlural("Every {n} weeks", n)
            "month" -> if (n == 1) tr("Every month") else trPlural("Every {n} months", n)
            else -> if (n == 1) tr("Every day") else tr("Every {n} days", n.toString())
        }, n.toLong(), 1L..(if (unit == "day") 365L else 12L), enabled = !saving) { every = it.toString() }
        FieldLabel(tr("First payment"))
        RecurringDatePicker(start, true, !saving) { start = it }
        FieldLabel(tr("Ends"))
        Segmented(listOf("never" to tr("Never"), "date" to tr("On a date"), "count" to tr("After")), endMode, enabled = !saving) { endMode = it }
        if (endMode == "date") RecurringDatePicker(end, false, !saving) { end = it }
        if (endMode == "count") {
            // A Long: a migrated schedule may allow billions of payments, which saving keeps.
            val payments = count.toLongOrNull() ?: 1
            Stepper(trPlural("After {n} payments", payments), payments, 1L..Long.MAX_VALUE, enabled = !saving) { count = it.toString() }
        }
        if (interval.valid) {
            FieldLabel(tr("Next"))
            val preview = draft()
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..2).mapNotNull { preview.next(it, TimeZone.getDefault()) }.forEach { at ->
                    Box(Modifier.height(36.dp).border(1.dp, palette.hairline, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center) { Text(recurringShortDay(at, locale), style = HomeType.line.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = palette.foreground) }
                }
            }
        }
        error?.let { FinePrint(tr(it), failed = true) }
        FinePrint(tr("A reminder comes the day before. A missed payment is skipped, never paid twice."))
        if (original != null) FinePrint(tr("Saving starts a new schedule at the first payment date. Previous attempts remain visible."))
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
    val activity = context as android.app.Activity
    val bounds = androidx.window.layout.WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        ?: androidx.core.graphics.Insets.NONE
    val region = usableRegion(bounds.width() - insets.left - insets.right, bounds.height() - insets.top - insets.bottom,
        hinges.map { WindowRegion(it.left - insets.left, it.top - insets.top, it.right - insets.left, it.bottom - insets.top) })
    val dialogContext = remember(context, hinges, region, configuration) {
        if (hinges.isEmpty()) context else android.view.ContextThemeWrapper(context, context.theme).apply {
            applyOverrideConfiguration(android.content.res.Configuration(configuration).apply {
                screenWidthDp = (region.width / density).toInt(); screenHeightDp = (region.height / density).toInt()
                orientation = if (region.width > region.height) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
            })
        }
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
            val dialog = if (shownStep == "date") DatePickerDialog(dialogContext, { _, year, month, day ->
                updateDate(year, month, day)
                if (time) step = "time" else { change(candidate); step = null }
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
            else TimePickerDialog(dialogContext, { _, hour, minute ->
                updateTime(hour, minute); change(candidate); step = null
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context))
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnDismissListener { if (!disposing && step == shownStep) step = null }
            picker = dialog
            if (hinges.isNotEmpty()) dialog.window?.let { window ->
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
            if (!privacy.covered) dialog.show()
            if (dialog is DatePickerDialog) dialog.datePicker.init(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)) { _, year, month, day ->
                updateDate(year, month, day)
            }
            if (dialog is TimePickerDialog) {
                val id = context.resources.getIdentifier("timePicker", "id", "android")
                dialog.findViewById<android.widget.TimePicker>(id)?.setOnTimeChangedListener { _, hour, minute ->
                    updateTime(hour, minute)
                }
            }
        }
        onDispose { disposing = true; picker?.dismiss(); picker = null }
    }
    PickerRow(if (time) recurringDate(value) else recurringDay(value), icon = SettingsIcons.calendar, enabled = enabled) { candidate = value; step = "date" }
}
