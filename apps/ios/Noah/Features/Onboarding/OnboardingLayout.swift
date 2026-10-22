import SwiftUI

/// Window metrics for onboarding. Narrow windows (phones, the iPhone Duo
/// closed) get one column, as in the prototype. Wide ones (the Duo open or
/// half-open, iPad, wide landscape) split into two panes at the window's
/// centre, which is where the Duo's vertical fold runs: the picture or
/// headline leads, words and controls trail, and nothing crosses the split.
/// Lay out from the window size and safe area alone; there is no hinge gutter.
struct OnboardingLayout: Equatable {
    /// The whole window, ignoring safe areas and the keyboard.
    var size: CGSize
    var insets: EdgeInsets
    /// Body text size relative to the default; larger text takes room from the compact tree.
    var textScale: CGFloat = 1

    static let paneContentWidth: CGFloat = 420

    var wide: Bool { size.width >= 700 || (size.width >= 560 && size.width > size.height) }

    /// Window coordinates.
    var leadingPane: CGRect {
        wide ? CGRect(x: 0, y: 0, width: size.width / 2, height: size.height) : CGRect(origin: .zero, size: size)
    }
    var trailingPane: CGRect {
        wide ? CGRect(x: size.width / 2, y: 0, width: size.width / 2, height: size.height) : CGRect(origin: .zero, size: size)
    }

    /// Height the compact column keeps below the tree's chain line for the
    /// wordmark, copy and buttons (the prototype's 496 → 818 pt).
    private static let treeContentReserve: CGFloat = 322
    private static let conveyorContentReserve: CGFloat = 300

    func scene(_ mode: TreeScene.Mode) -> ScenePlacement {
        let conveyor = mode == .conveyor
        // Design boxes: the welcome tree spans y 110…510 (chain 496), the conveyor 150…350 (chain 334).
        let box = conveyor ? CGRect(x: 0, y: 150, width: 393, height: 200) : CGRect(x: 0, y: 110, width: 393, height: 400)
        if wide {
            // Fill the pane with what is drawn and centre it: the scan band's top to the chain line
            // (leaves at 214, root at 496), or both conveyor trees with the hop.
            let content = conveyor ? CGRect(x: 35, y: 180, width: 330, height: 160) : CGRect(x: 20, y: 196, width: 353, height: 304)
            let pane = CGRect(x: insets.leading, y: insets.top, width: leadingPane.width - insets.leading,
                              height: size.height - insets.top - insets.bottom)
            let scale = min(pane.width * 0.94 / content.width, pane.height * (conveyor ? 0.7 : 0.86) / content.height, 2.6)
            let origin = CGPoint(x: pane.midX - content.midX * scale, y: pane.midY - content.midY * scale)
            return ScenePlacement(origin: origin, scale: scale, bounds: leadingPane)
        }
        // Compact: from the safe top down to the chain line is 437 design points
        // for the tree (51 above its box) and 275 for the conveyor; the rest is content.
        let available = size.height - insets.top - insets.bottom
        let reach: CGFloat = conveyor ? 275 : 437, margin: CGFloat = conveyor ? 91 : 51
        let reserve = (conveyor ? Self.conveyorContentReserve : Self.treeContentReserve) * min(max(textScale, 1), 1.8)
        let scale = max(0.5, min(size.width / box.width, (available - reserve) / reach, conveyor ? 1.15 : 1.25))
        let origin = CGPoint(x: size.width / 2 - box.midX * scale, y: insets.top + margin * scale - box.minY * scale)
        return ScenePlacement(origin: origin, scale: scale, bounds: CGRect(origin: .zero, size: size))
    }

    /// Compact: distance from the safe top to the scene's chain line, where content starts.
    func chainY(_ mode: TreeScene.Mode) -> CGFloat {
        let placement = scene(mode)
        return placement.point(CGPoint(x: 0, y: mode == .conveyor ? 334 : 496)).y - insets.top
    }
}

/// Two panes in a wide window, one column otherwise. Being one `Layout`, a
/// pose change re-places the same views, so their state survives.
/// Subviews: the leading content, then the trailing content.
struct OnboardingPanes: Layout {
    /// How a single column divides its height.
    enum Column: Equatable {
        /// The leading content gets this height (tree screens: down to the chain line).
        case leading(CGFloat)
        /// The leading content gets its ideal height; trailing takes the rest.
        case leadingFits
        /// The trailing content gets its ideal height at the bottom; leading takes the rest.
        case trailingFits
    }

    var wide: Bool
    var column: Column = .leadingFits
    /// Safe-area insets on the sides. The split sits at the window centre, so each pane pads only its outer edge.
    var sideInsets: (leading: CGFloat, trailing: CGFloat) = (0, 0)

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let leading = subviews[0], trailing = subviews[1]
        if wide {
            let leadingWidth = max(0, (bounds.width + sideInsets.leading + sideInsets.trailing) / 2 - sideInsets.leading)
            leading.place(at: bounds.origin, proposal: ProposedViewSize(width: leadingWidth, height: bounds.height))
            trailing.place(at: CGPoint(x: bounds.minX + leadingWidth, y: bounds.minY),
                           proposal: ProposedViewSize(width: bounds.width - leadingWidth, height: bounds.height))
            return
        }
        let height: CGFloat
        switch column {
        case .leading(let value): height = value
        case .leadingFits: height = leading.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height
        case .trailingFits:
            height = bounds.height - trailing.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height
        }
        let split = max(0, min(bounds.height, height))
        leading.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: split))
        trailing.place(at: CGPoint(x: bounds.minX, y: bounds.minY + split),
                       proposal: ProposedViewSize(width: bounds.width, height: bounds.height - split))
    }
}

/// Fills the space it is given and scrolls only when its content can't fit
/// (large Dynamic Type, short windows): scrolling is the last resort.
struct FitOrScroll<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                content.frame(minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
        }
    }
}

/// Vertical rhythm for screens with a headline and a field. Wide panes share
/// one vertical centre; with the keyboard up both top-align under the top bar,
/// so the headline sits level with the field. Compact keeps the prototype's
/// column with the action at the bottom.
struct OnboardingColumn {
    let wide: Bool
    /// A field is focused (the keyboard is up).
    let keyboard: Bool

    static let barClearance: CGFloat = 60

    /// Top padding of the leading headline.
    var headlineTop: CGFloat { !wide || keyboard ? Self.barClearance : 0 }
    var headlineAlignment: Alignment { wide && keyboard ? .top : .center }
    /// Max height of the spacer above the trailing content.
    var lead: CGFloat { wide && !keyboard ? .infinity : 0 }
    /// Max height of the spacer between the content and its action.
    var gap: CGFloat { wide ? NoahTokens.Space.screen : .infinity }
    /// Max height of the spacer below the action.
    var trail: CGFloat { wide ? .infinity : 0 }
    /// Top padding of the first trailing element; `compact` is the prototype's gap under the headline.
    func firstTop(compact: CGFloat) -> CGFloat { wide ? (keyboard ? Self.barClearance : 0) : compact }
}

extension View {
    /// The headline pane: under the top bar when compact, centred or top-aligned when wide.
    func onboardingHeadlinePane(_ column: OnboardingColumn) -> some View {
        padding(.top, column.headlineTop)
            .frame(maxHeight: column.wide ? .infinity : nil, alignment: column.headlineAlignment)
            .onboardingPane()
    }
}
