package com.noahwallet.onchain

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.PulseDot
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.send.SendPill
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A VTXO's time left as the row and page say it. */
@Composable
internal fun expiresText(vtxo: VtxoModel): String {
    if (vtxo.state == "Locked") return tr("Locked")
    if (vtxo.expired) return tr("Expired")
    return when (val left = VtxoRules.left(vtxo.expiry - vtxo.tip)) {
        VtxoRules.Left.UnderAnHour -> tr("Expires in under an hour")
        is VtxoRules.Left.Hours -> trPlural("Expires in {n} hours", left.hours)
        is VtxoRules.Left.Days -> trPlural("Expires in {n} days", left.days)
    }
}

internal fun money(app: AppModel, sats: Long) = if (app.hidden) BitcoinAmount.format(0, app.amountUnit,
    Locale.forLanguageTag(app.localeIdentifier)).replace("0", "••••")
else BitcoinAmount.format(sats, app.amountUnit, Locale.forLanguageTag(app.localeIdentifier))

/** The VTXO lifetime the server gives new VTXOs (blocks), for the expiry line and the lifetime bar. */
@Composable
private fun rememberLifetime(app: AppModel): Long? = produceState<Long?>(null) {
    value = try { withContext(Dispatchers.IO) { app.session.arkInfo()?.vtxoLifetime?.toLong() } }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
}.value

/**
 * VTXOs (prototype `SCR.coins`): the total, "In N VTXOs" and the expiry line, then Expiring, Spendable and Locked. ⋯ holds
 * the filter (named in a chip), Select VTXOs and Refresh list. Renewing is always today's review in a sheet; Home's Renew
 * opens it here with the due VTXOs selected.
 */
