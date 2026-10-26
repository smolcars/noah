import SwiftUI

/// Draws what waits for the next block (Approved item 8; prototype `glow`, `ping`, `march`): the
/// glow breathing at 1.6 s, two rings rippling out 0.8 s apart, the dashed outline marching and the
/// "Next block" pill pulsing with the breath. `back` goes under the tree (the node's own fill
/// covers the glow's middle), `front` over it. Without `animating` it's a steady 60 % glow.
enum ExitPulsePainter {
    static func back(_ context: GraphicsContext, kind: ExitPulse.Kind, center p: CGPoint, now: Double, breathe: Double, animating: Bool,
                     colors: MotionColors, strength: Double = 1) {
        switch kind {
        case .square(let side):
            glow(context, p, side * 1.6, (0.3 + 0.7 * breathe) * strength, colors)
            if animating {
                for index in 0..<2 {
                    let (q, e) = ring(now, index), h = side * (1 + 2.3 * e)
                    context.stroke(Path(roundedRect: CGRect(x: p.x - h / 2, y: p.y - h / 2, width: h, height: h), cornerRadius: 3.5 + 5 * e),
                                   with: .color(colors.orange.opacity(0.8 * pow(1 - q, 1.3) * strength)), lineWidth: 0.8 + 1.6 * (1 - q))
                }
            }
        case .coin(let r):
            glow(context, p, r * 2.1, (0.45 + 0.55 * breathe) * strength, colors)
            if animating {
                for index in 0..<2 {
                    let (q, e) = ring(now, index), radius = (r + 5) * (1 + 2.3 * e)
                    context.stroke(Path(ellipseIn: CGRect(x: p.x - radius, y: p.y - radius, width: radius * 2, height: radius * 2)),
                                   with: .color(colors.orange.opacity(0.8 * pow(1 - q, 1.3) * strength)), lineWidth: 0.8 + 1.6 * (1 - q))
                }
            }
        case .glow(let r):
            glow(context, p, r * 2.4, (0.4 + 0.6 * breathe) * strength, colors)
        case .pill(let size):
            let shape = Path(roundedRect: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height),
                             cornerRadius: size.height / 2)
            context.fill(shape, with: .color(colors.background))
            context.fill(shape, with: .color(colors.orange.opacity(0.06 + 0.16 * breathe)))
        case .dot, .alarm:
            break
        }
    }

    static func front(_ context: GraphicsContext, kind: ExitPulse.Kind, center p: CGPoint, now: Double, breathe: Double, animating: Bool,
                      colors: MotionColors) {
        switch kind {
        case .square(let side):
            context.stroke(Path(roundedRect: CGRect(x: p.x - side / 2, y: p.y - side / 2, width: side, height: side), cornerRadius: 3.5),
                           with: .color(colors.orange.opacity(0.7 + 0.3 * breathe)),
                           style: StrokeStyle(lineWidth: 1.8, dash: [3, 2.4], dashPhase: animating ? march(now, 5.4) : 0))
        case .coin(let r):
            context.stroke(Path(ellipseIn: CGRect(x: p.x - r - 5, y: p.y - r - 5, width: 2 * r + 10, height: 2 * r + 10)),
                           with: .color(colors.orange.opacity(0.6 + 0.4 * breathe)),
                           style: StrokeStyle(lineWidth: 1.6, dash: [3, 2.6], dashPhase: animating ? march(now, 5.6) : 0))
        case .pill(let size):
            let shape = Path(roundedRect: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height),
                             cornerRadius: size.height / 2)
            context.stroke(shape, with: .color(colors.orange.opacity(0.6 + 0.4 * breathe)), lineWidth: 1.4)
        case .dot:
            context.fill(Path(ellipseIn: CGRect(x: p.x - 3.4, y: p.y - 3.4, width: 6.8, height: 6.8)), with: .color(colors.orange.opacity(0.55 + 0.45 * breathe)))
        case .alarm(let r):
            context.stroke(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)),
                           with: .color(NoahTokens.Colors.failure.opacity(0.55 + 0.45 * breathe)), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
        case .glow:
            break
        }
    }

    /// A radial glow, no blur.
    static func glow(_ context: GraphicsContext, _ p: CGPoint, _ r: Double, _ alpha: Double, _ colors: MotionColors) {
        guard alpha > 0.001, r > 0 else { return }
        let gradient = Gradient(stops: [.init(color: colors.orange.opacity(0.42 * alpha), location: 0),
                                        .init(color: colors.orange.opacity(0.16 * alpha), location: 0.55),
                                        .init(color: colors.orange.opacity(0), location: 1)])
        context.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)),
                     with: .radialGradient(gradient, center: p, startRadius: 0, endRadius: r))
    }

    /// Ring `index`'s progress and its eased size.
    static func ring(_ now: Double, _ index: Int) -> (Double, Double) {
        let q = ExitPulse.ring(now, index)
        return (q, 1 - (1 - q) * (1 - q))
    }

    /// The dashed outline's offset: it marches 9 pt a second.
    static func march(_ now: Double, _ period: Double) -> Double { -(now * 9).truncatingRemainder(dividingBy: period) }
}

/// One pulse in a small overlay of its own, so only it redraws while it moves. It stops off-screen,
/// in the background and under Reduce Motion (`animating`).
struct ExitPulseView: View {
    let pulse: ExitPulse
    let front: Bool
    let animating: Bool
    let colors: MotionColors

    private var drawn: Bool {
        switch pulse.kind {
        case .dot, .alarm: front
        case .glow: !front
        default: true
        }
    }

    var body: some View {
        if drawn {
            let size = pulse.extent
            TimelineView(.animation(paused: !animating)) { timeline in
                Canvas { context, _ in
                    let now = timeline.date.timeIntervalSinceReferenceDate, center = CGPoint(x: size.width / 2, y: size.height / 2)
                    let breathe = ExitPulse.breathe(now, animating: animating)
                    if front {
                        ExitPulsePainter.front(context, kind: pulse.kind, center: center, now: now, breathe: breathe, animating: animating, colors: colors)
                    } else {
                        ExitPulsePainter.back(context, kind: pulse.kind, center: center, now: now, breathe: breathe, animating: animating, colors: colors)
                    }
                }
            }
            .frame(width: size.width, height: size.height)
            .position(pulse.center)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }
}
