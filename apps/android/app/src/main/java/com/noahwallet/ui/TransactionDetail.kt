package com.noahwallet.ui

import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.tr
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.*
import com.noahwallet.history.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailsHost(app: AppModel, selectedID: String?, onClose: () -> Unit,
    onRepeatPayment: (RepeatPayment) -> Unit, showEmptyPane: Boolean = false, content: @Composable () -> Unit) {
    val selected = app.history.transactions.firstOrNull { it.id == selectedID }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = AdaptiveLayout.useDetailPane(maxWidth.value, maxHeight.value, androidx.compose.ui.platform.LocalDensity.current.fontScale)
        val repeat: (RepeatPayment) -> Unit = { onClose(); onRepeatPayment(it) }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            if (expanded && (selected != null || showEmptyPane)) {
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (selected != null) TransactionDetail(app, selected, repeat, onClose)
                    else Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("Select a transaction"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Choose a payment to see its details."))
                    }
                }
            }
        }
        if (!expanded && selected != null) WalletModalBottomSheet(onDismissRequest = onClose,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TransactionDetail(app, selected, repeat, onClose)
        }
    }
}

@Composable
private fun TransactionDetail(app: AppModel, row: HistoryTransaction, onRepeat: (RepeatPayment) -> Unit, onClose: () -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
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
            Text(tr(when (row.accounting.direction) { "Transfer" -> "Transferred"; "None" -> "Canceled"; "Outgoing" -> "Sent"; else -> "Received" }))
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
        AmountDetail("Ark fee", row.offchainFeeSat, app.amountUnit)
        if (row.hasOnchainFee == true) AmountDetail("Onchain fee", row.onchainFeeSat, app.amountUnit)
        TextButton({ technical = !technical }, Modifier.fillMaxWidth().semantics { stateDescription = if (technical) "Expanded" else "Collapsed" }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(tr(if (technical) "Hide technical details" else "Technical details"))
        }
        if (technical) {
            DetailValue("Payment ID", row.id, true)
            row.txid?.let { DetailValue("Transaction ID", it, true) }
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
private fun CopyButton(label: String, value: String) {
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    val copyLabel = tr("Copy {label}", tr(label))
    TextButton({
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        copied = true
    }, Modifier.semantics { contentDescription = copyLabel }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(if (copied) tr("Copied") else copyLabel) }
}
@Composable
private fun DetailValue(label: String, value: String, copyable: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (copyable) CopyButton(label, value)
        }
        SelectionContainer { Text(tr(value)) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
@Composable
private fun AmountDetail(label: String, amount: Long?, unit: BitcoinAmountUnit) {
    if (amount != null) DetailValue(label, BitcoinAmount.format(amount, unit, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]))
}