@Composable
internal fun VtxosPage(app: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val model = rememberOnchainModel(app)
    var filter by rememberSaveable { mutableStateOf(VtxoRules.Filter.all) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()
    val lifetime = rememberLifetime(app)
    OnchainEffects(app, model, reloadOnChange = true)
    RenewReview(model, take = { takeRenewal(app) }, due = { ids -> renewableIDs(app, ids) },
        onStart = { ids -> selecting = true; selected = ids })
    val vtxos = model.overview?.vtxos.orEmpty()
    val chosen = OnchainDisplay.currentSelection(vtxos, selected.toSet())
    val visible = VtxoRules.visible(vtxos, filter)
    fun renew(ids: List<String>) { scope.launch { model.review(OnchainAction.refresh, ids) } }
    val idle = !model.busy && !app.isWalletSuspended
    SettingsFrame(tr("VTXOs"), nav.back, chip = filterChip(filter) { filter = VtxoRules.Filter.all }, actions = {
        VtxoMenu(filter, enabled = !model.busy, onFilter = {
            if (it != filter) haptics.selection()
            filter = it
        },
            onSelect = { selecting = true; selected = VtxoRules.preselected(vtxos).toList(); model.invalidate() },
            onRefresh = { model.invalidate(); scope.launch { model.load() } })
    }, foot = if (!selecting) null else ({
        RenewFoot(chosen.size, enabled = chosen.isNotEmpty() && idle, loading = model.busy,
            cancel = { selecting = false; selected = emptyList() }) { renew(chosen.map { it.id }) }
    })) {
        when {
            app.isWalletSuspended -> FinePrint(tr("Wallet suspended. Resume your wallet in Settings."))
            model.overview == null -> FinePrint(if (model.loading) tr("Loading…") else tr("Couldn't read wallet status. Pull to refresh."))
            vtxos.isEmpty() -> EmptyVtxos()
            else -> {
                VtxoSummary(app, vtxos, lifetime)
                DueLine(app, vtxos, offer = !selecting, enabled = idle) { due -> haptics.light(); renew(due) }
                if (visible.isEmpty()) FinePrint(tr("No VTXOs in this view."))
                VtxoGroups(app, visible, selecting, selected, enabled = !model.busy) { vtxo ->
                    if (selecting) { haptics.selection(); selected = VtxoRules.toggle(selected.toSet(), vtxo).toList() }
                    else nav.open(com.noahwallet.ui.settings.VtxoPage(vtxo.id))
                }
            }
        }
        OnchainStatus(app, model)
    }
    model.quote?.takeIf { it.action == OnchainAction.refresh }?.let { quote ->
        RenewSheet(app, model, quote, scope) { selecting = false; selected = emptyList() }
    }
}

/** Home's Renew hands its VTXOs over once. */
private fun takeRenewal(app: AppModel) = app.renewVtxos?.also { app.renewVtxos = null }

private suspend fun renewableIDs(app: AppModel, ids: Collection<String>) =
    app.session.onchainOverview().vtxos.filter { it.id in ids && it.renewable }.map { it.id }

@Composable
private fun VtxoSummary(app: AppModel, vtxos: List<VtxoModel>, lifetime: Long?) {
    BigTotal(money(app, vtxos.sumOf { it.amount }), trPlural("In {n} VTXOs", vtxos.size))
    ExpiryLine(vtxos, lifetime ?: vtxos.maxOf { it.expiry - it.tip }.coerceAtLeast(144))
}

@Composable
private fun RowScope.RenewFoot(count: Int, enabled: Boolean, loading: Boolean, cancel: () -> Unit, renew: () -> Unit) {
    FootPill(tr("Cancel"), outlined = true, weight = .6f, onClick = cancel)
    FootPill(if (count == 0) tr("Renew") else tr("Renew {n}", count.toString()), Modifier.testTag("vtxos-renew-selected"),
        enabled = enabled, loading = loading, onClick = renew)
}

/** The VTXOs due within a day, with Renew when [offer]; else when the next one expires. */
@Composable
private fun DueLine(app: AppModel, vtxos: List<VtxoModel>, offer: Boolean, enabled: Boolean, renew: (List<String>) -> Unit) {
    val palette = LocalPalette.current
    val due = VtxoRules.due(vtxos)
    if (due.isNotEmpty()) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(palette.orange, CircleShape))
            Text(trPlural("{n} VTXOs expire within a day", due.size), style = HomeType.line.copy(fontWeight = FontWeight.Medium),
                color = palette.foreground)
        }
        if (offer) SendPill(tr("Renew {amount}", money(app, due.sumOf { it.amount })),
            Modifier.padding(top = 14.dp).testTag("vtxos-renew-due"),
            outlined = true, height = 40.dp, enabled = enabled) { renew(due.map { it.id }) }
    } else vtxos.filter { it.refreshable }.minOfOrNull { it.expiry - it.tip }?.let { soonest ->
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(SettingsIcons.clock), null, Modifier.size(17.dp), tint = palette.muted)
            Text(tr("Next expiry in {time} · renews automatically", timeLeft(soonest)), style = HomeType.line, color = palette.muted)
        }
    }
}

/** The visible VTXOs by group; checkboxes while [selecting]. */
@Composable
private fun VtxoGroups(app: AppModel, visible: List<VtxoModel>, selecting: Boolean, selected: List<String>, enabled: Boolean, tap: (VtxoModel) -> Unit) {
    val palette = LocalPalette.current
    for ((group, list) in VtxoRules.groups(visible)) {
        Text(groupName(group), Modifier.padding(top = 18.dp, bottom = 2.dp).semantics { heading() }, style = HomeType.header, color = palette.muted)
        list.forEachIndexed { index, vtxo ->
            VtxoRow(app, vtxo, selecting, vtxo.id in selected, divider = index < list.lastIndex, enabled = enabled) { tap(vtxo) }
        }
    }
}

/** The active filter as a chip that clears it; none for All. */
private fun filterChip(filter: VtxoRules.Filter, clear: () -> Unit): (@Composable () -> Unit)? =
    if (filter == VtxoRules.Filter.all) null else ({ FilterChip(filterName(filter), onClear = clear) })

@Composable
private fun expiryLabel(blocks: Long, step: Long, unit: String): String {
    val n = (blocks / if (unit == "h") 6 else step * 1).toString()
    return when {
        blocks == 0L -> tr("Now")
        unit == "w" -> tr("{n} wk", n)
        unit == "d" -> tr("{n} d", n)
        else -> tr("{n} h", n)
    }
}

