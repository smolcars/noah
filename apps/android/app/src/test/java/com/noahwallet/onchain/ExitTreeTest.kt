package com.noahwallet.onchain

import org.junit.Assert.*
import org.junit.Test
import com.noahwallet.onchain.ExitGutter.St as st
import com.noahwallet.onchain.ExitScene.Mark as m

/** exit-v2-notes.md: the tree's geometry, its label column, the list's gutters, the counts and the motion's rest poses. */
class ExitTreeTest {
    private fun scene(vararg marks: ExitScene.Mark, heights: List<Long?> = marks.mapIndexed { i, mark -> if (mark == m.mined) 921_346L + i else null },
        phase: ExitStage.Phase = ExitStage.Phase.bump) = ExitScene(marks.size, phase, marks.toList(), heights)
    private fun geometry(n: Int, width: Float = 412f, height: Float = 300f, scale: Float = 1f) = TreeGeometry(width, height, n, scale, wide = false)
    private fun place(items: List<TreeLabels.Item>, scale: Float = 1f) =
        TreeLabels.place(items, 14 * scale, scale, 4f, 1_000f, { it.copy(top = 0f) }) { a, b -> if (a == b) "Block $a" else "Blocks $a–$b" }

    @Test fun theTreeStandsOnTheGroundWithYourCoinAtTheTop() {
        val geo = geometry(4)
        val ys = geo.levels { 0f }
        assertEquals("level 0 is the round's block", geo.roundY, ys[0], 0f)
        assertEquals("level n is your coin", geo.coinY, ys[4], .01f)
        assertTrue("levels climb from the ground", (1..4).all { ys[it] < ys[it - 1] })
        assertEquals("a four-transaction branch spaces evenly between its transactions", ys[1] - ys[2], ys[2] - ys[3], .01f)
        assertTrue(geo.trunk < geo.labels && geo.labels < geo.walletX)
    }

    @Test fun aLongBranchStacksItsMinedTransactionsButNeverBelowTheMinimumSpacing() {
        val geo = geometry(9, width = 375f, height = 236f)
        val unsent = geo.levels { 0f }
        val mined = geo.levels { if (it < 6) 1f else 0f }
        assertTrue("mined levels sit closer", mined[0] - mined[6] < unsent[0] - unsent[6])
        assertTrue("the waiting one and your coin get the room", mined[6] - mined[7] > unsent[6] - unsent[7])
        assertEquals("the coin stays at the top", geo.coinY, mined[9], .01f)
        for (lock in listOf(0f, 1f)) for (count in 0..9) {
            val ys = geo.levels(lock) { if (it < count) 1f else 0f }
            for (j in 1..9) assertTrue("gap $j with $count mined", ys[j - 1] - ys[j] >= geo.minimumGap(j, geo.node, geo.coinReserve(lock)) - .01f)
        }
        // A short branch never squeezes: it has the room at 26 dp a level.
        val four = geometry(4)
        assertArrayEquals(four.levels { 0f }, four.levels { 1f }, .01f)
    }

