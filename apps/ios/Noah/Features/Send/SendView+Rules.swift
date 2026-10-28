import SwiftUI

/// The screen Send shows for the model's state. The steps are today's `SendStage`s.
enum SendScreen: Int, Comparable {
    case amount, recipient, method, source, review, sending

    @MainActor static func of(_ model: SendModel) -> Self {
        if model.controller.locked { return .sending }
        if model.showReview { return .review }
        switch model.stage {
        case .amount: return .amount
        case .recipient: return .recipient
        case .method: return .method
        case .source: return .source
        case .review: return .review
        }
    }

    static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

extension SendModel {
    /// Phones hide the tab bar on every step after Amount; it returns on Done.
    var hidesTabBar: Bool { SendScreen.of(self) != .amount }
}

extension SendView {
    enum Field { case recipient, note }

    func error(_ text: String) -> some View {
        Text(verbatim: text).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.failure)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("send-error")
    }

    static func typeName(_ type: String?, destination: String, ownDomain: String) -> String? {
        switch type {
        case "lnurl":
            let domain = destination.split(separator: "@").last.map(String.init)?.lowercased()
            return domain == ownDomain.lowercased() ? "Noah user" : "Lightning address"
        case "onchain": return "Bitcoin address"
        case "lightning": return "Lightning invoice"
        case "bip321": return "Payment request"
        case "ark": return "Ark address"
        case "offer": return "Lightning offer"
        default: return nil
        }
    }

    static func typedAmount(_ model: SendModel, keeping previous: UInt64?) -> UInt64? {
        model.parsed.amount == nil ? model.sats : previous
    }

    /// Whether the review notes that the invoice sets a different amount from the one you typed.
    static func showsInvoiceNote(typed: UInt64?, fixed: Decimal?) -> Bool {
        guard let fixed, let typed, typed > 0 else { return false }
        return Decimal(typed) != fixed
    }

    static func glyph(_ rail: SendRail) -> String {
        switch rail { case .ark: "sailboat"; case .lightning: "bolt"; case .onchain: "link" }
    }
}

/// A small Liquid Glass chip ("Max").
struct SendChipStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .noahText(HomeText.strong)
            .foregroundStyle(NoahTokens.Colors.foreground)
            .padding(.horizontal, 14)
            .frame(minHeight: 32)
            .contentShape(Capsule())
            .glassCompat(in: Capsule(), interactive: true)
            .frame(minHeight: NoahTokens.Size.hitTarget)
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .animation(NoahTokens.Motion.tap, value: configuration.isPressed)
    }
}
