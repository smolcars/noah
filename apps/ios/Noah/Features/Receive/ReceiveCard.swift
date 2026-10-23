import SwiftUI

/// A payment arriving on the visible Receive screen (send-receive.md, "Arrivals"), timed
/// from when it was seen (prototype `arrive`).
struct ReceiveArrival: Equatable, Identifiable {
    enum Rail: Equatable { case ark, lightning, onchain }

    let id = UUID()
    var rail: Rail
    var sats: Int64
    var start: Date
    /// On-chain: the transaction and, once mined, the block it confirmed in.
    var txid: String?
    var confirmedHeight: UInt32?
    var confirmedAt: Date?
    /// On-chain: the outputs it spends, to follow a replacement (RBF).
    var inputs: Set<SendTransaction.Input> = []

    /// The card flips to its back this many seconds after the arrival starts.
    var flipAt: Double {
        switch rail {
        case .ark: 1.25
        case .lightning: 2.76
        case .onchain: 0.9
        }
    }
    static let stay = 2.9
    var flipBackAt: Double { flipAt + Self.stay }
    /// The card is back on its front.
    var settledAt: Double { flipBackAt + 0.8 }

    func elapsed(_ date: Date) -> Double { max(0, date.timeIntervalSince(start)) }

    /// The phase line under the card.
    func phase(at t: Double, tip: UInt32?, locale: Locale) -> (text: String, done: Bool) {
        let language = locale.identifier
        switch rail {
        case .ark: return ("", false)
        case .lightning:
            if t < 0.85 { return (L10n.text("Incoming over Lightning", locale: language), false) }
            if t < 1.65 { return (L10n.text("Ark holds it for you", locale: language), false) }
            if t < 2.5 { return (L10n.text("Claiming with your key", locale: language), false) }
            return t < flipAt + 1.6 ? (L10n.text("Received", locale: language), true) : ("", false)
        case .onchain:
            if t < 0.9 { return (L10n.text("Seen on the network", locale: language), false) }
            if let confirmedHeight, let confirmedAt, Date(timeInterval: t, since: start) >= confirmedAt {
                return (L10n.text("Confirmed in block {height}", confirmedHeight.formatted(.number.locale(locale)), locale: language), true)
            }
            return (tip.map { L10n.text("Waiting for block {height}", ($0 + 1).formatted(.number.locale(locale)), locale: language) } ?? "", false)
        }
    }

    /// Every moment the phase line can change, in seconds from the start.
    var phaseBoundaries: [Double] {
        switch rail {
        case .ark: []
        case .lightning: [0.85, 1.65, 2.5, flipAt + 1.6]
        case .onchain: [0.9] + (confirmedAt.map { [elapsed($0)] } ?? [])
        }
    }

    /// The rail line on the card's back.
    func railLine(locale: String) -> (glyph: String, text: String) {
        switch rail {
        case .ark: ("sailboat", L10n.text("via Ark · just now", locale: locale))
        case .lightning: ("bolt", L10n.text("via Lightning · just now", locale: locale))
        case .onchain: ("link", L10n.text("On-chain · waiting for a block", locale: locale))
        }
    }

    /// The card's turn in degrees at `t`: a bouncy flip to the back, then a snappy one home.
    func angle(at t: Double) -> Double {
        if t < flipAt || t >= settledAt { return 0 }
        if t < flipBackAt { return 180 * MotionTime.spring(t - flipAt, .bouncy) }
        return 180 + 180 * min(1, MotionTime.spring(t - flipBackAt, .snappy))
    }

    /// What a new check says about an on-chain arrival.
    enum Followed: Equatable { case unchanged, confirmed, gone }

    /// Follows an on-chain arrival in a new check: its confirmation, or the replacement that
    /// took its place (RBF, a transaction spending one of its inputs), even one already
    /// confirmed, so it never waits for a block that won't include it. Gone with nothing
    /// spending its inputs, its wait ends; a new deposit is never assumed to replace it.
    mutating func follow(_ snapshot: ReceiveChainSnapshot, at date: Date = Date()) -> Followed {
        guard rail == .onchain, let txid else { return .unchanged }
        guard let seen = snapshot.follow(txid: txid, inputs: inputs) else { return .gone }
        if seen.txid != txid {
            self.txid = seen.txid
            inputs = seen.inputs
            sats = seen.arrivingSats
            confirmedHeight = nil
            confirmedAt = nil
        }
        guard confirmedHeight == nil, let height = seen.confirmedHeight else { return .unchanged }
        confirmedHeight = height
        confirmedAt = date
        return .confirmed
    }

