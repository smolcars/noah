@testable import Noah
import Testing

/// Settings → VTXOs' expiry line: dots that meet stack upwards and the line grows to fit them, so none is
/// cut off at the top. Android's ExpiryLineStackTest checks the same numbers.
struct VtxoTimelineStackTests {
    /// A 353 pt line across a three-week lifetime, as the line places a dot by blocks left.
    static func x(_ left: Double) -> Double { 6 + (353 - 12) * left / 3_024 }

    static func place(_ amounts: [UInt64], lefts: [Double]) -> VtxoTimelineStack.Placement {
        VtxoTimelineStack.place(amounts: amounts) { index, _ in x(lefts[index]) }
    }

    /// Every ring, plus the margin, fits under the top edge.
    static func fits(_ placement: VtxoTimelineStack.Placement) -> Bool {
        let room = placement.height - VtxoTimelineStack.below - VtxoTimelineStack.margin
        return placement.dots.allSatisfy { $0.lift + $0.radius + VtxoTimelineStack.ring <= room + 1e-9 }
    }

    static func close(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 1e-9 }

    @Test func threeExpiringTogetherAllFitAndKeepTheirPlaces() {
        let lefts = [100.0, 140, 118]
        let placement = Self.place([149_750, 41_030, 149_750], lefts: lefts)
        let small = 3 + 7 * (41_030.0 / 149_750).squareRoot()
        #expect(placement.dots.map(\.index) == [0, 2, 1], "largest first, ties in list order")
        #expect(placement.dots.map(\.x) == [Self.x(100), Self.x(118), Self.x(140)], "never moved sideways")
        #expect(zip(placement.dots.map(\.lift), [10, 32.5, small + 45]).allSatisfy { Self.close($0.0, $0.1) })
        // At today's fixed 80 the third dot's centre sat above the top edge.
        #expect(placement.dots[2].lift > VtxoTimelineStack.minimumHeight - VtxoTimelineStack.below)
        #expect(Self.close(placement.height, 30 + small + 45 + small + 3.5 + 6))
        #expect(Self.fits(placement))
    }

    @Test func oneVtxoKeepsTodaysHeight() {
        let placement = Self.place([149_750], lefts: [100])
        #expect(placement.dots == [.init(index: 0, x: Self.x(100), radius: 10, lift: 10)], "resting on the baseline")
        #expect(placement.height == 80)
        #expect(Self.fits(placement))
    }

    @Test func manyEqualAtOneExpiryStopRisingAtTheCap() {
        let placement = Self.place(Array(repeating: 10_000, count: 12), lefts: Array(repeating: 100, count: 12))
        #expect(placement.dots.map(\.index) == Array(0..<12))
        #expect(placement.dots.allSatisfy { $0.x == Self.x(100) && $0.radius == 10 })
        // Each clears the one below by 1.5 until 60 steps (90 pt) is reached; the rest wait at the cap.
        #expect(placement.dots.map(\.lift) == [10, 32.5, 55, 77.5] + Array(repeating: 100, count: 8))
        #expect(placement.height == 30 + 100 + 10 + 3.5 + 6)
        #expect(Self.fits(placement))
    }
}
