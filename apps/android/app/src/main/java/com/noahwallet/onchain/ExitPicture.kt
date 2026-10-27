package com.noahwallet.onchain

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.OnboardingPose
import com.noahwallet.ui.send.StageArt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.first

/**
 * The exit picture (exit-v2-notes.md, The picture): your branch of the round's tree standing on a "Bitcoin" ground line,
 * the round's block as its base, ink blocks for mined transactions, a glowing square for the one waiting for the next
 * block, hollow circles for the rest, your coin at the top with the timelock dial, and the claim's stem to the
 * destination. It moves only when [scene] changes, with the house springs; under Reduce Motion it snaps. What waits for the
 * next block breathes for as long as the picture is on screen and resumed, in small overlays, so the tree's canvas stays
 * still between states. [balance] is the on-chain wallet's; [received] what a claim to this phone brought. [enter] false
 * draws it as it stands, without its entrance (Back to a page it already grew on).
 */
@Composable
internal fun ExitPicture(scene: ExitScene, description: String, modifier: Modifier = Modifier, money: (Long) -> String = { it.toString() },
                         balance: Long? = null, received: Long? = null, enter: Boolean = true) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val wide = LocalPose.current !is OnboardingPose.Compact
    val scale = min(density.fontScale, 1.25f)
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    var visible by remember { mutableStateOf(true) }
    val breath = rememberBreath(resumed && visible && !reduceMotion && scene.pulses)
    val measurer = rememberTextMeasurer(128)
    val styles = remember(density) { TreeStyles(density) }
    val measure = remember(measurer, styles) { TreeMeasure { text, size, bold -> styles.width(measurer, text, size, bold) } }
    val words = TreeStrings.of(androidx.compose.ui.platform.LocalResources.current, com.noahwallet.BuildConfig.APPLICATION_ID, money)
    BoxWithConstraints(modifier.onGloballyPositioned { visible = it.boundsInWindow().let { b -> b.width > 0f && b.height > 0f } }
        .clearAndSetSemantics { contentDescription = description }) {
        val w = maxWidth.value; val h = maxHeight.value
        // Too small to hold the tree and its labels (a pane mid-resize): draw nothing rather than overlap.
        if (w < 200f || h < 160f) return@BoxWithConstraints
        // A long path folds to the levels that keep their spacing here; the explainer draws 8 at most.
        val capacity = remember(w, h, scale, wide, scene.locked) { TreeGeometry(w, h, 1, scale, wide).capacity(if (scene.locked) 1f else 0f) }
        val drawn = scene.fold(if (scene.explain) min(capacity, 8) else capacity)
        // Each move plays to its end; a state that arrives meanwhile moves next, from where this one ends, so nothing jumps.
        var move by remember { mutableStateOf(TreeMotion(if (reduceMotion || !enter) drawn else null, drawn)) }
        val clock = remember { Animatable(0f) }
        val latest by rememberUpdatedState(drawn)
        LaunchedEffect(reduceMotion) {
            while (true) {
                if (clock.value < move.seconds) clock.animateTo(move.seconds, tween(((move.seconds - clock.value) * 1000).roundToInt(), easing = LinearEasing))
                val next = snapshotFlow { latest }.first { it != move.to }
                move = TreeMotion(if (reduceMotion) next else move.to, next)
                clock.snapTo(0f)
            }
        }
        val shown = move.to
        val layout = TreeLayout.of(shown, w, h, scale, wide, words, measure, balance, received)
        val before = move.from?.takeIf { it != shown && !move.labels.isNaN() }?.let { TreeLayout.of(it, w, h, scale, wide, words, measure, balance, received) }
        val stay = before?.labels.orEmpty().filter { old -> layout.labels.any { it.key == old.key && kotlin.math.abs(it.top - old.top) < .5f } }.map { it.key }.toSet()
        val frame = { TreeFrame(layout.geo, move, clock.value, before?.ys) }
        Glows(frame, breath, palette, shown)
        Canvas(Modifier.fillMaxSize()) {
            val k = density.density
            scale(k, k, Offset.Zero) {
                drawTree(frame(), palette, measurer, styles, layout, before, stay)
            }
        }
        Pulses(frame, breath, palette, shown)
        for (item in layout.labels) if (item.pill != null) Pill(item, layout, measurer, styles, measure, frame, breath, palette, if (item.key in stay) 1f else null, move)
    }
}

/** The pictures' one breathing rhythm (1.6 s) and the dashes' march; a steady glow when it's still. */
internal class Breath(private val phase: () -> Float, private val dash: () -> Float, val live: Boolean) {
    val breathe get() = if (live) .5f + .5f * sin(2 * PI * phase()).toFloat() else .6f
    /** A ring's growth, 0 → 1, [offset] of a cycle behind the first. */
    fun ring(offset: Float) = (phase() + offset) % 1f
    val march get() = if (live) dash() else 0f
}

/** Runs only while [live]: on screen, resumed and with animations on. */
@Composable
internal fun rememberBreath(live: Boolean): Breath {
    if (!live) return remember { Breath({ 0f }, { 0f }, false) }
    val clock = rememberInfiniteTransition(label = "breath")
    val phase = clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "breath")
    val dash = clock.animateFloat(0f, 5.4f, infiniteRepeatable(tween(600, easing = LinearEasing)), label = "march")
    return remember(phase, dash) { Breath({ phase.value }, { dash.value }, true) }
}

/** Something in the picture waits for the next block, or the lock just opened. */
private val ExitScene.pulses get() = !explain && ((waiting >= 0 && !stale) || ExitScene.Mark.needsFee in marks || (open && !claimed) ||
    (locked && !open && dial < 1f))

