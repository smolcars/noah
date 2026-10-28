import SwiftUI

/// Shared pieces of the Send and Receive motion (prototype `spring`, `anim`, `badge`,
/// `padlock`, `blockStrip`), evaluated from elapsed time so a redraw after a pose change
/// lands on the same frame.
struct MotionTime {
    enum Spring {
        case snappy, soft, bouncy, tap, calm
        var response: Double { switch self { case .snappy: 0.30; case .soft: 0.50; case .bouncy: 0.40; case .tap: 0.12; case .calm: 0.70 } }
        var damping: Double { switch self { case .snappy: 0.85; case .soft: 1; case .bouncy: 0.60; case .tap: 0.80; case .calm: 1 } }
    }

    /// Seconds since the scene started.
    let t: Double
    /// Reduce Motion: travel jumps to its end state and only opacity fades, over 180 ms.
    let reduceMotion: Bool

    /// SwiftUI's `.spring(response:dampingFraction:)` at `elapsed` seconds.
    static func spring(_ elapsed: Double, _ kind: Spring) -> Double {
        guard elapsed > 0 else { return 0 }
        let w0 = 2 * Double.pi / kind.response, z = kind.damping
        if z < 1 {
            let root = (1 - z * z).squareRoot(), wd = w0 * root
            return 1 - exp(-z * w0 * elapsed) * (cos(wd * elapsed) + z / root * sin(wd * elapsed))
        }
        return 1 - exp(-w0 * elapsed) * (1 + w0 * elapsed)
    }

    /// Transform progress of an animation that starts at `start` (may overshoot).
    func p(_ start: Double, _ kind: Spring) -> Double {
        if reduceMotion { return t >= start ? 1 : 0 }
        return Self.spring(t - start, kind)
    }

    /// Opacity progress, 0…1.
    func o(_ start: Double, _ kind: Spring) -> Double {
        if reduceMotion { return min(1, max(0, (t - start) / 0.18)) }
        return min(1, max(0, Self.spring(t - start, kind)))
    }

    /// A timed curve, 0…1 (ease-out cubic); Reduce Motion makes it a 180 ms step.
    func timed(_ start: Double, _ duration: Double) -> Double {
        if reduceMotion { return min(1, max(0, (t - start) / 0.18)) }
        let x = min(1, max(0, (t - start) / duration))
        return 1 - pow(1 - x, 3)
    }

    static func mix(_ a: Double, _ b: Double, _ p: Double) -> Double { a + (b - a) * p }
}

/// The stage's colours from the theme. The receive card is always white, so its marks use fixed ink.
struct MotionColors {
    var foreground: Color
    var background: Color
    var soft: Color
    var muted: Color
    var hairline: Color
    var orange = NoahTokens.Colors.orange

    static func theme(_ scheme: ColorScheme) -> Self {
        let dark = scheme == .dark
        return Self(foreground: dark ? .white : .black, background: dark ? .black : .white,
                    soft: dark ? Color(hex: 0x1C1C1E) : Color(hex: 0xF2F2F2),
                    muted: dark ? Color(hex: 0x8F8F8F) : Color(hex: 0x707070),
                    hairline: dark ? .white.opacity(0.16) : .black.opacity(0.12))
    }

    static let card = Self(foreground: Color(hex: 0x0A0A0A), background: .white, soft: Color(hex: 0xF2F2F2),
                           muted: Color(hex: 0x6B6B6B), hairline: .black.opacity(0.12))
}

/// Draws in a design coordinate space (the prototype's SVG viewBox) scaled to fit.
struct MotionCanvas {
    var context: GraphicsContext
    let scale: CGFloat
    let origin: CGPoint
    let colors: MotionColors

    init(context: GraphicsContext, size: CGSize, viewBox: CGRect, colors: MotionColors) {
        self.context = context
        self.colors = colors
        scale = min(size.width / viewBox.width, size.height / viewBox.height)
        origin = CGPoint(x: (size.width - viewBox.width * scale) / 2 - viewBox.minX * scale,
                         y: (size.height - viewBox.height * scale) / 2 - viewBox.minY * scale)
    }

