package com.noahwallet.recurring

import com.noahwallet.localization.tr
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.NoahButton
import com.noahwallet.ui.settings.SettingsColumn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

internal fun recurringDefaultEvery(unit: String) = if (unit == "day") "14" else "1"

internal fun recurringEndDate(value: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).apply {
    timeInMillis = value
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable fun RecurringPaymentEditorScreen(executor: RecurringExecutor, original: RecurringPayment?, done: () -> Unit) {
    val scope = rememberCoroutineScope(); val focus = LocalFocusManager.current
    var label by remember { mutableStateOf(original?.label ?: "") }
    var recipient by remember { mutableStateOf(original?.destination ?: "") }
    var amount by remember { mutableStateOf(original?.amountSat?.toString() ?: "") }
    var comment by remember { mutableStateOf(original?.comment ?: "") }
    var unit by remember { mutableStateOf(original?.interval?.unit ?: "month") }
    var every by remember { mutableStateOf(original?.interval?.every?.toString() ?: recurringDefaultEvery(unit)) }
    val tomorrow = remember { Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    var start by remember { mutableLongStateOf(original?.startAt ?: tomorrow) }
    var endMode by remember { mutableStateOf(if (original?.endAt != null) "date" else if (original?.maxOccurrences != null) "count" else "never") }
    var end by remember { mutableLongStateOf(original?.endAt ?: tomorrow) }
    var count by remember { mutableStateOf(original?.maxOccurrences?.toString() ?: "12") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val destination = runCatching { RecurringRecipient.resolve(recipient) }.getOrNull()
    val interval = RecurringInterval(unit, every.toIntOrNull() ?: 0)
    fun draft(): RecurringPayment {
        val now = System.currentTimeMillis()
        val through = recurringEndDate(end)
        return RecurringPayment(original?.id ?: UUID.randomUUID().toString(), label.trim().ifEmpty { (destination?.first ?: "").take(32) },
            destination?.first ?: "", destination?.second ?: "ark", amount.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull() ?: 0,
            comment = if (destination?.second == "lnurl") comment.take(140).trim() else "", interval = interval, startAt = start,
            endAt = if (endMode == "date") through else null, maxOccurrences = if (endMode == "count") count.toLongOrNull() ?: 0 else null,
            runs = original?.runs.orEmpty(), createdAt = original?.createdAt ?: now, updatedAt = now)
    }
    SettingsColumn {
        Text(tr(if (original == null) "New recurring payment" else "Edit recurring payment"), style = MaterialTheme.typography.headlineMedium)
        Text(tr("Noah sends this payment automatically from your Ark balance. Only this recipient and amount are ever allowed, and you can pause or cancel at any time."))
        if (original != null) Text(tr("Saving starts a new schedule at the first payment date. Previous attempts remain visible."))
        OutlinedTextField(label, { label = it }, label = { Text(tr("Name")) }, placeholder = { Text(tr("e.g. Rent, Donation, VPN")) }, enabled = !saving, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(recipient, { recipient = it }, label = { Text(tr("Recipient")) }, placeholder = { Text(tr("name@domain.com, ark1… or lno1…")) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        Text(tr(destination?.second?.let { when (it) { "ark" -> "Ark address"; "lnurl" -> "Lightning address"; else -> "BOLT12 offer" } } ?: "Ark address, Lightning address or BOLT12 offer"), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(amount, { amount = it }, label = { Text(tr("Amount (sats)")) }, enabled = !saving, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Text(tr("${amount.ifEmpty { "0" }} sats per payment, plus network fees"), style = MaterialTheme.typography.bodySmall)
        if (destination?.second == "lnurl") OutlinedTextField(comment, { comment = it }, label = { Text(tr("Note (optional)")) }, supportingText = { Text(tr("Shared with the recipient")) }, enabled = !saving, modifier = Modifier.fillMaxWidth())
        Text(tr("Repeat"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("week" to "Weekly", "month" to "Monthly", "day" to "Custom").forEach { (value, title) -> FilterChip(unit == value, { unit = value; every = recurringDefaultEvery(value) }, label = { Text(tr(title)) }, enabled = !saving) }
        }
        OutlinedTextField(every, { every = it }, label = { Text(tr("Every … ${unit}s")) }, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
        RecurringDatePicker("First payment", start, true, !saving) { start = it }
        Text(tr("Ends"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("never" to "Never", "date" to "On date", "count" to "After count").forEach { (value, title) -> FilterChip(endMode == value, { endMode = value }, label = { Text(tr(title)) }, enabled = !saving) }
        }
        if (endMode == "date") RecurringDatePicker("End date", end, false, !saving) { end = it }
        if (endMode == "count") OutlinedTextField(count, { count = it }, label = { Text(tr("Number of payments")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (interval.valid) {
            Text(tr("Preview · ${interval.description}"), style = MaterialTheme.typography.titleMedium)
            val preview = draft()
            (0..2).mapNotNull { preview.next(it, TimeZone.getDefault()) }.forEach { Text(tr(recurringDate(it))) }
            Text(tr("You'll get a reminder the day before and a notification after each payment. If a payment is missed while your phone is off, Noah skips it instead of paying twice."), style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        NoahButton(if (saving) "Saving…" else if (original == null) "Schedule payment" else "Save payment", !saving) {
            focus.clearFocus(); error = null; saving = true
            scope.launch {
                try { RecurringRecipient.resolve(recipient); executor.save(draft(), original != null); done() }
                catch (e: Exception) { error = e.message ?: "Couldn't save this payment. Try again." }
                finally { saving = false }
            }
        }
        TextButton(done, enabled = !saving) { Text(tr("Back to payments")) }
    }
}
@Composable private fun RecurringDatePicker(label: String, value: Long, time: Boolean, enabled: Boolean, change: (Long) -> Unit) {
    val context = LocalContext.current
    val privacy by rememberUpdatedState(com.noahwallet.ui.LocalWalletPrivacy.current)
    val covered = privacy.covered
    var picker by remember { mutableStateOf<android.app.Dialog?>(null) }
    DisposableEffect(covered, picker) {
        if (covered) picker?.hide() else picker?.show()
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }
    OutlinedButton({
        val calendar = Calendar.getInstance().apply { timeInMillis = value }
        picker = DatePickerDialog(context, { _, year, month, day ->
            calendar.set(year, month, day)
            if (time) { picker = TimePickerDialog(context, { _, hour, minute -> calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0); change(calendar.timeInMillis) }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).also { dialog ->
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnDismissListener { if (picker === dialog) picker = null }; if (!privacy.covered) dialog.show()
            } }
            else change(calendar.timeInMillis)
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).also { dialog ->
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnDismissListener { if (picker === dialog) picker = null }; if (!privacy.covered) dialog.show()
            }
    }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(tr("$label: ${if (time) recurringDate(value) else recurringDay(value)}")) }
}