    /// Every haptic of the arrival (prototype `hap` calls), by key.
    var haptics: [(key: String, at: Double, kind: OnboardingHaptics.Kind)] {
        switch rail {
        case .ark: [("ripple", 0.76, .light), ("flip", flipAt, .success)]
        case .lightning: [("lock", 0.85, .light), ("unlock", 2.07, .light), ("flip", flipAt, .success)]
        case .onchain: [("flip", flipAt, .success)]
        }
    }
}

/// Arrivals in the order they came: one plays at a time, and the next starts as soon as the
/// current one has flipped home (one check can bring two).
struct ReceiveArrivalQueue: Equatable {
    private(set) var current: ReceiveArrival?
    private(set) var waiting: [ReceiveArrival] = []

    /// Plays `next` now, or once the one playing has flipped home.
    mutating func announce(_ next: ReceiveArrival, at date: Date = Date()) {
        if let current, current.elapsed(date) < current.settledAt { waiting.append(next) } else { start(next, at: date) }
    }

    /// The current arrival has flipped home: the next one starts (true), or an Ark or
    /// Lightning arrival ends while an on-chain one stays for its block row (false).
    @discardableResult mutating func finish(at date: Date = Date()) -> Bool {
        if !waiting.isEmpty { start(waiting.removeFirst(), at: date); return true }
        if current?.rail != .onchain { current = nil }
        return false
    }

    /// Follows the on-chain arrivals (playing or waiting) in a new check; one that's gone
    /// ends, and the next starts. Returns whether the playing one just confirmed or ended, and
    /// the transactions followed now, replacements included.
    mutating func follow(_ snapshot: ReceiveChainSnapshot, at date: Date = Date()) -> (confirmed: Bool, ended: Bool, txids: Set<String>) {
        for index in waiting.indices.reversed() where waiting[index].follow(snapshot, at: date) == .gone {
            waiting.remove(at: index)
        }
        var confirmed = false, ended = false
        if var playing = current {
            switch playing.follow(snapshot, at: date) {
            case .unchanged: current = playing
            case .confirmed: current = playing; confirmed = true
            case .gone:
                ended = true
                current = nil
                if !waiting.isEmpty { start(waiting.removeFirst(), at: date) }
            }
        }
        return (confirmed, ended, Set(([current] + waiting).compactMap { $0?.txid }))
    }

    mutating func clear() {
        current = nil
        waiting = []
    }

    private mutating func start(_ next: ReceiveArrival, at date: Date) {
        var next = next
        next.start = date
        current = next
    }
}

/// The receive card: your QR on white with its name, caption and amount band on the front;
/// the arrival's ring, check, amount and rail on the back. A thin orange light circles it
/// while it's listening.
struct ReceiveCard: View {
    let payload: String?
    /// Verbatim title under the QR (the address itself for the Lightning address).
    let title: String
    let caption: String
    var band: (amount: String, fiat: String?)?
    let qrSide: CGFloat
    var listening = false
    var arrival: ReceiveArrival?
    var unit: BitcoinAmountUnit = .bip177
    var tilt = 0
    /// Redraws the QR from the centre when it appears or changes (off for still renders).
    var reveal = true
    /// No Lightning address yet: today's message and its Profile action instead of a QR.
    var unavailable = false
    var onProfile: () -> Void = {}
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    /// The arrival whose flip is home: nothing on the card moves any more.
    @State private var settled: UUID?

