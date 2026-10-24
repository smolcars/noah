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
            Text(verbatim: amount).noahText(SettingsText.big).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
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
                            .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted).lineLimit(2)
                    }
                }
                .lineLimit(1)
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
                    .buttonStyle(SettingsActionStyle())
            } else {
                Button(L10n.text("Done", locale: language), action: close).buttonStyle(SettingsActionStyle())
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
