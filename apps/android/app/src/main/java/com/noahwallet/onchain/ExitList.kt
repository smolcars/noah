package com.noahwallet.onchain

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.settings.*
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The exits (prototype `SCR.list`, option B): grouped Ready to claim, In progress and Finished; today's status filter in ⋯.
 * Exits that share transactions (Decision 6) share one gutter, their paths drawn once, lying on their side, as they're sent
 * and paid once; every other row has only its coin and its state.
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
    ExitFrame(tr("Exits"), back, chip = filter?.let { key -> { com.noahwallet.ui.settings.FilterChip(tr(ExitTimeline.labels[key] ?: key)) { filter = null } } }, actions = {
        StateMenu(filter, exits.map { it.state }.distinct()) { if (it != filter) haptics.selection(); filter = it }
    }, foot = {
        FootPill(tr("Exit more VTXOs"), Modifier.testTag("exit-more"), wrap = true, outlined = claimable.isNotEmpty(), enabled = !suspended, onClick = more)
        if (claimable.isNotEmpty()) FootPill(tr("Claim {amount}", money(claimable.sumOf { it.amount })), Modifier.testTag("exit-list-claim"), wrap = true, enabled = !suspended, onClick = claim)
    }, leading = {
        Text(exitCounts(stages.values) ?: tr("No exits in progress"), Modifier.padding(top = 8.dp), style = HomeType.line.copy(fontSize = 17.sp, lineHeight = 22.sp),
            color = palette.muted)
        // Every exit's fee children, each once, as Activity's "Exit fee" rows.
        val fees = ExitFees.total(exits)
        if (fees > 0) Text(tr("{amount} in network fees", money(fees)), style = HomeType.line.copy(fontSize = 17.sp, lineHeight = 22.sp), color = palette.muted)
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
        val sections = listOf(tr("Ready to claim") to visible.filter { stages.getValue(it.id).phase in ready },
            tr("In progress") to visible.filter { stages.getValue(it.id).live && stages.getValue(it.id).phase !in ready },
            tr("Finished") to visible.filter { !stages.getValue(it.id).live })
        for ((name, list) in sections) if (list.isNotEmpty()) {
            Text(name, Modifier.padding(top = 18.dp, bottom = 2.dp).semantics { heading() }, style = HomeType.header, color = palette.muted)
            val blocks = ExitList.blocks(list, shared)
            for ((index, block) in blocks.withIndex()) {
                val rows = block.map { it to stages.getValue(it.id) }
                // A line under every row but the section's last, as the rows are shown.
                val divider = index < blocks.lastIndex
                if (rows.size > 1) {
                    SharedRows(rows, tip, money, open, divider = divider)
                    val group = ExitList.Shared(block, block.flatMap { exit -> exit.packages.map { it.txid } }.distinct().size, block.sumOf { it.packages.size })
                    Text(tr("These {n} share part of their path: {count} transactions to send, not {total}. Shared ones are sent and paid once.",
                        group.exits.size.toString(), group.transactions.toString(), group.separately.toString()),
                        Modifier.padding(start = ExitGutter.sharedWidth.dp, top = 4.dp, bottom = 6.dp).testTag("exit-shared"), style = HomeType.small, color = palette.muted)
                } else ExitListRow(rows[0].first, rows[0].second, tip, money, divider = divider) { open(block[0].id) }
            }
        }
        status()
    }
}

/** "6 in progress · 1 ready to claim", leaving out the counts that are zero; null when both are. */
@Composable
internal fun exitCounts(stages: Collection<ExitStage>): String? = ExitCounts.of(stages).let { c ->
    listOfNotNull(trPlural("{n} in progress", c.inProgress).takeIf { c.inProgress > 0 }, trPlural("{n} ready to claim", c.ready).takeIf { c.ready > 0 })
        .joinToString(" · ").ifEmpty { null }
}

/** The line under an exit's amount: what it waits for, in words; it wraps rather than truncates. [shared]: it shares transactions. */
@Composable
private fun exitListLine(stage: ExitStage, tip: Long, shared: Boolean): String = when {
    stage.attention == ExitStage.Attention.needsFunds -> tr("Needs on-chain funds")
    stage.phase == ExitStage.Phase.wait -> (stage.left(tip) ?: 0).let { trPlural("Waiting · {n} blocks · {time}", it, exitAbout(it)) }
    stage.phase == ExitStage.Phase.claimed -> tr("Claimed · block {height}", number(stage.claimedBlock ?: 0))
    stage.phase == ExitStage.Phase.canceled -> tr("Canceled · still in Ark")
    shared && stage.live && stage.phase !in setOf(ExitStage.Phase.claimable, ExitStage.Phase.claiming) -> tr("{status} · shared", exitStatusWord(stage))
    else -> exitStatusWord(stage)
}

