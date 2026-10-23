import SwiftUI

/// Who the payment goes to, as the stage and the screens show it: an initial for people
/// and addresses, a glyph otherwise (prototype `REC`, `avHtml`).
struct SendRecipientLabel: Equatable {
    enum Avatar: Equatable { case initial(String), ark, lightning, chain, request }
    /// Verbatim name or address, or a source string to localize.
    var name: String
    var nameIsVerbatim: Bool
    var avatar: Avatar

    var glyph: String? {
        switch avatar {
        case .initial: nil
        case .ark: "sailboat"
        case .lightning: "bolt"
        case .chain: "link"
        case .request: "qrcode"
        }
    }

    /// Long addresses read as their ends: "bc1qxy…0h7r3".
    static func short(_ value: String) -> String {
        value.count > 16 ? String(value.prefix(6)) + "…" + String(value.suffix(4)) : value
    }

    /// From the destination as typed (a Lightning address keeps its name), or the reviewed payment.
    static func of(destination: String, payment: SendPayment? = nil, ownDomain: String) -> Self {
        let typed = SendDestination.cleaned(destination)
        let metadata = payment?.repeatMetadata.flatMap { RepeatPayment.normalizeLightningAddress(Self.metadataDestination($0) ?? "") }
        if let address = metadata ?? (SendDestination.isLightningAddress(typed) ? typed.lowercased() : nil)
            ?? payment.flatMap({ SendDestination.isLightningAddress($0.destination) ? $0.destination.lowercased() : nil }) {
            let parts = address.split(separator: "@").map(String.init)
            let noahUser = parts.count == 2 && parts[1] == ownDomain.lowercased()
            return Self(name: noahUser ? parts[0] : address, nameIsVerbatim: true,
                        avatar: .initial(String(parts[0].prefix(1)).lowercased()))
        }
        let parsed = SendDestination.parse(typed.isEmpty ? payment?.destination ?? "" : typed)
        switch parsed.destinationType ?? payment?.method {
        case "bip321": return Self(name: "Payment request", nameIsVerbatim: false, avatar: .request)
        case "lightning": return Self(name: "Lightning invoice", nameIsVerbatim: false, avatar: .lightning)
        case "offer": return Self(name: "Lightning offer", nameIsVerbatim: false, avatar: .lightning)
        case "lnurl": return Self(name: short(typed), nameIsVerbatim: true, avatar: .lightning)
        case "onchain": return Self(name: short(typed.isEmpty ? payment?.destination ?? "" : typed), nameIsVerbatim: true, avatar: .chain)
        case "ark": return Self(name: short(typed.isEmpty ? payment?.destination ?? "" : typed), nameIsVerbatim: true, avatar: .ark)
        default: return Self(name: short(typed.isEmpty ? payment?.destination ?? "" : typed), nameIsVerbatim: true, avatar: .lightning)
        }
    }

    private static func metadataDestination(_ json: String) -> String? {
        guard let object = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let noah = object["noah"] as? [String: Any], let details = noah["repeat_payment"] as? [String: Any]
        else { return nil }
        return details["destination"] as? String
    }
}

/// The sending stage (prototype `startSend`, `arkSend`, `lnSend`, `chainSend`): your coin
/// splits, the server and you sign, the padlock, bolt and key, the refund, and the block
/// row. Drawn from the motion clock, so a pose change continues where it was.
struct SendStageView: View {
    let rail: SendMotion.Rail
    let clock: SendMotionClock
    let recipient: SendRecipientLabel
    var tip: UInt32?
    var nextLabel: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @State private var settled = false