    func point(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: origin.x + x * scale, y: origin.y + y * scale) }

    /// A layer whose content is drawn around `center`, moved by `offset`, scaled and faded.
    func layer(center: CGPoint, opacity: Double, offset: CGSize = .zero, scale s: Double = 1, rotation: Double = 0,
               _ draw: (inout Self) -> Void) {
        guard opacity > 0.001, s > 0.001 else { return }
        var copy = self
        copy.context.opacity *= min(1, opacity)
        let pivot = point(center.x + offset.width, center.y + offset.height)
        copy.context.translateBy(x: pivot.x, y: pivot.y)
        if rotation != 0 { copy.context.rotate(by: .degrees(rotation)) }
        copy.context.scaleBy(x: s, y: s)
        copy.context.translateBy(x: -point(center.x, center.y).x, y: -point(center.x, center.y).y)
        draw(&copy)
    }

    func circle(_ x: Double, _ y: Double, _ r: Double) -> Path {
        Path(ellipseIn: CGRect(x: point(x, y).x - r * scale, y: point(x, y).y - r * scale, width: 2 * r * scale, height: 2 * r * scale))
    }

    func fill(_ path: Path, _ color: Color) { context.fill(path, with: .color(color)) }

    func stroke(_ path: Path, _ color: Color, _ width: Double, dash: [CGFloat] = [], phase: CGFloat = 0) {
        context.stroke(path, with: .color(color), style: StrokeStyle(
            lineWidth: width * scale, lineCap: .round, lineJoin: .round, dash: dash.map { $0 * scale }, dashPhase: phase * scale))
    }

    func line(_ a: CGPoint, _ b: CGPoint, progress: Double = 1) -> Path {
        var path = Path()
        path.move(to: point(a.x, a.y))
        path.addLine(to: point(a.x + (b.x - a.x) * progress, a.y + (b.y - a.y) * progress))
        return path
    }

    func text(_ string: String, _ x: Double, _ y: Double, size: Double, color: Color,
              weight: Font.Weight = .semibold, display: Bool = true) {
        let font: Font = display
            ? .custom(weight == .semibold ? "SpaceGrotesk-SemiBold" : "SpaceGrotesk-Medium", fixedSize: size * scale)
            : .system(size: size * scale, weight: weight)
        context.draw(Text(verbatim: string).font(font).foregroundColor(color), at: point(x, y), anchor: .center)
    }

    func symbol(_ name: String, _ x: Double, _ y: Double, size: Double, color: Color, weight: UIImage.SymbolWeight = .medium) {
        let configuration = UIImage.SymbolConfiguration(pointSize: size * scale, weight: weight)
        guard let symbol = UIImage(systemName: name, withConfiguration: configuration)?.withRenderingMode(.alwaysTemplate) else { return }
        var image = context.resolve(Image(uiImage: symbol))
        image.shading = .color(color)
        context.draw(image, at: point(x, y), anchor: .center)
    }

    // MARK: Coins and badges (prototype `coinGroup`, `badge`, `padlock`)

    /// Your coin: soft fill with an orange ring.
    func mineCoin(_ x: Double, _ y: Double, _ r: Double, ring: Double = 3) {
        fill(circle(x, y, r), colors.soft)
        stroke(circle(x, y, r), colors.orange, ring)
    }

    /// The piece you pay: a solid coin with ₿.
    func payCoin(_ x: Double, _ y: Double, _ r: Double, fill color: Color? = nil, ink: Color? = nil) {
        fill(circle(x, y, r), color ?? colors.foreground)
        text("₿", x, y + r * 0.02, size: r * 1.05, color: ink ?? colors.background)
    }

    /// You (orange person) or the Ark server, in a ring.
    func badge(server: Bool, _ x: Double, _ y: Double, r: Double = 20) {
        fill(circle(x, y, r), colors.background)
        stroke(circle(x, y, r), server ? colors.hairline : colors.orange, server ? 1.5 : 2)
        symbol(server ? "server.rack" : "person", x, y, size: r * 0.82, color: server ? colors.foreground : colors.orange)
    }

    func key(_ x: Double, _ y: Double) {
        symbol("key", x, y, size: 19, color: colors.orange, weight: .semibold)
    }

    /// Body and keyhole; the shackle is drawn by `shackle` so it can open.
    func padlockBody(_ x: Double, _ y: Double, ink: Color? = nil, paper: Color? = nil) {
        let rect = CGRect(x: point(x - 12, y - 2).x, y: point(x - 12, y - 2).y, width: 24 * scale, height: 18 * scale)
        let body = Path(roundedRect: rect, cornerRadius: 5 * scale)
        fill(body, paper ?? colors.background)
        stroke(body, ink ?? colors.foreground, 2.5)
        fill(circle(x, y + 7, 2.4), ink ?? colors.foreground)
    }

    func shackle(_ x: Double, _ y: Double, open: Double, ink: Color? = nil) {
        var path = Path()
        path.move(to: point(x - 8, y - 1))
        path.addLine(to: point(x - 8, y - 8))
        path.addArc(center: point(x, y - 8), radius: 8 * scale, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        path.addLine(to: point(x + 8, y - 1))
        // Opening lifts the shackle and turns it about its centre (prototype `unlock`).
        var lifted = self
        let center = point(x, y - 6)
        lifted.context.translateBy(x: center.x, y: center.y - 7 * scale * open)
        lifted.context.rotate(by: .degrees(18 * open))
        lifted.context.translateBy(x: -center.x, y: -center.y)
        lifted.stroke(path, ink ?? colors.foreground, 3)
    }

    /// The finale's check badge.
    func checkBadge(_ x: Double, _ y: Double) {
        fill(circle(x, y, 10), colors.foreground)
        var path = Path()
        path.move(to: point(x - 4.5, y))
        path.addLine(to: point(x - 1.5, y + 3))
        path.addLine(to: point(x + 4, y - 3))
        stroke(path, colors.background, 2.2)
    }
}