/**
 * One exit (prototype `xrow`): a 44 dp gutter with its coin and the coin's state (a glow when ready, the dial while locked,
 * a check once claimed, a red "!" when it needs funds, muted when canceled), its amount and its line, wrapping.
 */
@Composable
internal fun ExitListRow(exit: ExitModel, stage: ExitStage, tip: Long, money: (Long) -> String, divider: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val path = ExitGutter.path(exit, stage)
    val coin = GutterCoin(path, stage, tip)
    var visible by remember { mutableStateOf(true) }
    val breath = rememberBreath(coin.pulses && visible && rememberResumed() && !LocalReduceMotion.current)
    Box(Modifier.onGloballyPositioned { visible = it.boundsInWindow().height > 0f }) {
        Box(Modifier.matchParentSize()) {
            val at = Modifier.align(Alignment.CenterStart)
            Box(at.offset(x = (22 - 40).dp).size(80.dp).drawBehind { scale(density, density, Offset.Zero) { coin.pulse(this, Offset(40f, 40f), breath, palette) } })
            Box(at.size(44.dp).drawBehind { scale(density, density, Offset.Zero) { coin.draw(this, Offset(22f, size.height / density / 2), palette) } })
        }
        RowText(exit, stage, tip, money, ExitGutter.coinWidth, divider, Modifier, onClick = onClick)
    }
}

/** An exit's words beside its gutter (prototype `.xr`): amount, then its line, then the chevron; muted once canceled. */
@Composable
private fun RowText(exit: ExitModel, stage: ExitStage, tip: Long, money: (Long) -> String, gutter: Float, divider: Boolean, modifier: Modifier,
                    shared: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val failed = stage.attention == ExitStage.Attention.needsFunds
    val muted = stage.phase in setOf(ExitStage.Phase.canceled, ExitStage.Phase.spent, ExitStage.Phase.swept)
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).drawBehind {
        if (divider) drawRect(palette.hairline, Offset(gutter.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - gutter.dp.toPx(), 1.dp.toPx()))
    }.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {}.testTag("exit-row-${exit.id.take(12)}"),
        verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(gutter.dp))
        Column(Modifier.weight(1f).padding(vertical = 9.dp).graphicsLayer { alpha = if (muted) .55f else 1f }) {
            Text(money(exit.amount), style = HomeType.amount, color = palette.foreground)
            Text(exitListLine(stage, tip, shared), style = HomeType.small.copy(fontWeight = if (stage.phase == ExitStage.Phase.claimable) FontWeight.Medium else FontWeight.Normal),
                color = when { failed -> palette.failure; stage.phase == ExitStage.Phase.claimable -> palette.foreground; else -> palette.muted })
        }
        Icon(painterResource(com.noahwallet.ui.home.HomeIcons.chevron), null, Modifier.padding(start = 12.dp).size(18.dp), tint = palette.muted)
    }
}

/**
 * Exits that share transactions (prototype `drawGut`, shared): one gutter for all of them, the round's block at the left,
 * each transaction drawn once at the height of the rows it leads to, every coin at the right. Ink is in a block, the
 * waiting one glows, hollow ones aren't sent; the gutter stretches with the rows' text.
 */
