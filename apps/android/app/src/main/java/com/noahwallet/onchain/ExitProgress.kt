package com.noahwallet.onchain

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noahwallet.BuildConfig
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.PulseDot
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.OnboardingPose
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.send.SendPill
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the exit page needs beyond bark's exit: the wallet's tip and balance, the server's delta, and its last claim: the
 * claim transaction's whole fee and every exit it claimed.
 */
internal data class ExitContext(val tip: Long, val confirmed: Long?, val delta: Long?, val claimFee: Long? = null, val claimIds: List<String> = emptyList(),
                                val claimedTo: String? = null, val claimEstimate: Long? = null, val cancelable: Boolean = false,
                                /** The part of the exit's network fees ([ExitStage.feesPaid]) for steps its other exits share ([ExitFees.paid]). */
                                val sharedFees: Long = 0) {
    /** This exit's part of [claimFee]; see [ExitClaim.share]. */
    fun claimShare(id: String) = claimFee?.let { ExitClaim.share(it, claimIds, id) }

    /** The claim to show: Noah's record of it, kept past acknowledgement, else its journal attempt (a claim from before records). */
    fun claimed(record: ExitClaimRecords.Record?, attempt: OnchainOperationJournal.Attempt?) = when {
        record != null -> copy(claimFee = record.fee, claimIds = record.ids, claimedTo = record.destination)
        attempt != null -> copy(claimFee = attempt.quote.fee, claimIds = attempt.quote.ids, claimedTo = attempt.quote.destination?.takeIf { attempt.quote.destinationIsMine == false })
        else -> this
    }
}

/**
 * One exit (prototype `SCR.progress`): the picture follows bark's state as Noah progresses it by itself; the rows, Details
 * and the actions the state allows. Reading it never broadcasts: Move exit forward and Cancel exit each review first.
 */
