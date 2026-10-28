package com.noahwallet.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.*

/** A screen's tree area; [fill] scales the tree up to fill a split pane and centres it. */
internal data class SceneSlot(val rect: Rect, val fill: Boolean)

/** What the shared canvas draws. Only Welcome, Creating, Restoring and Notifications use the tree. */
internal enum class SceneMode { none, welcome, create, restore, conveyor }

/**
 * Clock and pose of the one onboarding canvas. It lives in the presentation ViewModel, so folding,
 * rotating or recreating the activity neither restarts nor jumps the animation.
 */
internal class SceneClock {
    var mode by mutableStateOf(SceneMode.none); private set
    /** The mode still drawn while the canvas fades out after [mode] becomes none. */
    var drawn = SceneMode.none; private set
    /** Mode start on the frame clock (System.nanoTime), kept across recreation. */
    var startNanos = 0L; private set
    /** Notifications denied: the conveyor freezes at this time and greys out. */
    var frozenAt by mutableStateOf<Float?>(null)
    /** Restore finished at this scene time; its final ripples follow. */
    var readyAt by mutableStateOf<Float?>(null)
    /** Last frame time; the canvas reads it so each frame only redraws. */
    var frameNanos by mutableLongStateOf(0L)
    /** Where the current screen wants the tree, in canvas pixels; kept while the canvas fades out. */
    var slot by mutableStateOf<SceneSlot?>(null)
    private val slots = mutableStateMapOf<SceneMode, SceneSlot>()

    fun show(next: SceneMode) {
        if (next == mode) return
        if (next != SceneMode.none) {
            // Welcome hands its grown tree straight to Creating; every other mode starts its own clock.
            startNanos = System.nanoTime()
            frozenAt = null; readyAt = null
        }
        mode = next
        if (next != SceneMode.none) drawn = next
        slots[next]?.let { slot = it }
    }

    fun report(mode: SceneMode, slot: SceneSlot) {
        slots[mode] = slot
        if (mode == this.mode) this.slot = slot
    }

    fun time(now: Long = frameNanos): Float = ((now - startNanos).coerceAtLeast(0) / 1e9).toFloat()
}

private val orange = Color(0xFFF7931A)

/** Decorative: hidden from TalkBack and draws no text. */
@Composable
internal fun TreeSceneCanvas(clock: SceneClock, foreground: Color, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val visible = clock.mode != SceneMode.none && clock.slot != null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (reduceMotion) 180 else 450), label = "scene")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The frame loop runs only while something is drawn and the activity is on screen.
    val running = clock.mode != SceneMode.none || alpha > 0f
    val static = (reduceMotion && clock.mode in setOf(SceneMode.welcome, SceneMode.conveyor)) ||
        (clock.mode in setOf(SceneMode.conveyor, SceneMode.restore) && clock.frozenAt != null)
    LaunchedEffect(running, static, lifecycle) {
        if (!running) return@LaunchedEffect
        if (static) { clock.frameNanos = System.nanoTime(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) withFrameNanos { clock.frameNanos = it }
        }
    }
    if (alpha <= 0f && !visible) return
    Canvas(modifier.graphicsLayer { this.alpha = alpha }.clearAndSetSemantics { }) {
        val slot = clock.slot ?: return@Canvas
        ScenePainter(this, foreground, reduceMotion, clock.frozenAt != null).scene(clock, slot)
    }
}

/**
 * The design-space boxes (prototype points) a mode fits into its slot: the prototype's full width in one
 * column, and the drawing's own bounds when it fills a pane. The conveyor may run past a pane's sides,
 * like a belt, so its trees stay legible in narrow panes.
 */
private class Boxes(val column: Rect, val pane: Rect, val overflow: Float = 1f)
private val treeBoxes = Boxes(Rect(0f, 196f, 393f, 508f), Rect(24f, 200f, 369f, 502f))
private val conveyorBoxes = Boxes(Rect(0f, 168f, 393f, 344f), Rect(28f, 172f, 372f, 340f), overflow = 1.45f)

private fun clamp(x: Float, a: Float = 0f, b: Float = 1f) = x.coerceIn(a, b)
private fun ease(x: Float) = 1 - (1 - x).pow(3)

