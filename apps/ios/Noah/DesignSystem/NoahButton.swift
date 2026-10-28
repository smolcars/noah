import SwiftUI

/// Full-width capsule actions. Liquid Glass (tinted brand for the primary
/// action) on iOS 26+, solid/bordered capsules before.
struct NoahButton: View {
    enum Kind { case primary, secondary }

    let title: LocalizedStringKey
    var kind: Kind = .primary
    var isLoading = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Text(title)
                    .font(NoahFont.inter(17, .semibold, relativeTo: .headline))
                    .opacity(isLoading ? 0 : 1)
                if isLoading {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, minHeight: 52)
        }
        .modifier(NoahButtonStyle(kind: kind))
        .disabled(isLoading)
    }
}

private struct NoahButtonStyle: ViewModifier {
    let kind: NoahButton.Kind

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            switch kind {
            case .primary:
                content.buttonStyle(.glassProminent).tint(NoahColor.brand).foregroundStyle(Color(hex: 0x1A2332))
            case .secondary:
                content.buttonStyle(.glass).foregroundStyle(NoahColor.foreground)
            }
        } else {
            switch kind {
            case .primary:
                content.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                    .tint(NoahColor.brand).foregroundStyle(Color(hex: 0x1A2332))
            case .secondary:
                content.buttonStyle(.bordered).buttonBorderShape(.capsule).tint(NoahColor.brand)
                    .foregroundStyle(NoahColor.foreground)
            }
        }
    }
}

/// Rounded card surface used for grouped content.
struct NoahCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NoahColor.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(NoahColor.border))
    }
}
