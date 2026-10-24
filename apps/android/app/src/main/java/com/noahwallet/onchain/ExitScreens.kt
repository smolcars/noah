package com.noahwallet.onchain

import android.content.Intent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.R
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.OnboardingPose
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.send.SendPill
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * An exit page (exit.md, Wide and foldable): one column on a phone; on a wide window or a book fold the explanation and
 * picture ([leading]) sit before the hinge and the details and actions ([trailing], [foot]) after it; on a tabletop fold
 * the picture sits above and the details below. [title] is a large title unless the page is a [hero] with its own header.
 * The foot is fixed and the content scrolls above it, so it never covers a row. [pictureLeads]: the leading pane doesn't
 * scroll, so a picture there can fill it ([exitPicture]).
 */
@Composable
internal fun ExitFrame(title: String, back: (() -> Unit)?, hero: Boolean = false, foot: (@Composable RowScope.() -> Unit)? = null,
                       scroll: ScrollState = rememberScrollState(), actions: @Composable RowScope.() -> Unit = {}, footFade: Float = .46f,
                       pictureLeads: Boolean = false, chip: (@Composable () -> Unit)? = null,
                       leading: @Composable ColumnScope.() -> Unit, trailing: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    when (val pose = LocalPose.current) {
        is OnboardingPose.Wide, is OnboardingPose.Tabletop -> Box(Modifier.fillMaxSize().background(palette.background)) {
            val split = pose is OnboardingPose.Wide
            Column(when (pose) {
                is OnboardingPose.Wide -> Modifier.width(pose.start).fillMaxHeight()
                else -> Modifier.height((pose as OnboardingPose.Tabletop).top).fillMaxWidth()
            }.testTag("exit-leading").padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = if (split) 24.dp else 8.dp)
                .then(if (!pictureLeads) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = if (split && !pictureLeads) Arrangement.Center else Arrangement.Top) {
                if (!hero) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, Modifier.semantics { heading() }, style = ActivityType.large.copy(fontSize = if (split) 36.sp else 30.sp,
                        lineHeight = if (split) 42.sp else 36.sp), color = palette.foreground)
                    chip?.invoke()
                }
                leading()
            }
            Box(when (pose) {
                is OnboardingPose.Wide -> Modifier.padding(start = pose.end)
                else -> Modifier.padding(top = (pose as OnboardingPose.Tabletop).bottom)
            }.fillMaxSize().testTag("exit-trailing")) {
                CompositionLocalProvider(LocalSettingsPane provides SettingsPane.trailing) {
                    SettingsFrame(title, null, hero = true, foot = foot, scroll = scroll, footFade = footFade, content = trailing)
                }
            }
            // The bar across the top, above both panes: Back leads, ⋯ trails.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                back?.let { BarButton(R.drawable.ic_activity_back, tr("Back"), Modifier.testTag("settings-back")) { haptics.light(); it() } }
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
        else -> SettingsFrame(title, back, hero = hero, foot = foot, scroll = scroll, actions = actions, footFade = footFade, chip = chip) { leading(); trailing() }
    }
}

/** A big amount (prototype `.big`, 46 sp) that shrinks rather than clips in narrow panes and longer languages. */
@Composable
internal fun ExitBig(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    androidx.compose.foundation.text.BasicText(text, modifier, style = ActivityType.big.copy(color = palette.foreground), maxLines = 1,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = ActivityType.big.fontSize))
}

/** The picture's size: 300 dp in a column (prototype `.stage`), the rest of the leading pane when split. */
@Composable
internal fun ColumnScope.exitPicture(): Modifier =
    if (LocalPose.current is OnboardingPose.Compact) Modifier.fillMaxWidth().height(300.dp) else Modifier.fillMaxWidth().weight(1f)

