import SwiftUI

/// Home's text styles that the shared tokens don't name (prototype sizes).
enum HomeText {
    static let footnote = NoahTokens.TextStyle(size: 13, lineHeight: 18, weight: .regular, display: false, tracking: 0, textStyle: .footnote)
    static let section = NoahTokens.TextStyle(size: 13, lineHeight: 18, weight: .medium, display: false, tracking: 0, textStyle: .footnote)
    static let strong = NoahTokens.TextStyle(size: 15, lineHeight: 20, weight: .semibold, display: false, tracking: 0, textStyle: .subheadline)
    static let amount = NoahTokens.TextStyle(size: 17, lineHeight: nil, weight: .medium, display: true, tracking: 0, textStyle: .body)
    static let initial = NoahTokens.TextStyle(size: 17, lineHeight: nil, weight: .semibold, display: true, tracking: 0, textStyle: .body)
    static let initialLarge = NoahTokens.TextStyle(size: 24, lineHeight: nil, weight: .semibold, display: true, tracking: 0, textStyle: .title2)
    static let chipInitial = NoahTokens.TextStyle(size: 14, lineHeight: nil, weight: .semibold, display: true, tracking: 0, textStyle: .subheadline)
    static let tucked = NoahTokens.TextStyle(size: 17, lineHeight: nil, weight: .semibold, display: true, tracking: -0.02, textStyle: .headline)
    static let received = NoahTokens.TextStyle(size: 30, lineHeight: nil, weight: .semibold, display: true, tracking: -0.02, textStyle: .title1)
    static let listTitle = NoahTokens.TextStyle(size: 22, lineHeight: 28, weight: .semibold, display: false, tracking: -0.01, textStyle: .title2)
    static let badge = NoahTokens.TextStyle(size: 11, lineHeight: nil, weight: .semibold, display: false, tracking: 0, textStyle: .caption2)
    static let toast = NoahTokens.TextStyle(size: 13, lineHeight: 18, weight: .semibold, display: false, tracking: 0, textStyle: .footnote)
    static let tab = NoahTokens.TextStyle(size: 10, lineHeight: nil, weight: .medium, display: false, tracking: 0, textStyle: .caption2)
}

extension AnyTransition {
    /// Rise and fade in; a plain fade under Reduce Motion.
    static func homeRise(_ reduceMotion: Bool, _ distance: CGFloat = 12) -> AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .offset(y: distance))
    }

    static func homeToast(_ reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.9)).combined(with: .offset(y: 8))
    }

    static func homePaneSheet(_ reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity)
    }
}

// MARK: Top bar