    var body: some View {
        let still = reduceMotion
        return TimelineView(.animation(paused: arrival == nil || settled == arrival?.id || scenePhase != .active)) { timeline in
            let t = arrival?.elapsed(timeline.date) ?? 0
            let angle = arrival?.angle(at: t) ?? 0
            let backShown = arrival.map { reduceMotion ? t >= $0.flipAt && t < $0.flipBackAt : cos(angle * .pi / 180) < 0 } ?? false
            ZStack {
                front
                    .opacity(backShown ? 0 : 1)
                    .animation(reduceMotion ? NoahTokens.Motion.reduceMotionFade : nil, value: backShown)
                if let arrival {
                    back(arrival, t: t)
                        .rotation3DEffect(.degrees(reduceMotion ? 0 : 180), axis: (x: 0, y: 1, z: 0))
                        .opacity(backShown ? 1 : 0)
                        .animation(reduceMotion ? NoahTokens.Motion.reduceMotionFade : nil, value: backShown)
                }
            }
            .background {
                RoundedRectangle(cornerRadius: NoahTokens.Radius.card, style: .continuous)
                    .fill(NoahTokens.Colors.qrCard)
                    .shadow(color: .black.opacity(colorScheme == .dark ? 0.6 : 0.08), radius: 20, y: 14)
            }
            .overlay {
                if colorScheme == .light {
                    RoundedRectangle(cornerRadius: NoahTokens.Radius.card, style: .continuous)
                        .strokeBorder(Color.black.opacity(0.07), lineWidth: 1)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: NoahTokens.Radius.card, style: .continuous))
            .rotation3DEffect(.degrees(reduceMotion ? 0 : angle), axis: (x: 0, y: 1, z: 0), perspective: 0.45)
            .overlay {
                if let arrival, arrival.rail != .onchain { ArrivalOverlay(arrival: arrival, t: t, qrSide: qrSide) }
            }
        }
        .keyframeAnimator(initialValue: 0.0, trigger: tilt) { content, degrees in
            content.rotation3DEffect(.degrees(still ? 0 : degrees), axis: (x: 0, y: 1, z: 0), perspective: 0.45)
        } keyframes: { _ in
            KeyframeTrack {
                SpringKeyframe(-10, duration: 0.18, spring: .init(response: 0.4, dampingRatio: 0.6))
                SpringKeyframe(0, duration: 0.5, spring: .init(response: 0.4, dampingRatio: 0.6))
            }
        }
        .background {
            if listening && !reduceMotion && arrival == nil { ListeningLight() }
        }
        .task(id: arrival?.id) {
            guard let arrival else { return }
            let wait = arrival.settledAt - arrival.elapsed(Date())
            if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
            settled = arrival.id
        }
        .accessibilityElement(children: .combine)
    }

