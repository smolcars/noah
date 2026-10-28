package com.noahwallet.onchain

import com.noahwallet.localization.L10n
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The picture's words, resolved from a locale's resources outside composition, so the layout (and its sweep test) reads
 * the text the picture draws. [money] formats amounts in the wallet's unit.
 */
internal class TreeStrings(private val text: (String, Array<out String>) -> String, private val count: (String, Long) -> String,
    val number: (Long) -> String, val money: (Long) -> String, val japanese: Boolean = false) {
    fun tr(source: String, vararg arguments: String) = text(source, arguments)
    fun trPlural(source: String, n: Long) = count(source, n)

    companion object {
        @android.annotation.SuppressLint("DiscouragedApi") // translations are found by name, as in L10n
        fun of(resources: android.content.res.Resources, packageName: String, money: (Long) -> String): TreeStrings {
            val locale = resources.configuration.locales[0]
            val format = java.text.NumberFormat.getIntegerInstance(locale)
            fun id(source: String, type: String) = resources.getIdentifier(L10n.resourceName(source), type, packageName)
            @Suppress("SpreadOperator") // getString is vararg; a source has at most a few arguments
            val text = { source: String, arguments: Array<out String> ->
                id(source, "string").let { if (it == 0) source else resources.getString(it, *arguments) }
            }
            return TreeStrings(text,
                { source, n ->
                    id(source, "plurals").let {
                        if (it == 0) source
                        else String.format(locale, resources.getQuantityString(it, n.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()), format.format(n))
                    }
                },
                { format.format(it) }, money, locale.language == "ja")
        }
    }
}

/**
 * The point [u] (0 → 1) along a link from ([px], [py]) to ([cx], [cy]): onboarding's S-curve, leaving and arriving
 * vertically, or sideways when [horizontal]. The picture draws links along it; a branch's outline follows it.
 */
@Suppress("LongParameterList") // exit-tree layout: flat geometry inputs
internal fun linkAt(px: Float, py: Float, cx: Float, cy: Float, u: Float, horizontal: Boolean = false): Pair<Float, Float> {
    val a = 1 - u
    return if (horizontal) { val mx = (px + cx) / 2; (a * a * a * px + 3 * a * a * u * mx + 3 * a * u * u * mx + u * u * u * cx) to
        (a * a * a * py + 3 * a * a * u * py + 3 * a * u * u * cy + u * u * u * cy) }
    else { val my = (py + cy) / 2; (a * a * a * px + 3 * a * a * u * px + 3 * a * u * u * cx + u * u * u * cx) to
        (a * a * a * py + 3 * a * a * u * my + 3 * a * u * u * my + u * u * u * cy) }
}

/** How wide [text] is at [size] dp (medium, or semibold when [bold]), as the picture draws it. */
internal fun interface TreeMeasure { fun width(text: String, size: Float, bold: Boolean): Float }

/** A drawn element's outline: a box, or a circle in its [l]…[b] square. */
internal data class TreeShape(val kind: Kind, val group: String, val l: Float, val t: Float, val r: Float, val b: Float, val circle: Boolean = false) {
    enum class Kind { round, node, coin, claim, badge, server, text, label, brace, stub, sibling, branch, amount, caption }
    val x get() = (l + r) / 2
    val y get() = (t + b) / 2

    /** Whether the two come closer than [margin]. */
    fun overlaps(other: TreeShape, margin: Float = 0f): Boolean = when {
        circle && other.circle -> hypot(x - other.x, y - other.y) < (r - l) / 2 + (other.r - other.l) / 2 + margin
        circle -> near(this, other, margin)
        other.circle -> near(other, this, margin)
        else -> l - margin / 2 < other.r + margin / 2 && other.l - margin / 2 < r + margin / 2 &&
            t - margin / 2 < other.b + margin / 2 && other.t - margin / 2 < b + margin / 2
    }

    fun inside(width: Float, height: Float) = l >= 0f && t >= 0f && r <= width && b <= height

