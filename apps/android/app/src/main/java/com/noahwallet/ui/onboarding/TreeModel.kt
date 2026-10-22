package com.noahwallet.ui.onboarding

import kotlin.math.floor

internal data class TreePoint(val x: Float, val y: Float)

internal class TreeNode(val x: Float, val y: Float, val index: Int, val children: List<TreeNode> = emptyList()) {
    var parent: TreeNode? = null
        internal set
    val point get() = TreePoint(x, y)
}

/** Built like bark's `Tree::new` (lib/src/tree, RADIX 4): leaves first, then parents of up to four children, up to one root. */
internal class VtxoTree private constructor(val levels: List<List<TreeNode>>) {
    val depth get() = levels.size - 1
    val leaves get() = levels[0]
    val root get() = levels[depth][0]

    companion object {
        const val RADIX = 4

        /** [ys] holds the y of each level, leaves first; leaves are spread evenly over [width]. */
        fun build(centerX: Float, width: Float, ys: List<Float>, depth: Int): VtxoTree {
            var count = 1
            repeat(depth) { count *= RADIX }
            val levels = mutableListOf(List(count) { TreeNode(centerX - width / 2 + it * width / (count - 1), ys[0], it) })
            for (level in 1..depth) {
                levels += levels.last().chunked(RADIX).mapIndexed { index, children ->
                    TreeNode(children.map { it.x }.average().toFloat(), ys[level], index, children).also { node ->
                        children.forEach { it.parent = node }
                    }
                }
            }
            return VtxoTree(levels)
        }
    }
}

/** The exit branch of [leaf], root first. */
internal fun pathOf(leaf: TreeNode): List<TreeNode> = generateSequence(leaf) { it.parent }.toList().asReversed()

/** Cubic link with vertical tangents at both ends, as drawn between a parent and a child. */
internal fun bezier(parent: TreeNode, child: TreeNode, u: Float): TreePoint {
    val middle = (parent.y + child.y) / 2
    val a = 1 - u
    return TreePoint(
        a * a * a * parent.x + 3 * a * a * u * parent.x + 3 * a * u * u * child.x + u * u * u * child.x,
        a * a * a * parent.y + 3 * a * a * u * middle + 3 * a * u * u * middle + u * u * u * child.y,
    )
}

/** Position [q] (0…1) along a root-first path. */
internal fun along(path: List<TreeNode>, q: Float): TreePoint {
    val steps = path.size - 1
    val k = minOf(steps - 1, floor(q * steps).toInt())
    return bezier(path[k], path[k + 1], (q * steps - k).coerceIn(0f, 1f))
}
