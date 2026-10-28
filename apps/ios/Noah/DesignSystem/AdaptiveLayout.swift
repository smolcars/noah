import SwiftUI

enum AdaptiveLayout {
    static let readableWidth: CGFloat = 640
    static let pairedWidth: CGFloat = 1180
    static let paneGap: CGFloat = 24

    // Same two readable panes as RN's adaptiveLayout.ts, measured inside safe areas.
    static func expanded(width: CGFloat, minimumPaneWidth: CGFloat = 260) -> Bool {
        width >= 2 * max(260, minimumPaneWidth) + paneGap
    }

    static func qrSide(availableHeight: CGFloat) -> CGFloat {
        max(0, min(270, availableHeight - 80))
    }
}

/// Changing the layout keeps the children mounted, including field and disclosure state.
struct AdaptiveColumns<Content: View>: View {
    @ScaledMetric(relativeTo: .body) private var minimumPaneWidth: CGFloat = 260
    @State private var width: CGFloat = 0
    @ViewBuilder let content: (Bool) -> Content

    var body: some View {
        let expanded = AdaptiveLayout.expanded(width: width, minimumPaneWidth: minimumPaneWidth)
        let layout = expanded
            ? AnyLayout(HStackLayout(alignment: .top, spacing: AdaptiveLayout.paneGap))
            : AnyLayout(VStackLayout(spacing: AdaptiveLayout.paneGap))
        layout { content(expanded) }
            .frame(maxWidth: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}

/// Grouped forms keep their normal single scroll surface until two panes fit.
/// Drafts and navigation belong to the owning screen, outside either form.
struct AdaptiveForm<Primary: View, Secondary: View>: View {
    @ScaledMetric(relativeTo: .body) private var minimumPaneWidth: CGFloat = 260
    @ViewBuilder let primary: Primary
    @ViewBuilder let secondary: Secondary

    var body: some View {
        GeometryReader { geometry in
            let expanded = AdaptiveLayout.expanded(width: geometry.size.width, minimumPaneWidth: minimumPaneWidth)
            HStack(alignment: .top, spacing: 0) {
                Form {
                    primary
                    if !expanded { secondary }
                }
                if expanded {
                    Form { secondary }
                }
            }
        }
        .readableWidth(AdaptiveLayout.pairedWidth)
    }
}

extension View {
    func readableWidth(_ maximum: CGFloat = AdaptiveLayout.readableWidth) -> some View {
        frame(maxWidth: maximum).frame(maxWidth: .infinity)
    }

    func scrollFocusedField(_ field: (some Hashable)?, using scroll: ScrollViewProxy) -> some View {
        modifier(FocusedFieldScroll(field: field, scroll: scroll))
    }
}

private struct FocusedFieldScroll<Field: Hashable>: ViewModifier {
    let field: Field?
    let scroll: ScrollViewProxy
    @State private var size = CGSize.zero
    private struct Request: Equatable { let field: Field?; let size: CGSize }

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
            .task(id: Request(field: field, size: size)) {
                guard let field else { return }
                await Task.yield()
                guard !Task.isCancelled else { return }
                scroll.scrollTo(field, anchor: .center)
            }
    }
}