@Composable
private fun filterName(filter: VtxoRules.Filter) = when (filter) {
    VtxoRules.Filter.all -> tr("All"); VtxoRules.Filter.expiring -> tr("Expiring")
    VtxoRules.Filter.spendable -> tr("Spendable"); VtxoRules.Filter.locked -> tr("Locked")
}

@Composable
private fun groupName(group: VtxoRules.Group) = when (group) {
    VtxoRules.Group.expiring -> tr("Expiring"); VtxoRules.Group.spendable -> tr("Spendable"); VtxoRules.Group.locked -> tr("Locked")
}

@Composable
internal fun timeLeft(blocks: Long) = when (val left = VtxoRules.left(blocks)) {
    VtxoRules.Left.UnderAnHour -> tr("under an hour")
    is VtxoRules.Left.Hours -> trPlural("{n} hours", left.hours)
    is VtxoRules.Left.Days -> trPlural("{n} days", left.days)
}

/** ⋯ (Activity's menu): the filter as a checked list, then Select VTXOs and Refresh list. */
@Composable
private fun VtxoMenu(filter: VtxoRules.Filter, enabled: Boolean, onFilter: (VtxoRules.Filter) -> Unit, onSelect: () -> Unit, onRefresh: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(SettingsIcons.more, tr("More"), Modifier.testTag("vtxos-more")) { haptics.light(); open = true }
        val dark = palette === NoahTokens.Dark
        DropdownMenu(open, { open = false }, Modifier.width(220.dp).testTag("vtxos-menu"), offset = DpOffset((-4).dp, 0.dp),
            shape = RoundedCornerShape(12.dp), containerColor = if (dark) androidx.compose.ui.graphics.Color(0xFF2A2A2C) else palette.sheet) {
            val colors = MenuDefaults.itemColors(textColor = palette.foreground, leadingIconColor = palette.foreground, trailingIconColor = palette.foreground)
            for (option in VtxoRules.Filter.entries) {
                val checked = option == filter
                DropdownMenuItem({ Text(filterName(option), style = ActivityType.menu) }, { open = false; onFilter(option) },
                    Modifier.semantics { role = Role.RadioButton; selected = checked }, colors = colors,
                    leadingIcon = { Icon(painterResource(SettingsIcons.check), null, Modifier.size(18.dp).graphicsLayer { alpha = if (checked) 1f else 0f }) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = palette.hairline)
            DropdownMenuItem({ Text(tr("Select VTXOs"), style = ActivityType.menu) }, { open = false; onSelect() }, enabled = enabled, colors = colors,
                leadingIcon = { Spacer(Modifier.size(18.dp)) }, trailingIcon = { Icon(painterResource(SettingsIcons.check), null, Modifier.size(20.dp)) })
            DropdownMenuItem({ Text(tr("Refresh list"), style = ActivityType.menu) }, { open = false; onRefresh() }, colors = colors,
                leadingIcon = { Spacer(Modifier.size(18.dp)) }, trailingIcon = { Icon(painterResource(SettingsIcons.refresh), null, Modifier.size(20.dp)) })
        }
    }
}

/** One VTXO (prototype `coinRow`): ₿ (or a lock), its expiry, block and ID, and the amount; a checkbox while selecting. */
@Composable
private fun VtxoRow(app: AppModel, vtxo: VtxoModel, selecting: Boolean, checked: Boolean, divider: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val locale = Locale.forLanguageTag(app.localeIdentifier)
    val locked = vtxo.state == "Locked"
    val selectable = VtxoRules.selectable(vtxo)
    val status = expiresText(vtxo)
    val amount = money(app, vtxo.amount)
    Row(Modifier.fillMaxWidth().height(66.dp)
        .drawBehind { if (divider) drawRect(palette.hairline, Offset(56.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 56.dp.toPx(), 1.dp.toPx())) }
        .clickable(enabled = enabled && (!selecting || selectable), role = if (selecting) Role.Checkbox else Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { if (selecting) toggleableState = androidx.compose.ui.state.ToggleableState(checked) }
        .graphicsLayer { alpha = if (selecting && !selectable) .4f else 1f }.testTag("vtxo-row-${vtxo.id.take(12)}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (selecting) Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked, null, enabled = selectable,
                colors = CheckboxDefaults.colors(checkedColor = palette.foreground, checkmarkColor = palette.background,
                    uncheckedColor = palette.muted, disabledUncheckedColor = palette.hairline))
        } else VtxoAvatar(42.dp, glyphAvatar = !locked, unit = app.amountUnit, icon = if (locked) SettingsIcons.lock else null, due = vtxo.renewable)
        Column(Modifier.weight(1f)) {
            Text(status, style = HomeType.rowTitle, color = if (vtxo.expired && !locked) palette.failure else palette.foreground, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(tr("Block {height} · {id}", java.text.NumberFormat.getIntegerInstance(locale).format(vtxo.expiry), vtxo.id.take(6) + "…"),
                style = HomeType.small, color = palette.muted, maxLines = 1)
        }
        Text(amount, style = HomeType.amount, color = if (locked) palette.muted else palette.foreground, maxLines = 1)
    }
}

/** ₿ or "s" in a soft circle, as a VTXO's avatar. */
@Composable
private fun VtxoAvatar(size: androidx.compose.ui.unit.Dp, glyphAvatar: Boolean, unit: BitcoinAmountUnit, icon: Int?, due: Boolean, pending: Boolean = false) {
    if (glyphAvatar) com.noahwallet.ui.settings.SettingsAvatar(size, initial = if (unit == BitcoinAmountUnit.sats) "s" else "₿", due = due, pending = pending)
    else com.noahwallet.ui.settings.SettingsAvatar(size, icon = icon, due = due, pending = pending)
}

/** No VTXOs yet: ghost rows and one line. */
@Composable
private fun EmptyVtxos() {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 300.dp).fillMaxWidth().clearAndSetSemantics { }) {
            repeat(3) { i ->
                Row(Modifier.fillMaxWidth().height(60.dp).graphicsLayer { alpha = 1 - i * .28f }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(42.dp).background(palette.background, CircleShape).drawBehind {
                        drawCircle(palette.hairline, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    }, contentAlignment = Alignment.Center) {
                        if (i == 1) Icon(painterResource(SettingsIcons.lock), null, Modifier.size(20.dp), tint = palette.muted)
                        else Text("₿", style = HomeType.avatar, color = palette.muted)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.fillMaxWidth(listOf(.58f, .46f, .52f)[i]).height(10.dp).background(palette.soft, RoundedCornerShape(5.dp)))
                        Box(Modifier.fillMaxWidth(listOf(.34f, .40f, .28f)[i]).height(10.dp).background(palette.soft, RoundedCornerShape(5.dp)))
                    }
                    Box(Modifier.size(54.dp, 12.dp).background(palette.soft, RoundedCornerShape(6.dp)))
                }
            }
        }
        Text(tr("No VTXOs yet"), Modifier.padding(top = 26.dp, bottom = 6.dp), style = HomeType.listTitle, color = palette.foreground)
        Text(tr("Bitcoin you receive shows up here as VTXOs."), Modifier.widthIn(max = 280.dp), style = HomeType.line, color = palette.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * The expiry line (prototype `timeline`): every VTXO placed by when it expires, sized by amount, stacked where they meet.
 * Orange rings and the tinted band mark those within the renew threshold; locked ones are dashed.
 */
@Composable
private fun ExpiryLine(vtxos: List<VtxoModel>, lifetime: Long) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val threshold = com.noahwallet.services.wallet.WalletConfiguration.base(BuildConfig.APP_VARIANT).vtxoRefreshExpiryThreshold.toLong()
    // Weeks across a long lifetime, days or hours across shorter ones (regtest, signet).
    val (step, unit) = expiryStep(lifetime)
    val labels = (0..7).map { it * step }.filter { it <= lifetime }.take(5).map { blocks -> blocks to expiryLabel(blocks, step, unit) }
    val style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    val description = tr("When your VTXOs expire")
    val biggest = vtxos.maxOf { it.amount }.coerceAtLeast(1)
    Canvas(Modifier.padding(top = 20.dp).fillMaxWidth().height(80.dp).semantics { contentDescription = description }.testTag("vtxos-expiry-line")) {
        val x0 = 6.dp.toPx(); val x1 = size.width - 6.dp.toPx(); val base = 50.dp.toPx()
        fun x(blocks: Long) = x0 + (x1 - x0) * (blocks.coerceIn(0, lifetime).toFloat() / lifetime)
        if (vtxos.any { it.state != "Locked" && (it.expiry - it.tip) <= threshold })
            drawRoundRect(palette.orangeTint, Offset(x0, 6.dp.toPx()), Size(x(threshold) - x0 + 4.dp.toPx(), base - 6.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawLine(palette.hairline, Offset(x0, base + 6.dp.toPx()), Offset(x1, base + 6.dp.toPx()), 1.dp.toPx())
        labels.forEachIndexed { i, (blocks, label) ->
            val x = x(blocks)
            drawLine(palette.muted, Offset(x, base + 3.dp.toPx()), Offset(x, base + 9.dp.toPx()), 1.dp.toPx())
            val text = measurer.measure(label, style.copy(color = palette.muted))
            val left = if (i == 0) x else (x - text.size.width / 2f).coerceAtMost(size.width - text.size.width)
            drawText(text, topLeft = Offset(left, base + 13.dp.toPx()))
        }
        val placed = mutableListOf<Triple<Float, Float, Float>>()
        for (vtxo in vtxos.sortedByDescending { it.amount }) {
            val r = (3f + 7f * kotlin.math.sqrt(vtxo.amount.toFloat() / biggest)) * density
            val cx = x(vtxo.expiry - vtxo.tip)
            var cy = base - r
            var tries = 0
            while (tries++ < 60 && placed.any { (px, py, pr) -> kotlin.math.hypot(px - cx, py - cy) < pr + r + 1.5f * density }) cy -= 1.5f * density
            placed += Triple(cx, cy, r)
            val locked = vtxo.state == "Locked"
            expiryCoin(Offset(cx, cy), r, locked, due = !locked && vtxo.expiry - vtxo.tip <= threshold, palette)
        }
    }
}

private fun expiryStep(lifetime: Long) = when {
    lifetime >= 1008 -> 1008L to "w"
    lifetime >= 144 -> 144L to "d"
    else -> maxOf(6L, lifetime / 30 * 6) to "h"
}

/** One VTXO on the expiry line: dashed when locked, ringed in orange when due. */
private fun DrawScope.expiryCoin(center: Offset, r: Float, locked: Boolean, due: Boolean, palette: NoahTokens.Palette) {
    if (locked) drawCircle(palette.foreground, r, center,
        style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.dp.toPx()))))
    else {
        drawCircle(palette.foreground.copy(alpha = if (due) 1f else .85f), r, center)
        if (due) drawCircle(palette.orange, r + 2.5.dp.toPx(), center, style = Stroke(2.dp.toPx()))
    }
}