/// You on the leading side (initial, name, network badge); the activity glyph, scan and settings trailing.
/// Compact layouts tuck the balance into the centre on scroll.
struct HomeTopBar: View {
    let address: String?
    let variant: AppVariant
    var tucked: String?
    var tuckShown = false
    /// Settings zooms out of the gear on phones.
    var settingsZoom: Namespace.ID?
    let onProfile: () -> Void
    let onScan: () -> Void
    let onSettings: () -> Void
    /// `HomeActivityGlyph`'s state.
    var activity: LocalizedStringKey?
    var backedUpAt: Double?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var name: String? { address?.split(separator: "@").first.map(String.init) }

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onProfile) {
                HStack(spacing: 8) {
                    ZStack {
                        Circle().strokeBorder(NoahTokens.Colors.orange, lineWidth: 1.5)
                        if let name, let initial = name.first {
                            Text(verbatim: String(initial).lowercased()).noahText(HomeText.chipInitial)
                        } else {
                            Image(systemName: "person").font(.system(size: 14, weight: .semibold))
                        }
                    }
                    .frame(width: 30, height: 30)
                    if let name { Text(verbatim: name).noahText(HomeText.strong).lineLimit(1) }
                    if let badge = variant.badge {
                        Text(verbatim: badge).noahText(HomeText.badge)
                            .foregroundStyle(NoahTokens.Colors.orangeText)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(NoahTokens.Colors.orangeTint, in: Capsule())
                    }
                }
                .foregroundStyle(NoahTokens.Colors.foreground)
                .padding(.leading, 5).padding(.trailing, name == nil && variant.badge == nil ? 5 : 14)
                .frame(height: 40)
                .contentShape(Capsule())
                .glassCompat(in: Capsule(), interactive: true)
                .frame(minHeight: NoahTokens.Size.hitTarget)
            }
            .buttonStyle(HomePressStyle())
            .layoutPriority(1)
            .accessibilityLabel(Text("Profile"))
            .accessibilityValue(Text(verbatim: address ?? ""))
            .accessibilityIdentifier("home-qr-button")
            // The tucked balance sits centred in the room between you and the buttons, never over them.
            Spacer(minLength: 8)
            if let tucked {
                Text(verbatim: tucked)
                    .noahText(HomeText.tucked)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(NoahTokens.Colors.foreground)
                    .opacity(tuckShown ? 1 : 0)
                    .offset(y: tuckShown || reduceMotion ? 0 : 6)
                    .animation(NoahTokens.Motion.travel(reduceMotion), value: tuckShown)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                Spacer(minLength: 8)
            }
            HomeActivityGlyph(running: activity, backedUpAt: backedUpAt).padding(.trailing, 4)
            GlassGroup(spacing: 8) {
                HStack(spacing: 8) {
                    iconButton(systemName: "qrcode.viewfinder", label: "Scan QR code", id: "home-scan-button", action: onScan)
                    iconButton(systemName: "gearshape", label: "Open settings", id: "home-settings-button", action: onSettings)
                        .modifier(SettingsZoomSource(namespace: settingsZoom))
                }
            }
        }
        .frame(height: NoahTokens.Size.hitTarget)
    }

    private func iconButton(systemName: String, label: LocalizedStringKey, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 19, weight: .medium))
                .foregroundStyle(NoahTokens.Colors.foreground)
                .frame(width: 40, height: 40)
                .contentShape(Circle())
                .glassCompat(in: Circle(), interactive: true)
                .frame(width: NoahTokens.Size.hitTarget, height: NoahTokens.Size.hitTarget)
        }
        .buttonStyle(HomePressStyle())
        .accessibilityLabel(Text(label))
        .accessibilityIdentifier(id)
    }
}

private struct SettingsZoomSource: ViewModifier {
    let namespace: Namespace.ID?

    func body(content: Content) -> some View {
        if let namespace {
            content.matchedTransitionSource(id: "settings", in: namespace) { $0.clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous)) }
        } else {
            content
        }
    }
}

extension AppVariant {
    /// The small network badge beside your name ("Signet", "Regtest"); mainnet has none.
    var badge: String? { self == .mainnet ? nil : rawValue.capitalized }
}

/// Scale down a little while pressed (prototype `:active`).
struct HomePressStyle: ButtonStyle {
    var scale: CGFloat = 0.94

    func makeBody(configuration: Configuration) -> some View {
        Pressed(configuration: configuration, scale: scale)
    }

    private struct Pressed: View {
        let configuration: Configuration
        let scale: CGFloat
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            configuration.label
                .scaleEffect(configuration.isPressed && !reduceMotion ? scale : 1)
                .animation(NoahTokens.Motion.tap, value: configuration.isPressed)
        }
    }
}

/// Capsule actions: the black pill (prominent glass tinted to the foreground) or clear glass.
struct HomePillStyle: ButtonStyle {
    var prominent = true
    var height: CGFloat = 36
    var font = HomeText.strong

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, prominent: prominent, height: height, font: font)
    }

    private struct Styled: View {
        let configuration: Configuration
        let prominent: Bool
        let height: CGFloat
        let font: NoahTokens.TextStyle
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            configuration.label
                .noahText(font)
                .lineLimit(1)
                .foregroundStyle(prominent ? NoahTokens.Colors.background : NoahTokens.Colors.foreground)
                .padding(.horizontal, 16)
                .frame(minHeight: height)
                .contentShape(Capsule())
                .glassCompat(in: Capsule(), interactive: isEnabled, tint: prominent ? NoahTokens.Colors.foreground : nil)
                .overlay { if !prominent { Capsule().strokeBorder(NoahTokens.Colors.hairline, lineWidth: 1) } }
                .opacity(isEnabled ? 1 : 0.5)
                .scaleEffect(configuration.isPressed && !reduceMotion ? 0.95 : 1)
                .animation(NoahTokens.Motion.tap, value: configuration.isPressed)
        }
    }
}

// MARK: Balance

/// The big number. Tap for the details; long-press hides it.
struct HomeBalanceView: View {
    let sats: UInt64?
    let unit: BitcoinAmountUnit
    let hidden: Bool
    var tuck: CGFloat = 0
    let onDetails: () -> Void
    let onToggleHidden: () -> Void
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static func hiddenText(unit: BitcoinAmountUnit) -> String {
        unit == .bip177 ? "₿\u{00A0}••••••" : "•••••• sats"
    }

