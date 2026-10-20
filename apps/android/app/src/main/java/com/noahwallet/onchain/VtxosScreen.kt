package com.noahwallet.onchain

import com.noahwallet.localization.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.noahwallet.ui.AppModel
import kotlinx.coroutines.launch

@Composable
fun VtxosScreen(app: AppModel) {
    var detailID by rememberSaveable { mutableStateOf<String?>(null) }
    val id = detailID
    if (id != null) {
        BackHandler { detailID = null }
        VtxoDetailScreen(app, id) { detailID = null }
        return
    }
    val model = rememberOnchainModel(app)
    var filter by rememberSaveable { mutableStateOf<VtxoStatus?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val vtxos = model.overview?.vtxos.orEmpty()
    val visible = vtxos.filter { filter == null || it.statusID == filter }
    OnchainScreen(app, model) {
        Text(tr("VTXOs"), style = MaterialTheme.typography.headlineSmall)
        FilterMenu(filter, listOf(null) + VtxoStatus.entries, { it?.label ?: "All" }) { filter = it }
        AmountRow(app, "Total (${vtxos.size} VTXOs)", vtxos.sumOf { it.amount })
        TextButton({ selecting = !selecting; selected = emptySet(); model.invalidate() }, enabled = !model.busy) { Text(tr(if (selecting) "Done selecting" else "Select VTXOs")) }
        if (selecting) {
            TextButton({ selected = visible.filter { it.refreshable }.map { it.id }.toSet(); model.invalidate() }) { Text(tr("Select visible")) }
            TextButton({ selected = vtxos.filter { it.refreshable && (it.expiring || it.expired) }.map { it.id }.toSet(); model.invalidate() }) { Text(tr("Select expiring")) }
            TextButton({ selected = emptySet(); model.invalidate() }) { Text(tr("Clear selection")) }
            Button({ scope.launch { model.review(OnchainAction.refresh, selected.toList()) } }, enabled = selected.isNotEmpty() && !model.busy) { Text(tr("Refresh ${selected.size} VTXOs")) }
        }
        if (visible.isEmpty() && !model.loading) Text(tr("No VTXOs in this view."))
        visible.forEach { vtxo ->
            HorizontalDivider()
            if (selecting) VtxoSelectionRow(vtxo.id, amountText(app, vtxo.amount), vtxo.status, vtxo.id in selected, vtxo.refreshable && !model.busy) { checked ->
                selected = if (checked) selected + vtxo.id else selected - vtxo.id; model.invalidate()
            } else TextButton({ detailID = vtxo.id }, enabled = !model.busy) { Text(tr("${vtxo.status} · ${amountText(app, vtxo.amount)} · ${vtxo.id.take(12)}…")) }
            Text(tr("Expires at block {_gt_value_2}", vtxo.expiry))
        }
    }
}

@Composable
private fun VtxoDetailScreen(app: AppModel, id: String, back: () -> Unit) {
    val model = rememberOnchainModel(app)
    val scope = rememberCoroutineScope()
    OnchainScreen(app, model) {
        TextButton(back) { Text(tr("Back to VTXOs")) }
        Text(tr("VTXO Details"), style = MaterialTheme.typography.headlineSmall)
        val vtxo = model.overview?.vtxos?.find { it.id == id }
        if (vtxo != null) {
            AmountRow(app, "Amount", vtxo.amount)
            DetailRow("VTXO ID", id); DetailRow("State", vtxo.status); DetailRow("Kind", vtxo.kind)
            DetailRow("Expiry Height", vtxo.expiry.toString()); DetailRow("Exit Delta", vtxo.exitDelta.toString())
            DetailRow("Exit depth", vtxo.exitDepth.toString()); DetailRow("Exit weight (WU)", vtxo.exitWeight.toString())
            DetailRow("Registered", if (vtxo.registered) "Yes" else "No")
            DetailRow("Server Public Key", vtxo.serverPubkey); DetailRow("Anchor Point", vtxo.anchorPoint); DetailRow("Point", vtxo.point)
            Button({ scope.launch { model.review(OnchainAction.refresh, listOf(id)) } }, enabled = vtxo.refreshable && !model.busy) { Text(tr("Refresh VTXO")) }
        } else if (!model.loading) Text(tr("VTXO is no longer in this view. Check its operation receipt or refresh."))
    }
}

@Composable
internal fun <T> FilterMenu(selected: T, values: List<T>, label: (T) -> String, change: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }) { Text(tr("Filter") + ": " + tr(label(selected))) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(tr(label(value))) }, onClick = { change(value); open = false }) }
        }
    }
}