private val welcomeTree = VtxoTree.build(196.5f, 330f, listOf(214f, 318f, 410f, 496f), 3)
private const val yourIndex = 41
private val you = welcomeTree.leaves[yourIndex]
private val yourPath = pathOf(you)
private val specks = List(12) { i ->
    val angle = i / 12f * 2 * PI.toFloat() + .4f
    val radius = 90f + (i * 53) % 50
    TreePoint(you.x + cos(angle) * radius * 1.2f, you.y + sin(angle) * radius * .9f + 30)
}
/** Your bitcoin is spread over several leaves. */
private val found = listOf(6, 23, 41, 50)
private const val bandStart = .4f
private const val bandEnd = 2.3f
private fun foundAt(i: Int) = bandStart + (bandEnd - bandStart) * (welcomeTree.leaves[i].x - 20) / 353
/** Scene times at which the restore scan reaches each of your leaves. */
internal val foundLeafTimes get() = found.map(::foundAt)
private val conveyorYs = listOf(226f, 282f, 334f)
private const val conveyorLeaf = 10
private const val roundSeconds = 3.8f

/** A stroke's colour, its alpha (orange strokes glow unless the scene is off) and its width, in prototype points. */
private data class Pen(val color: Color, val alpha: Float, val width: Float)

/** Prototype drawWelcome/drawCreate/drawRestore/drawConveyor, in prototype points. */
private class ScenePainter(val g: DrawScope, val base: Color, val reduceMotion: Boolean, val off: Boolean) {
    /** Design-space x range the slot covers, for the full-width chain line. */
    var left = 0f
    var right = 393f

    /** The clock's drawn mode at its current time (or where it froze), fitted into [slot]. */
    fun scene(clock: SceneClock, slot: SceneSlot) {
        val t = clock.time()
        when (clock.drawn) {
            SceneMode.welcome -> frame(slot, treeBoxes) { welcome(if (reduceMotion) 60f else t) }
            SceneMode.create -> frame(slot, treeBoxes) { create(t) }
            SceneMode.restore -> frame(slot, treeBoxes) { restore(clock.frozenAt ?: t, clock.readyAt) }
            SceneMode.conveyor -> frame(slot, conveyorBoxes) { conveyor(clock.frozenAt ?: if (reduceMotion) 1f else t) }
            SceneMode.none -> Unit
        }
    }

    fun frame(target: SceneSlot, boxes: Boxes, draw: () -> Unit) {
        val slot = target.rect
        val box = if (target.fill) boxes.pane else boxes.column
        // One column: fit the width, sitting low so the chain line is just above the words. A pane: fill it and centre.
        val scale = if (target.fill) minOf(slot.width * boxes.overflow / box.width, slot.height / box.height, 3f * g.density)
        else minOf(slot.width / box.width, slot.height / box.height, 2.2f * g.density)
        val x = slot.left + (slot.width - box.width * scale) / 2 - box.left * scale
        val y = slot.top + (slot.height - box.height * scale) * (if (target.fill) .5f else .72f) - box.top * scale
        left = (slot.left - x) / scale; right = (slot.right - x) / scale
        g.clipRect(slot.left, 0f, slot.right, g.size.height) {
            withTransform({ translate(x, y); scale(scale, scale, Offset.Zero) }) { draw() }
        }
    }

    private fun pr(t: Float, a: Float, b: Float) = if (reduceMotion) (if (t >= b) 1f else 0f) else clamp((t - a) / (b - a))
    private fun glows(color: Color) = color == orange && !off

    fun link(p: TreeNode, c: TreeNode, u0: Float, u1: Float, pen: Pen) {
        val (color, a, width) = pen
        if (u1 <= u0 || a <= 0f) return
        val path = Path()
        for (i in 0..14) {
            val q = bezier(p, c, u0 + (u1 - u0) * i / 14)
            if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        val alpha = a.coerceAtMost(1f)
        if (glows(color)) g.drawPath(path, orange.copy(alpha = .16f * alpha), style = Stroke(width + 4f, cap = StrokeCap.Round))
        g.drawPath(path, color.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round))
    }