/**
 * Renew (today's refresh review, renamed): the amount, fee and what you keep, then an explicit Renew. It closes when the
 * quote is dropped or confirmed; a stale quote can't be confirmed.
 */
@Composable
internal fun RenewSheet(app: AppModel, model: OnchainModel, quote: OnchainQuote, scope: kotlinx.coroutines.CoroutineScope, done: () -> Unit) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    val haptics = rememberHaptics()
    var current by remember(quote) { mutableStateOf(quote.current()) }
    LaunchedEffect(quote) { while (current) { kotlinx.coroutines.delay(250); current = quote.current() } }
    val count = quote.ids.size
    val joined = tr("Joined the next round")
    SettingsSheet(model::invalidate, if (count == 1) tr("Renew this VTXO") else trPlural("Renew {n} VTXOs", count),
        if (count == 1) tr("Joins the next Ark round, so it stays spendable.") else tr("Joins the next Ark round, so they stay spendable."),
        modifier = Modifier.testTag("renew-sheet")) { close ->
        Column(Modifier.padding(top = 8.dp)) {
            DetailRow(tr("Amount"), money(app, quote.amount), ValueKind.number, toasts = toasts)
            quote.fee?.let { fee ->
                DetailRow(tr("Fee"), money(app, fee), ValueKind.number, toasts = toasts)
                DetailRow(tr("You keep"), money(app, quote.net), ValueKind.number, toasts = toasts, last = true)
            }
        }
        if (!current) Text(tr(OnchainFailure.changed.text), Modifier.padding(top = 12.dp), style = HomeType.line, color = palette.failure)
        SheetActions(tr("Cancel"), tr("Renew"), onCancel = close, enabled = current && !app.isWalletSuspended && !model.busy, loading = model.busy,
            confirmTag = "renew-confirm", onConfirm = {
                // The page's scope: confirming drops the quote, which closes this sheet, and must not cancel the submission.
                scope.launch {
                    model.confirm(); app.refresh()
                    if (model.error == null) { haptics.success(); toasts.show(joined); done() }
                }
            })
    }
}

