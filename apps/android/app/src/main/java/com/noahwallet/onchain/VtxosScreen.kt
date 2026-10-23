package com.noahwallet.onchain

import com.noahwallet.localization.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.DetailValue
import androidx.compose.ui.platform.LocalUriHandler
import com.noahwallet.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VtxosScreen(app: AppModel) {
    var detailID by rememberSaveable { mutableStateOf(app.vtxoDetail?.also { app.vtxoDetail = null }) }
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
    val selectedVtxos = OnchainDisplay.currentSelection(vtxos, selected)
    val visible = vtxos.filter { filter == null || it.statusID == filter }
    OnchainScreen(app, model) {
        // Inside OnchainScreen's content, so its effects (and their first reset) run before the review starts.
        RenewReview(model, take = { app.renewVtxos?.also { app.renewVtxos = null } },
            due = { ids -> app.session.onchainOverview().vtxos.filter { it.id in ids && it.renewable }.map { it.id } },
            onStart = { ids -> selecting = true; selected = ids.toSet() })
        Text(tr("VTXOs"), style = MaterialTheme.typography.headlineSmall)
        FilterMenu(filter, listOf(null) + VtxoStatus.entries, { status -> status?.let { "${it.label} (${vtxos.count { row -> row.statusID == it }})" } ?: "All" }) { filter = it }
        AmountRow(app, "Total (${vtxos.size} VTXOs)", vtxos.sumOf { it.amount })
        TextButton({ selecting = !selecting; selected = emptySet(); model.invalidate() }, enabled = !model.busy) { Text(tr(if (selecting) "Done selecting" else "Select VTXOs")) }
        if (selecting) {
            DetailRow("Selected", selectedVtxos.size.toString())
            AmountRow(app, "Amount selected", selectedVtxos.sumOf { it.amount })
            TextButton({ selected = visible.filter { it.refreshable }.map { it.id }.toSet(); model.invalidate() }) { Text(tr("Select visible")) }
            TextButton({ selected = vtxos.filter { it.refreshable && (it.expiring || it.expired) }.map { it.id }.toSet(); model.invalidate() }) { Text(tr("Select expiring")) }
            TextButton({ selected = emptySet(); model.invalidate() }) { Text(tr("Clear selection")) }
            Button({ scope.launch { model.review(OnchainAction.refresh, selected.toList()) } }, enabled = selectedVtxos.isNotEmpty() && !model.busy) { Text(tr("Refresh ${selectedVtxos.size} VTXOs")) }
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
    val uri = LocalUriHandler.current
    val locale = java.util.Locale.forLanguageTag(app.localeIdentifier)
    OnchainScreen(app, model) {
        TextButton(back) { Text(tr("Back to VTXOs")) }
        Text(tr("VTXO Details"), style = MaterialTheme.typography.headlineSmall)
        val vtxo = model.overview?.vtxos?.find { it.id == id }
        if (vtxo != null) {
            AmountRow(app, "Amount", vtxo.amount)
            DetailValue("VTXO ID", id, true); DetailRow("State", vtxo.state); DetailRow("Status", vtxo.status); DetailRow("Kind", vtxo.kind)
            DetailRow("Current Block Height", java.text.NumberFormat.getIntegerInstance(locale).format(vtxo.tip))
            DetailRow("Expiry Height", java.text.NumberFormat.getIntegerInstance(locale).format(vtxo.expiry))
            DetailRow("Blocks Until Expiry", if (vtxo.expiry <= vtxo.tip) tr("Expired") else java.text.NumberFormat.getIntegerInstance(locale).format(vtxo.expiry - vtxo.tip))
            DetailRow("Exit Delta", vtxo.exitDelta.toString())
            DetailRow("Exit depth", vtxo.exitDepth.toString()); DetailRow("Exit weight (WU)", vtxo.exitWeight.toString())
            DetailRow("Registered", if (vtxo.registered) "Yes" else "No")
            DetailValue("Server Public Key", vtxo.serverPubkey, true); DetailValue("Anchor Point", vtxo.anchorPoint, true); DetailRow("Point", vtxo.point)
            OnchainDisplay.explorerURL(vtxo.anchorPoint, BuildConfig.APP_VARIANT)?.let { url ->
                TextButton({ uri.openUri(url) }) { Text(tr("View in explorer")) }
            }
            if (vtxo.expired || vtxo.expiring) Text(tr("Refresh this VTXO to keep it available."))
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

/**
 * Home's Renew: takes the request once and opens today's refresh review for those VTXOs that are still due ([due]:
 * expiring, spendable, not expired); the user still confirms, and submitting checks again, under the wallet's
 * locks, that every reviewed VTXO is still due. With none left there's no review, and Home's nudge catches up when
 * it next checks. It's keyed on
 * nothing that changes, so clearing the request can't cancel a review whose quote is slow. Nothing announces a
 * renewal: submitting only hands the VTXOs to a round, so the nudge simply folds away once none are due.
 */
@Composable
internal fun RenewReview(model: OnchainModel, take: () -> List<String>?, due: suspend (List<String>) -> List<String>,
                         onStart: (List<String>) -> Unit) {
    // A wallet that can't be read now has nothing proven due, so no review is opened on it.
    suspend fun stillDue(ids: List<String>): List<String> = try { withContext(Dispatchers.IO) { due(ids) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    LaunchedEffect(Unit) {
        val requested = take() ?: return@LaunchedEffect
        // Home checked a while ago: a VTXO may have expired, been locked or left the expiring set since.
        val ids = stillDue(requested)
        if (ids.isEmpty()) return@LaunchedEffect
        onStart(ids)
        model.review(OnchainAction.refresh, ids, renewal = true)
    }
}