    fun dot(x: Float, y: Float, r: Float, color: Color, a: Float) {
        if (r <= 0f || a <= 0f) return
        val alpha = a.coerceAtMost(1f)
        if (glows(color)) g.drawCircle(Brush.radialGradient(listOf(orange.copy(alpha = .45f * alpha), Color.Transparent),
            Offset(x, y), r + 6f), r + 6f, Offset(x, y))
        g.drawCircle(color.copy(alpha = alpha), r, Offset(x, y))
    }

    fun ring(x: Float, y: Float, r: Float, pen: Pen) {
        val (color, a, width) = pen
        if (r <= 0f || a <= 0f) return
        val alpha = a.coerceAtMost(1f)
        if (glows(color)) g.drawCircle(orange.copy(alpha = .16f * alpha), r, Offset(x, y), style = Stroke(width + 4f))
        g.drawCircle(color.copy(alpha = alpha), r, Offset(x, y), style = Stroke(width))
    }

    /** grow[d]: progress of the links leaving level d counted from the root. */
    fun tree(tree: VtxoTree, grow: List<Float>, alpha: Float = 1f, rootScale: Float = 1f) {
        val depth = tree.depth
        for (d in 0 until depth) {
            val progress = grow[d]
            if (progress <= 0f) continue
            val index = if (depth == 3) d else d + 1
            val width = listOf(1.3f, 1f, .7f)[index]
            val a = listOf(.24f, .2f, .16f)[index]
            for (parent in tree.levels[depth - d]) for (child in parent.children) link(parent, child, 0f, progress, Pen(base, a * alpha, width))
        }
        for (d in 1 until depth) if (grow[d - 1] >= 1f) for (node in tree.levels[depth - d]) dot(node.x, node.y, 1.8f, base, .45f * alpha)
        dot(tree.root.x, tree.root.y, 4 * rootScale, base, .9f * alpha)
    }

    /** The Bitcoin line with drifting block ticks, across the whole slot. */
    fun chain(y: Float, camera: Float, grow: Float = 1f) {
        val middle = (left + right) / 2
        val half = (right - left) / 2 * grow
        g.drawLine(base.copy(alpha = .18f), Offset(middle - half, y), Offset(middle + half, y), strokeWidth = 1f)
        val offset = ((camera % 26) + 26) % 26
        var x = floor((left - offset) / 26) * 26 - offset
        while (x < right + 26) {
            if (abs(x - middle) <= half) g.drawRect(base.copy(alpha = .2f), Offset(x - 2, y - 2), Size(4f, 4f))
            x += 26
        }
    }

    fun welcome(t: Float) {
        chain(496f, if (reduceMotion) 0f else t * 7, ease(pr(t, 0f, .6f)))
        tree(welcomeTree, listOf(ease(pr(t, .3f, .75f)), ease(pr(t, .6f, 1.05f)), ease(pr(t, .9f, 1.4f))), rootScale = ease(pr(t, .15f, .4f)))
        val flash = HashMap<Int, Float>()
        if (!reduceMotion) runners(t, flash)
        welcomeLeaves(t, flash)
        val s = pr(t, 1.55f, 1.95f)
        if (s > 0f) ring(you.x, you.y, 3.4f * ease(s) * (if (reduceMotion || t < 1.95f) 1f else 1 + .18f * sin((t - 1.95f) * 3.6f)), Pen(orange, 1f, 1.4f))
    }

    /** A dot runs from the root to a random leaf every 1.2 s, then the leaf [flash]es. */
    private fun runners(t: Float, flash: MutableMap<Int, Float>) {
        var n = max(0, ceil((t - 2f - .9f * 1.35f) / 1.2f).toInt())
        while (true) {
            val start = 2 + n * 1.2f
            if (start > t) break
            val q = (t - start) / .9f
            if (q < 1.35f) runner(n, q, flash)
            n++
        }
    }

    private fun runner(n: Int, q: Float, flash: MutableMap<Int, Float>) {
        var leaf = (n * 37 + 11) % 64
        if (leaf == yourIndex) leaf = (leaf + 5) % 64
        if (q < 1f) { val p = along(pathOf(welcomeTree.leaves[leaf]), ease(q)); dot(p.x, p.y, 1.9f, base, .85f) }
        else flash[leaf] = 1 - (q - 1) / .35f
    }