/** Text in picture units: one unit is one dp, whatever the font scale (the layout scales labels itself, up to 1.25). */
internal class TreeStyles(density: androidx.compose.ui.unit.Density) {
    private val unit = 1 / (density.density * density.fontScale)
    private val widths = HashMap<String, Float>()
    fun style(size: Float, bold: Boolean) = TextStyle(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, fontSize = (size * unit).sp)
    /** Measured once: a moving picture lays its labels out on every change. */
    fun width(measurer: TextMeasurer, text: String, size: Float, bold: Boolean) =
        widths.getOrPut("$bold|$size|$text") { measurer.measure(text, style(size, bold)).size.width.toFloat() }
}

/** Every position and value at one moment of a move (prototype `drawHero`'s locals); [before] is the levels' rest before it. */
private class TreeFrame(val geo: TreeGeometry, val move: TreeMotion, val t: Float, val before: FloatArray?) {
    val scene get() = move.to
    val n get() = move.n
    val ys = move.ys(geo, before, t)
    val cancel = move.cancel(t)
    val dim = move.dim(t)
    val tree = 1 - .6f * dim
    val land = move.land(t)
    val coin: Offset = if (land > 0f) geo.stem(land).let { Offset(it.first, it.second) } else Offset(geo.trunk, geo.coinY - 5 * move.lift(t))
    val coinR = if (land > 0f) geo.coinR + (geo.coinR * .62f - geo.coinR) * land else geo.coinR
    fun node(j: Int) = Offset(geo.trunk, ys[j])
    val claim: Offset get() = geo.claimNode.let { Offset(it.first, it.second) }
    /** The transaction (or claim) the overlays pulse, and its morph into the mempool. */
    val pulse = scene.waiting.takeIf { it >= 0 && !scene.stale }
    fun send(i: Int) = move.send(i, t)
    /** It was ready (an outlined square) before this move: it's sent from that square, not from a hollow circle. */
    fun wasReady(i: Int) = move.wasMark(i).let { it == ExitScene.Mark.ready || it == ExitScene.Mark.needsFee }
}

private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u
private fun clamp(x: Float, a: Float = 0f, b: Float = 1f) = x.coerceIn(a, b)

/** The prototype's pictures' icons, in 24-unit paths. Paths start with a no-op "m0 0" so the copy extractor skips them. */
private object TreeIcons {
    private fun parse(d: String) = PathParser().parsePathString(d).toPath()
    val phone = parse("m0 0M8.5 2.8h7a2.2 2.2 0 0 1 2.2 2.2v14a2.2 2.2 0 0 1-2.2 2.2h-7A2.2 2.2 0 0 1 6.3 19V5a2.2 2.2 0 0 1 2.2-2.2zM10.6 18.3h2.8")
    val wallet = parse("m0 0M4.5 7.2A2.2 2.2 0 0 1 6.7 5h9.8v2.6M4.5 7.2v10.6a1.9 1.9 0 0 0 1.9 1.9h12.4a.9.9 0 0 0 .9-.9v-9.5a.9.9 0 0 0-.9-.9H6.7a2.2 2.2 0 0 1-2.2-2.2zM15.6 13.6h.01")
    val check = parse("m0 0M5 12.5l4.5 4.5L19 7.5")
}

/** A branch link: onboarding's S-curve, handles at the middle; drawn from [u0] to [u1] of its length (prototype `link`). */
internal fun DrawScope.treeLink(p: Offset, c: Offset, u0: Float, u1: Float, color: Color, width: Float, alpha: Float, dash: FloatArray? = null,
                                horizontal: Boolean = false) {
    if (u1 <= u0 || alpha <= 0f) return
    fun at(u: Float) = linkAt(p.x, p.y, c.x, c.y, u, horizontal).let { (x, y) -> Offset(x, y) }
    val path = Path().apply { for (i in 0..20) { val q = at(u0 + (u1 - u0) * i / 20); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) } }
    drawPath(path, color, alpha = clamp(alpha), style = Stroke(width, cap = StrokeCap.Round, pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))
}

/** A radial glow in Bitcoin orange: no blur, which is costly on Android (prototype `glow`). */
internal fun DrawScope.treeGlow(at: Offset, r: Float, alpha: Float, orange: Color) {
    if (alpha <= 0f || r <= 0f) return
    drawCircle(Brush.radialGradient(0f to orange.copy(alpha = .42f * clamp(alpha)), .55f to orange.copy(alpha = .16f * clamp(alpha)),
        1f to orange.copy(alpha = 0f), center = at, radius = r), r, at)
}

/** Your coin (prototype `coin`): a soft fill in an orange ring, ink once on-chain. */
internal fun DrawScope.treeCoin(at: Offset, r: Float, fill: Color, ring: Color, alpha: Float = 1f) {
    if (r <= 0f || alpha <= 0f) return
    drawCircle(fill, r, at, alpha = clamp(alpha))
    drawCircle(ring, r, at, alpha = clamp(alpha), style = Stroke(clamp(r * .19f, 1.6f, 4f)))
}

/** The 24-unit padlock drawn [size] units wide (prototype `padlock`); [open] lifts and turns the shackle. */
internal fun DrawScope.treePadlock(at: Offset, size: Float, open: Float, alpha: Float, ink: Color, fill: Color) {
    if (size <= 0f || alpha <= 0f) return
    scale(size / 24f, size / 24f, at) { StageArt.run { padlock(at, alpha, open, ink, fill) } }
}