    var body: some View {
        // Read the clock here, so its changes redraw the stage even when the timeline is paused.
        let state = SendStagePainter.State(
            rail: rail, outcome: clock.outcome, submittedAt: clock.submittedAt, outcomeAt: clock.since(clock.outcomeAt),
            confirmedAt: clock.since(clock.confirmedAt), confirmedHeight: clock.confirmedHeight, tip: tip, recipient: recipient)
        TimelineView(.animation(paused: settled || scenePhase != .active)) { timeline in
            Canvas { context, size in
                var painter = SendStagePainter(
                    canvas: MotionCanvas(context: context, size: size, viewBox: CGRect(x: 20, y: 10, width: 320, height: 230),
                                         colors: .theme(colorScheme)),
                    state: state, reduceMotion: reduceMotion, nextLabel: nextLabel, locale: locale)
                painter.draw(at: state.submittedAt.map { max(0, timeline.date.timeIntervalSince($0)) } ?? 0)
            }
        }
        .task(id: Settle(state: state, reduceMotion: reduceMotion)) {
            // Stop redrawing once nothing moves any more; a new outcome, block or motion setting wakes it.
            settled = false
            guard let end = SendStagePainter.settleTime(state, reduceMotion: reduceMotion) else { return }
            let wait = end - (state.submittedAt.map { Date().timeIntervalSince($0) } ?? 0)
            if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
            settled = true
        }
        .accessibilityHidden(true)
    }

    /// What decides when the stage settles: its state and Reduce Motion.
    private struct Settle: Equatable {
        var state: SendStagePainter.State
        var reduceMotion: Bool
    }
}

struct SendStagePainter {
    struct State: Equatable {
        var rail: SendMotion.Rail
        var outcome: SendMotion.Outcome
        var submittedAt: Date?
        var outcomeAt: Double?
        var confirmedAt: Double?
        var confirmedHeight: UInt32?
        var tip: UInt32?
        var recipient: SendRecipientLabel
    }

    var canvas: MotionCanvas
    let state: State
    let reduceMotion: Bool
    let nextLabel: String
    let locale: Locale

    private static let coin = CGPoint(x: 150, y: 118), dest = CGPoint(x: 300, y: 118), pay = CGPoint(x: 190, y: 118)
    private static let server = CGPoint(x: 246, y: 44)

    /// When the frame stops changing, in seconds from submission; nil while something loops.
    static func settleTime(_ state: State, reduceMotion: Bool) -> Double? {
        // Reduce Motion keeps no loops: in flight (or still paying), the stage holds after its fades.
        if reduceMotion, state.outcome == .inFlight || state.outcome == .pending { return SendMotion.reducedInFlightEnd }
        guard let outcomeAt = state.outcomeAt else { return nil }
        let ending = SendMotion.endingStart(rail: state.rail, outcomeAt: outcomeAt)
        switch state.outcome {
        case .inFlight, .pending: return nil
        case .stopped: return outcomeAt + 0.6
        case .refunded: return ending + 2.4
        case .succeeded:
            switch state.rail {
            case .ark, .lightning: return ending + 3
            case .onchainSwap, .onchainWallet:
                // The "next" block blinks while waiting.
                guard reduceMotion || state.confirmedAt != nil else { return nil }
                return max(ending + 2, (state.confirmedAt ?? 0) + 1.5)
            }
        }
    }

    mutating func draw(at elapsed: Double) {
        var t = elapsed
        let outcomeAt = state.outcomeAt ?? .infinity
        var stage = canvas
        if state.outcome == .stopped {
            // Unknown or failed without a refund: the stage holds still and steps back.
            t = min(t, outcomeAt)
            stage.context.opacity *= 1 - 0.65 * min(1, max(0, (elapsed - outcomeAt) / 0.4))
        }
        let time = MotionTime(t: t, reduceMotion: reduceMotion)
        let ending: Double = switch state.outcome {
        case .succeeded, .refunded: SendMotion.endingStart(rail: state.rail, outcomeAt: outcomeAt)
        default: .infinity
        }
        Frame(canvas: stage, time: time, ending: ending, state: state, nextLabel: nextLabel, locale: locale,
              confirmedAt: state.confirmedAt).draw()
    }

    /// One frame. Element timings are the prototype's.
    private struct Frame {
        let canvas: MotionCanvas
        let time: MotionTime
        let ending: Double
        let state: State
        let nextLabel: String
        let locale: Locale
        let confirmedAt: Double?

        var t: Double { time.t }
        var rail: SendMotion.Rail { state.rail }
        var refunded: Bool { state.outcome == .refunded }
        var succeeded: Bool { state.outcome == .succeeded }
        typealias M = MotionTime

