import SwiftUI
import WidgetKit

/// The wave's line, its sea and the boat's frame (docs/native-rewrite/design/widgets.md, "The wave").
/// The line moves inside `band`; the sea runs from the line to the widget's bottom edge.
struct WaveGeometry {
    let line: Path
    let sea: Path
    let boat: CGRect

    // swiftlint:disable:next discouraged_optional_collection - nil: calm water
    init(values: [Int64]?, band: CGRect, size: CGSize, boat side: CGFloat, inset: CGFloat) {
        let boatX = size.width - inset - side
        var line = Path()
        let level: CGFloat
        if let values, values.count > 1 {
            let smooth = WidgetWave.swell(values.map(Double.init))
            let low = smooth.min() ?? 0, high = smooth.max() ?? 0
            // The maximum sits below the boat's headroom; the minimum 2 pt above the band's bottom.
            let top = Double(band.minY + ceil(0.63 * side) + 1)
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
            level = CGFloat(ys[ys.count - 1])
            // Flat at today's level under the boat to the edge.
            line.addLine(to: CGPoint(x: size.width, y: level))
        } else {
            level = band.minY + (band.height * 0.72).rounded()
            line = Self.calm(level: level, from: band.minX, to: size.width)
        }
        self.line = line
        sea = Self.sea(line, from: band.minX, size: size)
        boat = CGRect(x: boatX, y: level - 0.7 * side, width: side, height: side)
    }

    /// A flat line with a gentle ripple.
    static func calm(level: CGFloat, from start: CGFloat, to end: CGFloat) -> Path {
        var line = Path()
        line.move(to: CGPoint(x: start, y: level))
        var x = start + 3
        while x <= end + 3 {
            line.addLine(to: CGPoint(x: x, y: level + 1.4 * sin((x - start) / 7)))
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

/// The sea's colours: the line, and a fill from 28% at the band's top to nothing at the bottom edge.
struct WaveStroke: View {
    let line: Path
    let sea: Path
    let bandTop: CGFloat
    let height: CGFloat
    let look: WidgetLook

    var body: some View {
        ZStack {
            sea.fill(LinearGradient(colors: [look.wave.opacity(0.28), look.wave.opacity(0)],
                                    startPoint: UnitPoint(x: 0.5, y: height > 0 ? bandTop / height : 0), endPoint: .bottom))
            line.stroke(look.wave, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
        .widgetAccentable()
    }
}

/// The wave across a widget of `size`, with the longship riding at today. Decorative and private:
/// redacted (a locked Lock Screen or StandBy) it's calm water.
struct WaveLayer: View {
    let values: [Int64]? // swiftlint:disable:this discouraged_optional_collection - nil: calm water
    let band: CGRect
    let size: CGSize
    let boat: CGFloat
    let inset: CGFloat
    let look: WidgetLook
    @Environment(\.redactionReasons) private var redaction

    var body: some View {
        let geometry = WaveGeometry(values: redaction.contains(.privacy) ? nil : values, band: band, size: size,
                                    boat: boat, inset: inset)
        ZStack(alignment: .topLeading) {
            WaveStroke(line: geometry.line, sea: geometry.sea, bandTop: band.minY, height: size.height, look: look)
            Longship(look: look)
                .frame(width: geometry.boat.width, height: geometry.boat.height)
                .offset(x: geometry.boat.minX, y: geometry.boat.minY)
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .privacySensitive()
        .accessibilityHidden(true)
    }
}

/// The longship mask in the foreground colour.
struct Longship: View {
    let look: WidgetLook

    var body: some View {
        Image(.longship).renderingMode(.template).resizable().scaledToFit()
            .foregroundStyle(look.foreground)
            .accessibilityHidden(true)
    }
}

/// No wallet: just the longship, centred, on calm water.
struct NoWalletView: View {
    let look: WidgetLook

    var body: some View {
        GeometryReader { proxy in
            let side = min(60, proxy.size.height * 0.4)
            let level = proxy.size.height / 2 + 0.2 * side
            let line = WaveGeometry.calm(level: level, from: 0, to: proxy.size.width)
            ZStack(alignment: .topLeading) {
                WaveStroke(line: line, sea: WaveGeometry.sea(line, from: 0, size: proxy.size), bandTop: level - 8,
                           height: proxy.size.height, look: look)
                Longship(look: look)
                    .frame(width: side, height: side)
                    .offset(x: (proxy.size.width - side) / 2, y: level - 0.7 * side)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Colours for the rendering mode: as designed in full colour; the system's tint or white otherwise
/// (Tinted, Clear and the Lock Screen), with opacity for the muted levels.
struct WidgetLook {
    let fullColor: Bool

    init(_ mode: WidgetRenderingMode) { fullColor = mode == .fullColor }

    var foreground: Color { fullColor ? NoahTokens.Colors.foreground : .primary }
    var muted: Color { fullColor ? NoahTokens.Colors.muted : .secondary }
    var soft: Color { fullColor ? NoahTokens.Colors.soft : Color.primary.opacity(0.14) }
    /// Bitcoin orange: it's you, and never an amount.
    var wave: Color { fullColor ? NoahTokens.Colors.orange : .primary }
    var chipText: Color { fullColor ? NoahTokens.Colors.orangeText : .primary }
    var chipFill: Color { fullColor ? NoahTokens.Colors.orangeTint : Color.primary.opacity(0.18) }
}