/** The server's exit delta: the blocks an exit waits on-chain before it can be claimed. */
@Composable
internal fun rememberExitDelta(app: AppModel): Long? = produceState<Long?>(null) {
    value = try { withContext(Dispatchers.IO) { app.session.arkInfo()?.vtxoExitDelta?.toLong() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
}.value

/** One VTXO's exit estimate, for the warning's "About ₿X a VTXO now": bark's, for the largest that can exit. */
@Composable
private fun rememberSampleCost(app: AppModel, vtxos: List<VtxoModel>): ExitCosts.Cost? {
    val id = vtxos.filter { it.eligible }.maxByOrNull { it.amount }?.id
    return produceState<ExitCosts.Cost?>(null, id) {
        value = id?.let { try { withContext(Dispatchers.IO) { app.session.exitCosts(listOf(it))[it] } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } }
    }.value
}

/**
 * The warning (prototype `SCR.warn`), Settings → Emergency exit: one line, exits under way, four facts with the real
 * exit delta and per-VTXO fee, and Choose VTXOs. Reading it and estimating never broadcasts anything.
 */
@Composable
internal fun ExitWarningPage(app: AppModel, nav: SettingsNav) {
    val model = rememberOnchainModel(app)
    OnchainEffects(app, model, reloadOnChange = true)
    val overview = model.overview
    val needsFunds = remember(model.revision) { overview?.let { ExitFunding.shared.needsFunds(it.exits, it.confirmed, app.session.daemonRun) }.orEmpty() }
    ExitWarningContent(overview?.exits.orEmpty(), overview?.tip ?: 0, rememberExitDelta(app), rememberSampleCost(app, overview?.vtxos.orEmpty())?.broadcast,
        app.isWalletSuspended, { money(app, it) }, nav.back, choose = { nav.open(ExitChoosePage) }, open = { nav.open(ExitPage(it)) },
        all = { nav.open(ExitsPage) }, needsFunds = needsFunds) { OnchainStatus(app, model) }
}

@Composable
internal fun ExitWarningContent(exits: List<ExitModel>, tip: Long, delta: Long?, sample: Long?, suspended: Boolean, money: (Long) -> String,
                                back: (() -> Unit)?, choose: () -> Unit, open: (String) -> Unit, all: () -> Unit, needsFunds: Set<String> = emptySet(),
                                status: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    ExitFrame(tr("Emergency exit"), back, foot = {
        FootPill(tr("Choose VTXOs"), Modifier.testTag("exit-choose"), enabled = !suspended, onClick = choose)
    }, leading = {
        Text(tr("Emergency use only. It takes your bitcoin out of Ark without Ark’s server."), Modifier.padding(top = 8.dp),
            style = HomeType.line.copy(fontSize = 17.sp, lineHeight = 22.sp), color = palette.muted)
        val live = exits.filter { it.state !in ExitTimeline.terminal }
        if (exits.isNotEmpty()) Column(Modifier.padding(top = 14.dp)) {
            live.take(3).forEach { exit -> ExitRow(exit, tip, money, divider = true, needsFunds = exit.id in needsFunds) { open(exit.id) } }
            SettingsRow(tr("All exits"), SettingsIcons.exit, value = if (live.isEmpty()) null else tr("{n} in progress", live.size.toString()),
                divider = false, modifier = Modifier.testTag("exit-all"), onClick = all)
        }
        ExitPicture(ExitPose.sample(tip = tip), tr("Your VTXO’s path to the chain"), Modifier.padding(top = 14.dp).fillMaxWidth().height(236.dp).testTag("exit-warn-picture"))
    }) {
        if (suspended) FinePrint(tr("Wallet suspended. Resume your wallet in Settings."))
        Column(Modifier.padding(top = 20.dp)) {
            Fact(SettingsIcons.clock, tr("Takes a day or more"), delta?.let { tr("Then a {n}-block wait before you can claim.", it.toString()) }
                ?: tr("Then a wait of about a day before you can claim."))
            Fact(SettingsIcons.fee, tr("Costs on-chain fees"), sample?.let { tr("About {amount} a VTXO now, from your on-chain bitcoin.", money(it)) }
                ?: tr("Paid from your on-chain bitcoin."))
            Fact(SettingsIcons.noUndo, tr("Can’t be undone"), tr("Once its last transaction is sent."))
            Fact(SettingsIcons.ark, tr("Ark is faster and cheaper"), tr("If Ark works, send to a Bitcoin address instead."), divider = false)
        }
        FinePrint(tr("Reading this screen and estimating fees never broadcasts an exit."))
        status()
    }
}

@Composable
private fun Fact(icon: Int, title: String, line: String, divider: Boolean = true) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).drawBehind {
        if (divider) drawRect(palette.hairline, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
    }.padding(vertical = 9.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(34.dp).background(palette.soft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(19.dp), tint = palette.foreground)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ActivityType.value, color = palette.foreground)
            Text(line, style = HomeType.small, color = palette.muted)
        }
    }
}