        func draw() {
            drawChain()
            drawCoins()
            switch rail {
            case .ark: drawArk()
            case .lightning: drawLightning()
            case .onchainSwap: drawSwap()
            case .onchainWallet: drawWalletSend()
            }
            drawDestination()
        }

        // MARK: Your coin, the payment and your change

        private var split: Double { 0.42 }

        func drawCoins() {
            let c = SendStagePainter.coin
            if rail != .onchainWallet {
                // Your coin appears, then splits into the payment and your change.
                let appear = time.p(0, .bouncy), gone = time.p(split, .snappy)
                canvas.layer(center: c, scale: M.mix(0.7, 1, appear) * M.mix(1, 0.82, gone),
                             opacity: time.o(0, .bouncy) * (1 - time.o(split, .snappy))) { $0.mineCoin(c.x, c.y, 44) }
                let roll = time.p(1.05, .soft)
                let out = time.p(split, .bouncy)
                canvas.layer(center: c, offset: CGSize(width: M.mix(0, -62, out) + M.mix(0, -78, roll), height: M.mix(0, 34, out) + M.mix(0, 62, roll)),
                             scale: M.mix(0.5, 1, out) * M.mix(1, 0.6, roll),
                             opacity: time.o(split, .bouncy) * (1 - time.o(1.05, .soft))) { $0.mineCoin(c.x, c.y, 20) }
            } else {
                // From the on-chain wallet there is no Ark coin: the transaction is the motion.
                let appear = time.p(0, .bouncy)
                canvas.layer(center: c, scale: M.mix(0.7, 1, appear) * M.mix(1, 0.3, time.p(ending, .soft)),
                             opacity: time.o(0, .bouncy) * (1 - time.o(ending, .soft))) { $0.payCoin(c.x, c.y, 30) }
            }
        }

        /// The payment piece: where it is and how visible.
        func payLayer(offset: CGSize, scale: Double, opacity: Double) {
            let c = SendStagePainter.coin
            let out = time.p(split, .bouncy)
            canvas.layer(center: c, offset: CGSize(width: M.mix(0, 40, out) + offset.width, height: offset.height),
                         scale: M.mix(0.5, 1, out) * scale, opacity: time.o(split, .bouncy) * opacity) { $0.payCoin(c.x, c.y, 30) }
        }

        // MARK: Ark: you and the server sign, then it's delivered

        func drawArk() {
            let x = SendStagePainter.pay.x, y = SendStagePainter.pay.y
            let fade = 1 - time.o(ending, .snappy)
            canvas.layer(center: CGPoint(x: x - 70, y: y - 72), scale: M.mix(0.4, 1, time.p(1.15, .bouncy)),
                         opacity: time.o(1.15, .bouncy) * fade) { $0.badge(server: false, x - 70, y - 72) }
            canvas.layer(center: CGPoint(x: x + 70, y: y - 72), scale: M.mix(0.4, 1, time.p(1.26, .bouncy)),
                         opacity: time.o(1.26, .bouncy) * fade) { $0.badge(server: true, x + 70, y - 72) }
            var lines = canvas
            lines.context.opacity *= fade
            let first = time.timed(1.57, 0.32), second = time.timed(1.73, 0.32)
            if first > 0 { lines.stroke(lines.line(CGPoint(x: x - 58, y: y - 58), CGPoint(x: x - 17, y: y - 24), progress: first), lines.colors.orange, 2) }
            if second > 0 { lines.stroke(lines.line(CGPoint(x: x + 58, y: y - 58), CGPoint(x: x + 17, y: y - 24), progress: second), lines.colors.foreground, 2) }
            // The seal: a ring settles around the payment; while the call runs it pulses gently.
            if t >= 2.05 {
                var ring = lines
                ring.context.opacity *= 0.5 * time.o(2.05, .snappy)
                ring.stroke(ring.circle(x, y, 34), ring.colors.foreground, 1.5)
                if !time.reduceMotion {
                    let since = t - 2.05
                    let pulse = since < 1.4 || t < ending ? since.truncatingRemainder(dividingBy: 1.4) : 2
                    if pulse < 1.2 {
                        let p = M.spring(pulse, .soft)
                        var seal = lines
                        seal.context.opacity *= 1 - min(1, p)
                        seal.layer(center: SendStagePainter.pay, scale: M.mix(1, 1.7, p), opacity: 1) {
                            $0.stroke($0.circle(x, y, 30), $0.colors.foreground, 2)
                        }
                    }
                }
            }
            // Delivering: the payment travels into their mailbox.
            let travel = time.p(ending, .snappy)
            payLayer(offset: CGSize(width: M.mix(0, 110, travel), height: 0), scale: M.mix(1, 0.35, travel),
                     opacity: 1 - time.o(ending, .snappy))
        }

