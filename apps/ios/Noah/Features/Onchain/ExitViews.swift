import SwiftUI

/// Pieces the emergency exit's pages share (docs/native-rewrite/design/exit.md).

/// An exit page: the explanation and picture, then the details, over a fixed action area the
/// content scrolls above, so the actions never cover it. Wide windows (the Duo open, iPad,
/// landscape) put the explanation and picture in the leading pane and the details and actions
/// in the trailing one; nothing crosses the fold. The page keeps its state across a pose change.
struct ExitPanes<Leading: View, Trailing: View, Footer: View>: View {
    @ViewBuilder var leading: Leading
    @ViewBuilder var trailing: Trailing
    @ViewBuilder var footer: Footer
    @Environment(\.settings) private var settings

    var body: some View {
        if settings.wide {
            HStack(spacing: 0) {
                GeometryReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) { leading }
                            .padding(.leading, 32).padding(.trailing, 24).padding(.top, 6).padding(.bottom, 24)
                            .frame(maxWidth: .infinity, minHeight: proxy.size.height, alignment: .topLeading)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .environment(\.exitPaneHeight, proxy.size.height)
                }
                .frame(width: settings.leadingWidth)
                SettingsScroll { trailing }
                    .modifier(ExitFooter(buttons: footer))
            }
        } else {
            SettingsScroll {
                leading
                trailing
            }
            .modifier(ExitFooter(buttons: footer))
        }
    }
}

extension EnvironmentValues {
    /// The wide leading pane's height, for the picture that fills it.
    @Entry var exitPaneHeight: CGFloat = 0
}

/// The fixed action area: opaque behind its buttons and lines, with a short fade above, so
/// nothing scrolls under them (the prototype's buttons overlapped Details; this doesn't).
struct ExitFooter<Buttons: View>: ViewModifier {
    let buttons: Buttons
    @Environment(\.settings) private var settings

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            GlassGroup(spacing: 10) { VStack(spacing: 6) { buttons } }
                .padding(.leading, settings.wide ? 36 : NoahTokens.Space.screen)
                .padding(.trailing, settings.wide ? 24 : NoahTokens.Space.screen)
                .padding(.top, 10).padding(.bottom, settings.wide ? 16 : 8)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    NoahTokens.Colors.background.ignoresSafeArea()
                        .overlay(alignment: .top) {
                            LinearGradient(colors: [NoahTokens.Colors.background.opacity(0), NoahTokens.Colors.background],
                                           startPoint: .top, endPoint: .bottom)
                                .frame(height: 20).offset(y: -20)
                        }
                }
        }
    }
}

/// Amounts on the exit pages; hidden amounts stay hidden.
@MainActor struct ExitMoney {
    let app: AppModel
    let locale: Locale

    func callAsFunction(_ sats: UInt64) -> String {
        app.isBalanceHidden ? (app.amountUnit == .bip177 ? "₿••••" : "•••• sats")
            : BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale)
    }

    /// An estimate: "~₿5,533".
    func about(_ sats: UInt64) -> String { "~" + self(sats) }
}

/// The page's headline amount and its one line under it.
struct ExitHeadline: View {
    let amount: String
    var line: String?
    var failure = false

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: amount).noahText(SettingsText.big).monospacedDigit()
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.numericText())
            if let line {
                Text(verbatim: line).noahText(NoahTokens.Typography.sub).monospacedDigit()
                    .foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, 6)
        .accessibilityElement(children: .combine)
    }
}