/** One exit in a list: the coin, its amount and its phase in a line. The full row arrives with the exits list. */
@Composable
internal fun ExitRow(exit: ExitModel, tip: Long, money: (Long) -> String, divider: Boolean, needsFunds: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val stage = ExitStages.of(exit, tip, needsFunds)
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).drawBehind {
        if (divider) drawRect(palette.hairline, Offset(54.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 54.dp.toPx(), 1.dp.toPx()))
    }.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {}.testTag("exit-row-${exit.id.take(12)}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ExitCoin(40.dp, pending = stage.live, badge = needsFunds)
        Column(Modifier.weight(1f)) {
            Text(money(exit.amount), style = HomeType.amount, color = palette.foreground, maxLines = 1)
            Text(exitStatusWord(stage), style = HomeType.small, color = if (needsFunds) palette.failure else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Your coin as a list avatar (prototype `.av.coin`): a soft circle in an orange ring; [pending] adds the turning dashes. */
@Composable
internal fun ExitCoin(size: androidx.compose.ui.unit.Dp, pending: Boolean = false, badge: Boolean = false, done: Boolean = false) {
    val palette = LocalPalette.current
    Box {
        SettingsAvatar(size, pending = pending, badge = badge)
        Box(Modifier.size(size).drawBehind { drawCircle(palette.orange, this.size.minDimension / 2 - 1.25.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx())) })
        if (done) Box(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(19.dp).background(palette.background, CircleShape).padding(2.dp)
            .background(palette.foreground, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(SettingsIcons.check), null, Modifier.size(11.dp), tint = palette.background)
        }
    }
}

/** The one status word under an exit's amount (prototype `STATUS`). */
@Composable
internal fun exitStatusWord(stage: ExitStage): String = when {
    stage.attention == ExitStage.Attention.needsFunds -> tr("Waiting for on-chain funds")
    else -> when (stage.phase) {
        ExitStage.Phase.lift, ExitStage.Phase.publish -> tr("Starting")
        ExitStage.Phase.mempool, ExitStage.Phase.bump -> tr("Sending {n} of {total}", (stage.current + 1).toString(), stage.total.toString())
        ExitStage.Phase.confirm -> tr("Sending {n} of {total}", (stage.inBlocks + 1).coerceAtMost(stage.total).toString(), stage.total.toString())
        ExitStage.Phase.onchain, ExitStage.Phase.wait -> tr("Waiting for the timelock")
        ExitStage.Phase.claimable -> tr("Ready to claim")
        ExitStage.Phase.claiming -> tr("Claiming")
        ExitStage.Phase.claimed -> tr("Claimed")
        ExitStage.Phase.canceled -> tr("Canceled")
        ExitStage.Phase.spent -> tr("Already spent")
        ExitStage.Phase.swept -> tr("Swept")
    }
}

/**
 * Choose VTXOs (prototype `SCR.choose`): All or Choose; each VTXO's amount and expiry, "Fees more than it's worth" (Decision 4),
 * dust and locked ones shown but not selectable; the total and bark's estimate for the selection, and "Add on-chain funds"
 * when the confirmed on-chain balance can't pay the network fees. Review exit opens the start page.
 */
@Composable
internal fun ExitChoosePage(app: AppModel, nav: SettingsNav) {
    val model = rememberOnchainModel(app)
    OnchainEffects(app, model, reloadOnChange = true)
    var deposit by rememberSaveable { mutableStateOf<Long?>(null) }
    val vtxos = model.overview?.vtxos.orEmpty()
    val eligible = vtxos.filter { it.eligible }.map { it.id }
    var retries by remember { mutableIntStateOf(0) }
    // Each VTXO's own estimate, once per set of VTXOs (not on every periodic read); one bark couldn't estimate stays unknown
    // (null) and Try again asks for those. Kept with the set it classified, so a new set reads as unclassified.
    val classified by produceState<Pair<List<String>, Map<String, ExitCosts.Cost?>>?>(null, eligible, retries) {
        val known = value?.takeIf { it.first == eligible }?.second.orEmpty().filterValues { it != null }
        val missing = eligible.filter { it !in known }
        value = eligible to known + if (missing.isEmpty()) emptyMap() else try { withContext(Dispatchers.IO) { app.session.exitCosts(missing) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { missing.associateWith { null } }
    }
    val costs = classified?.takeIf { it.first == eligible }?.second
    // bark's "All" estimate names the VTXOs it would leave out; the last answer stands while a new one fails.
    val excluded by produceState(emptySet<String>(), eligible, retries) {
        value = try { withContext(Dispatchers.IO) { app.session.exitExcluded() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { value }
    }
    ExitChooseContent(vtxos, costs, model.overview?.confirmed, loading = model.overview == null && model.loading, ready = model.overview != null,
        suspended = app.isWalletSuspended, busy = model.busy, money = { money(app, it) }, back = nav.back,
        // bark's estimate for exactly the selection (shared transactions once); read-only.
        estimate = { ids -> try { withContext(Dispatchers.IO) { app.session.quoteOnchain(OnchainAction.startExit, ids) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null } },
        review = { nav.open(ExitStartPage(it)) }, deposit = { deposit = it }, retry = { retries++ }, refused = ExitErrors.refused, excluded = excluded) { OnchainStatus(app, model) }
    deposit?.let { need -> if (!app.isWalletSuspended) ExitDepositSheet(app, need) { deposit = null } }
}

@Composable
internal fun ExitChooseContent(all: List<VtxoModel>, costs: Map<String, ExitCosts.Cost?>?, confirmed: Long?, loading: Boolean, ready: Boolean,
                               suspended: Boolean, busy: Boolean, money: (Long) -> String, back: (() -> Unit)?,
                               estimate: suspend (List<String>) -> OnchainQuote?, review: (List<String>) -> Unit, deposit: (Long) -> Unit,
                               initial: List<String>? = null, retry: () -> Unit = {}, refused: Map<String, String> = emptyMap(),
                               excluded: Set<String> = emptySet(), status: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    var everything by rememberSaveable { mutableStateOf(initial == null) }
    var picked by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    val vtxos = all.sortedByDescending { it.amount }
    // "All" is fixed only once every VTXO is classified: until then it might hold one that costs more than it's worth.
    val unknown = if (costs == null) 0 else vtxos.count { ExitCosts.barred(it, refused, excluded) == null && costs[it.id] == null }
    val classified = costs != null && unknown == 0
    // No selection or total holds a VTXO that can't be selected, whatever was picked before.
    val chosen = if (everything) ExitCosts.all(vtxos, costs.orEmpty(), refused, excluded)
        else vtxos.filter { it.id in picked && ExitCosts.barred(it, refused, excluded) == null }
    val ids = chosen.map { it.id }
    // Only when the selection changes: never on the page's periodic read.
    val quote by produceState<OnchainQuote?>(null, ids, classified) {
        value = null
        if (ids.isEmpty() || (everything && !classified)) return@produceState
        delay(250)
        value = estimate(ids)
    }
    val fees = quote?.takeIf { it.fee != null && it.chainFee != null && it.ids == ids.sorted() }
    val value = chosen.sumOf { it.amount }
    val short = if (fees != null && confirmed != null) checkNotNull(fees.chainFee) - confirmed else 0
    // A summary above the pill: the fade turns solid sooner so rows never show through it (prototype `.foot:has(.fsum)`).
    ExitFrame(tr("Choose VTXOs"), back, footFade = if (everything) .46f else .3f, foot = {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!everything) Text(if (chosen.isEmpty()) tr("Choose the VTXOs to exit") else fees?.chainFee?.let { tr("{amount} · fees ~{fee}", money(value), money(it)) }
                ?: money(value), Modifier.padding(bottom = 8.dp), style = HomeType.amount.copy(fontSize = 15.sp), color = palette.foreground)
            SendPill(tr("Review exit"), Modifier.fillMaxWidth().testTag("exit-review"), height = 56.dp,
                enabled = chosen.isNotEmpty() && !busy && !suspended && (!everything || classified)) { haptics.light(); review(ids) }
        }
    }, leading = {
        Segmented(listOf(true to tr("All"), false to tr("Choose")), everything, Modifier.padding(top = 10.dp).testTag("exit-mode")) { everything = it }
        when {
            suspended -> FinePrint(tr("Wallet suspended. Resume your wallet in Settings."))
            !ready -> FinePrint(if (loading) tr("Loading…") else tr("Couldn't read wallet status. Pull to refresh."))
            vtxos.isEmpty() -> FinePrint(tr("No eligible spendable VTXOs."))
            else -> Column(Modifier.padding(top = 6.dp)) {
                vtxos.forEachIndexed { index, vtxo ->
                    ChooseRow(vtxo, costs?.get(vtxo.id), if (everything) null else vtxo.id in picked, money, divider = index < vtxos.lastIndex,
                        refused = refused, excluded = excluded) {
                        haptics.selection(); picked = if (vtxo.id in picked) picked - vtxo.id else picked + vtxo.id
                    }
                }
            }
        }
    }) {
        if (vtxos.isEmpty()) { status(); return@ExitFrame }
        Column(Modifier.padding(top = 18.dp)) {
            DetailRow(tr("VTXOs"), fees?.transactions?.let { "${chosen.size} · " + trPlural("{n} transactions", it) } ?: chosen.size.toString(),
                ValueKind.number, toasts = toasts)
            DetailRow(tr("Network fees"), fees?.chainFee?.let { "~" + money(it) } ?: "—", ValueKind.number, toasts = toasts)
            DetailRow(tr("Claim fee"), fees?.fee?.let { "~" + money(it) } ?: "—", ValueKind.number, toasts = toasts, last = true)
            TotalRow(tr("You get"), fees?.fee?.let { "~" + money(ExitRules.received(value, it)) } ?: money(value))
        }
        if (short > 0) {
            Warning(tr("Add about {amount} on-chain first. Each exit transaction’s fee comes from there.", money(short)), failed = true)
            SettingsLink(tr("Add on-chain funds"), Modifier.padding(top = 4.dp).testTag("exit-add-funds")) { deposit(short) }
        } else if (fees != null && confirmed != null) FinePrint(tr("Network fees come from your on-chain {amount} at {rate} sat/vB. Transactions your VTXOs share are paid once. Fees may change.",
            money(confirmed), fees.feeRate.toString()))
        else if (chosen.isNotEmpty() && (classified || !everything) && quote == null) FinePrint(tr("Fee estimate unavailable"))
        if (fees != null && value > 0 && fees.totalFee >= value) Warning(tr("Fees are more than these VTXOs are worth."), failed = true)
        if (everything) {
            if (unknown > 0) {
                FinePrint(trPlural("Couldn’t check the fees of {n} VTXOs, so All waits. Choose them one by one, or try again.", unknown), failed = true)
                SettingsLink(tr("Try again"), Modifier.testTag("exit-costs-retry"), enabled = !busy, onClick = retry)
            }
            val out = ExitCosts.leftOut(vtxos, costs.orEmpty())
            if (out.dust > 0) FinePrint(tr("Left out: {amount}, too small to exit.", money(out.dust)))
            if (out.locked > 0) FinePrint(tr("Left out: {amount}, in use right now.", money(out.locked)))
            if (out.uneconomic > 0) FinePrint(tr("Left out: {amount} that costs more in fees than it’s worth. Choose it to exit anyway.", money(out.uneconomic)))
        }
        Spacer(Modifier.height(40.dp))
        status()
    }
}

/** A total row (prototype `.br.tot`): a line above, the label in the foreground. */
@Composable
internal fun TotalRow(label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind { drawRect(palette.foreground, Offset.Zero, Size(size.width, 1.dp.toPx())) }
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = ActivityType.value, color = palette.foreground)
        Text(value, style = ActivityType.number, color = palette.foreground, maxLines = 1)
    }
}

/**
 * A VTXO to choose (prototype `vtxoRow`): a checkbox in Choose, the coin in All; ones that can't exit are dimmed.
 * [refused]: VTXOs bark refused to exit, with their reason; [excluded]: ones bark's "All" estimate leaves out.
 */
@Composable
private fun ChooseRow(vtxo: VtxoModel, cost: ExitCosts.Cost?, checked: Boolean?, money: (Long) -> String, divider: Boolean,
                      refused: Map<String, String>, excluded: Set<String>, toggle: () -> Unit) {
    val palette = LocalPalette.current
    val uneconomic = ExitCosts.uneconomic(vtxo, cost)
    val barred = ExitCosts.barred(vtxo, refused, excluded)
    val exitable = barred == null
    val sub = when (barred) {
        ExitCosts.Barred.locked -> tr("In use right now")
        ExitCosts.Barred.dust -> tr("Too small to exit · under {amount}", money(ExitRules.dust))
        ExitCosts.Barred.state -> vtxo.status
        ExitCosts.Barred.renew -> tr("Renew first")
        ExitCosts.Barred.refused -> tr(refused.getValue(vtxo.id))
        null -> if (uneconomic) tr("Fees more than it’s worth") else expiresText(vtxo)
    }
    val picking = checked != null
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).drawBehind {
        if (divider) drawRect(palette.hairline, Offset(54.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 54.dp.toPx(), 1.dp.toPx()))
    }.then(if (picking) Modifier.clickable(enabled = exitable, role = Role.Checkbox, onClick = toggle) else Modifier)
        .semantics(mergeDescendants = true) { if (picking) toggleableState = androidx.compose.ui.state.ToggleableState(checked == true && exitable) }
        .graphicsLayer { alpha = if (exitable) 1f else .45f }.testTag("exit-vtxo-${vtxo.id.take(12)}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (!picking) ExitCoin(32.dp)
            else if (exitable) Box(Modifier.size(20.dp).background(if (checked == true) palette.foreground else palette.background, RoundedCornerShape(6.dp))
                .then(if (checked == true) Modifier else Modifier.drawBehind {
                    drawRoundRect(palette.muted, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                }), contentAlignment = Alignment.Center) {
                if (checked == true) Icon(painterResource(SettingsIcons.check), null, Modifier.size(15.dp), tint = palette.background)
            } else Box(Modifier.size(14.dp, 2.dp).background(palette.muted, RoundedCornerShape(1.dp)))
        }
        Column(Modifier.weight(1f)) {
            Text(money(vtxo.amount), style = HomeType.amount, color = palette.foreground, maxLines = 1)
            Text(sub, style = HomeType.small, color = if (exitable && uneconomic) palette.failure else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (barred == ExitCosts.Barred.renew) Text(tr("Renew this VTXO in Settings → VTXOs before exiting it."), style = HomeType.small, color = palette.muted)
        }
    }
}

/**
 * Add on-chain funds: today's deposit sheet (a fresh address, its QR, Copy and Share). [need] is what's missing for
 * the fees, when known; exits use the deposit once it confirms.
 */
@Composable
internal fun ExitDepositSheet(app: AppModel, need: Long, dismiss: () -> Unit) {
    var address by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(app.isWalletSuspended) {
        try { if (!onchainReady(app)) fail(OnchainFailure.unavailable); address = app.session.exitDepositAddress() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Couldn't load a deposit address. Close and try again when the wallet is ready." }
    }
    ExitDepositContent(address, error, need, { money(app, it) }, dismiss)
}

@Composable
internal fun ExitDepositContent(address: String?, error: String?, need: Long, money: (Long) -> String, dismiss: () -> Unit) {
    val context = LocalContext.current
    val toasts = LocalSettingsToasts.current
    val copied = tr("Copied")
    SettingsSheet(dismiss, tr("Add on-chain funds"), if (need > 0) tr("Send about {amount} here. Exits use it once it confirms.", money(need))
        else tr("Exits use it once it confirms."), modifier = Modifier.testTag("exit-deposit-sheet")) { close ->
        address?.let { value ->
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                com.noahwallet.ui.ReceiveQR(value, maximumSide = 220.dp) { copy(context, value); toasts.show(copied) }
            }
            DetailRow(tr("Bitcoin address"), short(value, 10, 8), ValueKind.mono, copy = value, toasts = toasts, last = true)
            FinePrint(tr("The amount required may vary."))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SendPill(tr("Copy"), Modifier.weight(1f), outlined = true, icon = SettingsIcons.copy) { copy(context, value); toasts.show(copied) }
                SendPill(tr("Share"), Modifier.weight(1f), icon = SettingsIcons.share) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value), null))
                }
            }
        } ?: FinePrint(tr(error ?: "Loading address…"), failed = error != null)
        SendPill(tr("Done"), Modifier.fillMaxWidth().padding(top = 10.dp), outlined = true) { close() }
    }
}
