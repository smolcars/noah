import SwiftUI
import UIKit

extension AppAppearance {
    /// The Display row's value and the tile's name.
    var title: String {
        switch self {
        case .automatic: "Automatic"
        case .light: "Light"
        case .dark: "Dark"
        }
    }
}

/// Three tiny Home screens: Automatic, Light and Dark (docs/native-rewrite/design/appearance.md). Unlike the
/// other pickers it stays open after a pick, so you can compare.
struct SettingsAppearanceSheet: View {
    @Environment(\.settingsFixture) private var fixture
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var haptics = OnboardingHaptics()

    private var language: String { locale.identifier }

    var body: some View {
        SettingsSheetBody(title: L10n.text("Appearance", locale: language),
                          line: L10n.text("Follow your phone, or keep Noah light or dark.", locale: language)) {
            VStack(spacing: 14) {
                HStack(alignment: .top, spacing: 10) {
                    ForEach(AppAppearance.allCases, id: \.self) { choice in
                        AppearanceTile(choice: choice, selected: app.appearance == choice, balance: balance, initial: initial) { pick(choice, from: $0) }
                            .frame(maxWidth: 150)
                    }
                }
                Text(verbatim: hint).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("appearance-hint")
            }
            .frame(maxWidth: 470)
            .padding(.top, 20)
        } actions: { EmptyView() }
            .onboardingHaptics(haptics)
    }

    private var hint: String {
        switch app.appearance {
        case .automatic:
            L10n.text(colorScheme == .dark ? "Now dark · follows your phone" : "Now light · follows your phone", locale: language)
        case .light: L10n.text("Always light", locale: language)
        case .dark: L10n.text("Always dark", locale: language)
        }
    }

    private var balance: String {
        if app.isBalanceHidden { return app.amountUnit == .bip177 ? "₿••••" : "•••• sats" }
        let sats = (fixture?.summary ?? app.summary)?.balance.totalSats ?? 0
        return BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale)
    }

    private var initial: String? { app.server.state.lightningAddress?.first.map { String($0).lowercased() } }

    /// The selected tile does nothing. A pick re-themes the app at once, through the circle reveal when the
    /// colours on screen change (Automatic → Light with the phone light changes nothing).
    private func pick(_ choice: AppAppearance, from tile: UIView?) {
        guard choice != app.appearance, !AppearanceReveal.running else { return }
        haptics.play(.selection)
        guard let tile, let window = tile.window else { app.setAppearance(choice); return }
        let system = window.windowScene?.traitCollection.userInterfaceStyle ?? .unspecified
        guard app.appearance.resolved(system: system) != choice.resolved(system: system) else { app.setAppearance(choice); return }
        AppearanceReveal.run(in: window, from: tile.convert(CGPoint(x: tile.bounds.midX, y: tile.bounds.midY), to: window),
                             reduceMotion: reduceMotion) { app.setAppearance(choice) }
    }
}

/// One choice: its tiny Home, its name and a check circle. One button for screen readers.
private struct AppearanceTile: View {
    let choice: AppAppearance
    let selected: Bool
    let balance: String
    let initial: String?
    let pick: (UIView?) -> Void
    @State private var anchor = TileAnchor.Box()
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button { pick(anchor.view) } label: {
            VStack(spacing: 8) {
                screen
                    .aspectRatio(9 / 17, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay { RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(NoahTokens.Colors.hairline, lineWidth: 1) }
                    .background(TileAnchor(box: anchor))
                    // The Bitcoin unit sheet's selected stroke, 3 pt outside the tile.
                    .overlay {
                        RoundedRectangle(cornerRadius: 21, style: .continuous).strokeBorder(NoahTokens.Colors.foreground, lineWidth: 2)
                            .padding(-5)
                            .opacity(selected ? 1 : 0)
                    }
                Text(verbatim: L10n.text(choice.title, locale: locale.identifier)).noahText(NoahTokens.Typography.control)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                ZStack {
                    Circle().strokeBorder(NoahTokens.Colors.muted, lineWidth: 1.5).opacity(selected ? 0 : 1)
                    Circle().fill(NoahTokens.Colors.foreground).scaleEffect(selected || reduceMotion ? 1 : 0.5).opacity(selected ? 1 : 0)
                    SettingsCheck(on: selected, tint: NoahTokens.Colors.background).scaleEffect(0.7)
                }
                .frame(width: 22, height: 22)
                .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy), value: selected)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(HomePressStyle(scale: 0.95))
        .accessibilityLabel(Text(verbatim: L10n.text(choice.title, locale: locale.identifier)))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("preference-appearance-\(choice.rawValue)")
    }

