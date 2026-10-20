package com.noahwallet.onchain

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.AppModel
import kotlinx.coroutines.launch

@Composable
fun UnilateralExitScreen(app: AppModel) {
    var detailID by rememberSaveable { mutableStateOf<String?>(null) }
    val id = detailID
    if (id != null) {
        BackHandler { detailID = null }
        ExitVtxoDetailScreen(app, id) { detailID = null }
        return
    }
    val model = rememberOnchainModel(app)
    var selected by remember { mutableStateOf(setOf<String>()) }
    var destination by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var deposit by remember { mutableStateOf(false) }
    var depositFee by remember { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    val exits = model.overview?.exits.orEmpty()
    val eligible = model.overview?.vtxos.orEmpty().filter { it.eligible }
    val pending = exits.filter { it.state !in ExitTimeline.terminal }
    LaunchedEffect(model.quote) { model.quote?.let { depositFee = it.chainFee } }
    OnchainScreen(app, model) {
        Text("Emergency Exit", style = MaterialTheme.typography.headlineSmall)
        Text("Recover your Ark funds onchain if the Ark server is unavailable. Exits require confirmed onchain funds for broadcast fees and a timelock before claiming.")
        Text("Reading this screen and estimating fees never broadcasts an exit.")
        model.overview?.let { overview ->
            AmountRow(app, "Pending exit value", overview.pendingTotal)
            AmountRow(app, "Confirmed onchain funds", overview.confirmed)
            Text("Block height: ${overview.tip}")
            overview.claimableHeight?.let { Text("All claimable at: Block $it") }
        }
        TextButton({ deposit = true }) { Text("Deposit onchain funds") }
        Text("Start an exit", style = MaterialTheme.typography.titleLarge)
        Text("VTXOs below 330 sats cannot exit onchain.")
        TextButton({ selected = eligible.map { it.id }.toSet(); model.invalidate() }, enabled = !model.busy) { Text("Select all eligible VTXOs") }
        eligible.forEach { vtxo ->
            Row {
                Checkbox(vtxo.id in selected, { checked -> selected = if (checked) selected + vtxo.id else selected - vtxo.id; model.invalidate() }, enabled = !model.busy)
                AmountRow(app, vtxo.id.take(12) + "…", vtxo.amount)
            }
        }
        if (eligible.isEmpty()) Text("No eligible spendable VTXOs.")
        Button({ scope.launch { model.review(OnchainAction.startExit, selected.toList()) } }, enabled = !model.busy && selected.isNotEmpty()) { Text("Review selected exit") }
        TextButton({ scope.launch { model.review(OnchainAction.startExit, eligible.map { it.id }) } }, enabled = !model.busy && eligible.isNotEmpty()) { Text("Review entire wallet exit") }
        if (pending.isNotEmpty()) {
            HorizontalDivider()
            Text("Noah progresses exits automatically. You can also progress all pending exits manually.")
            Button({ scope.launch { model.review(OnchainAction.progress, pending.map { it.id }) } }, enabled = !model.busy) { Text("Progress Exits") }
        }
        if (exits.any { it.claimable }) {
            Text("Claim recovered funds", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(destination, { destination = it; model.invalidate() }, label = { Text("Bitcoin destination address") }, singleLine = true, enabled = !model.busy)
            Button({ scope.launch { model.review(OnchainAction.claim, exits.filter { it.claimable }.map { it.id }, destination = destination.trim()) } },
                enabled = !model.busy && destination.isNotBlank()) { Text("Review claim") }
        }
        HorizontalDivider()
        Text("Exits", style = MaterialTheme.typography.titleLarge)
        FilterMenu(filter, listOf("All") + ExitTimeline.labels.keys.toList()) { filter = it }
        if (exits.isEmpty()) Text("No exits have been started.")
        exits.filter { filter == "All" || it.state == filter }.forEach { exit ->
            TextButton({ detailID = exit.id }, enabled = !model.busy) { Text("${ExitTimeline.labels[exit.state] ?: exit.state} · ${amountText(app, exit.amount)} · ${exit.id.take(12)}…") }
        }
    }
    if (deposit && !app.isWalletSuspended) ExitDepositSheet(app, depositFee) { deposit = false }
}

@Composable
fun ExitVtxoDetailScreen(app: AppModel, id: String, back: () -> Unit) {
    val model = rememberOnchainModel(app, id)
    val scope = rememberCoroutineScope()
    OnchainScreen(app, model) {
        TextButton(back) { Text("Back to exits") }
        Text("Exit Details", style = MaterialTheme.typography.headlineSmall)
        val exit = model.exit
        if (exit != null) {
            AmountRow(app, "Amount", exit.amount)
            DetailRow("VTXO ID", id); Text("State: ${ExitTimeline.labels[exit.state] ?: exit.state}")
            exit.details.claimable_height?.let { height ->
                Text("Claimable at: Block $height"); Text("Remaining: ${maxOf(0, height - (model.overview?.tip ?: 0))} blocks")
            }
            if (exit.cancelable) TextButton({ scope.launch { model.review(OnchainAction.cancel, listOf(id)) } }, enabled = !model.busy) { Text("Cancel exit", color = MaterialTheme.colorScheme.error) }
            exit.timeline.forEach { item ->
                HorizontalDivider()
                Text(item.label + if (item.isCurrent) " · Current" else "", style = MaterialTheme.typography.titleLarge)
                Text(item.description)
                if (item.count > 1) Text("Updates: ${item.count}")
                item.startHeight?.let { Text("First height: $it") }; item.endHeight?.let { Text("Latest height: $it") }
                item.details?.let { details ->
                    details.tip_height?.let { Text("Synced tip: $it") }
                    details.confirmed_block?.let { Text("Confirmed block: ${it.height}") }
                    details.claimable_since?.let { Text("Claimable since: ${it.height}") }
                    details.last_scanned_block?.let { Text("Last scanned: ${it.height}") }
                    (details.claim_txid ?: details.txid)?.let { TransactionRow(it) }
                    details.transactions.orEmpty().forEachIndexed { index, tx ->
                        Text("Exit transaction ${index + 1}"); TransactionRow(tx.txid); Text(tx.status.kind.replace('-', ' '))
                        tx.status.child_txid?.let { Text("Child transaction"); TransactionRow(it) }
                        tx.status.block?.let { Text("Confirmed: Block ${it.height}") }
                    }
                }
            }
        } else if (!model.loading) Text("Exit status is unavailable. Pull to refresh.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExitDepositSheet(app: AppModel, fee: Long, dismiss: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(app.isWalletSuspended) {
        try { if (!onchainReady(app)) fail(OnchainFailure.unavailable); address = app.session.exitDepositAddress() }
        catch (_: Exception) { error = "Couldn't load a deposit address. Close and try again when the wallet is ready." }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Deposit Onchain Funds", style = MaterialTheme.typography.headlineSmall)
            if (fee > 0) AmountRow(app, "Suggested deposit", fee)
            Text("Broadcast fees are paid separately from confirmed onchain funds. Deposit bitcoin and wait for confirmation before progressing an exit. The amount required may vary.")
            address?.let { value ->
                com.noahwallet.ui.ReceiveQR(value) { copy(context, value) }
                DetailRow("Bitcoin deposit address", value)
                TextButton({ copy(context, value) }) { Text("Copy Bitcoin address") }
                TextButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value), "Share Bitcoin address")) }) { Text("Share") }
            } ?: Text(error ?: "Loading address…")
            TextButton(dismiss) { Text("Done") }
        }
    }
}