    @Test fun aPathLongerThanTheRoomFoldsInsteadOfSqueezing() {
        val probe = geometry(1)
        val cap = probe.capacity()
        assertTrue("a phone's picture holds a round's path and more", cap in 8..30)
        assertTrue("the dial's room leaves fewer", probe.capacity(1f) <= cap)
        // 100 transactions, 40 in blocks, the 41st waiting.
        val long = ExitScene(100, ExitStage.Phase.bump, List(100) { if (it < 40) m.mined else if (it == 40) m.waiting else m.unsent },
            List(100) { if (it < 40) 921_000L + it else null })
        val drawn = long.fold(cap)
        assertEquals(cap, drawn.n); assertEquals("counts stay real", 100, drawn.count)
        assertEquals("the oldest blocks are one stack at the base",
            0 until 40 - (drawn.spans!!.count { it.first == it.last && it.first < 40 }), drawn.spans.first())
        assertEquals(921_000L, drawn.oldest!!.first()); assertTrue(drawn.folded(0) && drawn.mined(0))
        assertEquals("the waiting one is drawn on its own", 40, drawn.real(drawn.waiting)); assertFalse(drawn.folded(drawn.waiting))
        assertEquals("the next two to send too", listOf(41..41, 42..42), drawn.spans.subList(drawn.waiting + 1, drawn.waiting + 3))
        assertTrue("the rest to send is one +N", drawn.folded(drawn.n - 2) && !drawn.mined(drawn.n - 2) && drawn.spans[drawn.n - 2].last == 98)
        assertEquals("and your coin", 99..99, drawn.spans.last())
        assertEquals("every transaction is in exactly one level", (0 until 100).toList(), drawn.spans.flatten())
        // A path that fits isn't touched; the explainer draws 8 at most and says the real count.
        val four = ExitScene.sample(4); assertSame(four, four.fold(cap))
        ExitScene.sample(100).fold(8).let {
            assertEquals(8, it.n)
            assertEquals(100, it.count)
            assertTrue(it.folded(6))
            assertEquals(0..0, it.spans!!.first())
        }
        // Mined to the coin: the stack and the latest blocks, then the coin.
        val done = ExitScene(50, ExitStage.Phase.wait, List(50) { m.mined }, List(50) { 900L + it }).fold(10)
        assertEquals(10, done.n); assertTrue(done.folded(0)); assertTrue((1 until 10).none { done.folded(it) }); assertEquals(49..49, done.spans!!.last())
        // Your other exits keep their level: where their branch leaves yours, folded or not.
        val shared = long.copy(others = listOf(ExitScene.Other(3, 1_000), ExitScene.Other(45, 2_000))).fold(cap)
        assertEquals(1, shared.others[0].level); assertEquals(44 in shared.spans!![shared.others[1].level - 1], true)
    }

    @Test fun otherPeoplesBranchesHideWhereLevelsArePackedTight() {
        val four = geometry(4); val ys = four.levels { 0f }
        assertTrue((1 until 4).all { four.stubs(ys, it).size == 3 })
        assertTrue("never at the coin", four.stubs(ys, 4).isEmpty())
        assertEquals("your other exit takes a stub's place", 2, four.stubs(ys, 2, others = 1).size)
        val deep = geometry(9, width = 375f, height = 236f); val packed = deep.levels { if (it < 7) 1f else 0f }
        assertTrue("a 9-transaction branch on a small phone hides the packed ones", (1 until 9).any { deep.stubs(packed, it).isEmpty() })
        for (j in 1 until 9) assertEquals(packed[j] - packed[j + 1] >= 20, deep.stubs(packed, j).isNotEmpty())
    }

    @Test fun labelsWrapBetweenWordsAndInsideOneOnlyWhenItIsTooWide() {
        val width = { s: String -> s.length * 5f }
        assertEquals(listOf("Other people's coins", "in this round"), TreeLabels.wrap("Other people's coins in this round", 100f, width))
        assertEquals(listOf("lägg till 3 765", "sats på", "blockkedjan"), TreeLabels.wrap("lägg till 3 765 sats på blockkedjan", 75f, width))
        assertEquals("a word wider than the room breaks inside", listOf("Unbr", "eaka", "ble", "word"), TreeLabels.wrap("Unbreakable word", 20f, width))
        assertEquals("text without spaces breaks between characters", listOf("このラウ", "ンドの他"), TreeLabels.wrap("このラウンドの他", 20f, width))
    }