    companion object {
        @Suppress("LongParameterList") // exit-tree layout: flat geometry inputs
        fun box(kind: Kind, group: String, l: Float, t: Float, r: Float, b: Float) = TreeShape(kind, group, l, t, r, b)
        fun circle(kind: Kind, group: String, x: Float, y: Float, radius: Float) = TreeShape(kind, group, x - radius, y - radius, x + radius, y + radius, true)
        private fun near(circle: TreeShape, box: TreeShape, margin: Float): Boolean {
            val x = circle.x.coerceIn(box.l, box.r); val y = circle.y.coerceIn(box.t, box.b)
            return hypot(circle.x - x, circle.y - y) < (circle.r - circle.l) / 2 + margin
        }
    }
}

/**
 * Where the picture puts everything for one still (exit-v2-notes.md, The picture), pure so the tests read the numbers the
 * painter draws. One rule keeps it legible: every drawn element has a clearance outline ([TreeShape]). The required ones
 * (the round's block, your path, your coin with its ring or dial, the claim, the destination and its lines, the server, the
 * texts and labels) keep their minimum spacing. The optional ones go where they're clear of everything before them, and
 * otherwise give way: your other exits' amounts and the caption first, then your other exits' coins (slid down their
 * branch or further out before they're left out), then the stubs.
 */