/// A row on the exit pages that never truncates (exit-v2-notes.md, "Layout"): when the label and
/// value don't fit on one line the value moves under the label, right-aligned; at accessibility
/// sizes they stack, left-aligned. A note (the fee split, needs funds) is its own wrapping line.
struct ExitDetailRow: View {
    let label: String
    let value: String
    var note: String?
    var numeric = false
    var failure = false
    var hairline = true
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let stacked = dynamicTypeSize.isAccessibilitySize
        VStack(alignment: stacked ? .leading : .trailing, spacing: 2) {
            if stacked {
                VStack(alignment: .leading, spacing: 2) {
                    labelText
                    valueText.multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 18) {
                        labelText.fixedSize()
                        Spacer(minLength: 0)
                        valueText.fixedSize()
                    }
                    VStack(alignment: .trailing, spacing: 2) {
                        labelText.frame(maxWidth: .infinity, alignment: .leading)
                        valueText.multilineTextAlignment(.trailing)
                    }
                }
            }
            if let note {
                Text(verbatim: note).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                    .multilineTextAlignment(stacked ? .leading : .trailing)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: stacked ? .leading : .trailing)
            }
        }
        .padding(.vertical, 10)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        .overlay(alignment: .bottom) { if hairline { HomeHairline() } }
        .accessibilityElement(children: .combine)
    }

    private var labelText: some View {
        Text(verbatim: label).noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var valueText: some View {
        Text(verbatim: value).noahText(numeric ? HomeText.amount : NoahTokens.Typography.label).monospacedDigit()
            .foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Review's one-line path (prototype `drawRail`): the round's block, your transactions and coin,
/// then the timelock's dial and the claim's destination, with a caption under each third.
struct ExitPathRail: View {
    let transactions: Int
    /// Several VTXOs: their shared transactions are sent once.
    var several = false
    var delta: UInt32 = 144
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale

    private var language: String { locale.identifier }

    var body: some View {
        VStack(spacing: 6) {
            Canvas { context, size in draw(context, size) }
                .frame(height: 58)
                .accessibilityHidden(true)
            HStack(alignment: .top, spacing: 10) {
                caption(L10n.plural("{n} transactions", count: transactions, locale: language),
                        several ? L10n.text("shared ones once", locale: language) : L10n.text("one block each", locale: language), .leading)
                caption(L10n.plural("{n} blocks", count: Int(delta), locale: language),
                        L10n.text("about {time}", VtxoList.timeLeft(Int64(delta), locale: language), locale: language), .center)
                caption(L10n.text("Claim", locale: language), L10n.text("to another wallet", locale: language), .trailing)
            }
        }
        .padding(.top, 18)
        .accessibilityElement(children: .combine)
    }

    private func caption(_ title: String, _ line: String, _ alignment: TextAlignment) -> some View {
        VStack(alignment: alignment == .leading ? .leading : alignment == .center ? .center : .trailing, spacing: 0) {
            Text(verbatim: title).noahText(HomeText.footnote).fontWeight(.medium).foregroundStyle(NoahTokens.Colors.foreground)
            Text(verbatim: line).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
        }
        .multilineTextAlignment(alignment)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: alignment == .leading ? .leading : alignment == .center ? .center : .trailing)
    }

    /// Where the rail puts things in `size`: the round's block, the transactions (as many as fit 14 pt apart, a
    /// 5 pt circle with its stroke and the clearance, 8 at most; the caption says how many there are), your coin
    /// last, then the timelock's dial and the destination.
    struct Layout: Equatable {
        var y: Double
        var block: CGRect
        var nodes: [CGPoint]
        var coin: CGPoint
        var dial: CGPoint
        var badge: CGPoint

        /// Every element's outline, for keeping them apart.
        var shapes: [ExitStageShape] {
            [.box(.round, "round", block)] + nodes.enumerated().map { .circle(.node, "node\($0.offset)", $0.element, 5.8) }
                + [.circle(.coin, "coin", coin, 9.9), .circle(.coin, "dial", dial, 19.5), .circle(.badge, "badge", badge, 14.75)]
        }
    }

    nonisolated static func layout(_ size: CGSize, transactions: Int) -> Layout {
        let y = size.height / 2 - 4, x0 = 10.0, room = size.width * 0.36 - x0 - 34
        let count = max(1, min(8, transactions, Int(room / 14))), step = room / Double(count)
        let xs = (0..<count).map { x0 + 30 + Double($0) * step }
        return Layout(y: y, block: CGRect(x: x0, y: y - 8, width: 16, height: 16), nodes: xs.dropLast().map { CGPoint(x: $0, y: y) },
                      coin: CGPoint(x: xs[count - 1] + 4, y: y), dial: CGPoint(x: size.width / 2, y: y), badge: CGPoint(x: size.width - 34, y: y))
    }

    private func draw(_ context: GraphicsContext, _ size: CGSize) {
        let colors = MotionColors.theme(colorScheme), layout = Self.layout(size, transactions: transactions), y = layout.y
        let canvas = MotionCanvas(context: context, size: size, viewBox: CGRect(origin: .zero, size: size), colors: colors), cx = layout.dial.x
        canvas.fill(Path(roundedRect: layout.block, cornerRadius: 4), colors.foreground)
        var px = layout.block.maxX
        for node in layout.nodes {
            canvas.stroke(canvas.line(CGPoint(x: px, y: y), CGPoint(x: node.x - 5.5, y: y)), colors.orange, 2)
            canvas.fill(canvas.circle(node.x, y, 5), colors.background)
            canvas.stroke(canvas.circle(node.x, y, 5), colors.orange, 1.6)
            px = node.x + 5.5
        }
        canvas.stroke(canvas.line(CGPoint(x: px, y: y), CGPoint(x: layout.coin.x - 9.5, y: y)), colors.orange, 2)
        canvas.mineCoin(layout.coin.x, y, 9, ring: 1.8)
        canvas.stroke(canvas.line(CGPoint(x: layout.coin.x + 9, y: y), CGPoint(x: cx - 17, y: y)), colors.hairline, 1.5, dash: [3, 3])
        canvas.stroke(canvas.circle(cx, y, 13), colors.hairline, 2)
        let ticks = ExitStagePainter.ticks(delta)
        for tick in 0..<ticks.count {
            let angle = -Double.pi / 2 + Double(tick) * 2 * .pi / Double(ticks.count), r1 = ticks.hours && tick % 6 == 0 ? 19.5 : 17.5
            canvas.stroke(canvas.line(CGPoint(x: cx + cos(angle) * 16, y: y + sin(angle) * 16), CGPoint(x: cx + cos(angle) * r1, y: y + sin(angle) * r1)),
                          colors.muted.opacity(0.5), 1)
        }
        canvas.layer(center: CGPoint(x: cx, y: y + 6), scale: 0.5, opacity: 1) { pad in
            pad.padlockBody(cx, y - 1)
            pad.shackle(cx, y - 1, open: 0)
        }
        canvas.stroke(canvas.line(CGPoint(x: cx + 21, y: y), CGPoint(x: layout.badge.x - 16, y: y)), colors.hairline, 1.5, dash: [3, 3])
        canvas.fill(canvas.circle(layout.badge.x, y, 14), colors.background)
        canvas.stroke(canvas.circle(layout.badge.x, y, 14), colors.hairline, 1.5)
        canvas.symbol("wallet.bifold", layout.badge.x, y, size: 14, color: colors.foreground)
    }
}

/// All / Choose (Settings v1's segmented control).
struct ExitSegment: View {
    @Binding var all: Bool
    @Namespace private var pill
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 0) {
            ForEach([true, false], id: \.self) { option in
                Button { all = option } label: {
                    Text(verbatim: L10n.text(option ? "All" : "Choose", locale: locale.identifier))
                        .noahText(NoahTokens.Typography.control).bold()
                        .foregroundStyle(option == all ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .background {
                            if option == all {
                                Capsule().fill(colorScheme == .dark ? Color(white: 0.23) : NoahTokens.Colors.background)
                                    .shadow(color: .black.opacity(0.14), radius: 2, y: 1)
                                    .matchedGeometryEffect(id: "pill", in: pill)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(option == all ? .isSelected : [])
                .accessibilityIdentifier(option ? "exit-mode-all" : "exit-mode-choose")
            }
        }
        .padding(3)
        .background(NoahTokens.Colors.soft, in: Capsule())
        .animation(NoahTokens.Motion.travel(reduceMotion), value: all)
        .padding(.top, 18)
    }
}

/// A warning line with the shield (prototype `.warn`): red, or muted for notes.
struct ExitWarning: View {
    let text: String
    var symbol = "exclamationmark.shield"
    var muted = false

    var body: some View {
        SettingsWarning(text: text, systemImage: symbol, failure: !muted)
    }
}

/// Your VTXO: a coin with the orange ring. Live exits turn a dashed ring around it; a red "!"
/// needs you, a check is done.
struct ExitCoin: View {
    enum Mark { case none, live, failure, done }
    var mark = Mark.none
    var size: CGFloat = 40
    @State private var turning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Circle().fill(NoahTokens.Colors.soft)
            .overlay { Circle().strokeBorder(NoahTokens.Colors.orange, lineWidth: 2.5) }
            .frame(width: size, height: size)
            .overlay {
                if mark == .live {
                    Circle().stroke(NoahTokens.Colors.muted, style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
                        .padding(-4)
                        .rotationEffect(.degrees(turning ? 360 : 0))
                        .onAppear {
                            guard !reduceMotion else { return }
                            withAnimation(.linear(duration: 8).repeatForever(autoreverses: false)) { turning = true }
                        }
                }
            }
            .overlay(alignment: .bottomTrailing) {
                switch mark {
                case .failure:
                    Text(verbatim: "!").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 17, height: 17).background(NoahTokens.Colors.failure, in: Circle())
                        .overlay { Circle().stroke(NoahTokens.Colors.background, lineWidth: 2) }
                        .offset(x: 3, y: 3)
                case .done:
                    Image(systemName: "checkmark").font(.system(size: 9, weight: .bold)).foregroundStyle(NoahTokens.Colors.background)
                        .frame(width: 17, height: 17).background(NoahTokens.Colors.foreground, in: Circle())
                        .overlay { Circle().stroke(NoahTokens.Colors.background, lineWidth: 2) }
                        .offset(x: 3, y: 3)
                default: EmptyView()
                }
            }
            .accessibilityHidden(true)
    }
}

/// One VTXO in Choose: a checkbox (or a dash when it can't exit), its amount and one line.
struct ExitVtxoRow: View {
    let vtxo: VtxoModel
    let uneconomic: Bool
    /// bark won't exit it: shown like one that can't exit, with bark's reason.
    var refusal: ExitRefusal? = nil
    /// nil: the All list, no checkbox.
    let selected: Bool?
    var hairline = true
    let toggle: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale

    private var language: String { locale.identifier }
    private var selectable: Bool { vtxo.eligible && refusal == nil }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        Button(action: toggle) {
            HStack(spacing: 14) {
                if let selected {
                    if selectable {
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 24))
                            .foregroundStyle(selected ? NoahTokens.Colors.foreground : NoahTokens.Colors.hairline)
                            .frame(width: 34)
                    } else {
                        Image(systemName: "minus.circle").font(.system(size: 24)).foregroundStyle(NoahTokens.Colors.hairline).frame(width: 34)
                    }
                } else {
                    ExitCoin(size: 32).frame(width: 34)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: money(vtxo.amount)).noahText(HomeText.amount).monospacedDigit()
                    Text(verbatim: line(money)).noahText(HomeText.footnote)
                        .foregroundStyle(selectable && uneconomic ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
                    if vtxo.eligible, let detail = refusal?.detail {
                        Text(verbatim: L10n.text(detail, locale: language))
                            .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                    }
                }
                // The row grows rather than cutting a line short.
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 62)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) { if hairline { HomeHairline().padding(.leading, 48) } }
        }
        .buttonStyle(SettingsRowPressStyle())
        // Ones that can't exit look disabled; All's list just doesn't take taps.
        .disabled(!selectable)
        .allowsHitTesting(selected != nil)
        .accessibilityAddTraits(selected == true ? .isSelected : [])
        .accessibilityIdentifier("exit-vtxo-\(vtxo.id)")
    }

    private func line(_ money: ExitMoney) -> String {
        if vtxo.state == "Locked" { return L10n.text("In use right now", locale: language) }
        if vtxo.amount < ExitRules.dust { return L10n.text("Too small to exit · under {amount}", money(ExitRules.dust), locale: language) }
        if let refusal { return L10n.text(refusal.label, locale: language) }
        if uneconomic { return L10n.text("Fees more than it’s worth", locale: language) }
        return VtxoList.expiresIn(Int64(vtxo.expiry) - Int64(vtxo.tip), locale: language)
    }
}