@Composable
internal fun ExitProgressPage(app: AppModel, nav: SettingsNav, id: String) {
    val model = rememberOnchainModel(app, id)
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val moved = tr("Exit moved forward"); val nothing = tr("Nothing to send yet")
    OnchainEffects(app, model, reloadOnChange = true)
    val overview = model.overview
    // What bark said the waiting package needs, when a check reported funds short: the amount to add comes from it.
    val exit = (model.exit ?: overview?.exits?.find { it.id == id })?.let { e -> ExitFunding.shared.needed(e.id)?.let { e.copy(nextPackageFee = it) } ?: e }
    val exits = overview?.exits.orEmpty().map { exit -> model.exit?.takeIf { it.id == exit.id } ?: exit }
    val seenStore = remember(app.settings) { ExitSeen.stored(app.settings) }
    // Recomputed on each completed read (every minute, as before), even an unchanged one; never a timer of its own.
    val (needsFunds, seen) = remember(model.revision) {
        overview?.let { o -> ExitFunding.shared.needsFunds(exits, o.confirmed, app.session.daemonRun) to seenStore.note(exits, o.tip) } ?: (emptySet<String>() to emptyMap())
    }
    val stalled = ExitFunding.shared.checkFailed || app.session.openWithoutDaemon
    val stage = exit?.let { ExitClaims.apply(it, ExitStages.of(it, overview?.tip ?: it.details.tip_height ?: 0, id in needsFunds, seen, stalled),
        ExitClaims.submitted(model.attempts)[id]) }
    // What the claim cost and where it went: Noah's record of it, or its attempt while the journal still has it.
    val claimRecords = remember(app.settings) { ExitClaimRecords.stored(app.settings) }
    val record = remember(model.revision, stage?.claimTxid) { claimRecords.of(id, stage?.claimTxid) }
    val claim = model.attempts.firstOrNull { it.quote.action == OnchainAction.claim && id in it.quote.ids && (stage?.claimTxid == null || it.receipt?.txid == stage.claimTxid) }
    val claimEstimate by produceState<Long?>(null, stage?.phase == ExitStage.Phase.claimable) {
        if (stage?.phase == ExitStage.Phase.claimable) value = try { withContext(Dispatchers.IO) { app.session.exitCosts(listOf(id))[id]?.claim } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    var deposit by remember { mutableStateOf<Long?>(null) }
    val context = ExitContext(overview?.tip ?: 0, overview?.confirmed, rememberExitDelta(app), claimEstimate = claimEstimate,
        cancelable = exit?.cancelable == true, sharedFees = exit?.let { ExitFees.paid(it, exits).second } ?: 0).claimed(record, claim)
    ExitProgressContent(exit, stage, context, model.busy, app.isWalletSuspended, { money(app, it) }, nav.back, loaded = model.revision > 0, others = exits,
        check = { scope.launch { model.review(OnchainAction.progress, overview?.exits.orEmpty().filter { it.state !in ExitTimeline.terminal }.map { it.id }) } },
        cancel = { scope.launch { model.review(OnchainAction.cancel, listOf(id)) } },
        claim = { nav.open(ExitClaimPage) }, addFunds = { deposit = it }, done = { nav.back?.invoke() }) { OnchainStatus(app, model) }
    model.quote?.let { quote ->
        when (quote.action) {
            OnchainAction.progress -> CheckSheet(quote, model.busy, { money(app, it) }, model::invalidate) {
                val before = model.exit
                scope.launch {
                    model.confirm()
                    // A completed check is bark's full progress: read again so a transaction still waiting shows why at once.
                    val completed = model.error == null && model.message == OnchainReceipt.progressedMessage
                    ExitFunding.shared.checked(model.overview?.exits.orEmpty(), completed)
                    if (completed) { model.load(); haptics.light() } else haptics.error()
                    // bark can't say whether it sent anything, so the exit itself tells; short of funds, the page says what it needs.
                    if (completed && ExitFunding.shared.needed(id) == null) toasts.show(if (model.exit != before) moved else nothing)
                    app.refresh() // Other exit and VTXO pages read the wallet again (OnchainEffects).
                }
            }
            OnchainAction.cancel -> CancelDialog(model::invalidate) {
                scope.launch { model.confirm(); if (model.message != null && model.error == null) haptics.success() else haptics.error() }
            }
            else -> {}
        }
    }
    deposit?.let { need -> if (!app.isWalletSuspended) ExitDepositSheet(app, need) { deposit = null } }
}

@Composable
internal fun ExitProgressContent(exit: ExitModel?, stage: ExitStage?, context: ExitContext, busy: Boolean, suspended: Boolean, money: (Long) -> String,
                                 back: (() -> Unit)?, check: () -> Unit, cancel: () -> Unit, claim: () -> Unit, addFunds: (Long) -> Unit, done: () -> Unit,
                                 loaded: Boolean = true, others: List<ExitModel> = emptyList(), status: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val scroll = rememberScrollState()
    var details by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val wide = LocalPose.current !is OnboardingPose.Compact
    if (exit == null || stage == null) {
        ExitFrame(tr("Exit"), back, hero = true, leading = {}) { FinePrint(if (loaded) tr("Exit status is unavailable. Pull to refresh.") else tr("Loading…")); status() }
        return
    }
    val scene = ExitScene.of(exit, stage, context.tip, others, context.confirmed, context.claimShare(exit.id), context.claimedTo)
    val phase = exitPhaseLine(stage, context.tip, money)
    val failed = stage.attention == ExitStage.Attention.needsFunds
    // Haptics as Send's: light on each block, success when claimed, error when it needs you.
    var previous by remember { mutableStateOf(stage) }
    LaunchedEffect(stage) {
        val was = previous; previous = stage
        when {
            was == stage -> {}
            (stage.phase == ExitStage.Phase.claimed || stage.phase == ExitStage.Phase.claimable) && was.phase != stage.phase -> haptics.success()
            failed && was.attention != stage.attention -> haptics.error()
            stage.inBlocks > was.inBlocks || stage.phase != was.phase -> haptics.light()
        }
    }
    val actions = ExitActions.of(stage, context.cancelable)
    // Beside a primary Claim or "Add on-chain funds", Move exit forward is a link at the top of the details, not a pill.
    val checkLink = ExitActions.Action.check in actions && (ExitActions.Action.claim in actions || ExitActions.Action.addFunds in actions)
    val received = context.claimShare(exit.id)?.takeIf { stage.phase == ExitStage.Phase.claimed }?.let { ExitRules.received(exit.amount, it) } ?: exit.amount
    val need = exit.nextPackageFee?.let { fee -> context.confirmed?.let { fee - it } }?.coerceAtLeast(0) ?: 0
    ExitFrame(tr("Exit"), back, hero = true, scroll = scroll, pictureLeads = true, foot = if (actions.isEmpty()) null else ({
        for (action in actions) when (action) {
            ExitActions.Action.done -> FootPill(tr("Done"), Modifier.testTag("exit-done"), wrap = true, onClick = done)
            ExitActions.Action.claim -> FootPill(context.claimEstimate?.let { tr("Claim {amount}", money(ExitRules.received(exit.amount, it))) } ?: tr("Claim"),
                Modifier.testTag("exit-claim"), wrap = true, enabled = !suspended, onClick = claim)
            ExitActions.Action.addFunds -> FootPill(tr("Add on-chain funds"), Modifier.testTag("exit-add-funds"), wrap = true, enabled = !suspended) { addFunds(need) }
            ExitActions.Action.cancel -> FootPill(tr("Cancel exit"), Modifier.testTag("exit-cancel"), wrap = true, outlined = true, weight = .8f,
                enabled = !busy && !suspended, onClick = cancel)
            ExitActions.Action.check -> if (!checkLink) FootPill(tr("Move exit forward"), Modifier.testTag("exit-check"), wrap = true,
                outlined = !stage.stale, enabled = !busy && !suspended, loading = busy, onClick = check)
        }
    }), leading = {
        if (!wide) ExitHeader(exit, stage, received, money, null)
        // TalkBack hears the phase, the progress and, once claiming, where the claim goes: the whole address.
        val destination = if (stage.phase == ExitStage.Phase.claiming || stage.phase == ExitStage.Phase.claimed)
            context.claimedTo?.let { tr("Another wallet") + ", " + it } ?: tr("This phone") else null
        ExitPicture(scene, listOfNotNull(phase.first, tr("{n} of {total} in blocks", stage.inBlocks.toString(), stage.total.toString()), destination).joinToString(". "),
            exitPicture().testTag("exit-picture"), money, balance = context.confirmed, received = received)
        ExitPhaseLine(phase.first, if (failed) palette.failure else if (phase.second) palette.foreground else palette.muted, Modifier.testTag("exit-phase"))
        if (wide) Text(exitExplanation(stage, context.delta), Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 40.dp),
            style = HomeType.line, color = palette.muted, textAlign = TextAlign.Center)
    }) {
        if (wide) ExitHeader(exit, stage, received, money, exitSubLine(stage, context, money))
        if (failed) Warning(tr("Add about {amount} on-chain. Noah sends the next transaction once it confirms.", money(need)), failed = true)
        if (checkLink) SettingsLink(tr("Move exit forward"), Modifier.testTag("exit-check"), enabled = !busy && !suspended, onClick = check)
        if (stage.stale && stage.live) Warning(trPlural("This exit is behind the current chain height: last checked {n} blocks ago, at block {height}. Moving it forward can also send exit transactions.",
            context.tip - (exit.details.tip_height ?: context.tip), number(exit.details.tip_height ?: 0)), SettingsIcons.clock)
        ExitRows(exit, stage, context, money)
        // The explorer without opening Details: the transaction that says the most about where the exit is.
        val explore = rememberExplore()
        exit.explorerTxid(stage.claimTxid)?.let { OnchainDisplay.explorerURL(it, BuildConfig.APP_VARIANT) }?.let { url ->
            SettingsLink(tr("View in explorer"), Modifier.padding(top = 8.dp).testTag("exit-explorer")) { explore(url) }
        }
        Details(details, { details = it }, scroll) { ExitDetailRows(exit, stage, context) }
        status()
    }
}

/** The amount (what you received once claimed) and the one status word, with its dot (prototype `.dstat`). */
@Composable
private fun ExitHeader(exit: ExitModel, stage: ExitStage, amount: Long, money: (Long) -> String, sub: String?) {
    val palette = LocalPalette.current
    val failed = stage.attention == ExitStage.Attention.needsFunds
    Column(Modifier.padding(top = 6.dp)) {
        ExitBig(money(amount), Modifier.testTag("exit-amount"))
        Row(Modifier.padding(top = 10.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                failed -> Box(Modifier.size(7.dp).background(palette.failure, CircleShape))
                stage.phase == ExitStage.Phase.claimable -> Box(Modifier.size(7.dp).background(palette.orange, CircleShape))
                stage.live -> PulseDot(palette.orange)
                else -> {}
            }
            Text(exitStatusWord(stage), Modifier.testTag("exit-status"), style = ActivityType.status, color = if (failed) palette.failure else palette.foreground)
        }
        sub?.let { Text(it, Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted) }
    }
}

/** The line under the picture: fades and rises in on each change (Send's `PhaseText`, in [color]). */
@Composable
private fun ExitPhaseLine(text: String, color: Color, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val enter = remember { Animatable(1f) }
    LaunchedEffect(text) { enter.snapTo(0f); enter.animateTo(1f, if (reduceMotion) tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS) else NoahTokens.Motion.snappy()) }
    Text(text, modifier.fillMaxWidth().padding(top = 2.dp).graphicsLayer {
        alpha = enter.value.coerceIn(0f, 1f); if (!reduceMotion) translationY = 6 * (1 - enter.value) * density
    }.semantics { liveRegion = LiveRegionMode.Polite }, style = HomeType.line.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        color = color, textAlign = TextAlign.Center)
}

