import CoreGraphics
import Foundation
import Testing
@testable import Noah

/// The onboarding tree is built like bark's `Tree::new` (RADIX 4).
struct OnboardingTreeTests {
    @Test func welcomeTreeGroupsByFourUpToOneRoot() {
        let tree = TreeScene.tree
        #expect(tree.depth == 3)
        #expect(tree.levels.map(\.count) == [64, 16, 4, 1])
        for level in 1...tree.depth {
            for node in tree.levels[level] { #expect(node.children.count == 4) }
        }
        // Every node below the root has exactly one parent, and parents list their children.
        for level in 0..<tree.depth {
            for (index, node) in tree.levels[level].enumerated() {
                guard let parent = node.parent else { Issue.record("Orphan at level \(level)"); continue }
                #expect(tree.levels[level + 1][parent].children.contains(index))
            }
        }
        #expect(tree.root.parent == nil)
    }

    @Test func yourLeafHangsFromBranchTenTwoAndTheRoot() {
        let tree = TreeScene.tree
        #expect(TreeScene.you == 41)
        #expect(tree.ancestry(ofLeaf: 41) == [41, 10, 2, 0])
        let path = tree.path(toLeaf: 41)
        #expect(path.count == 4)
        #expect(path.first == tree.root.point)
        #expect(path.last == tree.leaves[41].point)
        #expect(path[1] == tree.levels[2][2].point)
        #expect(path[2] == tree.levels[1][10].point)
    }

    @Test func positionsMatchThePrototype() {
        let tree = TreeScene.tree
        // Leaves spread over 330 pt around x 196.5 at y 214; the root sits on the chain at y 496.
        #expect(abs(tree.leaves[0].point.x - 31.5) < 0.001)
        #expect(abs(tree.leaves[63].point.x - 361.5) < 0.001)
        #expect(abs(tree.leaves[41].point.x - (31.5 + 41 * 330.0 / 63)) < 0.001)
        #expect(tree.leaves.allSatisfy { $0.point.y == 214 })
        #expect(abs(tree.root.point.x - 196.5) < 0.001)
        #expect(tree.root.point.y == 496)
        // A parent sits at the mean of its children.
        let parent = tree.levels[1][10]
        let mean = parent.children.map { tree.leaves[$0].point.x }.reduce(0, +) / 4
        #expect(abs(parent.point.x - mean) < 0.001)
    }

    @Test func conveyorTreesAreDepthTwo() {
        let tree = TreeScene.conveyorTree(110)
        #expect(tree.levels.map(\.count) == [16, 4, 1])
        #expect(tree.ancestry(ofLeaf: TreeScene.conveyorLeaf) == [10, 2, 0])
    }

    @Test func linksLeaveAndArriveVertically() {
        let parent = CGPoint(x: 0, y: 100), child = CGPoint(x: 50, y: 0)
        #expect(TreePainter.bezier(parent, child, 0) == parent)
        #expect(TreePainter.bezier(parent, child, 1) == child)
        // Control points share x with their ends, so the curve starts straight up.
        let early = TreePainter.bezier(parent, child, 0.01)
        #expect(abs(early.x - parent.x) < abs(early.y - parent.y))
    }

    @Test func restoredBalanceCountsUpToTheExactTotal() {
        let lit = TreeScene.found.map(\.leaf)
        #expect(TreeScene.restoredAmount(total: 152_742, lit: lit, at: 0) == 0)
        let middle = TreeScene.restoredAmount(total: 152_742, lit: lit, at: 1.2)
        #expect(middle > 0 && middle < 152_742)
        #expect(TreeScene.restoredAmount(total: 152_742, lit: lit, at: TreeScene.restoreReady) == 152_742)
        #expect(TreeScene.restoredAmount(total: 0, lit: [TreeScene.you], at: 10) == 0)
        // The sweep reaches every lit leaf before "Ready".
        for leaf in lit { #expect(TreeScene.foundAt(leaf) + 0.5 <= TreeScene.restoreReady) }
    }
}