@Composable
private fun SharedRows(rows: List<Pair<ExitModel, ExitStage>>, tip: Long, money: (Long) -> String, open: (String) -> Unit, divider: Boolean) {
    val palette = LocalPalette.current
    val paths = rows.map { (exit, stage) -> ExitGutter.path(exit, stage) }
    val coins = rows.mapIndexed { i, (_, stage) -> GutterCoin(paths[i], stage, tip) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val centers = remember(rows.size) { mutableStateListOf(*Array(rows.size) { Float.NaN }) }
    // Each transaction is drawn once, in its furthest state among the exits that share it (as the gutter's layout draws it).
    val states = ExitGutter.states(paths)
    val waiting = paths.flatMap { it.dropLast(1) }.map { it.first }.distinct().filter { states[it] == ExitGutter.St.waiting }
    var visible by remember { mutableStateOf(true) }
    val breath = rememberBreath((waiting.isNotEmpty() || coins.any { it.pulses }) && visible && rememberResumed() && !LocalReduceMotion.current)
    // The gutter once its rows are measured, worked out again only when they move.
    val layout by remember(paths) { derivedStateOf { if (centers.any { it.isNaN() }) null else ExitGutter.shared(paths, centers.toList()) } }
    // The waiting transactions as drawn: each once, or once per exit where a path too long folds each exit alone.
    val waits = layout?.nodes?.filter { !it.coin && it.id in waiting }.orEmpty()
    Box(Modifier.onGloballyPositioned { visible = it.boundsInWindow().height > 0f }) {
        // Under the gutter: the waiting transactions' glow and rings, and the coins' own.
        for (node in waits) GutterSpot(60f, { Offset(node.x, node.y) }) { c -> gutterPulse(c, 8f, breath, palette) }
        coins.forEachIndexed { i, coin -> if (coin.pulses) GutterSpot(80f, { layout?.let { Offset(90f, centers[i]) } }) { c -> coin.pulse(this, c, breath, palette) } }
        Column(Modifier.drawBehind {
            val l = layout ?: return@drawBehind
            scale(density, density, Offset.Zero) {
                for (link in l.links) treeLink(Offset(link.from.first, link.from.second), Offset(link.to.first, link.to.second), 0f, 1f,
                    if (link.ink) palette.foreground else palette.orange, if (link.ink) 2f else 1.6f, 1f, horizontal = true)
                drawRoundRect(palette.foreground, Offset(l.root.first - 4.5f, l.root.second - 4.5f), Size(9f, 9f), CornerRadius(2f))
                for (node in l.nodes) if (!node.coin) gutterNode(Offset(node.x, node.y), node.st, palette, node.folded)
                coins.forEachIndexed { i, coin -> coin.draw(this, Offset(90f, centers[i]), palette) }
            }
        }) {
            rows.forEachIndexed { i, (exit, stage) ->
                RowText(exit, stage, tip, money, ExitGutter.sharedWidth, divider || i < rows.lastIndex,
                    Modifier.onPlaced { c -> centers[i] = (c.positionInParent().y + c.size.height / 2f) / density }, shared = true) { open(exit.id) }
            }
        }
        // Above it: the waiting transactions' dashed outline, marching.
        for (node in waits) GutterSpot(14f, { Offset(node.x, node.y) }) { c ->
            drawRoundRect(palette.orange, Offset(c.x - 4, c.y - 4), Size(8f, 8f), CornerRadius(2.5f), alpha = .7f + .3f * breath.breathe,
                style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 1.6f), -breath.march * 3.6f / 5.4f)))
        }
    }
}

/** A small box centred on [center] (dp, in its parent's coordinates) that draws in dp. */
@Composable
private fun GutterSpot(side: Float, center: () -> Offset?, draw: DrawScope.(Offset) -> Unit) {
    Box(Modifier.offset {
        val c = center() ?: Offset(-10_000f, -10_000f)
        IntOffset(((c.x - side / 2) * density).roundToInt(), ((c.y - side / 2) * density).roundToInt())
    }.size(side.dp).drawBehind { scale(density, density, Offset.Zero) { draw(Offset(side / 2, side / 2)) } })
}

/**
 * A transaction in a gutter (prototype `txNode` at 8 dp): ink when mined, a filled square while it waits in the mempool, an
 * outlined square when it's ready to send (ringed red when short of funds), hollow when not sent. A long path's [folded]
 * ones: its oldest blocks as one stacked square, the rest to send as three dots.
 */