/** "about 16 h", or "under an hour" for a few blocks (iOS `ExitText.about`). */
@Composable
internal fun exitAbout(blocks: Long): String = if (blocks < 6) tr("under an hour") else tr("about {time}", when {
    blocks < 144 -> tr("{n} h", (blocks / 6).toString())
    blocks < 1_008 -> tr("{n} d", (blocks / 144).toString())
    else -> tr("{n} wk", (blocks / 1_008).toString())
})

@Composable
internal fun number(value: Long) = java.text.NumberFormat.getIntegerInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(value)

/** The phase line (prototype `info().phase`) and whether it reads as done (foreground). */
@Composable
internal fun exitPhaseLine(stage: ExitStage, tip: Long, money: (Long) -> String): Pair<String, Boolean> {
    val n = (stage.current + 1).toString(); val total = stage.total.toString()
    val next = number(tip + 1)
    return when (stage.phase) {
        ExitStage.Phase.lift -> tr("Leaving Ark") to false
        ExitStage.Phase.publish -> trPlural("{n} transactions to the chain", stage.total) to false
        ExitStage.Phase.mempool -> (if (stage.attention == ExitStage.Attention.needsFunds) tr("Needs on-chain funds") else tr("Sending {n} of {total}", n, total)) to false
        ExitStage.Phase.bump -> when (stage.attention) {
            ExitStage.Attention.shared -> tr("Already sent by someone else · {n} of {total}", n, total)
            ExitStage.Attention.feesRose -> stage.rate?.let { tr("Paying {rate} sat/vB · waiting for block {height}", it.toString(), next) } ?: tr("Fees rose. Paying more")
            ExitStage.Attention.stuck -> trPlural("Not in a block yet · {n} blocks", stage.waited)
            else -> stage.fee?.let { tr("Anchor + {fee} fee · {n} of {total}", money(it), n, total) } ?: tr("Waiting for block {height} · {n} of {total}", next, n, total)
        } to false
        ExitStage.Phase.confirm -> tr("Confirmed in block {height} · {n} of {total}", number(stage.lastBlock ?: tip), stage.inBlocks.toString(), total) to true
        ExitStage.Phase.onchain -> tr("On-chain in block {height}", number(stage.lastBlock ?: tip)) to true
        ExitStage.Phase.wait -> (stage.left(tip) ?: 0).let { left -> trPlural("Claimable in {n} blocks · {time}", left, exitAbout(left)) } to false
        ExitStage.Phase.claimable -> tr("Unlocked at block {height}", number(stage.unlockedAt ?: tip)) to true
        ExitStage.Phase.claiming -> tr("Claiming · waiting for block {height}", next) to false
        ExitStage.Phase.claimed -> tr("Claimed in block {height}", number(stage.claimedBlock ?: tip)) to true
        ExitStage.Phase.canceled -> tr("Exit canceled") to true
        ExitStage.Phase.spent -> tr("Already spent") to false
        ExitStage.Phase.swept -> tr("Swept") to false
    }
}