        // MARK: Lightning: lock, Ark holds it, the bolt, their receipt unlocks it

        func drawLightning() {
            let x = SendStagePainter.pay.x, y = SendStagePainter.pay.y, sv = SendStagePainter.server
            let paid = ending + 1.15
            let finale = paid + 0.3
            // The payment piece: held until paid (into the server) or refunded (fades).
            let into = succeeded ? time.p(paid, .snappy) : 0
            let refundFade = refunded ? time.o(ending + 1.2, .snappy) : 0
            payLayer(offset: CGSize(width: M.mix(0, 56, into), height: M.mix(0, -74, into)),
                     scale: M.mix(1, 0.3, into) * M.mix(1, 0.6, refundFade),
                     opacity: (1 - (succeeded ? time.o(paid, .snappy) : 0)) * (1 - refundFade))
            // Padlock on the payment.
            let lockOpacity = time.o(1.15, .bouncy) * (1 - time.o(succeeded ? paid : refunded ? ending + 1.2 : .infinity, .snappy))
            let drop = time.p(1.15, .bouncy)
            let opening = succeeded ? time.p(ending + 0.9, .bouncy) : refunded ? time.p(ending + 0.65, .bouncy) : 0
            canvas.layer(center: CGPoint(x: x, y: y - 32), offset: CGSize(width: 0, height: M.mix(-10, 0, drop)),
                         scale: M.mix(0.6, 1, drop), opacity: lockOpacity) {
                $0.shackle(x, y - 32, open: opening)
                $0.padlockBody(x, y - 32)
            }
            // Ark holds it: the server and the tether.
            let serverFade: Double = succeeded ? 1 - time.o(finale + 0.3, .soft)
                : refunded ? M.mix(1, 0.35, time.o(ending + 0.65, .soft)) * (1 - time.o(ending + 1.2, .soft)) : 1
            let pulse = succeeded && t >= finale && !time.reduceMotion ? 0.15 * sin(min(1, (t - finale) / 0.4) * .pi) : 0
            canvas.layer(center: sv, scale: M.mix(0.4, 1, time.p(1.7, .bouncy)) + pulse,
                         opacity: time.o(1.7, .bouncy) * serverFade) { $0.badge(server: true, sv.x, sv.y) }
            let tetherFade = 1 - time.o(succeeded ? paid : refunded ? ending + 0.65 : .infinity, .snappy)
            var tether = canvas
            tether.context.opacity *= min(1, max(0, (t - 1.85) / 0.3)) * tetherFade
            tether.stroke(tether.line(CGPoint(x: x + 14, y: y - 40), CGPoint(x: sv.x - 13, y: sv.y + 12)), tether.colors.foreground, 2, dash: [3, 5])
            drawBolt(until: ending)
            // Their receipt: the key travels from them through Ark to the lock.
            if succeeded, t >= ending, t < ending + 0.9 {
                let k = time.reduceMotion ? 1 : cubicBezier((t - ending) / 0.9, 0.35, 0, 0.25, 1)
                let start = CGPoint(x: SendStagePainter.dest.x - 8, y: SendStagePainter.dest.y - 34)
                let end = CGPoint(x: x, y: y - 30)
                let position = k < 0.5
                    ? CGPoint(x: M.mix(start.x, sv.x, k * 2), y: M.mix(start.y, sv.y, k * 2))
                    : CGPoint(x: M.mix(sv.x, end.x, (k - 0.5) * 2), y: M.mix(sv.y, end.y, (k - 0.5) * 2))
                var key = canvas
                key.context.opacity *= time.reduceMotion ? 1 : min(1, k * 2)
                key.key(position.x, position.y)
            }
            // Ark signs it back to you: a fresh coin settles in the centre.
            if refunded {
                let settle = time.p(ending + 1.2, .soft)
                let c = SendStagePainter.pay
                canvas.layer(center: c, offset: CGSize(width: M.mix(0, -10, settle), height: 0), scale: M.mix(0.6, 1.45, settle),
                             opacity: time.o(ending + 1.2, .soft)) {
                    $0.mineCoin(c.x, c.y, 30, ring: 2.5)
                    $0.text("₿", c.x, c.y + 0.6, size: 30, color: $0.colors.foreground)
                }
            }
        }

