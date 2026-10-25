package com.noahwallet.onchain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.settings.*

/**
 * The exits (prototype `SCR.list`): each exit's phase in a line with a five-step rail, grouped Ready to claim, Exiting and
 * Finished; today's status filter in ⋯; and, only when two or more exits share a transaction (Decision 6), their paths
 * drawn once, as they're sent and paid once.
 */
@Composable
internal fun ExitListPage(app: AppModel, nav: SettingsNav) {
    val model = rememberOnchainModel(app)
    OnchainEffects(app, model, reloadOnChange = true)
    val overview = model.overview
    val seenStore = remember(app.settings) { ExitSeen.stored(app.settings) }
    val (needsFunds, seen) = remember(model.revision) {
        overview?.let { ExitFunding.shared.needsFunds(it.exits, it.confirmed, app.session.daemonRun) to seenStore.note(it.exits, it.tip) } ?: (emptySet<String>() to emptyMap())
    }
    ExitListContent(overview?.exits.orEmpty(), overview?.tip ?: 0, needsFunds, ready = overview != null, loading = model.loading,
        claims = ExitClaims.submitted(model.attempts), seen = seen, stalled = ExitFunding.shared.checkFailed || app.session.openWithoutDaemon,
        suspended = app.isWalletSuspended, money = { money(app, it) }, back = nav.back, open = { nav.open(ExitPage(it)) },
        claim = { nav.open(ExitClaimPage) }, more = { nav.open(ExitChoosePage) }) { OnchainStatus(app, model) }
}

@Composable
internal fun ExitListContent(exits: List<ExitModel>, tip: Long, needsFunds: Set<String>, ready: Boolean, loading: Boolean, suspended: Boolean,
                             money: (Long) -> String, back: (() -> Unit)?, open: (String) -> Unit, claim: () -> Unit, more: () -> Unit,
                             claims: Map<String, String> = emptyMap(), seen: Map<String, Long> = emptyMap(), stalled: Boolean = false,
                             status: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val stages = exits.associate { it.id to ExitClaims.apply(it, ExitStages.of(it, tip, it.id in needsFunds, seen, stalled), claims[it.id]) }
    val live = exits.filter { stages.getValue(it.id).live }
    val claimable = exits.filter { stages.getValue(it.id).phase == ExitStage.Phase.claimable }
    val shared = ExitList.shared(exits)
    val sharing = shared.flatMap { group -> group.exits.map { it.id } }.toSet()
    ExitFrame(tr("Exits"), back, chip = filter?.let { key -> { com.noahwallet.ui.settings.FilterChip(tr(ExitTimeline.labels[key] ?: key)) { filter = null } } }, actions = {
        StateMenu(filter, exits.map { it.state }.distinct()) { if (it != filter) haptics.selection(); filter = it }
    }, foot = {
        FootPill(tr("Exit more VTXOs"), Modifier.testTag("exit-more"), outlined = claimable.isNotEmpty(), enabled = !suspended, onClick = more)
        if (claimable.isNotEmpty()) FootPill(tr("Claim {amount}", money(claimable.sumOf { it.amount })), Modifier.testTag("exit-list-claim"), enabled = !suspended, onClick = claim)
    }, leading = {
        val readyCount = claimable.size
        Text(listOfNotNull(tr("{n} in progress", (live.size - readyCount).toString()).takeIf { live.size > readyCount },
            tr("{n} ready to claim", readyCount.toString()).takeIf { readyCount > 0 }).joinToString(" · ").ifEmpty { tr("No exits in progress") },
            Modifier.padding(top = 8.dp), style = HomeType.line.copy(fontSize = 17.sp, lineHeight = 22.sp), color = palette.muted)
        // Every exit's fee children, each once, as Activity's "Exit fee" rows.
        val fees = ExitFees.total(exits)
        if (fees > 0) Text(tr("{amount} in network fees", money(fees)), style = HomeType.line.copy(fontSize = 17.sp, lineHeight = 22.sp), color = palette.muted)
        for (group in shared) {
            SharedPaths(group, money, Modifier.padding(top = 14.dp).fillMaxWidth().height(250.dp).testTag("exit-shared"))
            Text(tr("{n} exits from one round: {count} transactions, not {total}. Shared ones are sent and paid once.", group.exits.size.toString(),
                group.transactions.toString(), group.separately.toString()), Modifier.fillMaxWidth().padding(top = 6.dp), style = HomeType.small,
                color = palette.muted, textAlign = TextAlign.Center)
        }
    }) {
        when {
            !ready -> FinePrint(if (loading) tr("Loading…") else tr("Couldn't read wallet status. Pull to refresh."))
            exits.isEmpty() -> FinePrint(tr("No exits have been started."))
        }
        // Today's stale-exit warning, only with Noah's evidence that nothing progresses these.
        val stale = live.count { stages.getValue(it.id).stale }
        if (stale > 0) Warning(trPlural("{n} exits are behind the current chain height. Move any of them forward to catch up; this can also send exit transactions.", stale),
            SettingsIcons.clock)
        val visible = exits.filter { filter == null || it.state == filter }
        if (exits.isNotEmpty() && visible.isEmpty()) FinePrint(tr("No exits in this view."))
        val ready = setOf(ExitStage.Phase.claimable, ExitStage.Phase.claiming)
        val groups = listOf(tr("Ready to claim") to visible.filter { stages.getValue(it.id).phase in ready },
            tr("Exiting") to visible.filter { stages.getValue(it.id).live && stages.getValue(it.id).phase !in ready },
            tr("Finished") to visible.filter { !stages.getValue(it.id).live })
        for ((name, list) in groups) if (list.isNotEmpty()) {
            Text(name, Modifier.padding(top = 18.dp, bottom = 2.dp).semantics { heading() }, style = HomeType.header, color = palette.muted)
            list.forEachIndexed { i, exit ->
                ExitListRow(exit, stages.getValue(exit.id), tip, exit.id in sharing, money, divider = i < list.lastIndex) { open(exit.id) }
            }
        }
        status()
    }
}