/** The line under the status word on wide windows (prototype `sub`). */
@Composable
private fun exitSubLine(stage: ExitStage, context: ExitContext, money: (Long) -> String): String? {
    val next = number(context.tip + 1)
    return when {
        stage.attention == ExitStage.Attention.needsFunds -> tr("Needs on-chain funds")
        stage.attention == ExitStage.Attention.stuck -> tr("Waiting for a block with room")
        stage.attention == ExitStage.Attention.feesRose -> tr("Fees rose, so Noah paid more")
        else -> when (stage.phase) {
            ExitStage.Phase.lift, ExitStage.Phase.publish -> trPlural("{n} transactions", stage.total)
            // Ready, not yet sent with its fee child: it isn't waiting for a block yet (the picture says the same).
            ExitStage.Phase.mempool -> tr("Ready to send")
            ExitStage.Phase.bump, ExitStage.Phase.claiming -> tr("Waiting for block {height}", next)
            ExitStage.Phase.confirm -> tr("{n} of {total} in blocks", stage.inBlocks.toString(), stage.total.toString())
            ExitStage.Phase.onchain -> context.delta?.let { tr("Locked until block {height}", number((stage.lastBlock ?: context.tip) + it)) }
            ExitStage.Phase.wait -> tr("Claimable in about {time}", timeLeft(stage.left(context.tip) ?: 0))
            ExitStage.Phase.claimable -> tr("Claimable since block {height}", number(stage.unlockedAt ?: context.tip))
            ExitStage.Phase.claimed -> context.claimedTo?.let { tr("Sent to {address}", short(it, 6, 4)) } ?: tr("Claimed")
            ExitStage.Phase.canceled -> tr("Your VTXO stays in Ark")
            else -> null
        }
    }
}

