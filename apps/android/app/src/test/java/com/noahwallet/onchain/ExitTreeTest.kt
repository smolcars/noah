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
    private fun place(items: List<TreeLabels.Item>, scale: Float = 1f) = TreeLabels.place(items, 14 * scale, scale) { a, b -> "Blocks $a–$b" }

    @Test fun theTreeStandsOnTheGroundWithYourCoinAtTheTop() {
        val geo = geometry(4)
        val ys = geo.levels { 0f }
        assertEquals("level 0 is the round's block", geo.roundY, ys[0], 0f)
        assertEquals("level n is your coin", geo.coinY, ys[4], .01f)
        assertTrue("levels climb from the ground", (1..4).all { ys[it] < ys[it - 1] })
        assertEquals("a four-transaction branch spaces evenly", ys[0] - ys[1], ys[3] - ys[4], .01f)
        assertTrue(geo.trunk < geo.labels && geo.labels < geo.walletX)
    }

    @Test fun aLongBranchStacksItsMinedTransactionsLikeAPileOfBlocks() {
        val geo = geometry(9, width = 375f, height = 236f)
        val unsent = geo.levels { 0f }
        val mined = geo.levels { if (it < 6) 1f else 0f }
        assertTrue("mined levels sit closer", mined[0] - mined[6] < unsent[0] - unsent[6])
        assertTrue("the waiting one and your coin get the room", mined[6] - mined[7] > unsent[6] - unsent[7])
        assertEquals("the coin stays at the top", geo.coinY, mined[9], .01f)
        // A short branch never squeezes: it has the room at 26 dp a level.
        val four = geometry(4)
        assertArrayEquals(four.levels { 0f }, four.levels { 1f }, .01f)
    }

    @Test fun otherPeoplesBranchesHideWhereLevelsArePackedTight() {
        val four = geometry(4); val ys = four.levels { 0f }
        assertTrue((1 until 4).all { four.stubs(ys, it) })
        assertFalse("never at the coin", four.stubs(ys, 4))
        val deep = geometry(9, width = 375f, height = 236f); val packed = deep.levels { if (it < 7) 1f else 0f }
        assertTrue("a 9-transaction branch on a small phone hides the packed ones", (1 until 9).any { !deep.stubs(packed, it) })
        for (j in 1 until 9) assertEquals(packed[j] - packed[j + 1] >= 20, deep.stubs(packed, j))
    }

    @Test fun theCaptionWrapsByWordsOrHides() {
        val width = { s: String -> s.length * 5f }
        assertEquals(listOf("Other people's coins", "in this round"), wrapWords("Other people's coins in this round", 100f, width))
        assertEquals("a word wider than the room stays whole, so the picture can hide it", listOf("Unbreakable", "word"), wrapWords("Unbreakable word", 20f, width))
        assertEquals("text without spaces breaks between characters", listOf("このラウ", "ンドの他"), wrapWords("このラウンドの他", 20f, width))
    }

    @Test fun labelsGoByPriorityAndNeverOverlap() {
        val line = { s: String -> listOf(TreeLabels.Line(s)) }
        val waiting = TreeLabels.Item(100f, listOf(TreeLabels.Line("Next block"), TreeLabels.Line("fee ₿1,215")), 4f, TreeLabels.Pill.orange)
        val coin = TreeLabels.Item(40f, line("Your VTXO"), 3.5f)
        val block = TreeLabels.Item(110f, line("Block 921,346"), 2.2f, height = 921_346)
        val placed = place(listOf(block, coin, waiting))
        assertEquals(listOf("Next block|fee ₿1,215", "Your VTXO"), placed.map { it.item.key })
        for (a in placed) for (b in placed) if (a !== b) assertTrue("${a.item.key} overlaps ${b.item.key}", a.bottom < b.top || b.bottom < a.top)
        // The claim's label is right-aligned beside the stem: it never pushes the column's out.
        val claim = TreeLabels.Item(100f, line("Next block"), 4f, TreeLabels.Pill.orange, x = 300f)
        assertEquals(2, place(listOf(waiting, claim)).size)
    }

    @Test fun minedTransactionsThatDontFitFoldIntoARange() {
        // Nine transactions on a small phone: six mined ones packed under the waiting one.
        val geo = geometry(9, width = 375f, height = 236f)
        val s = scene(m.mined, m.mined, m.mined, m.mined, m.mined, m.mined, m.waiting, m.unsent, m.unsent)
        val ys = geo.levels { if (s.mined(it)) 1f else 0f }
        val items = (1..6).map { j -> TreeLabels.Item(ys[j], listOf(TreeLabels.Line("Block ${s.heights[j - 1]}")), if (j == 6) 2.2f else 1f, height = s.heights[j - 1]) } +
            TreeLabels.Item(ys[7], listOf(TreeLabels.Line("Next block"), TreeLabels.Line("fee")), 4f, TreeLabels.Pill.orange) +
            TreeLabels.Item(geo.coinY, listOf(TreeLabels.Line("Your VTXO")), 3.5f)
        val placed = place(items)
        val keys = placed.map { it.item.key }
        assertTrue("the waiting one is never squeezed", "Next block|fee" in keys)
        assertTrue("nor your coin", "Your VTXO" in keys)
        val range = placed.single { it.item.key == "range" }.item.lines.single().text
        val (from, to) = range.removePrefix("Blocks ").split("–").map { it.toLong() }
        assertEquals(921_346L, from)
        for (height in s.heights.filterNotNull()) assertTrue("block $height is neither labelled nor in $range", "Block $height" in keys || height in from..to)
        for (a in placed) for (b in placed) if (a !== b && a.right == b.right) assertTrue("${a.item.key} overlaps ${b.item.key}", a.bottom + 3 < b.top || b.bottom + 3 < a.top)
    }

    @Test fun labelsScaleWithTheFontUpToAQuarter() {
        assertEquals(14f * 1.25f, geometry(4, scale = 1.25f).lineHeight, 0f)
        val big = TreeLabels.Item(100f, listOf(TreeLabels.Line("Next block"), TreeLabels.Line("fee")), 4f, TreeLabels.Pill.orange)
        assertTrue(TreeLabels.height(big, 14 * 1.25f, 1.25f) > TreeLabels.height(big, 14f, 1f))
    }

    @Test fun theDialTicksEverySixBlocks() {
        assertEquals("144 blocks read as a 24-hour clock", 24, ExitDial.ticks(144))
        assertEquals(16, ExitDial.ticks(96))
        assertTrue(ExitDial.major(144, 6)); assertFalse(ExitDial.major(144, 7))
        assertEquals("under 72 blocks there's no hour reading", 8, ExitDial.ticks(48)); assertFalse(ExitDial.major(48, 6))
        assertEquals("never more than 48", 48, ExitDial.ticks(2_016)); assertEquals(1, ExitDial.ticks(1))
    }

    @Test fun theExitsRowLeavesOutZeroCounts() {
        fun stage(phase: ExitStage.Phase) = ExitStage(phase)
        assertEquals(ExitCounts.Counts(6, 1), ExitCounts.of(List(6) { stage(ExitStage.Phase.bump) } + stage(ExitStage.Phase.claimable)))
        assertEquals("claiming is still in progress", ExitCounts.Counts(1, 0), ExitCounts.of(listOf(stage(ExitStage.Phase.claiming))))
        assertEquals("finished ones count nowhere", ExitCounts.Counts(0, 0), ExitCounts.of(listOf(stage(ExitStage.Phase.claimed), stage(ExitStage.Phase.canceled))))
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
        val paths = listOf(listOf("root" to st.mined, "x" to st.waiting, "leafA" to st.unsent), listOf("root" to st.mined, "x" to st.waiting, "leafB" to st.unsent))
        val layout = ExitGutter.shared(paths, listOf(30f, 94f))
        assertEquals("root, x, and both coins", listOf("root", "x", "leafA", "leafB"), layout.nodes.map { it.id })
        assertEquals("a shared transaction sits between the rows it leads to", 62f, layout.nodes.single { it.id == "x" }.y, .01f)
        assertEquals(listOf(30f, 94f), layout.nodes.filter { it.coin }.map { it.y })
        assertTrue("coins sit at the right", layout.nodes.filter { it.coin }.all { it.x == 90f })
        assertEquals("each link once: round → root → x → each coin", 4, layout.links.size)
        assertTrue("ink only into mined ones", layout.links.first().ink && !layout.links.last().ink)
        assertEquals(62f, layout.root.second, .01f)
        // Nine transactions still end clear of the coins.
        val long = ExitGutter.shared(listOf(List(9) { "t$it" to st.unsent }, List(9) { if (it < 3) "t$it" to st.unsent else "u$it" to st.unsent }), listOf(30f, 94f))
        assertTrue(long.nodes.filter { !it.coin }.all { it.x <= 90f - 14 })
    }

    @Test fun theGutterReadsEachTransactionsState() {
        val e = exit("a", "Processing", listOf("root", "x", "y", "leaf"), listOf("confirmed", "awaiting-confirmation", "verify-inputs", "verify-inputs"))
        assertEquals(listOf(st.mined, st.waiting, st.unsent, st.unsent), ExitGutter.path(e, ExitStages.of(e, 100)).map { it.second })
        val parked = exit("a", "Processing", listOf("root", "x"), listOf("confirmed", "awaiting-cpfp-broadcast"))
        assertEquals(st.needsFunds, ExitGutter.path(parked, ExitStages.of(parked, 100, needsFunds = true)).last().second)
        assertEquals(ExitGutter.After.lock, ExitGutter.after(ExitStage(ExitStage.Phase.wait)))
        assertEquals(ExitGutter.After.ready, ExitGutter.after(ExitStage(ExitStage.Phase.claimable)))
        assertEquals(ExitGutter.After.claimed, ExitGutter.after(ExitStage(ExitStage.Phase.claimed)))
        assertEquals(ExitGutter.After.canceled, ExitGutter.after(ExitStage(ExitStage.Phase.canceled)))
    }

    @Test fun theHouseSpringsSettleAndOnlyBouncyOvershoots() {
        for (spring in HouseSpring.entries) {
            assertEquals(0f, spring(-.1f), 0f); assertEquals(0f, spring(0f), 0f); assertEquals(1f, spring(spring.seconds), 0f)
            assertTrue("${spring.name} settles within 3 s", spring.seconds in .1f..3f)
        }
        assertTrue((1..100).any { HouseSpring.bouncy(it / 100f) > 1.01f })
        assertTrue((1..300).all { HouseSpring.soft(it / 100f) <= 1f })
        assertTrue("snappy is quicker than calm", HouseSpring.snappy.seconds < HouseSpring.calm.seconds)
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

    @Test fun aBlockMinesTheWaitingOneThenTheNextIsSent() {
        val before = scene(m.mined, m.waiting, m.unsent, m.unsent)
        val after = scene(m.mined, m.mined, m.waiting, m.unsent)
        val move = TreeMotion(before, after)
        assertEquals(0f, move.mine(1, 0f), 0f); assertEquals(0f, move.send(2, .1f), 0f)
        assertTrue("mined first", move.mine(1, .3f) > .5f)
        assertTrue("then the next one goes to the mempool", move.send[2] > move.mine[1])
        assertEquals(1f, move.mine(1, move.seconds), 0f); assertEquals(1f, move.send(2, move.seconds), 0f)
        assertEquals("it settles from 1.22×", 1f, move.stamp(1, move.seconds), 0f)
        assertTrue("one ripple, then gone", move.ripple(1, .4f) in .1f..<1f); assertEquals(1f, move.ripple(1, move.seconds), 0f)
    }

    @Test fun theClaimLandsAfterItsBlock() {
        val base = scene(m.mined, m.mined, m.mined, m.mined)
        val claiming = base.copy(phase = ExitStage.Phase.claiming, lockedFrom = 100, claimableHeight = 244, tip = 244)
        val claimed = claiming.copy(phase = ExitStage.Phase.claimed, claimedBlock = 245)
        val sent = TreeMotion(base.copy(phase = ExitStage.Phase.claimable, lockedFrom = 100, claimableHeight = 244, tip = 244), claiming)
        assertTrue("the tree dims and the stem draws", sent.dim(sent.seconds) == 1f && sent.stem(sent.seconds) == 1f)
        assertTrue("the claim goes out after the stem starts", sent.claimSend(.2f) == 0f && sent.claimSend(sent.seconds) == 1f)
        val land = TreeMotion(claiming, claimed)
        assertEquals(0f, land.land(.4f), 0f)
        assertTrue("the coin travels after the claim is mined", land.land(1.2f) > 0f)
        assertEquals(0f, land.arrive(1.2f), 0f)
        assertEquals(1f, land.land(land.seconds), 0f); assertEquals(1f, land.ring(land.seconds), 0f)
        assertEquals("a claim to another wallet swaps the badge", 1f, TreeMotion(claiming, claimed.copy(destination = "bc1q")).swap(1f), 0f)
    }

    @Test fun theExplainerGrowsOnceThenItsLabelsFadeIn() {
        val explainer = ExitScene.sample()
        val open = TreeMotion(null, explainer)
        assertEquals("1.4 s for four transactions", 1.39f, open.growSeconds, .001f)
        assertEquals(0f, open.grow(1, 0f), 0f); assertEquals(1f, open.grow(4, open.growSeconds), 0f)
        assertTrue("root first", open.grow(1, .2f) > open.grow(3, .2f))
        assertEquals(0f, open.labels(1f), 0f); assertEquals(1f, open.labels(open.seconds), 0f)
        assertEquals("seen again, it's grown", 1f, TreeMotion(explainer, explainer).grow(1, 0f), 0f)
        val start = ExitScene(4, ExitStage.Phase.lift, List(4) { m.unsent })
        assertEquals("before it's sent, only the ghost shows", 0f, TreeMotion(start, start).pop(1, 0f), 0f)
        assertEquals(.5f, TreeMotion(start, start).ghost(0f), 0f)
    }
}