/** One exit (prototype `listRow`): the coin (turning while live, "!" when it needs you), amount, phase and its rail. */
@Composable
private fun ExitListRow(exit: ExitModel, stage: ExitStage, tip: Long, shared: Boolean, money: (Long) -> String, divider: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val failed = stage.attention == ExitStage.Attention.needsFunds
    val muted = stage.phase in setOf(ExitStage.Phase.canceled, ExitStage.Phase.spent, ExitStage.Phase.swept)
    val line = when {
        failed -> tr("Needs on-chain funds")
        stage.phase == ExitStage.Phase.wait -> (stage.left(tip) ?: 0).let { trPlural("Waiting · {n} blocks · {time}", it, exitAbout(it)) }
        stage.phase == ExitStage.Phase.claimed -> tr("Claimed · block {height}", number(stage.claimedBlock ?: 0))
        stage.phase == ExitStage.Phase.canceled -> tr("Canceled · still in Ark")
        shared && stage.live && stage.phase !in setOf(ExitStage.Phase.claimable, ExitStage.Phase.claiming) -> tr("{status} · shared", exitStatusWord(stage))
        else -> exitStatusWord(stage)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).drawBehind {
        if (divider) drawRect(palette.hairline, Offset(54.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 54.dp.toPx(), 1.dp.toPx()))
    }.clickable(role = Role.Button, onClick = onClick).graphicsLayer { alpha = if (muted) .55f else 1f }.semantics(mergeDescendants = true) {}
        .testTag("exit-row-${exit.id.take(12)}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ExitCoin(40.dp, pending = stage.live && !muted, badge = failed, done = stage.phase == ExitStage.Phase.claimed)
        Column(Modifier.weight(1f)) {
            Text(money(exit.amount), style = HomeType.amount, color = palette.foreground, maxLines = 1)
            Text(line, style = HomeType.small.copy(fontWeight = if (stage.phase == ExitStage.Phase.claimable) FontWeight.Medium else FontWeight.Normal),
                color = when { failed -> palette.failure; stage.phase == ExitStage.Phase.claimable -> palette.foreground; else -> palette.muted },
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (step in ExitList.rail(stage)) Box(Modifier.size(14.dp, 4.dp).background(when (step) {
                    ExitList.Step.done -> palette.foreground; ExitList.Step.now -> palette.orange; ExitList.Step.failed -> palette.failure
                    ExitList.Step.todo -> palette.hairline
                }, RoundedCornerShape(2.dp)))
            }
        }
        androidx.compose.material3.Icon(painterResource(com.noahwallet.ui.home.HomeIcons.chevron), null, Modifier.size(18.dp), tint = palette.muted)
    }
}