    @Test fun labelsGoByPriorityAndNeverOverlap() {
        val line = { s: String -> listOf(TreeLabels.Line(s)) }
        val waiting = TreeLabels.Item(100f, listOf(TreeLabels.Line("Next block"), TreeLabels.Line("fee ₿1,215")), 4f, TreeLabels.Pill.orange)
        val coin = TreeLabels.Item(40f, line("Your VTXO"), 3.5f)
        val block = TreeLabels.Item(110f, line("Block 921,346"), 2.2f, blocks = 921_346L..921_346L)
        val placed = place(listOf(block, coin, waiting))
        // The block doesn't fit beside its square under the pill, so it says its height where there's room.
        assertEquals(listOf("Next block|fee ₿1,215", "Your VTXO", "range"), placed.map { it.key })
        assertEquals("Block 921346", placed.last().lines.single().text)
        for (a in placed) for (b in placed) if (a !== b) assertTrue("${a.key} overlaps ${b.key}", a.bottom < b.top || b.bottom < a.top)
        // The claim's label is right-aligned beside the stem: it never pushes the column's out.
        val claim = TreeLabels.Item(100f, line("Next block"), 4f, TreeLabels.Pill.orange, x = 300f)
        assertEquals(2, place(listOf(waiting, claim)).size)
        // A label that doesn't fit takes its first fallback that does: the round's caption on one line.
        val round = TreeLabels.Item(108f, line("The round,") + line("already on Bitcoin"), 2f,
            fallbacks = listOf(TreeLabels.Item(140f, line("The round, already on Bitcoin"), 2f, shrinks = true)))
        assertEquals("The round, already on Bitcoin", place(listOf(waiting, round)).single { it.priority == 2f }.lines.single().text)
        // Placed between the bounds, at its whole height.
        val low = TreeLabels.place(listOf(waiting.copy(y = 990f)), 14f, 1f, 4f, 1_000f, { it }) { _, _ -> "" }.single()
        assertTrue(low.bottom <= 1_000f && low.top >= 4f)
    }

    @Test fun theExitsRowLeavesOutZeroCounts() {
        fun stage(phase: ExitStage.Phase) = ExitStage(phase)
        assertEquals(ExitCounts.Counts(6, 1), ExitCounts.of(List(6) { stage(ExitStage.Phase.bump) } + stage(ExitStage.Phase.claimable)))
        assertEquals("claiming is still in progress", ExitCounts.Counts(1, 0), ExitCounts.of(listOf(stage(ExitStage.Phase.claiming))))
        assertEquals("finished ones count nowhere", ExitCounts.Counts(0, 0),
            ExitCounts.of(listOf(stage(ExitStage.Phase.claimed), stage(ExitStage.Phase.canceled))))
        assertEquals(ExitCounts.Counts(0, 2), ExitCounts.of(List(2) { stage(ExitStage.Phase.claimable) }))
    }

    private fun exit(id: String, state: String, txs: List<String>, statuses: List<String> = txs.map { "verify-inputs" }) =
        ExitModel(id, 1_000, state, ExitDetails(kind = "processing", tip_height = 100, transactions = txs.zip(statuses).map { (tx, kind) ->
            ExitDetails.Transaction(tx, ExitDetails.Status(kind, block = if (kind == "confirmed") ExitDetails.Block(99) else null)) }),
            emptyList(), emptyList(), txs.map { ExitPackage(it) })

    @Test fun onlyExitsThatShareTransactionsShareAGutter() {
        val a = exit("a", "Processing", listOf("root", "x", "leafA")); val b = exit("b", "Processing", listOf("root", "x", "leafB"))
        val c = exit("c", "Processing", listOf("r2", "y", "leafC")); val d = exit("d", "Claimed", listOf("root", "z", "leafD"))
        val shared = ExitList.shared(listOf(a, b, c, d))
        assertEquals(listOf(listOf(a, b), listOf(c)), ExitList.blocks(listOf(a, c, b), shared))
        assertEquals("a finished exit has its own row", listOf(listOf(d)), ExitList.blocks(listOf(d), shared))
        assertEquals("a group split across sections: each part alone", listOf(listOf(a)), ExitList.blocks(listOf(a), shared))
    }