        /// The orange bolt from the server to them, looping while the payment is in flight.
        func drawBolt(until stop: Double) {
            let sv = SendStagePainter.server, d = SendStagePainter.dest
            var path = Path()
            path.move(to: canvas.point(sv.x + 15, sv.y + 8))
            path.addLine(to: canvas.point(sv.x + 34, sv.y + 26))
            path.addLine(to: canvas.point(sv.x + 26, sv.y + 31))
            path.addLine(to: canvas.point(d.x - 17, d.y - 20))
            guard t >= SendMotion.payingStart else { return }
            var bolt = canvas
            if refunded, t >= ending {
                // It fizzles to grey.
                let fizzle = min(1, (t - ending) / 0.42)
                bolt.context.opacity *= 1 - fizzle
                bolt.stroke(path, bolt.colors.muted, 3)
                return
            }
            if time.reduceMotion {
                bolt.context.opacity *= t < stop + 0.3 ? 0.9 : 0
                bolt.stroke(path, bolt.colors.orange, 3)
                return
            }
            // A 34-point dash runs along the bolt: first at 2.3 s, then every second while paying.
            let since = t - SendMotion.payingStart
            let index = since < 0.7 ? 0 : Int((since - 0.7) / 1.0) + 1
            let zapStart = index == 0 ? 0 : 0.7 + Double(index - 1) * 1.0
            let duration = index == 0 ? 0.48 : 0.768
            guard SendMotion.payingStart + zapStart < stop else { return }
            let k = cubicBezier(min(1, (since - zapStart) / duration), 0.4, 0, 0.2, 1)
            guard k < 1 else { return }
            // SVG dashoffset 34 → −140, wrapped into the 174-point pattern for Core Graphics.
            let offset = M.mix(34, -140, k).truncatingRemainder(dividingBy: 174)
            bolt.stroke(path, bolt.colors.orange, 3, dash: [34, 140], phase: CGFloat(offset < 0 ? offset + 174 : offset))
        }

        // MARK: On-chain

        /// The block row sits under the stage for on-chain sends.
        var row: ChainBlockRow {
            ChainBlockRow(x0: 86, y: 176, size: 38, tip: state.tip, confirmedHeight: state.confirmedHeight,
                          heldAt: ending + (rail == .onchainSwap ? 0.9 : 0.75), confirmedAt: confirmedAt,
                          blinking: succeeded && t >= ending + 0.6, nextLabel: nextLabel, locale: locale)
        }

        func drawChain() {
            guard rail.isOnchain else { return }
            row.draw(canvas, time: time, opacity: time.o(rail == .onchainSwap ? 0.9 : 0.2, .soft), dot: succeeded)
        }

