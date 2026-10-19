import SwiftUI

/// Liquid Glass on iOS 26+, a material fallback on iOS 18–25, and an opaque
/// surface when Reduce Transparency is on. Use this instead of calling
/// `glassEffect` directly so every surface degrades the same way.
struct GlassCompat<S: Shape>: ViewModifier {
    let shape: S
    var interactive = false

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content.background(Color(uiColor: .secondarySystemBackground), in: shape)
        } else if #available(iOS 26, *) {
            content.glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            content.background(.regularMaterial, in: shape)
        }
    }
}

extension View {
    /// Apply after sizing and padding.
    func glassCompat<S: Shape>(in shape: S, interactive: Bool = false) -> some View {
        modifier(GlassCompat(shape: shape, interactive: interactive))
    }

    /// `.glass` / `.glassProminent` on iOS 26+, bordered styles before.
    @ViewBuilder
    func glassButtonStyle(prominent: Bool = false) -> some View {
        if #available(iOS 26, *) {
            if prominent {
                buttonStyle(.glassProminent)
            } else {
                buttonStyle(.glass)
            }
        } else if prominent {
            buttonStyle(.borderedProminent)
        } else {
            buttonStyle(.bordered)
        }
    }
}