/** One sentence per phase beside the picture on wide windows (prototype `EXPL`). */
@Composable
private fun exitExplanation(stage: ExitStage, delta: Long?): String = when {
    stage.stale && stage.live -> tr("Noah hasn’t checked on this exit in a while. Moving it forward can also send its next step or raise a fee.")
    stage.attention == ExitStage.Attention.needsFunds -> tr("Each transaction’s fee comes from your confirmed on-chain bitcoin. Noah sends the next one once a deposit confirms.")
    stage.attention == ExitStage.Attention.feesRose -> tr("A block came without it. Noah pays more when the fee rate rises, so it gets into the next one.")
    stage.attention == ExitStage.Attention.stuck -> tr("Blocks are full right now. Noah raises the fee when rates rise. Move your exit forward to try again now.")
    stage.attention == ExitStage.Attention.shared -> tr("Someone else exiting from your round already sent this one, with their own fee. You don’t pay for it.")
    else -> when (stage.phase) {
        ExitStage.Phase.lift -> tr("Your VTXO leaves Ark. Nothing from here on needs Ark’s server.")
        ExitStage.Phase.publish -> trPlural("Your VTXO is a leaf of the round’s tree. To reach it, the {n} transactions on your branch go to the chain, root first.", stage.total)
        ExitStage.Phase.mempool -> tr("Each one is sent only after the one before it is in a block.")
        ExitStage.Phase.bump -> tr("Every exit transaction has a fee anchor. Noah attaches a fee to it, paid from your on-chain bitcoin.")
        ExitStage.Phase.confirm -> tr("One transaction per block: about ten minutes each.")
        ExitStage.Phase.onchain, ExitStage.Phase.wait -> delta?.let { trPlural("Your bitcoin is on-chain, locked for {n} blocks. The lock gives Ark time to stop a VTXO that was already spent.", it) }
            ?: tr("Your bitcoin is on-chain and locked for a while. The lock gives Ark time to stop a VTXO that was already spent.")
        ExitStage.Phase.claimable -> tr("The lock is open. Claim it to another wallet or to this phone’s on-chain wallet.")
        ExitStage.Phase.claiming -> tr("One last transaction. Its fee comes out of the amount.")
        ExitStage.Phase.claimed -> tr("It’s ordinary bitcoin now.")
        ExitStage.Phase.canceled -> tr("The transactions already on-chain stay there. Your VTXO stays in your wallet and can exit again.")
        ExitStage.Phase.spent -> tr("This VTXO was already spent, so it can’t exit.")
        ExitStage.Phase.swept -> tr("An output this exit needs was spent on-chain, so it can’t finish.")
    }
}