/**
 * One VTXO (prototype `SCR.coin`): amount, status and its lifetime, then State, Expires, Blocks left, VTXO ID and Anchor
 * (with the explorer); everything else under Details; Renew at the bottom. A VTXO a payment spent loads by ID.
 */
@Composable
internal fun VtxoDetailPage(app: AppModel, nav: SettingsNav, id: String) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    val context = LocalContext.current
    val model = rememberOnchainModel(app)
    val scope = rememberCoroutineScope()
    val locale = Locale.forLanguageTag(app.localeIdentifier)
    val lifetime = rememberLifetime(app)
    var details by rememberSaveable { mutableStateOf(false) }
    OnchainEffects(app, model, reloadOnChange = true)
    val listed = model.overview?.vtxos?.find { it.id == id }
    // A VTXO a payment spent isn't in the overview: load it by ID, again after each completed refresh.
    val stored by produceState<Result<VtxoModel>?>(null, id, model.revision, listed == null) {
        if (model.overview != null && listed == null) value = model.coin(id)
    }
    val vtxo = listed ?: stored?.getOrNull()
    val scroll = androidx.compose.foundation.rememberScrollState()
    val number = java.text.NumberFormat.getIntegerInstance(locale)
    val noBrowser = tr("No browser available to open the explorer.")
    SettingsFrame(tr("VTXO"), nav.back, hero = true, scroll = scroll, foot = if (vtxo?.refreshable == true) ({
        FootPill(tr("Renew"), Modifier.testTag("vtxo-renew"), enabled = !model.busy && !app.isWalletSuspended, loading = model.busy) {
            scope.launch { model.review(OnchainAction.refresh, listOf(id)) }
        }
    }) else null) {
        if (vtxo == null) {
            MissingVtxo(id, read = stored != null, gone = !model.loading && model.overview == null)
            OnchainStatus(app, model)
            return@SettingsFrame
        }
        VtxoHeader(app, vtxo, lifetime, number)
        VtxoRows(vtxo, id, number, noBrowser)
        if ((vtxo.expired || vtxo.expiring) && vtxo.refreshable) FinePrint(tr("Renew this VTXO to keep it available."))
        Details(details, { details = it }, scroll) { VtxoDetailRows(vtxo, number) }
        OnchainStatus(app, model)
    }
    model.quote?.takeIf { it.action == OnchainAction.refresh }?.let { quote -> RenewSheet(app, model, quote, scope) {} }
}

