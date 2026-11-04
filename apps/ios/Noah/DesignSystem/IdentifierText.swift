import SwiftUI
import UIKit

/// A payment identifier (invoice, address, hash, transaction ID) in mono. It wraps between any two
/// characters without adding hyphens the string doesn't contain (SwiftUI's Text hyphenates long words),
/// and selecting or copying it yields the exact string. `lines` > 0 shows only the start, ending in "…".
struct IdentifierText: UIViewRepresentable {
    let value: String
    var size: CGFloat = 15
    var style: UIFont.TextStyle = .subheadline
    var color: Color = NoahTokens.Colors.foreground
    var opacity: CGFloat = 1
    /// Ends lines at the trailing edge, as a value beside its label.
    var trailing = false
    var lines = 0
    /// Off inside a row that opens on a tap, so the tap reaches the row.
    var selectable = true

    func makeUIView(context _: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.required, for: .vertical)
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byCharWrapping
        paragraph.hyphenationFactor = 0
        let rtl = context.environment.layoutDirection == .rightToLeft
        paragraph.alignment = trailing ? (rtl ? .left : .right) : .natural
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(context.environment.dynamicTypeSize))
        let font = UIFontMetrics(forTextStyle: style).scaledFont(for: .monospacedSystemFont(ofSize: size, weight: .regular), compatibleWith: traits)
        view.attributedText = NSAttributedString(
            string: value,
            attributes: [.font: font, .foregroundColor: UIColor(color).withAlphaComponent(opacity), .paragraphStyle: paragraph])
        view.isUserInteractionEnabled = selectable
        view.textContainer.maximumNumberOfLines = lines
        view.textContainer.lineBreakMode = lines > 0 ? .byTruncatingTail : .byCharWrapping
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView view: UITextView, context _: Context) -> CGSize? {
        // No proposed width (ideal size): the whole string on one line.
        let fit = view.sizeThatFits(CGSize(width: proposal.width ?? .greatestFiniteMagnitude, height: .greatestFiniteMagnitude))
        return CGSize(width: proposal.width.map { min($0, ceil(fit.width)) } ?? ceil(fit.width), height: ceil(fit.height))
    }
}