/** A circled check (prototype `checkBadge`). */
internal fun DrawScope.treeCheck(at: Offset, scale: Float, ink: Color, paper: Color) {
    if (scale <= 0f) return
    scale(scale, scale, at) {
        drawCircle(ink, 9f, at)
        StageArt.run { icon(TreeIcons.check, at, 13f, paper, width = 2.6f) }
    }
}

/** Another wallet, as the claim's destination: Review's path ends at it. */
internal fun DrawScope.exitWalletBadge(at: Offset, palette: NoahTokens.Palette) =
    treeBadge(at, 14f, TreeIcons.wallet, palette.foreground, palette.hairline, palette.background, 1f)

/** A circle badge with an icon: the server, the phone or the other wallet (prototype `badge`). */
private fun DrawScope.treeBadge(at: Offset, r: Float, icon: Path, ink: Color, ring: Color, paper: Color, alpha: Float, dashed: Boolean = false, dots: Boolean = false) {
    if (alpha <= 0f) return
    val a = clamp(alpha)
    drawCircle(paper, r, at, alpha = a)
    drawCircle(ring, r, at, alpha = a, style = Stroke(1.5f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3f, 3f)) else null))
    StageArt.run {
        icon(icon, at, r * 1.2f, ink, alpha = a)
        if (dots) icon(StageArt.serverDots, at, r * 1.2f, ink, width = 2.8f, alpha = a)
    }
}

private fun DrawScope.text(layout: TextLayoutResult, x: Float, y: Float, color: Color, alpha: Float, align: Int = -1) {
    if (alpha <= 0.01f) return
    val left = when (align) { -1 -> x; 1 -> x - layout.size.width; else -> x - layout.size.width / 2f }
    drawText(layout, color = color, alpha = clamp(alpha), topLeft = Offset(left, y - layout.size.height / 2f))
}

/**
 * A transaction on your branch (prototype `txNode`). Hollow circle: not sent. Dashed square: waiting for the next block (its
 * glow, rings and marching outline are the overlays' while it pulses). Solid outlined square: ready to send, not sent yet
 * (ringed red when it's short of funds).
 * Ink square: in a block, filled from the bottom as it's mined, settling from 1.22×, with one ripple.
 */
private fun DrawScope.txNode(at: Offset, z: Float, mark: ExitScene.Mark, send: Float, mine: Float, stamp: Float, ripple: Float, alpha: Float,
                             pop: Float, grey: Boolean, outline: Boolean, palette: NoahTokens.Palette, ready: Boolean = false) {
    if (alpha <= 0f) return
    val a = clamp(alpha)
    if (mine > 0f) {
        val h = z * (1 + .22f * (1 - stamp))
        val box = Offset(at.x - h / 2, at.y - h / 2)
        if (mine < 1f) drawRoundRect(palette.background, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(3.5f), alpha = a)
        val shape = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(box.x, box.y, box.x + h, box.y + h, CornerRadius(3.5f))) }
        clipPath(shape) { val fill = h * clamp(mine * 1.6f); drawRect(palette.foreground, Offset(box.x, box.y + h - fill), Size(h, fill), alpha = a) }
        if (mine < 1f) drawRoundRect(palette.foreground, box, Size(h, h), CornerRadius(3.5f), alpha = a, style = Stroke(1.6f))
        if (ripple > 0f && ripple < 1f) drawRoundRect(palette.foreground, Offset(box.x - 10 * ripple, box.y - 10 * ripple), Size(h + 20 * ripple, h + 20 * ripple),
            CornerRadius(4 + 6 * ripple), alpha = a * .4f * (1 - ripple), style = Stroke(1.2f))
    } else if (send > 0f || (ready && mark == ExitScene.Mark.waiting)) {
        // From a hollow circle; or, sent once it was ready, from its outlined square, whose solid outline fades into the dashes.
        if (send < 1f && !ready) drawCircle(palette.orange, 5.5f * (1 - send), at, alpha = a, style = Stroke(1.6f))
        val h = if (ready) z else z * (.6f + .4f * send)
        drawRoundRect(palette.background, Offset(at.x - h / 2, at.y - h / 2), Size(h, h), CornerRadius(3.5f), alpha = a)
        if (ready && send < 1f) drawRoundRect(palette.orange, Offset(at.x - h / 2, at.y - h / 2), Size(h, h), CornerRadius(3.5f), alpha = a * (1 - send), style = Stroke(1.6f))
        if (outline) drawRoundRect(if (grey) palette.muted else palette.orange, Offset(at.x - h / 2, at.y - h / 2), Size(h, h), CornerRadius(3.5f),
            alpha = a * send * if (grey) .8f else .88f, style = Stroke(1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.4f))))
    } else if (mark == ExitScene.Mark.needsFee || mark == ExitScene.Mark.ready) {
        drawRoundRect(palette.background, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(3.5f), alpha = a)
        drawRoundRect(palette.orange, Offset(at.x - z / 2, at.y - z / 2), Size(z, z), CornerRadius(3.5f), alpha = a, style = Stroke(1.6f))
    } else {
        val r = (if (z >= 13) 6.2f else z * .42f) * pop
        drawCircle(palette.background, r, at, alpha = a)
        drawCircle(palette.orange, r, at, alpha = a, style = Stroke(1.6f))
    }
}