@Suppress("LongParameterList") // exit-tree layout: flat geometry inputs
internal class TreeLayout(val scene: ExitScene, val words: TreeStrings, val geo: TreeGeometry, val ys: FloatArray, val labels: List<TreeLabels.Item>,
    val stubs: List<Stub>, val siblings: List<Sibling>, val caption: List<String>?, val captionY: Float,
    val wallet: List<WalletLine>, val more: Map<Int, String>, val shapes: List<TreeShape>) {
    /** A stub's ring, [dy] from its level (so it follows the level as the picture moves). */
    data class Stub(val level: Int, val x: Float, val dy: Float)
    /** Your other exit [other] (an index in the scene's), the [slot]th at level [level]; [amount] is its text size, null when left out. */
    data class Sibling(val other: Int, val level: Int, val slot: Int, val x: Float, val dy: Float, val amount: Float?)
    /** A line under the phone's (or the destination's) badge, right-aligned at the canvas's edge less 12. */
    data class WalletLine(val text: String, val y: Float, val size: Float, val bold: Boolean, val tone: TreeLabels.Tone)

    companion object {
        /** Text's height at [size]: the line box the picture draws it in. */
        fun textHeight(size: Float) = size * 1.2f
        /** The "+N" in a folded node. */
        const val moreSize = 8f

        /** The layout for [scene] (already folded to fit) at rest, in a [width] × [height] dp picture. */
        @Suppress("LongParameterList") // exit-tree layout: flat geometry inputs
        fun of(scene: ExitScene, width: Float, height: Float, scale: Float, wide: Boolean, words: TreeStrings, measure: TreeMeasure,
            balance: Long? = null, received: Long? = null): TreeLayout {
            val geo = TreeGeometry(width, height, scene.n, scale, wide)
            val lock = if (scene.locked && !scene.claiming) 1f else 0f
            val ys = geo.levels(lock, scene.docked, scene.ringed) { if (scene.mined(it)) 1f else 0f }
            val server = scene.explain || scene.lifting || scene.canceled
            val fit = { item: TreeLabels.Item -> fit(item, geo, measure, server) }
            val labels = TreeLabels.place(items(scene, geo, ys, words), geo.lineHeight, scale, geo.labelTop, geo.labelBottom, fit) { a, b ->
                if (a == b) words.tr("Block {height}", words.number(a)) else words.tr("Blocks {from}–{to}", words.number(a), words.number(b))
            }
            return arrange(scene, geo, ys, lock, labels, server, words, measure, balance, received)
        }

        /** The label column's items for [s] (prototype `heroLabels`), before they're fit and placed. */
        fun items(s: ExitScene, geo: TreeGeometry, ys: FloatArray, w: TreeStrings): List<TreeLabels.Item> {
            if (s.explain) return explainItems(s, geo, ys, w)
            if (s.claiming) return listOf(claimItem(s, geo, w))
            val items = mutableListOf(roundItem(geo, w, TreeLabels.Tone.muted, false))
            val n = s.n
            val latest = (0 until n).lastOrNull { s.mined(it) }
            var unsent = -1
            for (j in 1..n) {
                val i = j - 1
                if (s.marks[i] == ExitScene.Mark.unsent) { if (unsent < 0) unsent = j }
                else levelItem(s, i, ys[j], latest, w)?.let { items += it }
            }
            if (unsent in 1 until n && !s.lifting) items += TreeLabels.Item(ys[unsent],
                listOf(labelLine(w.trPlural("{n} more to send", (s.count - s.real(unsent - 1)).toLong()))), 1.5f)
            coinItem(s, geo, w)?.let { items += it }
            return items
        }

        private fun labelLine(text: String, tone: TreeLabels.Tone = TreeLabels.Tone.muted, bold: Boolean = false) = TreeLabels.Line(text, tone, bold)

        /** The round's block: bark gives no height, so it says what it is; on one line, shrunk, where two don't fit. */
        private fun roundItem(geo: TreeGeometry, w: TreeStrings, tone: TreeLabels.Tone, bold: Boolean) = TreeLabels.Item(geo.roundY - geo.lineHeight + 3,
            listOf(labelLine(w.tr("The round,"), tone, bold), labelLine(w.tr("already on Bitcoin"))), 2f,
            fallbacks = listOf(TreeLabels.Item(geo.roundY, listOf(labelLine(w.tr("The round,") + (if (w.japanese) "" else " ") + w.tr("already on Bitcoin"))),
                2f, shrinks = true)))

        /** Explaining the exit: your VTXO, the transactions to the chain, and the round. */
        private fun explainItems(s: ExitScene, geo: TreeGeometry, ys: FloatArray, w: TreeStrings): List<TreeLabels.Item> {
            val items = mutableListOf<TreeLabels.Item>()
            val n = s.n; val k = geo.scale
            val strong = TreeLabels.Tone.strong
            items += TreeLabels.Item(geo.coinY, listOf(labelLine(w.tr("Your VTXO"), strong, true), labelLine(w.tr("in Ark"))), 3f)
            if (n > 1) items += TreeLabels.Item((ys[1] + ys[n - 1]) / 2 - 7 * k,
                listOf(labelLine(w.trPlural("{n} transactions", s.count.toLong()), strong, true),
                    labelLine(w.tr("one block each,")), labelLine(w.tr("root first"))), 2f, brace = min(ys[n] + geo.coinR + 5, ys[1] + 7) to ys[1] + 7)
            items += roundItem(geo, w, strong, true)
            return items
        }

        /** The claim transaction, beside its node on the stem. */
        private fun claimItem(s: ExitScene, geo: TreeGeometry, w: TreeStrings): TreeLabels.Item {
            val strong = TreeLabels.Tone.strong
            val (x, y) = geo.claimNode
            return TreeLabels.Item(y,
                if (s.claimed) listOf(labelLine(w.tr("Block {height}", w.number(s.claimedBlock ?: s.tip)), strong, true), labelLine(w.tr("claim")))
                else listOfNotNull(labelLine(w.tr("Next block"), strong, true), s.claimFee?.let { labelLine(w.tr("claim fee {amount}", w.money(it))) }), 4f,
                if (s.claimed) null else TreeLabels.Pill.orange, x = x - 16)
        }

        /** Level [i]'s label at [y]: its block, Ready to send, Needs a fee, or Next block; null for one with nothing to say. */
        private fun levelItem(s: ExitScene, i: Int, y: Float, latest: Int?, w: TreeStrings): TreeLabels.Item? {
            val strong = TreeLabels.Tone.strong
            val shared = if (s.shared[i]) " " + w.tr("· shared") else ""
            return when (s.marks[i]) {
                ExitScene.Mark.mined -> if (i == s.n - 1 && s.locked) null else s.heights[i]?.let { height ->
                    val oldest = s.oldest?.getOrNull(i)?.takeIf { s.folded(i) } ?: height
                    TreeLabels.Item(y, listOf(labelLine(blocksText(oldest, height, w) + shared, strong)), if (i == latest) 2.2f else 1f,
                        blocks = oldest..height)
                }
                ExitScene.Mark.ready -> TreeLabels.Item(y, listOf(labelLine(w.tr("Ready to send"), strong, true)), 4f)
                ExitScene.Mark.needsFee -> TreeLabels.Item(y, listOfNotNull(labelLine(w.tr("Needs a fee"), TreeLabels.Tone.failure, true),
                    s.need?.let { labelLine(w.tr("add {amount} on-chain", w.money(it)), TreeLabels.Tone.failure) }), 4f, TreeLabels.Pill.failure)
                ExitScene.Mark.waiting -> waitingItem(s, i, y, shared, w)
                ExitScene.Mark.unsent -> null
            }
        }

        /** A mined level's block, or the blocks it folds. */
        private fun blocksText(oldest: Long, height: Long, w: TreeStrings) =
            if (oldest != height) w.tr("Blocks {from}–{to}", w.number(oldest), w.number(height)) else w.tr("Block {height}", w.number(height))

        private fun waitingItem(s: ExitScene, i: Int, y: Float, shared: String, w: TreeStrings): TreeLabels.Item {
            val sub = when {
                s.stale -> s.checkedAt?.let { w.tr("last checked at {height}", w.number(it)) }
                s.theirs[i] -> w.tr("sent by someone else")
                s.attention == ExitStage.Attention.feesRose -> s.rate?.let { w.tr("fee raised · {rate} sat/vB",
                    it.toString()) } ?: w.tr("a block came without it")
                s.attention == ExitStage.Attention.stuck -> w.trPlural("{n} blocks without it", s.waited)
                else -> s.fee?.let { w.tr("fee {amount}", w.money(it)) + shared }
            }
            return TreeLabels.Item(y, listOfNotNull(labelLine(w.tr("Next block"), TreeLabels.Tone.strong, true), sub?.let { labelLine(it) }), 4f,
                if (s.stale) TreeLabels.Pill.grey else TreeLabels.Pill.orange)
        }

        /** Your coin's label: locked or unlocked, or back in Ark; null while there's nothing to say. */
        private fun coinItem(s: ExitScene, geo: TreeGeometry, w: TreeStrings): TreeLabels.Item? {
            val strong = TreeLabels.Tone.strong
            return when {
                s.locked -> TreeLabels.Item(geo.coinY, if (s.open) listOf(labelLine(w.tr("Unlocked"), strong, true),
                    labelLine(w.tr("since block {height}", w.number(s.unlockedAt ?: s.claimableHeight ?: s.tip))))
                else listOfNotNull(labelLine(w.trPlural("Locked · {n} blocks", s.left), strong, true),
                    s.claimableHeight?.let { labelLine(w.tr("opens at {height}", w.number(it))) }), 3.5f)
                s.coin == ExitScene.Mark.unsent -> TreeLabels.Item(geo.coinY,
                    listOf(labelLine(if (s.canceled) w.tr("Back in Ark") else w.tr("Your VTXO"), strong, true)), 3.5f)
                else -> null
            }
        }

        /**
         * [item] wrapped to the room it has (translations and large text run long): its column, narrower beside the phone's
         * badge and, while the Ark line shows ([server]), the server's; or, for the claim, right of your coin. A pill's text
         * keeps 8 dp inside it. A label that shrinks rather than wraps gets its [TreeLabels.Item.size].
         */
        fun fit(item: TreeLabels.Item, geo: TreeGeometry, measure: TreeMeasure, server: Boolean): TreeLabels.Item {
            val fallbacks = item.fallbacks.map { fit(it, geo, measure, server) }.filter { !it.shrinks || it.size >= .75f }
            if (item.shrinks) {
                val line = item.lines.first()
                val top = item.y - geo.lineHeight / 2
                val room = item.x?.let(geo::claimColumn) ?: geo.column(top, top + geo.lineHeight, server)
                return item.copy(size = min(1f, room / max(1f, measure.width(line.text, geo.fontSize, line.bold))), fallbacks = fallbacks)
            }
            fun wrapped(width: Float): TreeLabels.Item {
                var head = 1
                val lines = item.lines.flatMapIndexed { i, line ->
                    val size = if (i == 0) geo.fontSize else geo.subSize
                    val parts = TreeLabels.wrap(line.text, width - if (i == 0 && item.pill != null) 8 else 0) { measure.width(it, size, line.bold) }
                    if (i == 0) head = parts.size
                    parts.map { line.copy(text = it) }
                }
                return item.copy(lines = lines, head = head, fallbacks = fallbacks)
            }
            item.x?.let { return wrapped(geo.claimColumn(it)) }
            val widest = geo.width - 12 - geo.labels
            val full = wrapped(widest)
            val top = full.y - geo.lineHeight / 2
            val room = geo.column(top, top + TreeLabels.height(full, geo.lineHeight, geo.scale), server)
            return if (room < widest) wrapped(room) else full
        }

        /** A line [i] of [item]'s y and size. */
        fun lineY(item: TreeLabels.Item, i: Int,
            geo: TreeGeometry) = item.y + i * geo.lineHeight + if (item.pill != null && i >= item.head) 4 * geo.scale else 0f
        fun lineSize(item: TreeLabels.Item, i: Int, geo: TreeGeometry) = (if (i < item.head) geo.fontSize else geo.subSize) * item.size

        /** A pill around a label's first line, however many lines it wrapped to: l, t, r, b. */
        fun pill(item: TreeLabels.Item, geo: TreeGeometry, measure: TreeMeasure): FloatArray {
            val width = item.lines.take(item.head).maxOf { measure.width(it.text, geo.fontSize, it.bold) }
            val l = item.x?.let { it - width - 8 } ?: (geo.labels - 8)
            val t = item.y - (geo.lineHeight + 5) / 2
            return floatArrayOf(l, t, l + width + 16, t + (item.head - 1) * geo.lineHeight + geo.lineHeight + 5)
        }

        /** A label's outline: its lines at their sizes, and its pill. */
        fun frame(item: TreeLabels.Item, geo: TreeGeometry, measure: TreeMeasure, group: String): TreeShape {
            val width = item.lines.indices.maxOf { measure.width(item.lines[it].text, lineSize(item, it, geo), item.lines[it].bold) }
            var l = item.x?.let { it - width } ?: geo.labels; var t = item.top; var r = l + width; var b = item.bottom
            if (item.pill != null) pill(item, geo, measure).let { p -> l = min(l, p[0]); t = min(t, p[1]); r = max(r, p[2]); b = max(b, p[3]) }
            return TreeShape.box(TreeShape.Kind.label, group, l, t, r, b)
        }

        /** The badge's two lines' text: whose wallet, then its address, what arrived or the balance. */
        private fun walletText(s: ExitScene, w: TreeStrings, balance: Long?, received: Long?): Pair<String, String?> {
            val toOther = s.destination != null && s.claiming
            val first = when { !s.claiming -> w.tr("On-chain"); toOther -> w.tr("Another wallet"); else -> w.tr("This phone") }
            val second = when {
                toOther -> com.noahwallet.ui.activity.short(checkNotNull(s.destination), 6, 4)
                s.claimed && received != null -> "+" + w.money(received)
                else -> balance?.let(w.money)
            }
            return first to second
        }

        /** The lines under the badge: the on-chain balance, or where the claim went and what arrived. */
        fun walletLines(s: ExitScene, geo: TreeGeometry, w: TreeStrings, balance: Long?, received: Long?): List<WalletLine> {
            if (!s.wallet && !s.claiming) return emptyList()
            val (first, second) = walletText(s, w, balance, received)
            val grow = if (s.claiming) 1.1f else 1f; val k = geo.scale
            val sizes = 10.5f * k * grow to 11f * k * grow
            // Both inside the picture, however large the text.
            val y2 = min(geo.ground + 24 * k * grow, geo.height - textHeight(sizes.second) / 2 - 1)
            val y1 = min(geo.ground + 11 * k, y2 - (textHeight(sizes.first) + textHeight(sizes.second)) / 2)
            val needsFee = ExitScene.Mark.needsFee in s.marks
            return listOfNotNull(WalletLine(first, y1, sizes.first, s.claiming, if (s.claiming) TreeLabels.Tone.strong else TreeLabels.Tone.muted),
                second?.let { WalletLine(it, y2, sizes.second, true,
                    when { needsFee -> TreeLabels.Tone.failure; s.claiming -> TreeLabels.Tone.muted; else -> TreeLabels.Tone.strong }) })
        }

        /**
         * Every drawn element's outline, the required ones first, then the optional ones where they're clear of everything
         * before them: the stubs, your other exits' coins (slid down their branch or further out if they must be), their
         * amounts, and the caption.
         */
        @Suppress("LongParameterList") // exit-tree layout: flat geometry inputs
        fun arrange(s: ExitScene, geo: TreeGeometry, ys: FloatArray, lock: Float, labels: List<TreeLabels.Item>, server: Boolean, w: TreeStrings,
            measure: TreeMeasure, balance: Long?, received: Long?): TreeLayout {
            val a = Arrangement(s, geo, ys, w, measure)
            val lines = a.required(lock, labels, server, balance, received)
            val stubs = a.stubs()
            val siblings = a.siblings()
            a.amounts(siblings)
            val (caption, captionY) = a.caption(stubs)
            return TreeLayout(s, w, geo, ys, labels, stubs, siblings, caption, captionY, lines, a.more, a.shapes)
        }

        /** [arrange]'s working state: every outline placed so far, and the folded nodes' "+N". */
        private class Arrangement(val s: ExitScene, val geo: TreeGeometry, val ys: FloatArray, val w: TreeStrings, val measure: TreeMeasure) {
            val n = s.n; val k = geo.scale; val tx = geo.trunk
            val shapes = mutableListOf<TreeShape>()
            val more = mutableMapOf<Int, String>()
            val othersAt = s.others.withIndex().filter { it.value.level in 1 until n }.groupBy { it.value.level }

            fun text(string: String, x: Float, y: Float, size: Float, bold: Boolean, right: Boolean, kind: TreeShape.Kind, group: String): TreeShape {
                val width = measure.width(string, size, bold); val h = textHeight(size)
                return TreeShape.box(kind, group, if (right) x - width else x, y - h / 2, if (right) x else x + width, y + h / 2)
            }
            fun clear(shape: TreeShape) = shape.inside(geo.width,
                geo.height) && shapes.none { it.group != shape.group && it.overlaps(shape, TreeGeometry.clearance) }

            /** The required outlines: the round, the nodes, your coin, the claim, the badge and its lines, the words and the labels. */
            fun required(lock: Float, labels: List<TreeLabels.Item>, server: Boolean, balance: Long?, received: Long?): List<WalletLine> {
                shapes += TreeShape.box(TreeShape.Kind.round, "round", tx - geo.block / 2, geo.ground - geo.block, tx + geo.block / 2, geo.ground)
                nodes()
                // Your coin with its dashed ring or, while locked, the timelock's dial; while claiming, the coin alone.
                shapes += TreeShape.circle(TreeShape.Kind.coin, "coin", tx, geo.coinY - if (s.lifting) 5 else 0,
                    if (s.claiming) geo.coinR else geo.coinReserve(lock))
                if (s.claiming) geo.claimNode.let { (x, y) -> shapes += TreeShape.box(TreeShape.Kind.claim, "claim", x - 6.5f, y - 6.5f, x + 6.5f, y + 6.5f) }
                val lines = walletLines(s, geo, w, balance, received)
                if (s.wallet || s.claiming) badge(lines)
                shapes += text(w.tr("Bitcoin"), 14f, geo.ground + 12 * k, 10.5f * k, false, false, TreeShape.Kind.text, "bitcoin")
                if (server) {
                    shapes += text(w.tr("Ark"), 14f, geo.coinY - 10, 11f * k, false, false, TreeShape.Kind.text, "ark")
                    shapes += TreeShape.circle(TreeShape.Kind.server, "server", geo.width - 30, geo.coinY, 14f)
                }
                labels.forEachIndexed { index, label ->
                    shapes += frame(label, geo, measure, "label$index")
                    label.brace?.let { (y0, y1) -> shapes += TreeShape.box(TreeShape.Kind.brace, "label$index", tx + 27, y0, tx + 32, y1) }
                }
                return lines
            }

            /** Each level's node (wider for a "+N"), the fee child docked on it, and the ring of one short of funds. */
            private fun nodes() {
                for (level in 1 until n) {
                    val i = level - 1; var half = geo.node / 2
                    if (s.folded(i) && !s.mined(i)) {
                        val label = "+" + w.number(s.spans!![i].count().toLong())
                        more[level] = label
                        half = max(half, (measure.width(label, moreSize, true) + 6) / 2)
                    }
                    shapes += TreeShape.box(TreeShape.Kind.node, "node$level", tx - half, ys[level] - geo.node / 2, tx + half, ys[level] + geo.node / 2)
                    // The fee child docked on the waiting transaction's anchor; the red ring of one short of funds.
                    if (s.marks[i] == ExitScene.Mark.waiting && s.paid[i]) shapes += TreeShape.circle(TreeShape.Kind.node, "node$level", tx + 9, ys[level] + 7,
                        4.8f)
                    if (s.marks[i] == ExitScene.Mark.needsFee) shapes += TreeShape.circle(TreeShape.Kind.node, "node$level", tx, ys[level], geo.ring(geo.node))
                }
            }

            /** The phone's (or the destination's) badge, its marks and its lines. */
            private fun badge(lines: List<WalletLine>) {
                shapes += TreeShape.circle(TreeShape.Kind.badge, "badge", geo.walletX, geo.walletY, if (s.claimed) 24f else 15f)
                if (s.claimed) shapes += TreeShape.circle(TreeShape.Kind.badge, "badge", geo.walletX + 15, geo.walletY - 15, 9f)
                if (ExitScene.Mark.needsFee in s.marks) shapes += TreeShape.circle(TreeShape.Kind.badge, "badge", geo.walletX + 12, geo.walletY - 12, 6.5f)
                for (line in lines) shapes += text(line.text, geo.width - 12, line.y, line.size, line.bold, true, TreeShape.Kind.text, "badge")
            }

            fun stubs(): List<Stub> {
                // The other people's stubs.
                val stubs = mutableListOf<Stub>()
                for (level in 1 until n) {
                    if (!s.nodes[level - 1]) continue
                    for ((x, y) in geo.stubs(ys, level, othersAt[level]?.size ?: 0)) {
                        val shape = TreeShape.circle(TreeShape.Kind.stub, "stub$level", x, y, 3f)
                        if (!clear(shape)) continue
                        stubs += Stub(level, x, y - ys[level]); shapes += shape
                    }
                }
                return stubs
            }

            // Your other exits' coins: at their place, else slid down their branch toward it or up it (where a branch clears
            // the coins nearer your path), else further out. Each branch is outlined along the curve drawn, short of where it
            // joins your path: there it may meet the stubs, the other branches and its level, but nowhere may a branch and
            // another coin cross. As many stay as fit, lower levels first, each at the first spot in its order that leaves
            // room for the rest (a search with a budget, so a crowded picture can't take long).
            private class Spot(val x: Float, val y: Float, val outline: List<TreeShape>) {
                val l = outline.minOf { it.l }; val t = outline.minOf { it.t }; val r = outline.maxOf { it.r }; val b = outline.maxOf { it.b }
            }
            fun branch(x: Float, y: Float, level: Int, group: String): List<TreeShape> {
                val joinY = ys[level] - 4
                return (1..20).map { linkAt(x, y + 6, tx, joinY, it / 20f) }.filter { (bx, by) -> hypot(bx - tx, by - joinY) > 9 }
                    .map { (bx, by) -> TreeShape.circle(TreeShape.Kind.branch, group, bx, by, .7f) }
            }
            fun meets(shape: TreeShape, level: Int) = shape.kind == TreeShape.Kind.stub || shape.kind == TreeShape.Kind.branch || shape.group == "node$level"
            fun apart(a: Spot, b: Spot) = with(TreeGeometry.clearance) { a.l > b.r + this || b.l > a.r + this || a.t > b.b + this || b.t > a.b + this } ||
                a.outline.none { p -> b.outline.any { q -> (p.kind != TreeShape.Kind.branch || q.kind != TreeShape.Kind.branch) && p.overlaps(q,
                    TreeGeometry.clearance) } }

            fun siblings(): MutableList<Sibling> {
                val others = othersAt.keys.sorted().flatMap { level ->
                    othersAt.getValue(level).mapIndexed { index, other -> Triple(level, index, other.index) }
                }
                val best = best(others.size, options(others))
                val siblings = mutableListOf<Sibling>()
                for ((i, spot) in best.withIndex()) if (spot != null) {
                    val (level, index, other) = others[i]
                    siblings += Sibling(other, level, index, spot.x, spot.y - ys[level], null); shapes += spot.outline
                }
                return siblings
            }

            /** Each one's spots, in order, that are clear of everything else. */
            private fun options(others: List<Triple<Int, Int, Int>>) = others.map { (level, index) ->
                val (bx, by) = geo.other(ys, level, index); val group = "sibling$level.$index"
                listOf(0f, .45f, .9f).flatMap { shift ->
                    (generateSequence(by) { it + 3 }.takeWhile { it <= max(by,
                        ys[level] - 10) } + generateSequence(by - 3) { it - 3 }.takeWhile { it >= geo.otherTop })
                        .map { bx - shift * geo.spread to it }.toList()
                }.mapNotNull { (x, y) ->
                    val coin = TreeShape.circle(TreeShape.Kind.sibling, group, x, y, 6f)
                    val line = if (clear(coin)) branch(x, y, level, group) else return@mapNotNull null
                    if (line.any { b -> shapes.any { it.group != group && !meets(it, level) && it.overlaps(b, TreeGeometry.clearance) } }) null
                    else Spot(x, y, listOf(coin) + line)
                }
            }

            /** Then the most that fit together, the earlier ones first, each at its first spot that works. */
            private fun best(size: Int, options: List<List<Spot>>): Array<Spot?> {
                val chosen = arrayOfNulls<Spot>(size); var best = chosen.copyOf(); var kept = 0; var budget = 20_000
                fun search(i: Int, count: Int) {
                    if (count + size - i <= kept || budget <= 0) return
                    if (i == size) { best = chosen.copyOf(); kept = count; return }
                    for (spot in options[i]) {
                        if (kept == size || budget <= 0) return
                        budget -= 1 + i
                        if ((0 until i).all { j -> chosen[j]?.let { apart(it, spot) } ?: true }) {
                            chosen[i] = spot; search(i + 1, count + 1); chosen[i] = null
                        }
                    }
                    search(i + 1, count)
                }
                search(0, 0)
                return best
            }

            fun amounts(siblings: MutableList<Sibling>) {
                // Their amounts, left of each coin (on a halo): the list says them when there's no room.
                for ((index, sibling) in siblings.withIndex()) {
                    amount(sibling)?.let { (size, shape) -> siblings[index] = sibling.copy(amount = size); shapes += shape }
                }
            }

            /** [sibling]'s amount's size and outline, null when it doesn't fit. */
            private fun amount(sibling: Sibling): Pair<Float, TreeShape>? {
                val amount = w.money(s.others[sibling.other].amount); val y = ys[sibling.level] + sibling.dy
                val full = 9.5f * k; val width = measure.width(amount, full, false); val room = sibling.x - 14
                val size = if (width <= room) full else full * room / width
                if (size < full * .75f) return null
                val textWidth = measure.width(amount, size, false); val h = textHeight(size)
                val shape = TreeShape.box(TreeShape.Kind.amount, "sibling${sibling.level}.${sibling.slot}", sibling.x - 12 - textWidth, y - h / 2,
                    sibling.x - 8, y + h / 2)
                return if (clear(shape)) size to shape else null
            }

            fun caption(stubs: List<Stub>): Pair<List<String>?, Float> {
                // The caption, under the lowest stubs, while there are stubs to explain: centred above the round's block, else
                // reaching down beside it to the ground; left out when neither fits.
                var caption: List<String>? = null; var captionY = 0f
                if (n > 1 && stubs.isNotEmpty()) {
                    val size = 10f * k; val lh = 12.5f * k; val right = tx - geo.block / 2 - 9
                    val wrapped = TreeLabels.wrap(w.tr("Other people’s coins in this round"), right - 14) { measure.width(it, size, false) }
                    val width = wrapped.maxOf { measure.width(it, size, false) }
                    val top = ys[1] + 9; val height = wrapped.size * lh
                    val above = (geo.ground - geo.block - 5) - top
                    val spots = listOfNotNull(if (height <= above) top + (above - height) / 2 else null, if (height <= geo.ground - 3 - top) top else null)
                    if (width <= right - 14) spots.map { TreeShape.box(TreeShape.Kind.caption, "caption", right - width, it, right,
                        it + height) }.firstOrNull(::clear)?.let { shape ->
                        caption = wrapped; captionY = shape.t + lh / 2 - ys[1]; shapes += shape
                    }
                }
                return caption to captionY
            }
        }
    }
}