    @Test fun aSharedGutterDrawsEachTransactionOnce() {
        val paths = listOf(listOf("root" to st.mined, "x" to st.waiting, "leafA" to st.unsent),
            listOf("root" to st.mined, "x" to st.waiting, "leafB" to st.unsent))
        val layout = ExitGutter.shared(paths, listOf(30f, 94f))
        assertEquals("root, x, and both coins", listOf("root", "x", "leafA", "leafB"), layout.nodes.map { it.id })
        assertEquals("a shared transaction sits between the rows it leads to", 62f, layout.nodes.single { it.id == "x" }.y, .01f)
        assertEquals(listOf(30f, 94f), layout.nodes.filter { it.coin }.map { it.y })
        assertTrue("coins sit at the right", layout.nodes.filter { it.coin }.all { it.x == 90f })
        assertEquals("each link once: round → root → x → each coin", 4, layout.links.size)
        assertTrue("ink only into mined ones", layout.links.first().ink && !layout.links.last().ink)
        assertEquals(62f, layout.root.second, .01f)
        // Nine transactions still end clear of the coins.
        val long = ExitGutter.shared(listOf(List(9) { "t$it" to st.unsent },
            List(9) { if (it < 3) "t$it" to st.unsent else "u$it" to st.unsent }), listOf(30f, 94f))
        assertTrue(long.nodes.filter { !it.coin }.all { it.x <= 90f - 14 })
    }

    @Test fun aFoldCoversEveryTransactionOnceAndKeepsEachOneBeingSent() {
        val (mined, current, unsent) = ExitFold.Kind.entries
        // Two exits sharing a long path, one a block behind the other: both transactions being sent keep their own places.
        val kinds = List(30) { mined } + listOf(current, current) + List(40) { unsent }
        for (cap in 4..12) {
            val spans = ExitFold.spans(kinds, cap)
            assertEquals("cap $cap", kinds.indices.toList(), spans.flatMap { it.toList() })
            assertTrue("cap $cap: ${spans.size}", spans.size <= cap)
            assertTrue(30..30 in spans && 31..31 in spans)
        }
        assertEquals("nothing folds where everything fits", List(5) { it..it }, ExitFold.spans(List(5) { unsent }, 5))
    }

    @Test fun aFoldedGutterKeepsWhatPathsShareInOnePlace() {
        // Three exits on a 60-transaction path, the third leaving it after 22: the 22 fold as one trunk (the oldest stacked)
        // drawn once, between all three rows, and the third's own blocks fold apart from the others'.
        val paths = listOf("a", "b", "c").map { r ->
            List(60) { i -> (if (i < 59 && (r != "c" || i < 22)) "t$i" else "$r$i") to when { i < 25 -> st.mined; i == 25 -> st.waiting; else -> st.unsent } }
        }
        val layout = ExitGutter.shared(paths, listOf(32f, 96f, 160f))
        val trunk = layout.nodes.filter { !it.coin && it.id.startsWith("t") && it.id.drop(1).toInt() < 22 }
        assertTrue("the oldest stacked", trunk.minBy { it.x }.let { it.folded && it.st == st.mined })
        assertEquals("up to the 22nd", "t21", trunk.maxBy { it.x }.id)
        for (node in trunk) assertEquals("${node.id} drawn once, between the rows", 96f, node.y, .01f)
        assertTrue("then the third's own, apart", layout.nodes.any { it.id.startsWith("c") && !it.coin && it.y == 160f })
    }

    @Test fun exitsThatShareMoreSitTogetherSoBranchesNeverMeet() {
        // A and A' share a branch after the first transaction, B leaves there: listed A, B, A', the branch's transactions would
        // sit on B's row, on top of B's own. Sat together they each keep their band.
        fun path(branch: String, r: Int) = List(20) { i -> (if (i == 0) "t0" else if (i < 10) "$branch$i" else "$branch$r.$i") to when { i < 6 ->
            st.mined
            i == 6 -> st.waiting
            else -> st.unsent
        } }
        val listed = listOf(path("a", 0), path("b", 1), path("a", 2))
        val rows = ExitGutter.tree(listed, { row -> row.dropLast(1).map { it.first } })
        assertEquals(listOf(listed[0], listed[2], listed[1]), rows)
        val drawn = ExitGutter.shared(rows, listOf(32f, 96f, 160f)).nodes.filter { !it.coin }
        for (a in drawn) for (b in drawn) if (a !== b) assertFalse("${a.id} on ${b.id}", kotlin.math.abs(a.x - b.x) < 10 && kotlin.math.abs(a.y - b.y) < 10)
    }