private fun DrawScope.drawTree(f: TreeFrame, palette: NoahTokens.Palette, measurer: TextMeasurer, styles: TreeStyles, layout: TreeLayout,
                               before: TreeLayout?, stay: Set<String>) {
    val geo = f.geo; val m = f.move; val s = m.to; val t = f.t; val n = s.n; val k = geo.scale
    val tx = geo.trunk; val gY = geo.ground; val rS = geo.block; val yC = geo.coinY; val rC = geo.coinR
    val wx = geo.walletX; val wy = geo.walletY; val w = geo.width
    fun measure(value: String, size: Float, bold: Boolean = false) = measurer.measure(value, styles.style(size, bold))
    val canU = f.cancel; val treeA = f.tree
    val labA = if (s.explain) m.labels(t) else 1 - f.dim
    fun tone(tone: TreeLabels.Tone) = when (tone) { TreeLabels.Tone.strong -> palette.foreground; TreeLabels.Tone.failure -> palette.failure; else -> palette.muted }

    // Ark: a faint line at the top with the server. Your VTXO sits on it until the exit starts; a cancel brings it back.
    val arkA = m.ark(t)
    if (arkA > .01f) {
        drawLine(palette.hairline, Offset(14f, yC), Offset(w - 50, yC), 1f, alpha = arkA)
        text(measure(layout.words.tr("Ark"), 11f * k), 14f, yC - 10, palette.muted, arkA)
        treeBadge(Offset(w - 30, yC), 14f, StageArt.server, palette.foreground, palette.hairline, palette.background,
            if (s.explain) 1f else arkA, dashed = s.lifting, dots = true)
    }
    // The chain: the ground line, with the round's transaction in its block as the base.
    drawLine(palette.foreground, Offset(12f, gY + .5f), Offset(w - 12, gY + .5f), 1.2f, alpha = .32f)
    text(measure(layout.words.tr("Bitcoin"), 10.5f * k), 14f, gY + 12 * k, palette.muted, 1f)

    // Everyone else's part of the round: short faint stubs, and one caption, where they're clear of everything else.
    val slide = m.slide(t)
    fun stub(x: Float, dy: Float, y: Float, a: Float) {
        if (a <= .02f) return
        val tip = Offset(x, y + dy)
        treeLink(Offset(tip.x, tip.y + 3), Offset(tx, y - 8), 0f, 1f, palette.foreground, 1f, .22f * a)
        drawCircle(palette.background, 2.6f, tip, alpha = .34f * a)
        drawCircle(palette.foreground, 2.6f, tip, alpha = .34f * a, style = Stroke(1f))
    }
    for (s0 in layout.stubs) stub(s0.x, s0.dy, f.ys[s0.level], treeA * m.pop(s0.level, t) * (1 - .6f * canU) * if (m.emerges(s0.level - 1)) slide else 1f)
    // A level folding into the stack or "+N" takes its stubs with it as they fade.
    if (before != null && slide < 1f) for ((level, into) in m.merging) for (s0 in before.stubs) if (s0.level == level + 1) {
        stub(s0.x, s0.dy, lerp(before.ys[level + 1], f.ys[into + 1], slide), treeA * (1 - slide) * (1 - .6f * canU))
    }
    layout.caption?.let { lines ->
        val a = treeA * m.pop(1, t) * (1 - .6f * canU) * labA
        val y0 = f.ys[1] + layout.captionY; val right = tx - rS / 2 - 9
        lines.forEachIndexed { i, l -> text(measure(l, 10f * k), right, y0 + i * 12.5f * k, palette.muted, a * .9f, 1) }
    }
    // Your other exits from this round: their branches leave yours where they stop sharing. Each slides from where it was,
    // matched by the exit; one that's new fades in, one left out fades where it was.
    fun same(a: TreeLayout.Sibling, b: TreeLayout.Sibling, was: ExitScene) =
        a.other == b.other && was.others.getOrNull(a.other)?.amount == s.others.getOrNull(b.other)?.amount
    fun sibling(sibling: TreeLayout.Sibling, at: Offset, join: Float, a: Float, amount: Long, words: TreeStrings) {
        if (a <= .01f) return
        treeLink(Offset(at.x, at.y + 6), Offset(tx, join - 4), 0f, 1f, palette.orange, 1.4f, .7f * a, if (sibling.slot > 0) floatArrayOf(3f, 3f) else null)
        treeCoin(at, 6f, palette.soft, palette.orange, a)
        sibling.amount?.let { size ->
            // On a background halo, so the stubs behind it don't run through the amount.
            val text = measure(words.money(amount), size)
            drawRoundRect(palette.background, Offset(at.x - 12 - text.size.width, at.y - text.size.height / 2f), Size(text.size.width + 4f, text.size.height.toFloat()),
                CornerRadius(3f), alpha = a * labA)
            text(text, at.x - 10, at.y, palette.muted, a * labA, 1)
        }
    }
    for (sib in layout.siblings) {
        val old = before?.siblings?.firstOrNull { same(it, sib, before.scene) }
        val (x, y) = m.sibling(sib, old, f.ys, before?.ys, t)
        val fade = if (before != null && old == null) slide else 1f
        sibling(sib, Offset(x, y), f.ys[sib.level], treeA * m.pop(sib.level, t) * fade, s.others[sib.other].amount, layout.words)
    }
    if (before != null && slide < 1f) for (old in before.siblings) if (layout.siblings.none { same(old, it, before.scene) }) {
        sibling(old, Offset(old.x, before.ys[old.level] + old.dy), before.ys[old.level], treeA * (1 - slide), before.scene.others[old.other].amount, before.words)
    }
    // Before it's sent (Start), a ghost of the branch: what the exit will send.
    val ghost = m.ghost(t)
    if (ghost > 0f) {
        treeLink(Offset(tx, gY - rS), Offset(tx, yC + rC), 0f, 1f, palette.muted, 1.2f, ghost, floatArrayOf(2f, 4f))
        for (j in 1 until n) { drawCircle(palette.background, 4f, f.node(j), alpha = ghost); drawCircle(palette.muted, 4f, f.node(j), alpha = ghost, style = Stroke(1.2f)) }
    }
    // Your branch: ink below a transaction once it's in a block, orange while it's on its way.
    for (j in 1..n) {
        val u = m.grow(j, t)
        if (u <= 0f) continue
        val mU = m.mine(j - 1, t)
        val p0 = Offset(tx, if (j == 1) gY - rS else f.ys[j - 1] - geo.node / 2)
        val p1 = Offset(tx, f.ys[j] + if (j == n) rC else geo.node / 2)
        if (p0.y - p1.y < 1) continue
        val width = lerp(4.6f, 2.2f, (j - 1f) / (n - 1).coerceAtLeast(1))
        val a = treeA * if (mU < 1f) 1 - .6f * canU else 1f
        if (mU < 1f) treeLink(p0, p1, mU, u, palette.orange, width, .95f * a)
        if (mU > 0f) treeLink(p0, p1, 0f, min(u, mU), palette.foreground, width + .3f, a)
    }
    // The round's block on the ground, already on-chain.
    drawRoundRect(palette.foreground, Offset(tx - rS / 2, gY - rS), Size(rS, rS), CornerRadius(4.5f), alpha = treeA)
    // The transactions. A long path's folded levels: its oldest blocks as one stack, the rest to send as "+N".
    fun more(at: Offset, z: Float, label: String, alpha: Float) {
        val text = measure(label, TreeLayout.moreSize, true); val half = max(z / 2, (text.size.width + 6) / 2f)
        drawRoundRect(palette.background, Offset(at.x - half, at.y - z / 2), Size(2 * half, z), CornerRadius(z / 2), alpha = alpha)
        drawRoundRect(palette.orange, Offset(at.x - half, at.y - z / 2), Size(2 * half, z), CornerRadius(z / 2), alpha = alpha, style = Stroke(1.4f))
        text(text, at.x, at.y, palette.orange, alpha, 0)
    }
    fun plates(at: Offset, z: Float, alpha: Float) {
        for (plate in 1..2) drawLine(palette.background, Offset(at.x - z / 2 + 1.5f, at.y - z / 2 + z * plate / 3f), Offset(at.x + z / 2 - 1.5f, at.y - z / 2 + z * plate / 3f), 1.2f, alpha = alpha)
    }
    // A level folding into the stack or "+N" slides into it as it fades.
    val was = m.from
    if (before != null && was != null && slide < 1f) for ((level, into) in m.merging) {
        val at = Offset(tx, lerp(before.ys[level + 1], f.ys[into + 1], slide)); val z = geo.node; val a = treeA * (1 - slide)
        val mark = was.marks[level]; val mined = if (mark == ExitScene.Mark.mined) 1f else 0f
        before.more[level + 1]?.let { more(at, z, it, a) } ?: txNode(at, z, mark, if (mark == ExitScene.Mark.waiting) 1f else 0f, mined, 1f, 0f, a, 1f, s.stale, true, palette)
        if (was.folded(level) && mined >= 1f) plates(at, z, a)
    }
    for (j in 1 until n) {
        val i = j - 1
        val pop = clamp(m.pop(j, t), 0f, 1.3f)
        if (pop <= 0f) continue
        val mU = m.mine(i, t); val z = geo.node; val at = f.node(j)
        // One coming out of "+N" (or a stack forming) grows in as it slides.
        val alpha = treeA * (if (mU < 1f) 1 - .65f * canU else 1f) * if (m.emerges(i)) slide else 1f
        layout.more[j]?.let { label -> more(at, z, label, alpha); continue }
        txNode(at, z, if (f.cancel >= 1f) ExitScene.Mark.unsent else s.marks[i], f.send(i), mU, m.stamp(i, t), m.ripple(i, t),
            alpha, pop * if (m.emerges(i)) .5f + .5f * slide else 1f, s.stale, outline = f.pulse != i, palette, ready = f.wasReady(i))
        if (s.folded(i) && mU >= 1f) plates(at, z, alpha)
    }
    // Fee children: a dot from your on-chain wallet, docked on the waiting transaction's anchor (hollow when someone else
    // paid), riding with it into its block.
    val wA = m.wallet(t)
    for (pose in listOfNotNull(s, m.from).distinct()) pose.waiting.takeIf { it >= 0 && pose.paid[it] && !pose.explain }?.let { at ->
        // The level this transaction is drawn at now.
        val i = if (pose === s) at else (0 until n).firstOrNull { m.was(it) == at } ?: return@let
        val j = i + 1
        val anchor = if (j == n) Offset(f.coin.x + rC * .8f, f.coin.y + rC * .62f) else Offset(tx + 9, f.ys[j] + 7)
        val fa = treeA * (1 - m.mine(i, t)) * if (pose === s) 1f else if (s.waiting == i) 0f else 1 - f.cancel
        fun fly(u: Float, r: Float, alpha: Float) {
            if (u <= 0f || alpha <= 0f || fa <= 0f) return
            val q = Offset(lerp(wx - 8, anchor.x, u), lerp(wy - 10, anchor.y, u) - sin(PI * clamp(u)).toFloat() * 34)
            if (u > .96f) drawLine(palette.foreground, Offset(anchor.x - 5, anchor.y - 4), anchor, 1f, alpha = .5f * alpha * fa)
            if (pose.theirs[at]) { drawCircle(palette.background, r, q, alpha = alpha * fa); drawCircle(palette.muted, r, q, alpha = alpha * fa, style = Stroke(1.4f)) }
            else drawCircle(palette.foreground, r, q, alpha = alpha * fa)
        }
        val bump = if (pose === s) m.bump(t) else if (pose.attention == ExitStage.Attention.feesRose) 1f else 0f
        val docked = if (pose === s) m.fee(t) else 1f
        if (pose.theirs[at]) fly(1f, 3.4f, docked)
        else if (bump > 0f) { fly(1f, 3.4f, 1 - bump); fly(bump, 4.8f, 1f) } else fly(docked, 3.4f, 1f)
    }
    // The claim: a stem from your coin to where it goes, with the claim transaction on it.
    val stem = m.stem(t)
    if (stem > 0f) {
        val path = Path(); val ink = Path(); val cm = m.claimMine(t)
        for (i in 0..30) { val q = geo.stem(stem * i / 30); if (i == 0) path.moveTo(q.first, q.second) else path.lineTo(q.first, q.second) }
        drawPath(path, palette.orange, style = Stroke(2f, cap = StrokeCap.Round))
        if (cm > 0f) {
            for (i in 0..30) { val q = geo.stem(cm * i / 30); if (i == 0) ink.moveTo(q.first, q.second) else ink.lineTo(q.first, q.second) }
            drawPath(ink, palette.foreground, style = Stroke(2.3f, cap = StrokeCap.Round))
        }
        val cs = m.claimSend(t)
        if (cs > 0f) txNode(f.claim, 13f, ExitScene.Mark.waiting, cs, cm, m.claimStamp(t), m.claimRipple(t), 1f, 1f, false,
            outline = !(s.claiming && !s.claimed), palette)
    }
    // Your VTXO: the last transaction's output, at the top of the branch.
    val last = n - 1
    val coinMined = m.mine(last, t)
    val land = f.land
    if (land < .98f) treeCoin(f.coin, f.coinR * (1 + .18f * (1 - m.stamp(last, t)) * if (coinMined > 0f) 1f else 0f),
        if (coinMined > .5f) palette.foreground else palette.soft, palette.orange, 1 - clamp((land - .85f) / .13f))
    if (f.send(last) > 0f && coinMined < 1f && f.pulse != last && !s.canceled) drawCircle(if (s.stale) palette.muted else palette.orange, f.coinR + 5, f.coin,
        alpha = f.send(last) * .6f, style = Stroke(1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.6f))))
    if (land > .5f) drawCircle(palette.muted, rC * .8f, Offset(tx, yC), alpha = .45f * clamp((land - .5f) * 2) * treeA,
        style = Stroke(1.3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
    val ripple = m.ripple(last, t)
    if (coinMined > 0f && ripple > 0f && ripple < 1f) drawCircle(palette.foreground, f.coinR + 4 + 12 * ripple, f.coin, alpha = .4f * (1 - ripple), style = Stroke(1.2f))
    // The timelock: a dial around your coin, a tick per 6 blocks, and a padlock.
    val lock = m.lock(t)
    if (lock > 0f && stem < 1f) {
        val r = rC + 10; val a = lock * (1 - stem); val open = clamp(m.open(t))
        val delta = ((s.claimableHeight ?: 0) - (s.lockedFrom ?: 0)).coerceAtLeast(1)
        val ticks = ExitDial.ticks(delta); val pk = (m.dial(t) * delta).roundToInt() / delta.toFloat()
        drawCircle(palette.hairline, r, Offset(tx, yC), alpha = a, style = Stroke(2.2f))
        for (h in 0 until ticks) {
            val an = -PI / 2 + h * 2 * PI / ticks; val r1 = r + if (ExitDial.major(delta, h)) 8f else 5.5f
            drawLine(palette.muted, Offset(tx + cos(an).toFloat() * (r + 3.5f), yC + sin(an).toFloat() * (r + 3.5f)),
                Offset(tx + cos(an).toFloat() * r1, yC + sin(an).toFloat() * r1), 1f, StrokeCap.Round, alpha = a * if (h / ticks.toFloat() < pk) .75f else .35f)
        }
        if (pk > 0f) drawArc(if (open > 0f) palette.orange else palette.foreground, -90f, 360f * min(1f, pk) * lock, false, Offset(tx - r, yC - r), Size(2 * r, 2 * r),
            alpha = a, style = Stroke(2.6f, cap = StrokeCap.Round))
        treePadlock(Offset(tx + rC * .78f, yC + rC * .72f), 15f * m.padlock(t), open, 1 - stem, palette.foreground, palette.background)
    }
    // Your on-chain wallet: every fee comes from it. At the claim it's the destination: this phone, or another wallet.
    val dest = s.claiming
    if (wA > 0f || dest) {
        val arrive = m.arrive(t); val grow = if (arrive > 0f) 1 + .12f * arrive else 1f
        val nf = ExitScene.Mark.needsFee in s.marks; val swap = m.swap(t); val aW = if (dest) 1f else wA
        scale(grow, grow, Offset(wx, wy)) {
            if (swap < 1f) treeBadge(Offset(wx, wy), 15f, TreeIcons.phone, palette.foreground, if (nf) palette.failure else palette.hairline, palette.background, aW * (1 - swap))
            if (swap > 0f) treeBadge(Offset(wx, wy), 15f, TreeIcons.wallet, palette.foreground, palette.hairline, palette.background, swap)
        }
        val ring = m.ring(t)
        if (ring > 0f) drawArc(palette.orange, -90f, 360f * ring, false, Offset(wx - 20 * grow, wy - 20 * grow), Size(40 * grow, 40 * grow), style = Stroke(2.2f))
        if (arrive > 0f) treeCheck(Offset(wx + 15, wy - 15), clamp(arrive, 0f, 1.2f), palette.foreground, palette.background)
        if (nf) {
            drawCircle(palette.failure, 6.5f, Offset(wx + 12, wy - 12))
            text(measure("!", 10.5f * k, true), wx + 12, wy - 12, Color.White, 1f, 0)
            val j = s.marks.indexOf(ExitScene.Mark.needsFee) + 1
            val curve = Path().apply { moveTo(wx - 17, wy - 4); quadraticTo(wx - 30, f.ys[j], tx + 16, f.ys[j]) }
            drawPath(curve, palette.failure, alpha = .45f, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f))))
        }
        for (line in layout.wallet) text(measure(line.text, line.size, line.bold), w - 12, line.y, tone(line.tone), aW, 1)
    }
    // The label column.
    val fade = m.labels(t)
    fun drawLabels(list: List<TreeLabels.Item>, alpha: (TreeLabels.Item) -> Float, pills: Boolean) {
        for (item in list) {
            val a = alpha(item) * if (item.priority >= 4f) 1f else labA
            if (a <= .01f) continue
            val x = item.x ?: geo.labels; val align = if (item.x != null) 1 else -1
            // The current pose's pill and its first line(s) are the overlay's ([Pill]), which breathes.
            if (item.pill != null && pills) TreeLayout.pill(item, geo) { text, size, bold -> styles.width(measurer, text, size, bold) }.let { (l, top, r, b) ->
                val radius = CornerRadius((geo.lineHeight + 5) / 2)
                drawRoundRect(palette.background, Offset(l, top), Size(r - l, b - top), radius, alpha = a)
                drawRoundRect(pillColor(item.pill, palette), Offset(l, top), Size(r - l, b - top), radius, alpha = a * .9f, style = Stroke(1.4f))
            }
            item.lines.forEachIndexed { i, line ->
                if (item.pill != null && i < item.head && !pills) return@forEachIndexed
                text(measure(line.text, TreeLayout.lineSize(item, i, geo), line.bold), x, TreeLayout.lineY(item, i, geo), tone(line.tone), a, align)
            }
            item.brace?.let { (y0, y1) ->
                val brace = Path().apply { moveTo(tx + 27, y0); lineTo(tx + 32, y0); lineTo(tx + 32, y1); lineTo(tx + 27, y1) }
                drawPath(brace, palette.foreground, alpha = a * .5f, style = Stroke(1f))
            }
        }
    }
    before?.let { old -> drawLabels(old.labels.filter { it.key !in stay }, { 1 - fade }, true) }
    drawLabels(layout.labels, { if (it.key in stay || before == null) 1f else fade }, false)
}