/// Add on-chain funds: today's deposit address, with its QR, Copy and Share.
struct ExitDepositSheet: View {
    /// About how much is missing, when known.
    var need: UInt64?
    let close: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Environment(\.settings) private var settings
    @State private var address: String?
    @State private var error: String?

    private var language: String { locale.identifier }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        SettingsSheetBody(title: L10n.text("Add on-chain funds", locale: language),
                          line: need.map { L10n.text("Send about {amount} here. Exits use it once it confirms.", money($0), locale: language) }
                              ?? L10n.text("Exits use it once it confirms.", locale: language)) {
            if let address {
                ReceiveQRCode(uri: address).aspectRatio(1, contentMode: .fit).frame(maxWidth: 200)
                    .padding(12).background(.white, in: RoundedRectangle(cornerRadius: 20))
                    .frame(maxWidth: .infinity).padding(.top, 18)
                    .accessibilityLabel("Bitcoin deposit address QR code")
                Text(verbatim: address).font(SettingsText.mono).textSelection(.enabled).padding(.top, 14)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("exit-deposit-address")
            } else if let error {
                SettingsFine(text: L10n.text(error, locale: language), failure: true)
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 24)
            }
        } actions: {
            if let address {
                Button {
                    SecureCopy.copy(address)
                    settings.toast("Copied")
                } label: { Label(L10n.text("Copy", locale: language), systemImage: "doc.on.doc") }
                    .buttonStyle(OnboardingSecondaryButtonStyle(height: NoahTokens.Size.button))
                ShareLink(item: address) { Label(L10n.text("Share", locale: language), systemImage: "square.and.arrow.up") }
                    .buttonStyle(SettingsActionStyle(wrap: true))
            } else {
                Button(L10n.text("Done", locale: language), action: close).buttonStyle(SettingsActionStyle(wrap: true))
            }
        }
        .task {
            do {
                guard !app.isWalletSuspended else { throw OnchainFailure.unavailable }
                address = try await app.session.exitDepositAddress()
            } catch {
                self.error = "Couldn't load a deposit address. Close and try again when the wallet is ready."
            }
        }
    }
}

/// A sheet's identity for `settingsSheet`.
enum ExitSheet: Identifiable, Equatable {
    case deposit(UInt64?), check, cancel
    var id: String {
        switch self {
        case .deposit: "deposit"
        case .check: "check"
        case .cancel: "cancel"
        }
    }
}
