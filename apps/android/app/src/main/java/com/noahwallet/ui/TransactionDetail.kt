package com.noahwallet.ui

import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.*
import com.noahwallet.history.*

/** What Home's sheet says a payment did and what it paid: Activity's shared status and fee rules, so a failed send never reads "Sent". */
internal data class PaymentSummary(val status: String, val tone: com.noahwallet.ui.activity.ActivityRules.Tone,
                                   val fees: List<com.noahwallet.ui.activity.ActivityRules.FeeLine>)
internal fun paymentSummary(row: HistoryTransaction, ownDomain: String?): PaymentSummary {
    val activity = com.noahwallet.ui.activity.ActivityRules.Row(row, com.noahwallet.ui.home.HomeRow.of(row, ownDomain))
    val (status, tone) = com.noahwallet.ui.activity.ActivityRules.status(activity)
    return PaymentSummary(status, tone, com.noahwallet.ui.activity.ActivityRules.fees(row, activity.home))
}

@Composable
internal fun TransactionDetail(app: AppModel, row: HistoryTransaction, onRepeat: (RepeatPayment) -> Unit, onClose: () -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val domain = app.server.state.lightningAddress?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }
    val summary = remember(row, domain) { paymentSummary(row, domain) }
    var technical by rememberSaveable(row.id) { mutableStateOf(false) }
    var explorerError by remember(row.id) { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().readableWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val closeLabel = tr("Close payment details")
            Text(tr("Payment details"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClose, Modifier.semantics { contentDescription = closeLabel }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(tr("Close")) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(transactionIcon(row)), null, Modifier.size(36.dp), tint = transactionColor(row))
            Text(if (row.isRenewal) trPlural(summary.status, row.inputVtxos.orEmpty().size.coerceAtLeast(1)) else tr(summary.status),
                color = if (summary.tone == com.noahwallet.ui.activity.ActivityRules.Tone.failed) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            Text(tr(BitcoinAmount.format(row.amount, app.amountUnit, locale)), style = MaterialTheme.typography.headlineLarge)
            Text(tr(row.btcPrice?.let { FiatCurrency.display(row.amount, it, app.preferredCurrency, locale) } ?: "Historical value unavailable"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr(row.receiptStatus))
            row.destination?.takeIf(String::isNotEmpty)?.let { destination ->
                Text(tr(destination), maxLines = 2, overflow = TextOverflow.Ellipsis)
                CopyButton("destination", destination)
            }
            if (row.canRepeat) Button({ onRepeat(row.repeatPayment!!) }) { Text(tr("Repeat payment")) }
        }
        DetailValue("Date", row.formattedDate())
        DetailValue("Route", if (row.type == "Lnurl") "Lightning address" else row.displayLabel)
        row.repeatPayment?.let { repeat ->
            if (repeat.amountMode == "FIAT" && repeat.fiatCurrency != null) repeat.amountInput.toDoubleOrNull()?.let {
                DetailValue("Entered as", FiatCurrency.format(it, repeat.fiatCurrency, locale))
            }
            if (repeat.comment.isNotEmpty()) DetailValue("Message", repeat.comment)
        }
        summary.fees.forEach { line ->
            DetailValue(line.label, when (val value = line.value) {
                is com.noahwallet.ui.activity.ActivityRules.FeeValue.Sats -> BitcoinAmount.format(value.sats, app.amountUnit, locale)
                is com.noahwallet.ui.activity.ActivityRules.FeeValue.UpTo -> tr("Up to {amount}", BitcoinAmount.format(value.sats, app.amountUnit, locale))
                com.noahwallet.ui.activity.ActivityRules.FeeValue.Free -> tr("Free")
                com.noahwallet.ui.activity.ActivityRules.FeeValue.None -> tr("None")
            })
        }
        TextButton({ technical = !technical }, Modifier.fillMaxWidth().semantics { stateDescription = if (technical) "Expanded" else "Collapsed" }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(tr(if (technical) "Hide technical details" else "Technical details"))
        }
        if (technical) {
            DetailValue("Payment ID", row.id, true)
            row.txid?.let { DetailValue("Transaction ID", it, true) }
            row.preimage?.let { DetailValue("Preimage", it, true, Modifier.testTag("transaction-detail-preimage")) }
            row.explorerURL(BuildConfig.APP_VARIANT)?.let { url ->
                TextButton({
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (_: android.content.ActivityNotFoundException) { explorerError = true }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(tr("View in explorer")) }
                if (explorerError) Text(tr("No browser available to open the explorer."))
            }
            row.receivedOn?.singleOrNull()?.destination?.takeIf(String::isNotEmpty)?.let { DetailValue("Invoice", it, true) }
            if (row.source == "onchain-wallet" || row.balanceChangeSat != null) {
                DetailValue("Chain status", if (row.hasConfirmation == true) "Confirmed" else "Unconfirmed")
                AmountDetail("Balance change", row.balanceChangeSat, app.amountUnit)
                row.confirmationHeight?.let { DetailValue("Block height", it.toString()) }
                row.confirmationHash?.let { DetailValue("Block hash", it, true) }
                row.txHex?.let { DetailValue("Raw transaction", it, true) }
            }
            row.movementKindLabel?.let { DetailValue("Movement type", it) }
            row.movementId?.let { DetailValue("Movement ID", it.toString(), true) }
            row.subsystemName?.let { DetailValue("Subsystem", "$it (${row.subsystemKind.orEmpty()})") }
            AmountDetail("Intended balance change", row.intendedBalanceSat, app.amountUnit)
            AmountDetail("Effective balance change", row.effectiveBalanceSat, app.amountUnit)
            row.chainAnchor?.let { DetailValue("Chain anchor", it, true) }
            row.metadataJson?.takeIf(String::isNotEmpty)?.let { DetailValue("Metadata", it, true) }
            listOf("Sent to" to row.sentTo, "Received on" to row.receivedOn).forEach { (label, values) ->
                if (!values.isNullOrEmpty()) DetailValue(label, values.joinToString("\n") { "${it.destination} · ${BitcoinAmount.format(it.amount_sat, app.amountUnit, locale)}" }, true)
            }
            listOf("Input VTXOs" to row.inputVtxos, "Output VTXOs" to row.outputVtxos, "Exited VTXOs" to row.exitedVtxos).forEach { (label, values) ->
                if (!values.isNullOrEmpty()) DetailValue(label, values.joinToString("\n"), true)
            }
        }
    }
}

@Composable
private fun CopyButton(label: String, value: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    val copyLabel = tr("Copy {label}", tr(label))
    TextButton({
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        copied = true
    }, modifier.semantics { contentDescription = copyLabel }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(if (copied) tr("Copied") else copyLabel) }
}
@Composable
internal fun DetailValue(label: String, value: String, copyable: Boolean = false, modifier: Modifier = Modifier,
    displayedValue: String = value, copyModifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (copyable) CopyButton(label, value, copyModifier)
        }
        SelectionContainer { Text(tr(displayedValue)) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
@Composable
private fun AmountDetail(label: String, amount: Long?, unit: BitcoinAmountUnit) {
    if (amount != null) DetailValue(label, BitcoinAmount.format(amount, unit, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]))
}
