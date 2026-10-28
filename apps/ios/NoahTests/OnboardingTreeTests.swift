import Foundation
@testable import Noah
import Testing

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
