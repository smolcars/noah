import CoreGraphics

/// The onboarding picture of a VTXO tree, built like bark's `Tree::new`
/// (`lib/src/tree/mod.rs`, RADIX 4): leaves first, then parents grouping up
/// to four children each, up to a single root. Coordinates are the
/// prototype's design points; leaves sit at `ys[0]`, the root at `ys[depth]`.
struct VtxoTree: Sendable {
    struct Node: Sendable, Equatable {
        var point: CGPoint
        /// Indices into the level below.
        var children: [Int] = []
        /// Index into the level above.
        var parent: Int?
    }

    static let radix = 4

    /// `levels[0]` holds the leaves, `levels[depth]` the root.
    let levels: [[Node]]

    var depth: Int { levels.count - 1 }
    var leaves: [Node] { levels[0] }
    var root: Node { levels[depth][0] }

    init(centerX: CGFloat, width: CGFloat, ys: [CGFloat], depth: Int) {
        precondition(depth >= 1 && ys.count > depth)
        let count = (0..<depth).reduce(1) { value, _ in value * Self.radix }
        var levels = [(0..<count).map { index in
            Node(point: CGPoint(x: centerX - width / 2 + CGFloat(index) * width / CGFloat(count - 1), y: ys[0]))
        }]
        for level in 1...depth {
            var current: [Node] = []
            for start in stride(from: 0, to: levels[level - 1].count, by: Self.radix) {
                let children = Array(start..<min(start + Self.radix, levels[level - 1].count))
                let x = children.reduce(0) { $0 + levels[level - 1][$1].point.x } / CGFloat(children.count)
                for child in children { levels[level - 1][child].parent = current.count }
                current.append(Node(point: CGPoint(x: x, y: ys[level]), children: children))
            }
            levels.append(current)
        }
        self.levels = levels
    }

    /// Node indices from the leaf up to the root, one per level.
    func ancestry(ofLeaf leaf: Int) -> [Int] {
        var indices = [leaf]
        var level = 0
        while let parent = levels[level][indices[level]].parent {
            indices.append(parent)
            level += 1
        }
        return indices
    }

    /// The exit branch: points from the root down to the leaf.
    func path(toLeaf leaf: Int) -> [CGPoint] {
        ancestry(ofLeaf: leaf).enumerated().map { levels[$0.offset][$0.element].point }.reversed()
    }
}