    @Test fun aPathThatPartsTooOftenFoldsEachExitAlone() {
        // Seven nine-transaction exits sharing a waiting root, each leaving the rest one transaction later: too many trunks
        // for the room, so each exit folds alone, every copy of the root waiting.
        val paths = (0 until 7).map { r -> List(9) { i -> (if (i <= r) "s$i" else "r$r.$i") to if (i == 0) st.waiting else st.unsent } }
        val drawn = ExitGutter.shared(paths, List(7) { 32f + 64f * it }).nodes.filter { !it.coin }
        val columns = drawn.map { it.x }.distinct().sorted()
        for ((a, b) in columns.zipWithNext()) assertTrue("$a → $b", b - a >= ExitGutter.minStep - .01f)
        assertEquals(7, drawn.count { it.id == "s0" && !it.folded && it.st == st.waiting })
    }

    @Test fun aRestartedExitNeverHidesASiblingsProgress() {
        // The first row restarted (Start: nothing listed yet); its sibling has the shared root in a block and the next one waiting.
        val paths = listOf(listOf("root" to st.unsent, "x" to st.unsent, "leafA" to st.unsent),
            listOf("root" to st.mined, "x" to st.waiting, "leafB" to st.unsent))
        assertEquals(mapOf("root" to st.mined, "x" to st.waiting, "leafA" to st.unsent, "leafB" to st.unsent), ExitGutter.states(paths))
        val layout = ExitGutter.shared(paths, listOf(30f, 94f))
        assertEquals(st.mined, layout.nodes.single { it.id == "root" }.st); assertEquals(st.waiting, layout.nodes.single { it.id == "x" }.st)
        assertTrue("the root's link is ink", layout.links.first().ink)
    }

    @Test fun theGutterReadsEachTransactionsState() {
        val e = exit("a", "Processing", listOf("root", "x", "y", "leaf"), listOf("confirmed", "awaiting-confirmation", "verify-inputs", "verify-inputs"))
        assertEquals(listOf(st.mined, st.waiting, st.unsent, st.unsent), ExitGutter.path(e, ExitStages.of(e, 100)).map { it.second })
        val ready = exit("a", "Processing", listOf("root", "x"), listOf("confirmed", "awaiting-cpfp-broadcast"))
        assertEquals("ready to send isn't in the mempool", st.ready, ExitGutter.path(ready, ExitStages.of(ready, 100)).last().second)
        val parked = exit("a", "Processing", listOf("root", "x"), listOf("confirmed", "awaiting-cpfp-broadcast"))
        assertEquals(st.needsFunds, ExitGutter.path(parked, ExitStages.of(parked, 100, needsFunds = true)).last().second)
        assertEquals(ExitGutter.After.lock, ExitGutter.after(ExitStage(ExitStage.Phase.wait)))
        assertEquals(ExitGutter.After.ready, ExitGutter.after(ExitStage(ExitStage.Phase.claimable)))
        assertEquals(ExitGutter.After.claimed, ExitGutter.after(ExitStage(ExitStage.Phase.claimed)))
        assertEquals(ExitGutter.After.canceled, ExitGutter.after(ExitStage(ExitStage.Phase.canceled)))
    }

    @Test fun withAnimationsOffThePictureIsItsRestPoseAtOnce() {
        val s = scene(m.mined, m.waiting, m.unsent, m.unsent)
        val still = TreeMotion(s, s)
        assertEquals(0f, still.seconds, 0f)
        for (t in listOf(0f, .5f)) {
            assertEquals(1f, still.mine(0, t), 0f); assertEquals(1f, still.send(1, t), 0f); assertEquals(0f, still.send(2, t), 0f)
            assertEquals(1f, still.grow(4, t), 0f); assertEquals(1f, still.labels(t), 0f); assertFalse(still.flash(t))
        }
        val breath = Breath({ .25f }, { 3f }, live = false)
        assertEquals("a steady glow", .6f, breath.breathe, 0f); assertEquals("dashes don't march", 0f, breath.march, 0f)
        assertEquals(1f, Breath({ .25f }, { 3f }, live = true).breathe, .001f)
    }
}
