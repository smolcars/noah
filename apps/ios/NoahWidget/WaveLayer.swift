import SwiftUI
import WidgetKit

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
            // The boat and the water it sits on, centred.
            let level = proxy.size.height / 2 + 0.4 * side + 2
            let line = WaveGeometry.calm(level: level, from: 0, to: proxy.size.width)
            ZStack(alignment: .topLeading) {
                WaveStroke(line: line, sea: WaveGeometry.sea(line, from: 0, size: proxy.size), bandTop: level - 8,
                           height: proxy.size.height, look: look)
                let boat = WaveGeometry.boat(x: (proxy.size.width - side) / 2, waterline: level - WaveGeometry.ripple, side: side)
                Longship(look: look)
                    .frame(width: side, height: side)
                    .offset(x: boat.minX, y: boat.minY)
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
