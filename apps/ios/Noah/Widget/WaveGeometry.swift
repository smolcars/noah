import SwiftUI

/// The wave's line, its sea and the boat's frame (docs/native-rewrite/design/widgets.md, "The wave").
/// The line moves inside `band`; the sea runs from the line to the widget's bottom edge.
struct WaveGeometry {
    /// The longship mask's hull bottom, as a share of its height (rows 18–209 of 256), and its masthead.
    static let hull: CGFloat = 0.82
    static let masthead: CGFloat = 18 / 256
    /// Room above the line's highest point for a boat sailing there.
    static func headroom(_ side: CGFloat) -> CGFloat { ceil(0.76 * side) + 2 }
    /// Calm water's ripple amplitude.
    static let ripple: CGFloat = 1.4

    /// The boat sails on the line: its hull rests on the 2 pt stroke's upper edge, 1 pt above `waterline`.
    static func boat(x: CGFloat, waterline: CGFloat, side: CGFloat) -> CGRect {
        CGRect(x: x, y: waterline - hull * side - 1, width: side, height: side)
    }

    let line: Path
    let sea: Path
    let boat: CGRect
    /// The line's centre at its highest under the boat.
    let waterline: CGFloat

    // swiftlint:disable:next discouraged_optional_collection - nil: calm water
    init(values: [Int64]?, band: CGRect, size: CGSize, boat side: CGFloat, inset: CGFloat) {
        let boatX = size.width - inset - side
        var line = Path()
        if let values, values.count > 1 {
            let smooth = WidgetWave.swell(values.map(Double.init))
            let low = smooth.min() ?? 0, high = smooth.max() ?? 0
            // The maximum sits below the boat's headroom; the minimum 2 pt above the band's bottom.
            let top = Double(band.minY + Self.headroom(side))
            let span = max(0, Double(band.maxY - 2) - top)
            let left = Double(band.minX), right = Double(boatX - 2)
            let xs = smooth.indices.map { left + Double($0) / Double(smooth.count - 1) * (right - left) }
            let ys = smooth.map { top + (1 - (high > low ? ($0 - low) / (high - low) : 0.5)) * span }
            let tangents = WidgetWave.tangents(xs: xs, ys: ys)
            line.move(to: CGPoint(x: xs[0], y: ys[0]))
            for index in 0..<xs.count - 1 {
                let step = (xs[index + 1] - xs[index]) / 3
                line.addCurve(to: CGPoint(x: xs[index + 1], y: ys[index + 1]),
                              control1: CGPoint(x: xs[index] + step, y: ys[index] + tangents[index] * step),
                              control2: CGPoint(x: xs[index + 1] - step, y: ys[index + 1] - tangents[index + 1] * step))
            }
            // Flat at today's level under the boat to the edge.
            line.addLine(to: CGPoint(x: size.width, y: CGFloat(ys[ys.count - 1])))
            waterline = CGFloat(ys[ys.count - 1])
        } else {
            let level = band.minY + (band.height * 0.72).rounded()
            line = Self.calm(level: level, from: band.minX, to: size.width)
            waterline = level - Self.ripple // On the crests, so no trough lifts the line into the hull.
        }
        self.line = line
        sea = Self.sea(line, from: band.minX, size: size)
        boat = Self.boat(x: boatX, waterline: waterline, side: side)
    }

    /// A flat line with a gentle ripple.
    static func calm(level: CGFloat, from start: CGFloat, to end: CGFloat) -> Path {
        var line = Path()
        line.move(to: CGPoint(x: start, y: level))
        var x = start + 3
        while x <= end + 3 {
            line.addLine(to: CGPoint(x: x, y: level + ripple * sin((x - start) / 7)))
            x += 3
        }
        return line
    }

    static func sea(_ line: Path, from start: CGFloat, size: CGSize) -> Path {
        var sea = line
        sea.addLine(to: CGPoint(x: size.width, y: size.height))
        sea.addLine(to: CGPoint(x: start, y: size.height))
        sea.closeSubpath()
        return sea
    }
}