/// The block row, mempool.space style (send-receive.md, "Block row"): the three most
/// recent mined blocks with their heights, then a dashed "next" block holding your
/// transaction as an orange dot. When it confirms, the block fills, the dot turns into
/// the background colour and the row slides one block along.
struct ChainBlockRow {
    var x0: Double
    var y: Double
    var size: Double
    var tip: UInt32?
    /// Height your transaction confirmed in.
    var confirmedHeight: UInt32?
    /// When the transaction reached the "next" block (dot held), and when it confirmed.
    var heldAt: Double?
    var confirmedAt: Double?
    var blinking: Bool
    var nextLabel: String
    var locale: Locale

    var gap: Double { size + 12 }

    /// Where the transaction dot sits.
    var slot: CGPoint { CGPoint(x: x0 + 3 * gap + size / 2, y: y + size / 2) }

    private func height(_ value: UInt32?) -> String {
        value.map { $0.formatted(.number.locale(locale)) } ?? ""
    }

    func draw(_ canvas: MotionCanvas, time: MotionTime, opacity: Double, dot: Bool) {
        guard opacity > 0.001 else { return }
        var canvas = canvas
        canvas.context.opacity *= opacity
        let colors = canvas.colors
        let label = size > 34 ? 11.5 : 10.5
        let confirmed = confirmedHeight != nil && confirmedAt.map { time.t >= $0 } == true
        let mined = confirmed ? time.p(confirmedAt ?? 0, .snappy) : 0
        // The row slides one block along as the transaction confirms.
        let shift = -gap * mined
        let base = confirmed ? confirmedHeight.map { $0 &- 3 } : tip.map { $0 &- 2 }
        for index in 0..<3 {
            let x = x0 + Double(index) * gap + shift
            let h = confirmed ? base.map { $0 &+ UInt32(index) } : base.map { $0 &+ UInt32(index) }
            let fade = index == 0 && confirmed ? 1 - time.o(confirmedAt ?? 0, .snappy) : 1
            let rect = CGRect(x: canvas.point(x, y).x, y: canvas.point(x, y).y, width: size * canvas.scale, height: size * canvas.scale)
            canvas.fill(Path(roundedRect: rect, cornerRadius: 7 * canvas.scale), colors.foreground.opacity((0.55 + Double(index) * 0.15) * fade))
            if tip != nil || confirmed {
                canvas.text(height(h), x + size / 2, y + size + 14, size: label, color: colors.muted.opacity(fade), weight: .medium)
            }
        }
        // The block that held your transaction.
        let txX = x0 + 3 * gap + shift
        let txRect = CGRect(x: canvas.point(txX, y).x, y: canvas.point(txX, y).y, width: size * canvas.scale, height: size * canvas.scale)
        if confirmed {
            let bounce = 1 + 0.22 * (1 - time.p(confirmedAt ?? 0, .bouncy))
            var bounced = canvas
            let center = canvas.point(txX + size / 2, y + size / 2)
            bounced.context.translateBy(x: center.x, y: center.y)
            bounced.context.scaleBy(x: bounce, y: bounce)
            bounced.context.translateBy(x: -center.x, y: -center.y)
            bounced.fill(Path(roundedRect: txRect, cornerRadius: 6 * canvas.scale), colors.foreground)
            if dot { bounced.fill(bounced.circle(txX + size / 2, y + size / 2, 6), colors.background) }
            canvas.text(height(confirmedHeight), txX + size / 2, y + size + 14, size: label, color: colors.foreground, weight: .medium)
            // A fresh "next" block slides in behind it.
            let nextX = txX + gap
            let nextRect = CGRect(x: canvas.point(nextX, y).x, y: canvas.point(nextX, y).y, width: size * canvas.scale, height: size * canvas.scale)
            var next = canvas
            next.context.opacity *= time.o(confirmedAt ?? 0, .snappy)
            next.stroke(Path(roundedRect: nextRect, cornerRadius: 6 * canvas.scale), colors.foreground, 2, dash: [4, 4])
            next.text(nextLabel, nextX + size / 2, y + size + 14, size: label, color: colors.muted, weight: .medium)
        } else {
            var pending = canvas
            if blinking, !time.reduceMotion {
                // 650 ms each way between full and 30%.
                let phase = (time.t.truncatingRemainder(dividingBy: 1.3)) / 0.65
                pending.context.opacity *= phase < 1 ? 1 - 0.7 * phase : 0.3 + 0.7 * (phase - 1)
            }
            pending.stroke(Path(roundedRect: txRect, cornerRadius: 6 * canvas.scale), colors.foreground, 2, dash: [4, 4])
            canvas.text(nextLabel, txX + size / 2, y + size + 14, size: label, color: colors.muted, weight: .medium)
            if dot, let heldAt, time.t >= heldAt { canvas.fill(canvas.circle(slot.x, slot.y, 6), colors.orange) }
        }
    }
}