    private var front: some View {
        VStack(spacing: 0) {
            if let payload {
                StyledQRCode(content: payload, reveal: reveal)
                    .id(payload)
                    .frame(width: qrSide, height: qrSide)
                    .accessibilityIdentifier("receive-qr")
                Text(verbatim: title)
                    .noahText(NoahTokens.Typography.button)
                    .foregroundStyle(NoahTokens.Colors.qrInk)
                    .lineLimit(1).truncationMode(.middle)
                    .padding(.top, 12)
                    .accessibilityIdentifier("receive-qr-title")
                Text(LocalizedStringKey(caption))
                    .noahText(HomeText.footnote)
                    .foregroundStyle(Color(hex: 0x6B6B6B))
                    .multilineTextAlignment(.center)
                    .padding(.top, 2)
            } else if !unavailable {
                ProgressView().tint(NoahTokens.Colors.qrInk).frame(width: qrSide, height: qrSide)
            } else {
                VStack(alignment: .leading, spacing: NoahTokens.Space.s) {
                    Text("Lightning address unavailable").noahText(NoahTokens.Typography.button)
                    Text("Finish setting up your Lightning address before sharing your QR.")
                        .noahText(HomeText.footnote).foregroundStyle(Color(hex: 0x6B6B6B))
                    Button("Open Profile", action: onProfile)
                        .noahText(HomeText.strong)
                        .foregroundStyle(NoahTokens.Colors.orangeText)
                        .padding(.top, NoahTokens.Space.xs)
                        .accessibilityIdentifier("receive-open-profile")
                }
                .foregroundStyle(NoahTokens.Colors.qrInk)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 8)
            }
            if let band {
                HStack(alignment: .firstTextBaseline) {
                    Text(verbatim: band.amount).noahText(SendText.band).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.6)
                    Spacer(minLength: 8)
                    if let fiat = band.fiat { Text(verbatim: fiat).noahText(NoahTokens.Typography.sub).foregroundStyle(Color(hex: 0xBDBDBD)) }
                }
                .padding(.horizontal, 22).padding(.vertical, 14)
                .background(Color(hex: 0x0A0A0A))
                .padding(.horizontal, -22).padding(.bottom, -18)
                .padding(.top, 14)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 30)))
                .accessibilityIdentifier("receive-band")
            }
        }
        .padding(.horizontal, 22).padding(.top, 22).padding(.bottom, 18)
        .frame(maxWidth: .infinity)
    }

    private func back(_ arrival: ReceiveArrival, t: Double) -> some View {
        let time = MotionTime(t: t, reduceMotion: reduceMotion)
        let ring = time.timed(arrival.flipAt + 0.25, 0.65), check = time.timed(arrival.flipAt + 0.65, 0.38)
        let line = arrival.railLine(locale: locale.identifier)
        return VStack(spacing: 10) {
            ZStack {
                Circle().trim(from: 0, to: ring)
                    .stroke(NoahTokens.Colors.orange, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(1.25)
                CheckShape(points: [CGPoint(x: 27, y: 43), CGPoint(x: 37, y: 53), CGPoint(x: 58, y: 31)], box: 84)
                    .trim(from: 0, to: check)
                    .stroke(NoahTokens.Colors.qrInk, style: StrokeStyle(lineWidth: 3.2, lineCap: .round, lineJoin: .round))
            }
            .frame(width: 76, height: 76)
            Text(verbatim: "+" + BitcoinAmount.format(sats: arrival.sats, unit: unit, locale: locale))
                .noahText(SendText.backAmount).foregroundStyle(NoahTokens.Colors.qrInk)
                .lineLimit(1).minimumScaleFactor(0.5)
                .accessibilityIdentifier("receive-arrival-amount")
            HStack(spacing: 6) {
                Image(systemName: line.glyph).font(.system(size: 14, weight: .medium))
                Text(verbatim: line.text).noahText(NoahTokens.Typography.control)
            }
            .foregroundStyle(Color(hex: 0x6B6B6B))
        }
        .padding(22)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A thin orange segment slowly circling the card, 7 s a lap (prototype `.comet`).
private struct ListeningLight: View {
    var body: some View {
        TimelineView(.animation) { timeline in
            GeometryReader { proxy in
                let size = CGSize(width: proxy.size.width + 3, height: proxy.size.height + 3)
                let radius = NoahTokens.Radius.card + 1.5
                let perimeter = 2 * (size.width + size.height) - (8 - 2 * .pi) * radius
                let lap = timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 7) / 7
                RoundedRectangle(cornerRadius: radius, style: .circular)
                    .stroke(NoahTokens.Colors.orange, style: StrokeStyle(
                        lineWidth: 2.5, lineCap: .round, dash: [46, max(1, perimeter - 46)], dashPhase: -perimeter * lap))
                    .frame(width: size.width, height: size.height)
                    .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Above the card: the Ark server delivers a coin (Ark), or the bolt hits it, it holds a
/// locked coin and your key claims it (Lightning). The card is always white, so its marks
/// are fixed colours.
private struct ArrivalOverlay: View {
    let arrival: ReceiveArrival
    let t: Double
    let qrSide: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { proxy in
            let w = proxy.size.width
            Canvas { context, size in
                // The canvas reaches 110 pt above and 60 pt right of the card.
                let canvas = MotionCanvas(context: context, size: size,
                                          viewBox: CGRect(x: -10, y: -110, width: w + 70, height: proxy.size.height + 120), colors: .card)
                draw(canvas, w: w)
            }
            .frame(width: w + 70, height: proxy.size.height + 120)
            .offset(x: -10, y: -110)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func draw(_ canvas: MotionCanvas, w: Double) {
        let time = MotionTime(t: t, reduceMotion: reduceMotion)
        let cx = w / 2, cy = 22 + Double(qrSide) / 2, sy = -34.0
        typealias M = MotionTime
        switch arrival.rail {
        case .ark:
            // Ark's mailbox drops a coin into the card, an orange ripple, then the flip.
            canvas.layer(center: CGPoint(x: cx, y: sy), scale: M.mix(0.4, 1, time.p(0, .bouncy)),
                         opacity: time.o(0, .bouncy) * (1 - time.o(0.63, .soft))) { $0.badge(server: true, cx, sy) }
            let drop = time.p(0.38, .bouncy)
            canvas.layer(center: CGPoint(x: cx, y: cy), offset: CGSize(width: 0, height: M.mix(sy - cy, 0, drop)),
                         scale: M.mix(0.4, 1, drop), opacity: time.o(0.38, .bouncy) * (1 - time.o(arrival.flipAt, .snappy))) {
                $0.payCoin(cx, cy, 26, fill: NoahTokens.Colors.qrInk, ink: .white)
            }
            if t >= 0.76 && !reduceMotion {
                let ripple = M.spring(t - 0.76, .soft)
                canvas.layer(center: CGPoint(x: cx, y: cy), scale: M.mix(1, 2.6, ripple), opacity: 1 - min(1, ripple)) {
                    $0.stroke($0.circle(cx, cy, 28), $0.colors.orange, 2.5)
                }
            }
        case .lightning:
            let serverFade = 1 - time.o(2.5, .soft)
            canvas.layer(center: CGPoint(x: cx, y: sy), scale: M.mix(0.4, 1, time.p(0, .bouncy)),
                         opacity: time.o(0, .bouncy) * serverFade) { $0.badge(server: true, cx, sy) }
            // "Incoming over Lightning": the bolt hits the server.
            if t >= 0.25 && t < 0.87 || (reduceMotion && t >= 0.25 && t < 0.85) {
                var path = Path()
                path.move(to: canvas.point(w + 40, sy - 60))
                path.addLine(to: canvas.point(cx + 44, sy - 14))
                path.addLine(to: canvas.point(cx + 52, sy - 2))
                path.addLine(to: canvas.point(cx + 16, sy))
                if reduceMotion {
                    canvas.stroke(path, canvas.colors.orange, 3.2)
                } else {
                    let k = min(1, (t - 0.25) / 0.62)
                    let offset = M.mix(240, -240, k)
                    canvas.stroke(path, canvas.colors.orange, 3.2, dash: [240, 240], phase: CGFloat(offset < 0 ? offset + 480 : offset))
                }
            }
            // "Ark holds it for you": a locked coin drops into the card; your key opens it.
            let drop = time.p(0.85, .bouncy)
            let coinOpacity = time.o(0.85, .bouncy) * (1 - time.o(arrival.flipAt, .snappy))
            canvas.layer(center: CGPoint(x: cx, y: cy), offset: CGSize(width: 0, height: M.mix(sy - cy, 0, drop)),
                         scale: M.mix(0.4, 1, drop), opacity: coinOpacity) { canvas in
                canvas.payCoin(cx, cy, 26, fill: NoahTokens.Colors.qrInk, ink: .white)
                var lock = canvas
                lock.context.opacity *= 1 - time.o(2.5, .snappy)
                lock.shackle(cx, cy - 25, open: time.p(2.07, .bouncy), ink: NoahTokens.Colors.qrInk)
                lock.padlockBody(cx, cy - 25, ink: NoahTokens.Colors.qrInk, paper: .white)
            }
            if t >= 1.65 && t < 2.07 {
                let rise = time.p(1.65, .snappy)
                var key = canvas
                key.context.opacity *= time.o(1.65, .snappy)
                key.key(cx, M.mix(cy + 170, cy - 30, rise))
            }
        case .onchain:
            break
        }
    }
}

/// The block row under the card for an on-chain arrival (prototype `chainbox`).
struct ReceiveBlockRow: View {
    let arrival: ReceiveArrival
    let tip: UInt32?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @State private var settled: String?

    /// When the row stops changing, in seconds from the arrival; nil while "next" blinks.
    static func settleTime(_ arrival: ReceiveArrival, reduceMotion: Bool) -> Double? {
        if let confirmedAt = arrival.confirmedAt { return arrival.elapsed(confirmedAt) + 1.5 }
        return reduceMotion ? 1.2 : nil
    }

    private var settleKey: String { "\(arrival.id)-\(arrival.txid ?? "")-\(arrival.confirmedAt?.timeIntervalSince1970 ?? 0)-\(reduceMotion)" }

    var body: some View {
        TimelineView(.animation(paused: scenePhase != .active || settled == settleKey)) { timeline in
            let t = arrival.elapsed(timeline.date)
            Canvas { context, size in
                Self.draw(MotionCanvas(context: context, size: size, viewBox: CGRect(x: 0, y: 0, width: 300, height: 58),
                                       colors: .theme(colorScheme)),
                          arrival: arrival, tip: tip, time: MotionTime(t: t, reduceMotion: reduceMotion),
                          nextLabel: L10n.text("next", locale: locale.identifier), locale: locale)
            }
        }
        .task(id: settleKey) {
            // Stop redrawing once nothing moves; a confirmation wakes it.
            let key = settleKey
            guard let end = Self.settleTime(arrival, reduceMotion: reduceMotion) else { return }
            let wait = end - arrival.elapsed(Date())
            if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
            settled = key
        }
        .accessibilityHidden(true)
    }

    static func draw(_ canvas: MotionCanvas, arrival: ReceiveArrival, tip: UInt32?, time: MotionTime, nextLabel: String, locale: Locale) {
        let t = time.t
        let row = ChainBlockRow(x0: 72, y: 6, size: 30, tip: tip, confirmedHeight: arrival.confirmedHeight,
                                heldAt: 0.7, confirmedAt: arrival.confirmedAt.map { $0.timeIntervalSince(arrival.start) },
                                blinking: t >= 0.9, nextLabel: nextLabel, locale: locale)
        row.draw(canvas, time: time, opacity: time.o(0, .soft), dot: true)
        // Your transaction drops into the "next" block.
        if t < 0.7 {
            let drop = time.p(0.2, .bouncy)
            var dot = canvas
            dot.context.opacity *= time.o(0.2, .bouncy)
            dot.fill(dot.circle(row.slot.x, row.slot.y - 40 * (1 - drop), 6), dot.colors.orange)
        }
    }
}