/** A VTXO not in the overview: its ID once read failed ([read]), or that it's gone. */
@Composable
private fun MissingVtxo(id: String, read: Boolean, gone: Boolean) {
    val toasts = LocalSettingsToasts.current
    if (read) {
        DetailRow(tr("VTXO ID"), short(id), ValueKind.mono, copy = id, toasts = toasts, last = true)
        FinePrint(tr("Couldn't read wallet status. Pull to refresh."))
    } else if (gone) {
        @Suppress("MaxLineLength") // one translation source string
        FinePrint(tr("This VTXO is no longer in the wallet's current VTXOs. Refresh to check its status."))
    }
}

@Composable
private fun VtxoHeader(app: AppModel, vtxo: VtxoModel, lifetime: Long?, number: java.text.NumberFormat) {
    val palette = LocalPalette.current
    val locked = vtxo.state == "Locked"
    Column(Modifier.padding(top = 6.dp)) {
        VtxoAvatar(60.dp, glyphAvatar = !locked, unit = app.amountUnit, icon = if (locked) SettingsIcons.lock else null, due = vtxo.renewable)
        Text(money(app, vtxo.amount), Modifier.padding(top = 18.dp), style = ActivityType.big, color = palette.foreground, maxLines = 1)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vtxo.renewable) PulseDot(palette.orange)
            Text(expiresText(vtxo), style = ActivityType.status, color = if (vtxo.expired && !locked) palette.failure else palette.foreground)
        }
        lifetime?.let { life -> LifetimeBar(vtxo, life, number) }
    }
}