@Composable
private fun ExitRows(exit: ExitModel, stage: ExitStage, context: ExitContext, money: (Long) -> String) {
    Column(Modifier.padding(top = 18.dp)) {
        when (stage.phase) {
            ExitStage.Phase.claimed -> {
                ExitValueRow(tr("Claimed in"), tr("Block {height}", number(stage.claimedBlock ?: 0)))
                ExitValueRow(tr("Network fees"), money(stage.feesPaid), ValueKind.number, note = feesNote(context, money))
                // A batched claim's fee is paid once: this exit shows its share, and the whole fee and how many it covered.
                context.claimShare(exit.id)?.let { share -> ExitValueRow(tr("Claim fee"), if (context.claimIds.size > 1) tr("{amount} · share of {total} for {n} exits",
                    money(share), money(checkNotNull(context.claimFee)), context.claimIds.size.toString()) else money(share), ValueKind.number) }
                val began = exit.historyDetails.firstOrNull()?.tip_height
                ExitValueRow(tr("Took"), if (began != null && stage.claimedBlock != null) (stage.claimedBlock - began).let { trPlural("{n} blocks · {time}", it, exitAbout(it)) } else tr("Unknown"), last = true)
            }
            ExitStage.Phase.canceled -> {
                ExitValueRow(tr("VTXO"), tr("{amount} · in Ark", money(exit.amount)))
                ExitValueRow(tr("Already on-chain"), tr("{n} of {total}", stage.inBlocks.toString(), stage.total.toString()), last = true)
            }
            ExitStage.Phase.spent, ExitStage.Phase.swept -> ExitValueRow(tr("State"), tr(ExitTimeline.labels[exit.state] ?: exit.state), last = true)
            else -> {
                ExitValueRow(tr("Transactions"), tr("{n} of {total} in blocks", stage.inBlocks.toString(), stage.total.toString()))
                val left = context.confirmed?.takeIf { stage.attention == ExitStage.Attention.needsFunds }
                ExitValueRow(tr("Network fees"), money(stage.feesPaid), ValueKind.number, failed = left != null,
                    note = left?.let { l -> exit.nextPackageFee?.let { tr("{left} left on-chain. The next fee needs about {need}.", money(l), money(it)) } } ?: feesNote(context, money))
                val claimable = stage.claimableHeight?.let { tr("Block {height}", number(it)) }
                    ?: context.delta?.let { tr("About block {height}", number(context.tip + (stage.total - stage.inBlocks) + it)) }
                    ?: tr("After the timelock")
                ExitValueRow(tr("Claimable"), claimable, last = stage.phase != ExitStage.Phase.wait)
                if (stage.phase == ExitStage.Phase.wait) ExitValueRow(tr("Left"), trPlural("{n} blocks", stage.left(context.tip) ?: 0), ValueKind.number, last = true)
            }
        }
    }
}