private fun pillColor(pill: TreeLabels.Pill, palette: NoahTokens.Palette) = when (pill) {
    TreeLabels.Pill.orange -> palette.orange; TreeLabels.Pill.grey -> palette.muted; TreeLabels.Pill.failure -> palette.failure
}

/** A small box centred on [center] (dp), drawn by [draw] in dp: the overlays and glows, so only they redraw. */
@Composable
private fun Spot(side: Float, center: () -> Offset?, draw: DrawScope.(Float) -> Unit) {
    Box(Modifier.offset {
        val c = center() ?: Offset(-10_000f, -10_000f)
        IntOffset(((c.x - side / 2) * density).roundToInt(), ((c.y - side / 2) * density).roundToInt())
    }.size(side.dp).drawBehind {
        scale(density, density, Offset.Zero) { draw(side / 2) }
    })
}

/**
 * Under the tree, for whatever waits for the next block: its glow, breathing at 1.6 s, and two rings rippling out 0.8 s apart
 * (rounded squares for transactions, circles and the dashed ring for your coin). Once the lock opens, your coin's glow.
 * The canvas draws the nodes over them, so a ring starts at its node's edge.
 */
@Composable
private fun Glows(frame: () -> TreeFrame, breath: Breath, palette: NoahTokens.Palette, scene: ExitScene) {
    val claimWaits = scene.claiming && !scene.claimed
    val pulse = scene.waiting.takeIf { it >= 0 && !scene.stale }
    if (claimWaits || (pulse != null && pulse < scene.n - 1)) Spot(64f, { frame().let { f -> if (claimWaits) f.claim else f.node(checkNotNull(pulse) + 1) } }) { half ->
        val f = frame(); val c = Offset(half, half)
        val z = if (claimWaits) 13f else f.geo.node
        val u = if (claimWaits) f.move.claimSend(f.t) else f.send(checkNotNull(pulse))
        treeGlow(c, z * 1.6f, (.3f + .7f * breath.breathe) * u, palette.orange)
        if (u >= 1f && breath.live) for (offset in listOf(0f, .5f)) {
            val q = breath.ring(offset); val e = 1 - (1 - q) * (1 - q); val h = z * (1 + 2.3f * e)
            drawRoundRect(palette.orange, Offset(c.x - h / 2, c.y - h / 2), Size(h, h), CornerRadius(3.5f + 5 * e), alpha = .8f * (1 - q).pow(1.3f),
                style = Stroke(.8f + 1.6f * (1 - q)))
        }
    }
    if (pulse == scene.n - 1) Spot(170f, { frame().coin }) { half ->
        val f = frame(); val c = Offset(half, half); val u = f.send(scene.n - 1)
        treeGlow(c, f.coinR * 2.1f, (.45f + .55f * breath.breathe) * u, palette.orange)
        val z = (f.coinR + 5) * 2
        if (u >= 1f && breath.live) for (offset in listOf(0f, .5f)) {
            val q = breath.ring(offset); val e = 1 - (1 - q) * (1 - q)
            drawCircle(palette.orange, z * (1 + 2.3f * e) / 2, c, alpha = .8f * (1 - q).pow(1.3f), style = Stroke(.8f + 1.6f * (1 - q)))
        }
        drawCircle(palette.orange, f.coinR + 5, c, alpha = u * (.6f + .4f * breath.breathe),
            style = Stroke(1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.6f), -breath.march * 5.6f / 5.4f)))
    }
    if (scene.open && !scene.claiming) Spot(100f, { frame().coin }) { half ->
        val f = frame()
        treeGlow(Offset(half, half), f.coinR * 2.4f, (.4f + .6f * breath.breathe) * clamp(f.move.open(f.t)), palette.orange)
    }
}