    /// Hiding morphs the digits into dots: a blur-replace with the bouncy spring.
    private var morph: AnyTransition { reduceMotion ? .opacity : AnyTransition(.blurReplace) }

    private var formatted: String? {
        sats.map { BitcoinAmount.format(sats: Int64(clamping: $0), unit: unit, locale: locale) }
    }

    var body: some View {
        number
            .noahText(NoahTokens.Typography.balance)
        .foregroundStyle(NoahTokens.Colors.foreground)
        .lineLimit(1)
        .minimumScaleFactor(0.5)
        .opacity(1 - tuck)
        .scaleEffect(1 - 0.06 * tuck, anchor: .bottomLeading)
        .offset(y: -6 * tuck)
        .contentShape(Rectangle())
        .onTapGesture(perform: onDetails)
        .onLongPressGesture(minimumDuration: 0.5, perform: onToggleHidden)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Balance"))
        .accessibilityValue(Text(verbatim: hidden ? "••••" : formatted ?? ""))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(.default, onDetails)
        .accessibilityAction(named: Text(LocalizedStringKey(hidden ? "Show wallet balance" : "Hide wallet balance")), onToggleHidden)
        .accessibilityIdentifier("home-balance")
    }

    @ViewBuilder private var number: some View {
        ZStack(alignment: .leading) {
            if let sats, let formatted {
                if hidden {
                    Text(verbatim: Self.hiddenText(unit: unit)).transition(morph)
                } else {
                    Text(verbatim: formatted)
                        .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(sats)))
                        .animation(NoahTokens.Motion.travel(reduceMotion), value: sats)
                        .transition(morph)
                }
            } else {
                ProgressView().frame(height: 50)
            }
        }
    }
}

/// The status line under the fiat value. Coming or going it fades and what's below glides (snappy); under
/// Reduce Motion it comes and goes at once. A new line in its place just replaces the text.
struct HomeStatusRow: View {
    let line: HomeStatusLine?
    let balance: WalletBalance?
    let unit: BitcoinAmountUnit
    let hidden: Bool
    /// Nil: plain text (`HomeStatusLine.tap`).
    let action: (() -> Void)?
    /// `line`, set in an animation only when it comes or goes, so what's below moves with it.
    @State private var shown: HomeStatusLine?
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Never empty, so the row appears (and observes) before its first line.
            Color.clear.frame(height: 0)
            if let shown {
                Group {
                    if let action {
                        Button(action: action) { content(shown).contentShape(Rectangle()) }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("home-status")
                    } else {
                        content(shown).accessibilityElement(children: .combine).accessibilityIdentifier("home-status")
                    }
                }
                .padding(.top, 8)
                .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear { shown = line }
        .onChange(of: line) { old, new in
            withAnimation((old == nil) != (new == nil) && !reduceMotion ? NoahTokens.Motion.snappy : nil) { shown = new }
        }
    }

    private func content(_ line: HomeStatusLine) -> some View {
        let isError = if case .error = line { true } else { false }
        return HStack(alignment: .firstTextBaseline, spacing: 8) {
            if !isError { HomePulseDot().alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 } }
            text(line)
                .noahText(NoahTokens.Typography.sub)
                .foregroundStyle(isError ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 20)
    }

    private func amount(_ sats: UInt64) -> String {
        WalletBalance.display(sats, hidden: hidden, unit: unit, locale: locale)
    }

    private func text(_ line: HomeStatusLine) -> Text {
        switch line {
        case .error(let message): Text(LocalizedStringKey(message))
        case .arriving(let sats):
            Text(L10n.text("{amount} arriving", hidden ? "••••" : "+" + amount(sats), locale: locale.identifier))
        case .pendingRound(let sats):
            Text(LocalizedStringKey(balance?.pendingRoundMessage(amount: amount(sats)) ?? ""))
        case .rounds(let count): Text(LocalizedStringKey(count == 1 ? "Round in progress" : "Rounds in progress"))
        }
    }
}