/** What part of the fees paid for transactions your other exits share, each paid once. */
@Composable
private fun feesNote(context: ExitContext, money: (Long) -> String) = if (context.sharedFees > 0)
    tr("Includes {shared} for transactions your other exits share. Each is paid once.", money(context.sharedFees)) else null

/** bark's transactions for the exit, root first: the current state's, or the last list it recorded (later states don't carry them). */
internal val ExitModel.path get() = details.transactions ?: historyDetails.lastOrNull { !it.transactions.isNullOrEmpty() }?.transactions.orEmpty()

/** The transaction to open in an explorer: the claim once there is one, else the furthest step that's been sent. */
internal fun ExitModel.explorerTxid(claimTxid: String?): String? =
    claimTxid ?: path.lastOrNull { it.status.block != null || it.status.kind == "awaiting-confirmation" }?.txid

/** Opens an explorer page, or says there's no browser. */
@Composable
private fun rememberExplore(): (String) -> Unit {
    val toasts = LocalSettingsToasts.current
    val host = LocalContext.current
    val noBrowser = tr("No browser available to open the explorer.")
    return { url ->
        try { host.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) { toasts.show(noBrowser) }
    }
}

/** Today's explorer button beside a Details row ([DetailRow]'s). */
@Composable
private fun ExplorerButton(url: String?, open: (String) -> Unit) {
    val palette = LocalPalette.current
    url ?: return
    val view = tr("View in explorer")
    Box(Modifier.offset(x = 4.dp).size(30.dp).clip(CircleShape).clickable(onClickLabel = view, role = androidx.compose.ui.semantics.Role.Button) { open(url) }
        .semantics { contentDescription = view }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.noahwallet.R.drawable.ic_activity_external), null, Modifier.size(17.dp), tint = palette.muted)
    }
}