@Composable
private fun VtxoRows(vtxo: VtxoModel, id: String, number: java.text.NumberFormat, noBrowser: String) {
    val toasts = LocalSettingsToasts.current
    val context = LocalContext.current
    Column(Modifier.padding(top = 22.dp)) {
        DetailRow(tr("State"), when (vtxo.state) { "Spendable" -> tr("Spendable"); "Locked" -> tr("Locked"); else -> vtxo.state }, toasts = toasts)
        DetailRow(tr("Expires"), tr("Block {height}", number.format(vtxo.expiry)), toasts = toasts)
        DetailRow(tr("Blocks left"), if (vtxo.expiry <= vtxo.tip) tr("Expired") else number.format(vtxo.expiry - vtxo.tip), ValueKind.number,
            toasts = toasts)
        DetailRow(tr("VTXO ID"), short(id), ValueKind.mono, copy = id, toasts = toasts)
        DetailRow(tr("Anchor"), short(vtxo.anchorPoint), ValueKind.mono, copy = vtxo.anchorPoint,
            explorer = OnchainDisplay.explorerURL(vtxo.anchorPoint, BuildConfig.APP_VARIANT), toasts = toasts, last = true, onExplorer = { url ->
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) }
                catch (_: android.content.ActivityNotFoundException) { toasts.show(noBrowser) }
            })
    }
}

@Composable
private fun VtxoDetailRows(vtxo: VtxoModel, number: java.text.NumberFormat) {
    val toasts = LocalSettingsToasts.current
    DetailRow(tr("Type"), vtxo.kind, small = true, toasts = toasts)
    DetailRow(tr("Status"), if (vtxo.registered) tr("Registered") else tr("Not registered"), small = true, toasts = toasts)
    DetailRow(tr("Exit delta"), trPlural("{n} blocks", vtxo.exitDelta), small = true, toasts = toasts)
    DetailRow(tr("Exit depth"), number.format(vtxo.exitDepth), ValueKind.number, small = true, toasts = toasts)
    DetailRow(tr("Exit weight"), tr("{n} wu", number.format(vtxo.exitWeight)), small = true, toasts = toasts)
    DetailBlock(tr("Server key"), vtxo.serverPubkey, toasts)
    DetailBlock(tr("Point"), vtxo.point, toasts)
    DetailRow(tr("Current block"), number.format(vtxo.tip), ValueKind.number, small = true, toasts = toasts, last = true)
}

/** How much of its lifetime a VTXO has used (prototype `.life`): created at expiry − lifetime, an orange mark for now. */
@Composable
private fun LifetimeBar(vtxo: VtxoModel, lifetime: Long, number: java.text.NumberFormat) {
    val palette = LocalPalette.current
    val start = vtxo.expiry - lifetime
    val used = ((vtxo.tip - start).toFloat() / lifetime).coerceIn(0f, 1f)
    Column(Modifier.padding(top = 18.dp).widthIn(max = 340.dp).fillMaxWidth().clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val y = 4.dp.toPx()
            drawRoundRect(palette.soft, Offset(0f, y), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawRoundRect(palette.foreground, Offset(0f, y), Size(size.width * used, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawRoundRect(palette.orange, Offset(size.width * used - 1.dp.toPx(), 0f), Size(2.dp.toPx(), 14.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(tr("Block {height}", number.format(start.coerceAtLeast(0))), Modifier.weight(1f), style = SettingsType.tick, color = palette.muted)
            Text(number.format(vtxo.expiry), style = SettingsType.tick, color = palette.muted)
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
