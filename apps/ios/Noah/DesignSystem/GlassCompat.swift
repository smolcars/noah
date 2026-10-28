import SwiftUI

/// Liquid Glass on iOS 26+, a material fallback on iOS 18–25, and an opaque
/// surface when Reduce Transparency is on. Use this instead of calling
/// `glassEffect` directly so every surface degrades the same way.
/// A tint makes prominent glass; before iOS 26 and under Reduce Transparency
/// it becomes a solid fill of that colour.
struct GlassCompat<S: Shape>: ViewModifier {
    let shape: S
    var interactive = false
    var tint: Color?

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content.background(tint ?? Color(uiColor: .secondarySystemBackground), in: shape)
        } else if #available(iOS 26, *) {
            content.glassEffect(glass, in: shape)
        } else if let tint {
            content.background(tint, in: shape)
        } else {
            content.background(.regularMaterial, in: shape)
        }
    }

    @available(iOS 26, *)
    private var glass: Glass {
        var glass = Glass.regular
        if let tint { glass = glass.tint(tint) }
        return interactive ? glass.interactive() : glass
    }
}

/// `GlassEffectContainer` on iOS 26+, so neighbouring glass blends and
/// morphs; plain content before.
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat?
    @ViewBuilder var content: Content

    var body: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
    }
}

extension View {
    /// Apply after sizing and padding.
    func glassCompat(in shape: some Shape, interactive: Bool = false, tint: Color? = nil) -> some View {
        modifier(GlassCompat(shape: shape, interactive: interactive, tint: tint))
    }

    /// Glass morph identity inside a `GlassGroup` on iOS 26+; nothing before.
    /// Apply after `glassCompat`.
    @ViewBuilder
    func glassCompatID<ID: Hashable & Sendable>(_ id: ID, in namespace: Namespace.ID?) -> some View {
        if #available(iOS 26, *), let namespace {
            glassEffectID(id, in: namespace)
        } else {
            self
        }
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