/** Details: the VTXO, each transaction root first with its state (and the explorer), the exit, and bark's timeline. */
@Composable
private fun ExitDetailRows(exit: ExitModel, stage: ExitStage, context: ExitContext) {
    val toasts = LocalSettingsToasts.current
    val explore = rememberExplore()
    ExitValueRow(tr("VTXO ID"), short(exit.id, 10, 6), ValueKind.mono, small = true) { CopyIcon("VTXO ID", exit.id, toasts) }
    val txs = exit.path
    val ids = txs.map { it.txid }.ifEmpty { exit.packages.map { it.txid } }
    if (ids.isNotEmpty()) {
        SectionLabel(tr("Transactions, root first"))
        ids.forEachIndexed { i, txid ->
            val tx = txs.getOrNull(i)
            val name = when (i) { ids.lastIndex -> tr("Your VTXO"); 0 -> tr("Tree root"); else -> tr("Tree branch") }
            val state = when {
                tx?.status?.block != null -> tr("Block {height}", number(tx.status.block.height))
                i == stage.current && stage.attention == ExitStage.Attention.needsFunds -> tr("Needs on-chain funds")
                tx?.status?.kind == "awaiting-confirmation" -> if (tx.status.origin != null && tx.status.origin != "wallet") tr("Sent by someone else") else tr("In the mempool")
                tx?.status?.kind == "awaiting-cpfp-broadcast" -> tr("Ready to send")
                stage.phase in setOf(ExitStage.Phase.wait, ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed) -> tr("In a block")
                else -> tr("Not sent yet")
            }
            val label = (i + 1).toString() + " · " + name
            ExitValueRow(label, state, small = true, failed = i == stage.current && stage.attention == ExitStage.Attention.needsFunds) {
                CopyIcon(label, txid, toasts); ExplorerButton(OnchainDisplay.explorerURL(txid, BuildConfig.APP_VARIANT), explore)
            }
        }
    }
    SectionLabel(tr("Exit"))
    stage.rate?.let { ExitValueRow(tr("Fee rate"), tr("{rate} sat/vB, raised when rates rise", it.toString()), small = true) }
    context.delta?.let { ExitValueRow(tr("Timelock"), trPlural("{n} blocks", it), small = true) }
    stage.claimTxid?.let { ExitValueRow(tr("Claim"), short(it), ValueKind.mono, small = true) {
        CopyIcon("Claim", it, toasts); ExplorerButton(OnchainDisplay.explorerURL(it, BuildConfig.APP_VARIANT), explore)
    } }
    // bark's height for this state: it keeps it while nothing changes, so it says when the state was recorded, not when last checked.
    ExitValueRow(tr("Recorded at"), exit.details.tip_height?.let { tr("Block {height}", number(it)) } ?: tr("Unknown"), small = true, last = true)
    SectionLabel(tr("Timeline"))
    val items = exit.timeline
    items.forEachIndexed { i, item ->
        val heights = when {
            item.startHeight == null -> ""
            item.endHeight == null || item.endHeight == item.startHeight -> tr("Block {height}", number(item.startHeight))
            else -> tr("Blocks {from}–{to}", number(item.startHeight), number(item.endHeight))
        }
        ExitValueRow(tr(item.label), heights, small = true, last = i == items.lastIndex)
    }
}

/** "Move exit forward" (today's review): what it does, its estimated fees, and an explicit Move forward. */
@Composable
private fun CheckSheet(quote: OnchainQuote, busy: Boolean, money: (Long) -> String, dismiss: () -> Unit, check: () -> Unit) {
    var current by remember(quote) { mutableStateOf(quote.current()) }
    LaunchedEffect(quote) { while (current) { kotlinx.coroutines.delay(250); current = quote.current() } }
    SettingsSheet(dismiss, tr("Move your exit forward?"), tr("Sends the next step of your exit, or raises the fee on a step that’s stuck. Fees come from your on-chain bitcoin. Noah also does this on its own, including in the background when the system lets it."),
        modifier = Modifier.testTag("exit-check-sheet")) { close ->
        Column(Modifier.padding(top = 8.dp)) {
            ExitValueRow(tr("Exits"), quote.ids.size.toString(), ValueKind.number)
            quote.chainFee?.let { ExitValueRow(tr("Estimated fees"), "~" + money(it), ValueKind.number, last = true) }
        }
        if (!current) Warning(tr(OnchainFailure.changed.text), failed = true)
        SheetActions(tr("Cancel"), tr("Move forward"), onCancel = close, onConfirm = check, enabled = current && !busy, loading = busy, confirmTag = "exit-check-confirm", wrap = true)
    }
}

/** Cancel exit (today's review as a system dialog): what stays, what doesn't. bark checks again that it still can. */
@Composable
private fun CancelDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    val palette = LocalPalette.current
    WalletAlertDialog(onDismissRequest = dismiss, title = { Text(tr("Cancel exit?")) },
        text = { Text(tr("Transactions already on-chain stay there. Your VTXO stays in your wallet and can exit again.")) },
        dismissButton = { TextButton(dismiss) { Text(tr("Keep exiting")) } },
        confirmButton = { TextButton(confirm, Modifier.testTag("exit-cancel-confirm")) { Text(tr("Cancel exit"), color = palette.failure) } })
}