    /** Every leaf but yours pops in from the middle out, twinkling, brighter while it flashes. */
    private fun welcomeLeaves(t: Float, flash: Map<Int, Float>) {
        welcomeTree.leaves.forEachIndexed { i, leaf ->
            if (i == yourIndex) return@forEachIndexed
            val k = abs(i - 31.5f) * .006f
            val pop = pr(t, 1.25f + k, 1.5f + k)
            if (pop <= 0f) return@forEachIndexed
            val twinkle = if (reduceMotion) 0f else max(0f, sin(t * .8f + i * 2.17f)).pow(12) * .35f
            val f = flash[i] ?: 0f
            dot(leaf.x, leaf.y, 1.5f * ease(pop) + 1.2f * f, base, .38f + twinkle + .45f * f)
        }
    }

    fun create(t: Float) {
        chain(496f, if (reduceMotion) 0f else t * 7)
        tree(welcomeTree, listOf(1f, 1f, 1f))
        val wave = t - 2.55f
        welcomeTree.leaves.forEachIndexed { i, leaf ->
            if (i == yourIndex) return@forEachIndexed
            val f = if (wave > 0 && !reduceMotion) exp(-((abs(leaf.x - you.x) - wave * 260) / 14).pow(2)) * clamp(1 - wave / 1.4f) else 0f
            dot(leaf.x, leaf.y, 1.5f + f * 1.4f, base, .38f + .5f * f)
        }
        specks(t)
        val fill = pr(t, 1f, 1.3f)
        ring(you.x, you.y, 3.4f, Pen(orange, 1f, 1.4f))
        if (fill > 0f) dot(you.x, you.y, 4.2f * ease(fill), orange, 1f)
        val segments = listOf(1.4f to 1.75f, 1.75f to 2.1f, 2.1f to 2.45f)
        if (pr(t, 1.4f, 1.75f) > 0f) dot(yourPath[0].x, yourPath[0].y, 4.4f, orange, 1f)
        for (k in 0 until 3) {
            val u = ease(pr(t, segments[k].first, segments[k].second))
            link(yourPath[k], yourPath[k + 1], 0f, u, Pen(orange, .95f, 1.6f))
            if (u >= 1f && k < 2) dot(yourPath[k + 1].x, yourPath[k + 1].y, 2.6f, orange, 1f)
        }
        if (!reduceMotion) for (k in 0 until 2) {
            val q = pr(t, 2.55f + k * .22f, 3.45f + k * .22f)
            if (q > 0f && q < 1f) ring(you.x, you.y, 4 + 30 * ease(q), Pen(orange, .7f * (1 - q), 1.2f))
        }
    }

    /** Orange specks gather into your leaf, one after another. */
    private fun specks(t: Float) {
        specks.forEachIndexed { i, s ->
            val q = pr(t, .15f + i * .06f, .7f + i * .06f)
            if (q <= 0f || q >= 1f) return@forEachIndexed
            val e = q * q
            dot(s.x + (you.x - s.x) * e, s.y + (you.y - s.y) * e, 1.8f, orange, .95f)
        }
    }

    fun restore(t: Float, readyAt: Float?) {
        chain(496f, if (reduceMotion) 0f else t * 7)
        tree(welcomeTree, listOf(1f, 1f, 1f))
        if (off) { welcomeTree.leaves.forEach { dot(it.x, it.y, 1.5f, base, .38f) }; return }
        val bandX = 20 + 353 * pr(t, bandStart, bandEnd)
        if (!reduceMotion && t > bandStart && t < bandEnd + .15f) band(bandX)
        restoreLeaves(t, bandX)
        // The final ripples wait for the real restore to finish.
        val rippleStart = readyAt?.let { max(it, bandEnd + .3f) }
        if (!reduceMotion && rippleStart != null && t > rippleStart) ripples(clamp((t - rippleStart) / .9f))
    }

    /** Every leaf, with yours lit up once the scan at [bandX] has passed them. */
    private fun restoreLeaves(t: Float, bandX: Float) {
        welcomeTree.leaves.forEachIndexed { i, leaf ->
            if (i in found && leaf.x <= bandX) return@forEachIndexed
            dot(leaf.x, leaf.y, 1.5f, base, .38f)
        }
        for (i in found) if (welcomeTree.leaves[i].x <= bandX) foundLeaf(i, t)
    }