/** ⋯: today's status filter, as a checked list of the states the exits are in. */
@Composable
private fun StateMenu(filter: String?, states: List<String>, onFilter: (String?) -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(SettingsIcons.more, tr("More"), Modifier.testTag("exit-list-more")) { haptics.light(); open = true }
        val dark = palette === NoahTokens.Dark
        DropdownMenu(open, { open = false }, Modifier.width(220.dp), offset = DpOffset((-4).dp, 0.dp), shape = RoundedCornerShape(12.dp),
            containerColor = if (dark) androidx.compose.ui.graphics.Color(0xFF2A2A2C) else palette.sheet) {
            val colors = MenuDefaults.itemColors(textColor = palette.foreground, leadingIconColor = palette.foreground)
            for (option in listOf<String?>(null) + ExitTimeline.labels.keys.filter { it in states }) {
                val checked = option == filter
                DropdownMenuItem({ Text(option?.let { tr(ExitTimeline.labels.getValue(it)) } ?: tr("All"), style = ActivityType.menu) }, { open = false; onFilter(option) },
                    Modifier.semantics { role = Role.RadioButton; selected = checked }, colors = colors,
                    leadingIcon = { androidx.compose.material3.Icon(painterResource(SettingsIcons.check), null, Modifier.size(18.dp).graphicsLayer { alpha = if (checked) 1f else 0f }) })
            }
        }
    }
}

/**
 * Several exits from one round (prototype `drawShared`): the round's block, then the shared transactions drawn once up to
 * each exit's coin, all in your orange. Mined ones are solid squares, ones in the mempool dashed with their fee, and
 * ones not yet sent hollow.
 */
@Composable
private fun SharedPaths(group: ExitList.Shared, money: (Long) -> String, modifier: Modifier) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    fun units(value: Float) = with(density) { (value / (density.density * fontScale)).sp }
    val style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = units(10.5f))
    val words = TextStyle(fontSize = units(11f))
    val label = tr("shared")
    val description = tr("{n} exits from one round: {count} transactions, not {total}. Shared ones are sent and paid once.", group.exits.size.toString(),
        group.transactions.toString(), group.separately.toString())
    val amounts = group.exits.map { money(it.amount) }
    Canvas(modifier.semantics { contentDescription = description }) {
        val k = density.density
        scale(k, k, Offset.Zero) {
            val w = size.width / k; val h = size.height / k
            val layout = ExitList.layout(group)
            val rows = ExitList.rows(h, layout.maxOfOrNull { it.depth } ?: 0)
            val s = 26f; val y0 = rows.y0; val cx = w / 2
            val spread = minOf(74f, w * .2f)
            fun at(node: ExitList.Placed) = Offset(cx + node.x * spread * 2, rows.y(node.depth))
            drawRoundRect(palette.foreground, Offset(cx - s / 2, y0), Size(s, s), CornerRadius(6f), alpha = .4f)
            val t = measurer.measure(label, words.copy(color = palette.muted))
            drawText(t, topLeft = Offset(cx - 14 - t.size.width, y0 - 22 - t.size.height * .78f))
            for (node in layout) {
                val p = at(node)
                val parent = node.parent?.let { id -> layout.first { it.txid == id } }
                val to = parent?.let { at(it) } ?: Offset(cx, y0)
                val live = node.state != ExitList.TxState.waiting
                pathLink(Offset(to.x, to.y - (if (parent == null) 0f else 7f)), Offset(p.x, p.y + if (node.coin != null) 12f else 7f), palette.orange, if (live) 1f else .5f)
            }
            for (node in layout) {
                val p = at(node)
                when {
                    node.coin != null -> {
                        drawCircle(palette.soft, 12f, p); drawCircle(palette.orange, 12f, p, style = Stroke(2.3f))
                        val m = measurer.measure(amounts[node.coin], style.copy(color = palette.foreground))
                        drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y - 19 - m.size.height * .78f))
                    }
                    node.state == ExitList.TxState.mined -> drawRoundRect(palette.foreground, Offset(p.x - 7, p.y - 7), Size(14f, 14f), CornerRadius(3.5f))
                    node.state == ExitList.TxState.sent -> {
                        drawRoundRect(palette.foreground, Offset(p.x - 8, p.y - 8), Size(16f, 16f), CornerRadius(4f),
                            style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                        drawCircle(palette.orange, 3.4f, p)
                    }
                    else -> { drawCircle(palette.background, 5.5f, p); drawCircle(palette.muted, 5.5f, p, style = Stroke(1.5f)) }
                }
            }
        }
    }
}


private fun DrawScope.pathLink(from: Offset, to: Offset, color: androidx.compose.ui.graphics.Color, alpha: Float) {
    val my = (from.y + to.y) / 2
    val path = Path().apply { moveTo(from.x, from.y); cubicTo(from.x, my, to.x, my, to.x, to.y) }
    drawPath(path, color, alpha = alpha, style = Stroke(1.5f, cap = StrokeCap.Round))
}