private fun DrawScope.gutterNode(at: Offset, st: ExitGutter.St, palette: NoahTokens.Palette, folded: Boolean = false) {
    val z = 8f
    if (folded) {
        if (st == ExitGutter.St.mined) {
            drawRoundRect(palette.foreground, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(2.5f))
            for (plate in 1..2) drawLine(palette.background, Offset(at.x - z / 2 + 1f, at.y - z / 2 + z * plate / 3f), Offset(at.x + z / 2 - 1f, at.y - z / 2 + z * plate / 3f), 1f)
        } else for (dx in listOf(-2.8f, 0f, 2.8f)) drawCircle(palette.orange, 1f, Offset(at.x + dx, at.y))
        return
    }
    when (st) {
        ExitGutter.St.mined -> drawRoundRect(palette.foreground, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(2.5f))
        ExitGutter.St.waiting -> drawRoundRect(palette.background, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(2.5f))
        ExitGutter.St.ready -> {
            drawRoundRect(palette.background, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(2.5f))
            drawRoundRect(palette.orange, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(2.5f), style = Stroke(1.6f))
        }
        ExitGutter.St.needsFunds -> {
            drawRoundRect(palette.background, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(2.5f))
            drawRoundRect(palette.orange, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(2.5f), style = Stroke(1.6f))
            drawCircle(palette.failure, z * .95f, at, alpha = .8f, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
        }
        ExitGutter.St.unsent -> { drawCircle(palette.background, 1.85f, at); drawCircle(palette.orange, 1.85f, at, style = Stroke(1.4f)) }
    }
}

/** The glow and rings of a transaction waiting for the next block, [z] dp square; a steady glow when still. */
private fun DrawScope.gutterPulse(c: Offset, z: Float, breath: Breath, palette: NoahTokens.Palette) {
    treeGlow(c, z * 1.6f, .3f + .7f * breath.breathe, palette.orange)
    if (breath.live) for (offset in listOf(0f, .5f)) {
        val q = breath.ring(offset); val e = 1 - (1 - q) * (1 - q); val h = z * (1 + 2.3f * e)
        drawRoundRect(palette.orange, Offset(c.x - h / 2, c.y - h / 2), Size(h, h), CornerRadius(3.5f + 5 * e), alpha = .8f * (1 - q).pow(1.3f),
            style = Stroke(.8f + 1.6f * (1 - q)))
    }
}

/** An exit's coin in a gutter, and what it shows: ink once on-chain, the dial while locked, glowing when ready, a check once claimed. */
private class GutterCoin(path: List<Pair<String, ExitGutter.St>>, stage: ExitStage, tip: Long) {
    private val after = ExitGutter.after(stage)
    private val waits = path.lastOrNull()?.second == ExitGutter.St.waiting
    private val mined = path.lastOrNull()?.second == ExitGutter.St.mined
    private val short = path.any { it.second == ExitGutter.St.needsFunds }
    private val frac = stage.claimableHeight?.let { end -> stage.lockedFrom?.let { start ->
        ((tip - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f) } } ?: 0f
    val pulses = waits || after == ExitGutter.After.ready

    /** Under the coin: its glow, rings and dashed ring while it waits; its glow once ready. */
    fun pulse(scope: DrawScope, c: Offset, breath: Breath, palette: NoahTokens.Palette) = with(scope) {
        if (waits) {
            treeGlow(c, 20f, .3f + .7f * breath.breathe, palette.orange)
            if (breath.live) for (offset in listOf(0f, .5f)) {
                val q = breath.ring(offset); val e = 1 - (1 - q) * (1 - q)
                drawCircle(palette.orange, 14f * (1 + 2.3f * e), c, alpha = .8f * (1 - q).pow(1.3f), style = Stroke(.8f + 1.6f * (1 - q)))
            }
            drawCircle(palette.orange, 14f, c, alpha = .6f + .4f * breath.breathe,
                style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.4f), -breath.march)))
        }
        if (after == ExitGutter.After.ready) treeGlow(c, 22f, .4f + .6f * breath.breathe, palette.orange)
    }

    fun draw(scope: DrawScope, c: Offset, palette: NoahTokens.Palette) = with(scope) {
        val finished = after == ExitGutter.After.claimed || after == ExitGutter.After.canceled
        val a = if (finished) .6f else 1f
        if (after == ExitGutter.After.ready) drawCircle(palette.orange, 14f, c, style = Stroke(2f))
        if (after == ExitGutter.After.lock) {
            drawCircle(palette.hairline, 14f, c, style = Stroke(2f))
            drawArc(palette.foreground, -90f, 360f * frac, false, Offset(c.x - 14, c.y - 14), Size(28f, 28f), style = Stroke(2.2f, cap = StrokeCap.Round))
        }
        treeCoin(c, 10f, if (mined && after != ExitGutter.After.claimed && after != ExitGutter.After.ready) palette.foreground else palette.soft,
            if (after == ExitGutter.After.canceled) palette.muted else palette.orange, a)
        if (after == ExitGutter.After.claimed) treeCheck(Offset(c.x + 8, c.y + 8), .62f, palette.foreground, palette.background)
        if (after == ExitGutter.After.lock) treePadlock(Offset(c.x + 8, c.y + 8), 10f, 0f, 1f, palette.foreground, palette.background)
        if (short) {
            drawCircle(palette.failure, 6f, Offset(c.x + 8, c.y + 8))
            drawLine(Color.White, Offset(c.x + 8, c.y + 5), Offset(c.x + 8, c.y + 8.6f), 1.6f, StrokeCap.Round)
            drawCircle(Color.White, .9f, Offset(c.x + 8, c.y + 10.8f))
        }
    }
}

/** Resumed: the screen is in front, not paused behind another or in the background. */
@Composable
internal fun rememberResumed() = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)

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