/// The top bar's quiet wallet activity, in a fixed slot so nothing moves: a turning glyph while a
/// background task or backup runs, a green check for about 2 s once a backup completes, else nothing.
struct HomeActivityGlyph: View {
    /// What's running, for VoiceOver; nil when idle.
    let running: LocalizedStringKey?
    /// A backup that has just completed.
    let backedUpAt: Double?
    @State private var checked = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if checked {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(NoahColor.success)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.3).combined(with: .opacity))
                    .accessibilityLabel(Text("Backup completed"))
            } else if let running {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .foregroundStyle(NoahTokens.Colors.muted)
                    .symbolEffect(.rotate, options: .repeat(.continuous), isActive: !reduceMotion)
                    .transition(.opacity)
                    .accessibilityLabel(Text(running))
            }
        }
        .font(.system(size: 17, weight: .semibold))
        .frame(width: 26, height: NoahTokens.Size.hitTarget)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: checked)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: running == nil)
        .task(id: backedUpAt) {
            guard backedUpAt != nil else { checked = false; return }
            checked = true
            try? await Task.sleep(for: .seconds(2))
            checked = false
        }
    }
}

/// A small dot that pulses slowly; still under Reduce Motion.
struct HomePulseDot: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if reduceMotion {
                Circle().fill(NoahTokens.Colors.muted)
            } else {
                Circle().fill(NoahTokens.Colors.muted)
                    .phaseAnimator([false, true]) { dot, dim in
                        dot.opacity(dim ? 0.25 : 1).scaleEffect(dim ? 0.7 : 1)
                    } animation: { _ in .easeInOut(duration: 0.8) }
            }
        }
        .frame(width: 7, height: 7)
        .accessibilityHidden(true)
    }
}

// MARK: Nudge

/// One nudge card. Resolving it folds it away; the next slides in.
struct HomeNudgeCard: View {
    let nudge: HomeNudge
    let unit: BitcoinAmountUnit
    let hidden: Bool
    var busy = false
    let action: () -> Void
    let dismiss: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .regular))
                .foregroundStyle(NoahTokens.Colors.foreground)
                .frame(width: 24, height: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                title.noahText(HomeText.strong).foregroundStyle(NoahTokens.Colors.foreground)
                if let subtitle {
                    subtitle.noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            if let button {
                Button(action: action) {
                    ZStack {
                        Text(button).opacity(busy ? 0 : 1)
                        if busy { ProgressView().tint(NoahTokens.Colors.background) }
                    }
                }
                .buttonStyle(HomePillStyle())
                .disabled(busy)
                .accessibilityIdentifier(identifier)
            }
            if dismissible {
                Button(action: dismiss) {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .bold))
                        .foregroundStyle(NoahTokens.Colors.muted)
                        .frame(width: 28, height: 28)
                        .frame(minWidth: NoahTokens.Size.hitTarget, minHeight: NoahTokens.Size.hitTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, -8)
                .accessibilityLabel(Text(LocalizedStringKey(dismissLabel)))
            }
        }
        .padding(.vertical, 12).padding(.leading, 16).padding(.trailing, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    private var icon: String {
        switch nudge {
        case .notice: "info.circle"
        case .renew: "clock.arrow.circlepath"
        case .backupFailed: "exclamationmark.icloud"
        case .backUp: "checkmark.shield"
        case .email: "envelope"
        case .update: "arrow.down.circle"
        }
    }

    private var title: Text {
        switch nudge {
        case .notice(let text): Text(LocalizedStringKey(text))
        case .renew(let renewal):
            Text(L10n.text("Renew {amount}", WalletBalance.display(renewal.sats, hidden: hidden, unit: unit, locale: locale),
                           locale: locale.identifier))
        case .backupFailed: Text("Backup failed")
        case .backUp: Text("Back up your wallet")
        case .email: Text("Add your email")
        case .update: Text("Update available")
        }
    }

    private var subtitle: Text? {
        switch nudge {
        case .notice: nil
        case .renew(let renewal): Text(verbatim: renewal.expiryText(locale: locale.identifier))
        case .backupFailed(let message): Text(LocalizedStringKey(message))
        case .backUp: Text("Save your recovery phrase")
        case .email: Text("For urgent wallet alerts")
        case .update: Text("Get the latest fixes")
        }
    }

    private var button: LocalizedStringKey? {
        switch nudge {
        case .notice: nil
        case .renew: "Renew"
        case .backupFailed: "Retry"
        case .backUp: "Back up"
        case .email: "Add"
        case .update: "Update"
        }
    }

    private var identifier: String {
        switch nudge {
        case .backUp: "home-backup-prompt"
        default: "home-nudge-\(nudge.id)"
        }
    }

    private var dismissible: Bool {
        switch nudge {
        case .notice, .email: true
        default: false
        }
    }

    private var dismissLabel: String {
        if case .email = nudge { "Dismiss emergency email prompt" } else { "Close" }
    }
}

