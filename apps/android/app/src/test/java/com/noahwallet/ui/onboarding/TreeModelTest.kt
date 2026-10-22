package com.noahwallet.ui.onboarding

import org.junit.Assert.*
import org.junit.Test

class TreeModelTest {
    private val tree = VtxoTree.build(196.5f, 330f, listOf(214f, 318f, 410f, 496f), 3)

    @Test fun radixFourLevelsFromLeavesToOneRoot() {
        assertEquals(listOf(64, 16, 4, 1), tree.levels.map { it.size })
        assertEquals(3, tree.depth)
        assertSame(tree.levels[3][0], tree.root)
        // Every parent groups up to four consecutive children and sits at their mean x.
        for (level in 1..3) for (parent in tree.levels[level]) {
            assertTrue(parent.children.size in 1..VtxoTree.RADIX)
            assertEquals(parent.children.map { it.x }.average().toFloat(), parent.x, 1e-3f)
            parent.children.forEach { assertSame(parent, it.parent) }
        }
        assertNull(tree.root.parent)
        assertEquals(31.5f, tree.leaves.first().x, 1e-3f)
        assertEquals(361.5f, tree.leaves.last().x, 1e-3f)
    }

    @Test fun yourLeafsExitBranchRunsThroughItsParents() {
        val leaf = tree.leaves[41]
        val chain = generateSequence(leaf) { it.parent }.map { it.index }.toList()
        assertEquals(listOf(41, 10, 2, 0), chain)
        val path = pathOf(leaf)
        assertEquals(listOf(tree.root, tree.levels[2][2], tree.levels[1][10], leaf), path)
        assertEquals(TreePoint(tree.root.x, tree.root.y), along(path, 0f))
        assertEquals(leaf.x, along(path, 1f).x, 1e-3f)
        assertEquals(leaf.y, along(path, 1f).y, 1e-3f)
    }

    @Test fun linksHaveVerticalTangents() {
        val parent = tree.root
        val child = tree.levels[2][0]
        assertEquals(parent.point, bezier(parent, child, 0f))
        assertEquals(child.x, bezier(parent, child, 1f).x, 1e-3f)
        // Halfway the curve crosses the midpoint between the levels.
        assertEquals((parent.y + child.y) / 2, bezier(parent, child, .5f).y, 1e-3f)
        // Near the ends it leaves vertically: x barely moves while y moves.
        val start = bezier(parent, child, .01f)
        assertTrue(kotlin.math.abs(start.x - parent.x) < kotlin.math.abs(start.y - parent.y))
    }

    @Test fun conveyorRoundsAreDepthTwo() {
        val round = VtxoTree.build(110f, 150f, listOf(226f, 282f, 334f), 2)
        assertEquals(listOf(16, 4, 1), round.levels.map { it.size })
        assertEquals(listOf(10, 2, 0), generateSequence(round.leaves[10]) { it.parent }.map { it.index }.toList())
    }
}
