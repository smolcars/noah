package com.noahwallet.onchain

import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
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
    val destination = model.claimDestination
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var deposit by remember { mutableStateOf(false) }
    var depositFee by remember { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    val exits = model.overview?.exits.orEmpty()
    val eligible = model.overview?.vtxos.orEmpty().filter { it.eligible }
    val pending = exits.filter { it.state !in ExitTimeline.terminal }
    LaunchedEffect(model.quote) { depositFee = model.quote?.chainFee ?: 0 }
    LaunchedEffect(model.overview?.walletID) {
        if (model.overview != null && destination.isEmpty()) model.useWalletClaimAddress()
    }
    LaunchedEffect(destination, app.isWalletSuspended, model.overview?.walletID) { model.checkClaimDestination() }
    OnchainScreen(app, model) {
        Text(tr("Emergency Exit"), style = MaterialTheme.typography.headlineSmall)
        Text(tr("Recover your Ark funds onchain if the Ark server is unavailable. Exits require confirmed onchain funds for broadcast fees and a timelock before claiming."))
        Text(tr("Reading this screen and estimating fees never broadcasts an exit."))
        model.overview?.let { overview ->
            ExitOverviewSummary(app, overview)
        }
        TextButton({ deposit = true }) { Text(tr("Deposit Onchain Funds")) }
        Text(tr("Start an exit"), style = MaterialTheme.typography.titleLarge)
        Text(tr("VTXOs below 330 sats cannot exit onchain."))
        TextButton({ selected = eligible.map { it.id }.toSet(); model.invalidate() }, enabled = !model.busy) { Text(tr("Select all eligible VTXOs")) }
        eligible.forEach { vtxo ->
            VtxoSelectionRow(vtxo.id, amountText(app, vtxo.amount), vtxo.status, vtxo.id in selected, !model.busy) { checked ->
                selected = if (checked) selected + vtxo.id else selected - vtxo.id; model.invalidate()
            }
        }
        if (eligible.isEmpty()) Text(tr("No eligible spendable VTXOs."))
        Button({ scope.launch { model.review(OnchainAction.startExit, selected.toList()) } }, enabled = !model.busy && selected.isNotEmpty()) { Text(tr("Review selected exit")) }
        TextButton({ scope.launch { model.review(OnchainAction.startExit, eligible.map { it.id }) } }, enabled = !model.busy && eligible.isNotEmpty()) { Text(tr("Review entire wallet exit")) }
        if (pending.isNotEmpty()) {
            HorizontalDivider()
            Text(tr("Noah progresses exits automatically. You can also progress all pending exits manually."))
            Button({ scope.launch { model.review(OnchainAction.progress, pending.map { it.id }) } }, enabled = !model.busy) { Text(tr("Progress Exits")) }
        }
        if (exits.any { it.claimable }) {
            Text(tr("Claim recovered funds"), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(destination, model::editClaimDestination, label = { Text(tr("Bitcoin destination address")) },
                supportingText = { Text(tr(model.claimDestinationError ?: if (model.claimDestinationIsMine == false)
                    "This address isn't in your Noah wallet. The claimed funds will be sent there." else "")) },
                isError = model.claimDestinationError != null || model.claimDestinationIsMine == false,
                singleLine = true, enabled = !model.busy)
            TextButton({ scope.launch { model.useWalletClaimAddress() } }, enabled = !model.busy) { Text(tr("Use my Noah wallet address")) }
            Button({ scope.launch { model.review(OnchainAction.claim, exits.filter { it.claimable }.map { it.id }, destination = destination.trim()) } },
                enabled = !model.busy && destination.isNotBlank()) { Text(tr("Review Claim")) }
        }
        HorizontalDivider()
        Text(tr("Exits"), style = MaterialTheme.typography.titleLarge)
        FilterMenu(filter, listOf(null) + ExitTimeline.labels.keys, { key -> key?.let { ExitTimeline.labels[it] ?: it } ?: "All" }) { filter = it }
        if (exits.isEmpty()) Text(tr("No exits have been started."))
        exits.filter { filter == null || it.state == filter }.forEach { exit ->
            TextButton({ detailID = exit.id }, enabled = !model.busy) { Text(tr("${ExitTimeline.labels[exit.state] ?: exit.state} · ${amountText(app, exit.amount)} · ${exit.id.take(12)}…")) }
        }
    }
    if (deposit && !app.isWalletSuspended) ExitDepositSheet(app, depositFee) { deposit = false }
}

@Composable
internal fun ExitOverviewSummary(app: AppModel, overview: OnchainOverview) {
    val exits = overview.exits
    val eligible = overview.vtxos.filter { it.eligible }
    val pending = exits.filter { it.state !in ExitTimeline.terminal }
    DetailRow("Tracked", exits.size.toString())
    DetailRow("Pending", pending.size.toString())
    DetailRow("Claimable", exits.count { it.claimable }.toString())
    DetailRow("Claiming", exits.count { it.state == "ClaimInProgress" }.toString())
    DetailRow("Claimed", exits.count { it.state == "Claimed" }.toString())
    DetailRow("Available", eligible.size.toString())
    AmountRow(app, "Available Value", eligible.sumOf { it.amount })
    AmountRow(app, "Pending exit value", overview.pendingTotal)
    AmountRow(app, "Confirmed onchain funds", overview.confirmed)
    Text(tr("Block height: ${overview.tip}"))
    exits.mapNotNull { it.details.tip_height }.maxOrNull()?.let { DetailRow("Exit Synced Tip", it.toString()) }
    val staleCount = exits.count { (it.details.tip_height ?: overview.tip) < overview.tip }
    if (staleCount > 0) {
        Text(trPlural("{_gt_value_4} exits are behind the current chain height. Use Progress to check the chain; this can also broadcast exit transactions.", staleCount))
    }
    val belowDust = overview.vtxos.filter { it.state == "Spendable" && it.amount < ExitRules.dust }
    if (belowDust.isNotEmpty()) {
        DetailRow("Below dust limit", belowDust.size.toString())
        AmountRow(app, "Below dust limit", belowDust.sumOf { it.amount })
    }
    overview.claimableHeight?.let { Text(tr("All claimable at: Block $it")) }
}

@Composable
fun ExitVtxoDetailScreen(app: AppModel, id: String, back: () -> Unit) {
    val model = rememberOnchainModel(app, id)
    val scope = rememberCoroutineScope()
    OnchainScreen(app, model) {
        TextButton(back) { Text(tr("Back to exits")) }
        Text(tr("Exit Details"), style = MaterialTheme.typography.headlineSmall)
        val exit = model.exit
        if (exit != null) {
            AmountRow(app, "Amount", exit.amount)
            DetailRow("VTXO ID", id); Text(tr("State: ${ExitTimeline.labels[exit.state] ?: exit.state}"))
            ExitBlockStatusRows(exit.details, model.overview?.tip)
            Text(tr("Current State"), style = MaterialTheme.typography.titleLarge)
            ExitDetailsRows(exit.details)
            if (exit.cancelable) TextButton({ scope.launch { model.review(OnchainAction.cancel, listOf(id)) } }, enabled = !model.busy) { Text(tr("Cancel Exit"), color = MaterialTheme.colorScheme.error) }
            exit.timeline.forEach { item ->
                HorizontalDivider()
                Text(tr(item.label + if (item.isCurrent) " · Current" else ""), style = MaterialTheme.typography.titleLarge)
                Text(tr(item.description))
                if (item.count > 1) Text(tr("Updates: ${item.count}"))
                item.startHeight?.let { Text(tr("First height: $it")) }; item.endHeight?.let { Text(tr("Latest height: $it")) }
                item.details?.let { ExitDetailsRows(it) }
            }
        } else if (!model.loading) Text(tr("Exit status is unavailable. Pull to refresh."))
    }
}

@Composable
internal fun ExitBlockStatusRows(details: ExitDetails, tip: Long?) {
    DetailRow("Current Height", tip?.toString() ?: tr("Unknown"))
    DetailRow("Exit Tip", details.tip_height?.toString() ?: tr("Unknown"))
    DetailRow("Claimable Height", details.displayClaimableHeight?.toString() ?: tr("Unknown"))
    DetailRow("Remaining", tr(OnchainDisplay.remaining(tip, details.displayClaimableHeight),
        (details.displayClaimableHeight ?: 0) - (tip ?: 0)))
}

@Composable
internal fun ExitDetailsRows(details: ExitDetails) {
    details.tip_height?.let { DetailRow("Synced tip", it.toString()) }
    details.confirmed_block?.let { DetailRow("Confirmed block", OnchainDisplay.block(it)) }
    details.claimable_since?.let { DetailRow("Claimable since", OnchainDisplay.block(it)) }
    details.last_scanned_block?.let { DetailRow("Last scanned", OnchainDisplay.block(it)) }
    details.block?.let { DetailRow("Claimed block", OnchainDisplay.block(it)) }
    details.displayClaimableHeight?.let { DetailRow("Claimable at", it.toString()) }
    details.kind?.let { DetailRow("State detail", it) }
    (details.claim_txid ?: details.txid)?.let { TransactionRow(it) }
    details.transactions.orEmpty().forEachIndexed { index, tx ->
        Text(tr("Exit transaction ${index + 1}")); TransactionRow(tx.txid); Text(tr(tx.status.kind.replace('-', ' ')))
        tx.status.child_txid?.let { Text(tr("Child transaction")); TransactionRow(it) }
        tx.status.block?.let { DetailRow("Confirmed", OnchainDisplay.block(it)) }
    }
}