/// Holds the nudge on screen so a change folds the old card away (soft) before
/// the next slides in (snappy). A rebuilt slot shows its nudge at once.
struct HomeNudgeSlot<Card: View>: View {
    let nudge: HomeNudge?
    @ViewBuilder let card: (HomeNudge) -> Card
    @State private var shown: HomeNudge?
    @State private var started = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var transition: AnyTransition {
        reduceMotion ? .opacity : .asymmetric(insertion: .opacity.combined(with: .offset(y: 14)),
                                              removal: .opacity.combined(with: .offset(x: 30)))
    }

    var body: some View {
        VStack(spacing: 0) {
            // Never empty, so the slot appears (and observes) before its first nudge.
            Color.clear.frame(height: 0)
            if let shown {
                card(shown)
                    .padding(.top, 24)
                    .id(shown.id)
                    .transition(transition)
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            shown = nudge
        }
        .task(id: nudge) {
            guard started else { return }
            if shown?.id == nudge?.id { shown = nudge; return }
            if shown != nil {
                withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft)) { shown = nil }
                do { try await Task.sleep(for: .milliseconds(420)) } catch { return }
            }
            withAnimation(NoahTokens.Motion.travel(reduceMotion)) { shown = nudge }
        }
    }
}

// MARK: Toast, pane sheet, suspended

struct HomeToast: Equatable {
    let id = UUID()
    let text: String
    var seconds: Double = 1.7
}

struct HomeToastView: View {
    let text: String

    var body: some View {
        Text(LocalizedStringKey(text))
            .noahText(HomeText.toast)
            .foregroundStyle(NoahTokens.Colors.background)
            .lineLimit(1)
            .padding(.horizontal, 16)
            .frame(height: 34)
            .background(NoahTokens.Colors.foreground, in: Capsule())
            .accessibilityAddTraits(.isStaticText)
    }
}

/// Home's sheets on wide windows: kept inside the trailing pane so they never cross the fold.
struct HomePaneSheet<Content: View>: View {
    /// The trailing pane's width.
    let width: CGFloat
    var fitted = true
    let onDismiss: () -> Void
    @ViewBuilder var content: Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Color.black.opacity(0.3)
                .ignoresSafeArea()
                .onTapGesture(perform: onDismiss)
                .accessibilityHidden(true)
            VStack(spacing: 0) {
                if fitted {
                    Capsule().fill(NoahTokens.Colors.hairline).frame(width: 36, height: 5)
                        .padding(.top, 10).padding(.bottom, 20)
                        .accessibilityHidden(true)
                }
                content
            }
            .frame(width: max(0, width - 24))
            .frame(maxHeight: fitted ? nil : .infinity)
            .background {
                if fitted {
                    Color.clear.glassCompat(in: RoundedRectangle(cornerRadius: NoahTokens.Radius.sheet, style: .continuous))
                } else {
                    RoundedRectangle(cornerRadius: 28, style: .continuous).fill(NoahTokens.Colors.sheet)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: fitted ? NoahTokens.Radius.sheet : 28, style: .continuous))
            .padding(12)
            .transition(.homePaneSheet(reduceMotion))
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape, onDismiss)
        }
    }
}

/// Today's suspended state, restyled: nothing else on Home.
struct HomeSuspendedView: View {
    /// Settings → Advanced, where Suspend wallet is.
    var onOpenSettings: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: NoahTokens.Space.s) {
            Image(systemName: "pause.circle")
                .font(.system(size: 40, weight: .regular))
                .foregroundStyle(NoahTokens.Colors.failure)
                .padding(.bottom, NoahTokens.Space.s)
                .accessibilityHidden(true)
            Text("Wallet Suspended").noahText(NoahTokens.Typography.title).foregroundStyle(NoahTokens.Colors.foreground)
            Text("All wallet operations are disabled. Resume your wallet in Settings.")
                .noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
            Button("Open Settings", action: onOpenSettings)
                .buttonStyle(HomePillStyle(height: 44))
                .padding(.top, NoahTokens.Space.s)
                .accessibilityIdentifier("home-suspended-settings")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .contain)
    }
}