    @ViewBuilder private var screen: some View {
        switch choice {
        case .automatic:
            // Light, with the dark one in the lower-right triangle and a hairline along the diagonal.
            ZStack {
                AppearanceMiniHome(balance: balance, initial: initial).environment(\.colorScheme, .light)
                AppearanceMiniHome(balance: balance, initial: initial).environment(\.colorScheme, .dark).clipShape(LowerRightHalf())
                Diagonal().stroke(NoahTokens.Colors.muted.opacity(0.5), lineWidth: 1)
            }
        case .light: AppearanceMiniHome(balance: balance, initial: initial).environment(\.colorScheme, .light)
        case .dark: AppearanceMiniHome(balance: balance, initial: initial).environment(\.colorScheme, .dark)
        }
    }
}

/// A tiny Home in the light or dark tokens (the `colorScheme` it's given), drawn with shapes and text rather
/// than captured: the name chip, the balance, the fiat line, three Recent rows and the tab bar.
private struct AppearanceMiniHome: View {
    let balance: String
    let initial: String?

    var body: some View {
        GeometryReader { proxy in
            // Sizes from the prototype's 76-pt tile, scaled to this one.
            let scale = proxy.size.width / 76
            let inner = proxy.size.width - 14 * scale
            VStack(alignment: .leading, spacing: 4 * scale) {
                HStack(spacing: 3 * scale) {
                    Circle().strokeBorder(NoahTokens.Colors.orange, lineWidth: 1.5 * scale)
                        .overlay { Text(verbatim: initial ?? "").font(HomeText.chipInitial.font(size: 5.5 * scale)) }
                        .frame(width: 10 * scale, height: 10 * scale)
                    bar(inner * 0.3, scale)
                }
                Text(verbatim: balance).font(NoahTokens.Typography.balance.font(size: 17 * scale)).tracking(-0.03 * 17 * scale)
                    .lineLimit(1).minimumScaleFactor(0.4)
                    .padding(.top, 8 * scale)
                bar(inner * 0.4, scale).padding(.bottom, 4 * scale)
                ForEach([0.6, 0.45, 0.52], id: \.self) { width in
                    HStack(spacing: 4 * scale) {
                        Circle().fill(NoahTokens.Colors.soft).frame(width: 9 * scale, height: 9 * scale)
                        bar(inner * width, scale)
                    }
                    .padding(.vertical, 3 * scale)
                }
                Spacer(minLength: 0)
                tabBar(scale)
            }
            .padding(.vertical, 9 * scale).padding(.horizontal, 7 * scale)
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .background(NoahTokens.Colors.background)
        .accessibilityHidden(true)
    }

    private func tabBar(_ scale: CGFloat) -> some View {
        Capsule().fill(NoahTokens.Colors.soft).frame(height: 14 * scale)
            .overlay {
                HStack(spacing: 0) {
                    ForEach(0..<3, id: \.self) { tab in
                        Circle().fill(tab == 0 ? NoahTokens.Colors.foreground : NoahTokens.Colors.hairline)
                            .frame(width: 4 * scale, height: 4 * scale)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 6 * scale)
            }
    }

    private func bar(_ width: CGFloat, _ scale: CGFloat) -> some View {
        Capsule().fill(NoahTokens.Colors.hairline).frame(width: width, height: 3 * scale)
    }
}

private struct LowerRightHalf: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            path.closeSubpath()
        }
    }
}

private struct Diagonal: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        }
    }
}

/// A plain UIKit view behind a tile, so the reveal knows the tile's window and where its centre is.
private struct TileAnchor: UIViewRepresentable {
    final class Box {
        weak var view: UIView?
    }

    let box: Box

    func makeUIView(context _: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        box.view = view
        return view
    }

    func updateUIView(_ view: UIView, context _: Context) { box.view = view }
}