    /** The scan's soft band at [x]. */
    private fun band(x: Float) {
        g.drawRect(Brush.horizontalGradient(listOf(orange.copy(alpha = 0f), orange.copy(alpha = .18f), orange.copy(alpha = 0f)),
            x - 28, x + 28), Offset(x - 28, 196f), Size(56f, 300f))
    }

    /** Ripples from each of your leaves once the restore is ready, [q] of the way out. */
    private fun ripples(q: Float) {
        for (i in found) welcomeTree.leaves[i].let { ring(it.x, it.y, 4 + 18 * ease(q), Pen(orange, .6f * (1 - q), 1.1f)) }
    }

    /** One of your leaves the scan has reached: its path back to the root lights up, leaf first. */
    private fun foundLeaf(i: Int, t: Float) {
        val leaf = welcomeTree.leaves[i]
        val at = foundAt(i)
        val u = ease(pr(t, at, at + .45f))
        val path = pathOf(leaf)
        for (k in 2 downTo 0) { val su = clamp(u * 3 - (2 - k)); if (su > 0f) link(path[k], path[k + 1], 1 - su, 1f, Pen(orange, .9f, 1.4f)) }
        if (u >= 1f) dot(path[0].x, path[0].y, 4.4f, orange, 1f)
        dot(leaf.x, leaf.y, 3.6f, orange, 1f)
    }

    fun conveyor(t: Float) {
        val color = if (off) base else orange
        val a = if (off) .35f else 1f
        val k = floor(t / roundSeconds).toInt()
        val u = t - k * roundSeconds
        val camera = 180f * k + 180 * (if (reduceMotion) (if (u >= 3.4f) 1f else 0f) else ease(clamp((u - 2.6f) / .8f)))
        chain(334f, camera)
        fun round(j: Int) = VtxoTree.build(110 + 180f * j - camera, 150f, conveyorYs, 2)
        val expiring = round(k)
        val next = round(k + 1)
        val sweep = pr(u, 1.9f, 2.7f)
        val growth = ease(pr(u, 0f, 1f))
        tree(expiring, listOf(1 - clamp(sweep * 2 - 1), 1 - clamp(sweep * 2)), alpha = 1 - .7f * sweep)
        expiring.leaves.forEachIndexed { i, leaf -> if (i != conveyorLeaf || u >= 1.1f) dot(leaf.x, leaf.y, 1.4f * (1 - sweep), base, .38f * (1 - sweep)) }
        tree(next, listOf(clamp(growth * 2), clamp(growth * 2 - 1)), rootScale = clamp(growth * 3))
        next.leaves.forEachIndexed { i, leaf -> if (i != conveyorLeaf || u < 1.8f) dot(leaf.x, leaf.y, 1.4f * pr(u, .9f, 1.1f), base, .38f) }
        val from = pathOf(expiring.leaves[conveyorLeaf])
        val to = pathOf(next.leaves[conveyorLeaf])
        val fade = (if (u < 1.1f) 1f else 1 - pr(u, 1.1f, 1.5f)) * (1 - sweep)
        for (s in 0 until 2) link(from[s], from[s + 1], 0f, 1f, Pen(color, .9f * fade * a, 1.4f))
        val trace = ease(pr(u, 1.8f, 2.2f))
        for (s in 0 until 2) link(to[s], to[s + 1], 0f, clamp(trace * 2 - s), Pen(color, .9f * a, 1.4f))
        hop(u, expiring.leaves[conveyorLeaf], next.leaves[conveyorLeaf], Pen(color, a, 0f))
    }

    /** Your leaf hops from the expiring round to the next one, with a short trail; [pen]'s width is unused. */
    private fun hop(u: Float, start: TreeNode, end: TreeNode, pen: Pen) {
        val (color, a) = pen
        fun hop(h: Float) = TreePoint(start.x + (end.x - start.x) * h, start.y + (end.y - start.y) * h - sin(PI.toFloat() * h) * 46)
        val h = ease(pr(u, 1.1f, 1.8f))
        if (h > 0f && h < 1f && !reduceMotion) for (i in 1..3) {
            val trail = hop(ease(pr(u - .04f * i, 1.1f, 1.8f)))
            dot(trail.x, trail.y, 3 - i * .6f, color, .25f * a)
        }
        val leaf = hop(h)
        dot(leaf.x, leaf.y, 3.4f, color, a)
    }
}
