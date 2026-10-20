package com.noahwallet.onchain

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
    var filter by rememberSaveable { mutableStateOf("All") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val vtxos = model.overview?.vtxos.orEmpty()
    val visible = vtxos.filter { filter == "All" || it.status == filter }
    OnchainScreen(app, model) {
        Text("VTXOs", style = MaterialTheme.typography.headlineSmall)
        FilterMenu(filter, listOf("All", "Active", "Expiring", "Expired", "Locked")) { filter = it }
        AmountRow(app, "Total (${vtxos.size} VTXOs)", vtxos.sumOf { it.amount })
        TextButton({ selecting = !selecting; selected = emptySet(); model.invalidate() }, enabled = !model.busy) { Text(if (selecting) "Done selecting" else "Select VTXOs") }
        if (selecting) {
            TextButton({ selected = visible.filter { it.refreshable }.map { it.id }.toSet(); model.invalidate() }) { Text("Select visible") }
            TextButton({ selected = vtxos.filter { it.refreshable && (it.expiring || it.expired) }.map { it.id }.toSet(); model.invalidate() }) { Text("Select expiring") }
            TextButton({ selected = emptySet(); model.invalidate() }) { Text("Clear selection") }
            Button({ scope.launch { model.review(OnchainAction.refresh, selected.toList()) } }, enabled = selected.isNotEmpty() && !model.busy) { Text("Refresh ${selected.size} VTXOs") }
        }
        if (visible.isEmpty() && !model.loading) Text("No VTXOs in this view.")
        visible.forEach { vtxo ->
            HorizontalDivider()
            if (selecting) Row {
                Checkbox(vtxo.id in selected, { checked -> selected = if (checked) selected + vtxo.id else selected - vtxo.id; model.invalidate() }, enabled = vtxo.refreshable && !model.busy)
                AmountRow(app, vtxo.status, vtxo.amount)
            } else TextButton({ detailID = vtxo.id }, enabled = !model.busy) { Text("${vtxo.status} · ${amountText(app, vtxo.amount)} · ${vtxo.id.take(12)}…") }
            Text("Expires at block ${vtxo.expiry}")
        }
    }
}

@Composable
private fun VtxoDetailScreen(app: AppModel, id: String, back: () -> Unit) {
    val model = rememberOnchainModel(app)
    val scope = rememberCoroutineScope()
    OnchainScreen(app, model) {
        TextButton(back) { Text("Back to VTXOs") }
        Text("VTXO Details", style = MaterialTheme.typography.headlineSmall)
        val vtxo = model.overview?.vtxos?.find { it.id == id }
        if (vtxo != null) {
            AmountRow(app, "Amount", vtxo.amount)
            DetailRow("VTXO ID", id); DetailRow("State", vtxo.status); DetailRow("Kind", vtxo.kind)
            DetailRow("Expiry height", vtxo.expiry.toString()); DetailRow("Exit delta", vtxo.exitDelta.toString())
            DetailRow("Exit depth", vtxo.exitDepth.toString()); DetailRow("Exit weight (WU)", vtxo.exitWeight.toString())
            DetailRow("Registered", if (vtxo.registered) "Yes" else "No")
            DetailRow("Server public key", vtxo.serverPubkey); DetailRow("Anchor point", vtxo.anchorPoint); DetailRow("Point", vtxo.point)
            Button({ scope.launch { model.review(OnchainAction.refresh, listOf(id)) } }, enabled = vtxo.refreshable && !model.busy) { Text("Refresh VTXO") }
        } else if (!model.loading) Text("VTXO is no longer in this view. Check its operation receipt or refresh.")
    }
}

@Composable
internal fun FilterMenu(selected: String, values: List<String>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }) { Text("Filter: ${ExitTimeline.labels[selected] ?: selected}") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(ExitTimeline.labels[value] ?: value) }, onClick = { change(value); open = false }) }
        }
    }
}