/** Above the tree: the waiting transaction's or claim's dashed outline, marching; a red ring short of funds; the dial's tip. */
@Composable
private fun Pulses(frame: () -> TreeFrame, breath: Breath, palette: NoahTokens.Palette, scene: ExitScene) {
    val claimWaits = scene.claiming && !scene.claimed
    val pulse = scene.waiting.takeIf { it >= 0 && !scene.stale }
    if (claimWaits || (pulse != null && pulse < scene.n - 1)) Spot(24f, { frame().let { f -> if (claimWaits) f.claim else f.node(checkNotNull(pulse) + 1) } }) { half ->
        val f = frame(); val c = Offset(half, half)
        val z = if (claimWaits) 13f else f.geo.node
        val u = if (claimWaits) f.move.claimSend(f.t) else f.send(checkNotNull(pulse))
        val h = if (!claimWaits && f.wasReady(checkNotNull(pulse))) z else z * (.6f + .4f * u)
        drawRoundRect(palette.orange, Offset(c.x - h / 2, c.y - h / 2), Size(h, h), CornerRadius(3.5f), alpha = u * (.7f + .3f * breath.breathe),
            style = Stroke(1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.4f), -breath.march)))
    }
    // Ready but short of funds: a red dashed ring that breathes.
    val short = scene.marks.indexOf(ExitScene.Mark.needsFee)
    if (short >= 0) Spot(44f, { frame().let { f -> if (short == f.n - 1) f.coin else f.node(short + 1) } }) { half ->
        // Round your coin, where its dashed ring would be; round a transaction, just outside its square.
        val f = frame(); val r = if (short == f.n - 1) f.coinR + 5 else f.geo.ring(f.geo.node) - 1
        drawCircle(palette.failure, r, Offset(half, half), alpha = .55f + .45f * breath.breathe,
            style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
    }
    // The timelock's tip: the block it has reached.
    if (scene.locked && !scene.open) Spot(12f, {
        frame().let { f ->
            val pk = f.move.dial(f.t); val an = -PI / 2 + 2 * PI * pk; val r = f.geo.coinR + 10
            if (pk >= 1f || f.move.lock(f.t) < 1f) null else Offset(f.geo.trunk + cos(an).toFloat() * r, f.geo.coinY + sin(an).toFloat() * r)
        }
    }) { half -> drawCircle(palette.orange, 3.4f, Offset(half, half), alpha = .55f + .45f * breath.breathe) }
}