        func drawSwap() {
            let x = SendStagePainter.pay.x, y = SendStagePainter.pay.y, sv = SendStagePainter.server
            // Your coin goes into Ark, which pays the address from its own coins.
            let into = succeeded ? time.p(ending, .snappy) : 0
            payLayer(offset: CGSize(width: M.mix(0, 56, into), height: M.mix(0, -74, into)), scale: M.mix(1, 0.3, into),
                     opacity: 1 - (succeeded ? time.o(ending, .snappy) : 0))
            canvas.layer(center: sv, scale: M.mix(0.4, 1, time.p(1.15, .bouncy)),
                         opacity: time.o(1.15, .bouncy) * (1 - (succeeded ? time.o(ending + 0.6, .soft) : 0))) {
                $0.badge(server: true, sv.x, sv.y)
            }
            var tether = canvas
            tether.context.opacity *= min(1, max(0, (t - 1.3) / 0.3)) * (1 - (succeeded ? time.o(ending, .snappy) : 0))
            tether.stroke(tether.line(CGPoint(x: x + 14, y: y - 26), CGPoint(x: sv.x - 13, y: sv.y + 12)), tether.colors.foreground, 2, dash: [3, 5])
            if succeeded { dropDot(from: sv, at: ending + 0.25, held: row.heldAt ?? .infinity) }
        }

        func drawWalletSend() {
            if succeeded { dropDot(from: SendStagePainter.coin, at: ending, held: row.heldAt ?? .infinity) }
        }

        /// Your transaction, an orange dot, drops into the "next" block.
        func dropDot(from start: CGPoint, at begin: Double, held: Double) {
            guard t >= begin, t < held else { return }
            let slot = row.slot
            let p = time.p(begin, .soft)
            var dot = canvas
            dot.context.opacity *= time.o(begin, .soft)
            dot.fill(dot.circle(M.mix(start.x, slot.x, p), M.mix(start.y, slot.y, p), 6), dot.colors.orange)
        }

        // MARK: Them, and the finale

        func drawDestination() {
            let d = SendStagePainter.dest
            let finale: Double = succeeded ? (rail == .ark ? ending + 0.35 : rail == .lightning ? ending + 1.45 : .infinity) : .infinity
            let move = time.p(finale + 0.12, .snappy)
            let shake: Double = refunded && t >= ending && t < ending + 0.3 && !time.reduceMotion
                ? [0, -6, 5, 0][min(3, Int((t - ending) / 0.1))] : 0
            let dim = refunded ? M.mix(1, 0.3, time.o(ending + 1.2, .soft)) : 1
            canvas.layer(center: d, offset: CGSize(width: M.mix(20, 0, time.p(0.08, .snappy)) + M.mix(0, 180 - d.x, move) + shake, height: 0),
                         scale: M.mix(1, 1.5, move), opacity: time.o(0.08, .snappy) * dim) { canvas in
                canvas.fill(canvas.circle(d.x, d.y, 26), canvas.colors.soft)
                switch state.recipient.avatar {
                case .initial(let letter): canvas.text(letter, d.x, d.y, size: 20, color: canvas.colors.foreground)
                default: canvas.symbol(state.recipient.glyph ?? "bolt", d.x, d.y, size: 17, color: canvas.colors.foreground)
                }
                // The orange ring draws, then the check pops.
                let ring = time.reduceMotion ? (t >= finale + 0.26 ? 1 : 0) : min(1, max(0, M.spring(t - finale - 0.26, .soft)))
                if ring > 0 {
                    var path = Path()
                    path.addArc(center: canvas.point(d.x, d.y), radius: 33 * canvas.scale, startAngle: .degrees(-90),
                                endAngle: .degrees(-90 + 360 * ring), clockwise: false)
                    canvas.stroke(path, canvas.colors.orange, 2)
                }
                let pop = time.p(finale + 0.52, .bouncy)
                canvas.layer(center: CGPoint(x: d.x + 19, y: d.y - 19), scale: pop, opacity: time.o(finale + 0.52, .bouncy)) {
                    $0.checkBadge(d.x + 19, d.y - 19)
                }
            }
        }

        /// CSS `cubic-bezier(x1, y1, x2, y2)` at `x`.
        func cubicBezier(_ x: Double, _ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) -> Double {
            let x = min(1, max(0, x))
            var u = x
            for _ in 0..<8 {
                let bx = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u - x
                let dx = 3 * (1 - u) * (1 - u) * x1 + 6 * (1 - u) * u * (x2 - x1) + 3 * u * u * (1 - x2)
                if abs(dx) < 1e-6 { break }
                u = min(1, max(0, u - bx / dx))
            }
            return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u
        }
    }
}