/**
 * A label's pill, in the current pose: "Next block" tinted orange, its tint and outline breathing; grey when Noah can't
 * vouch for it; red when it needs a fee. A block that came without it flashes the outline ink once. It holds every line the
 * label's first line wrapped to.
 */
@Composable
private fun Pill(item: TreeLabels.Item, layout: TreeLayout, measurer: TextMeasurer, styles: TreeStyles, measure: TreeMeasure, frame: () -> TreeFrame,
                 breath: Breath, palette: NoahTokens.Palette, fixed: Float?, move: TreeMotion) {
    val geo = layout.geo
    val pill = checkNotNull(item.pill)
    val (l, t, r, b) = TreeLayout.pill(item, geo, measure)
    val lines = (0 until item.head).map { measurer.measure(item.lines[it].text, styles.style(geo.fontSize, item.lines[it].bold)) }
    val right = item.x != null
    Box(Modifier.offset { IntOffset((l * density).roundToInt(), (t * density).roundToInt()) }.size((r - l).dp, (b - t).dp).drawBehind {
        val f = frame()
        val a = (fixed ?: if (move.labels.isNaN()) 1f else move.labels(f.t)) * if (right) f.move.claimSend(f.t) else 1f
        if (a <= .01f) return@drawBehind
        scale(density, density, Offset.Zero) {
            val radius = CornerRadius((geo.lineHeight + 5) / 2); val size = Size(r - l, b - t)
            drawRoundRect(palette.background, Offset.Zero, size, radius, alpha = a)
            if (pill == TreeLabels.Pill.orange) drawRoundRect(palette.orange, Offset.Zero, size, radius, alpha = a * (.06f + .16f * breath.breathe))
            val flash = f.move.flash(f.t)
            drawRoundRect(if (flash) palette.foreground else pillColor(pill, palette), Offset(.7f, .7f), Size(size.width - 1.4f, size.height - 1.4f), radius,
                alpha = a * if (pill == TreeLabels.Pill.orange && !flash) .6f + .4f * breath.breathe else .9f, style = Stroke(1.4f))
            lines.forEachIndexed { i, text ->
                val x = if (right) checkNotNull(item.x) - text.size.width - l else geo.labels - l
                drawText(text, color = if (pill == TreeLabels.Pill.failure) palette.failure else palette.foreground, alpha = a,
                    topLeft = Offset(x, TreeLayout.lineY(item, i, geo) - t - text.size.height / 2f))
            }
        }
    })
}
