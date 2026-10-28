import SwiftUI

/// Send and Receive text styles the shared tokens don't name (prototype sizes).
enum SendText {
    /// The keypad amount: Space Grotesk 600, 68 pt.
    static let amount = NoahTokens.TextStyle(size: 68, lineHeight: nil, weight: .semibold, display: true, tracking: -0.035, textStyle: .largeTitle)
    static let sheetAmount = NoahTokens.TextStyle(size: 54, lineHeight: nil, weight: .semibold, display: true, tracking: -0.035, textStyle: .largeTitle)
    /// "Send ₿X", "Sending ₿X": Space Grotesk 600, 40 pt.
    static let big = NoahTokens.TextStyle(size: 40, lineHeight: nil, weight: .semibold, display: true, tracking: -0.025, textStyle: .largeTitle)
    /// The amount above the To field.
    static let summary = NoahTokens.TextStyle(size: 34, lineHeight: nil, weight: .semibold, display: true, tracking: -0.02, textStyle: .title1)
    static let key = NoahTokens.TextStyle(size: 30, lineHeight: nil, weight: .medium, display: true, tracking: 0, textStyle: .title2)
    static let rowNumber = NoahTokens.TextStyle(size: 17, lineHeight: nil, weight: .medium, display: true, tracking: 0, textStyle: .body)
    static let navTitle = NoahTokens.TextStyle(size: 17, lineHeight: 22, weight: .semibold, display: false, tracking: 0, textStyle: .headline)
    static let initial = NoahTokens.TextStyle(size: 18, lineHeight: nil, weight: .semibold, display: true, tracking: 0, textStyle: .body)
    static let recentInitial = NoahTokens.TextStyle(size: 22, lineHeight: nil, weight: .semibold, display: true, tracking: 0, textStyle: .title3)
    static let small = NoahTokens.TextStyle(size: 12, lineHeight: 16, weight: .regular, display: false, tracking: 0, textStyle: .caption1)
    static let mono = NoahTokens.TextStyle(size: 13, lineHeight: 18, weight: .regular, display: false, tracking: 0, textStyle: .footnote)
    static let backAmount = NoahTokens.TextStyle(size: 44, lineHeight: nil, weight: .semibold, display: true, tracking: -0.03, textStyle: .largeTitle)
    /// Receive's pills under the card (prototype `.ract .pill`).
    static let pill = NoahTokens.TextStyle(size: 16, lineHeight: 22, weight: .semibold, display: false, tracking: 0, textStyle: .headline)
    static let band = NoahTokens.TextStyle(size: 24, lineHeight: nil, weight: .semibold, display: true, tracking: -0.02, textStyle: .title2)
}

/// A 40-pt Liquid Glass circle with an SF Symbol (scan, back, copy), in a 44-pt hit area.
struct SendIconButton: View {
    let systemName: String
    let label: LocalizedStringKey
    var id = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .medium))
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

/// A circle on the soft surface: an initial for people and addresses, a rail glyph otherwise.
struct SendAvatar: View {
    let label: SendRecipientLabel
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            Circle().fill(NoahTokens.Colors.soft)
            switch label.avatar {
            case .initial(let letter):
                Text(verbatim: letter)
                    .noahText(size > 50 ? SendText.recentInitial : SendText.initial)
                    .scaleEffect(size < 40 ? 0.85 : 1)
            default:
                Image(systemName: label.glyph ?? "bolt").font(.system(size: size * 0.42, weight: .medium))
            }
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A 48-pt label/value row with a hairline under it (prototype `.br`).
struct SendDetailRow<Value: View>: View {
    let label: LocalizedStringKey
    var last = false
    /// Too long for one line, the label sits above the value and both wrap rather than truncate.
    var wraps = false
    @ViewBuilder let value: Value

    var body: some View {
        let title = Text(label).noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
        let line = HStack(spacing: 12) {
            title.lineLimit(1)
            Spacer(minLength: 8)
            value.foregroundStyle(NoahTokens.Colors.foreground).lineLimit(1)
        }
        Group {
            if wraps {
                ViewThatFits(in: .horizontal) {
                    line
                    VStack(alignment: .leading, spacing: 2) {
                        title
                        value.foregroundStyle(NoahTokens.Colors.foreground)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 10)
                }
            } else {
                line
            }
        }
        .frame(minHeight: 48)
        .overlay(alignment: .bottom) {
            if !last { Rectangle().fill(NoahTokens.Colors.hairline).frame(height: 1) }
        }
        .accessibilityElement(children: .combine)
    }
}

extension SendDetailRow where Value == AnyView {
    /// A number in Space Grotesk with tabular digits.
    init(_ label: LocalizedStringKey, number: String, last: Bool = false, wraps: Bool = false) {
        self.init(label: label, last: last, wraps: wraps) {
            AnyView(Text(verbatim: number).noahText(SendText.rowNumber).monospacedDigit())
        }
    }

    init(_ label: LocalizedStringKey, text: String, verbatim: Bool = false, glyph: String? = nil, last: Bool = false, wraps: Bool = false) {
        self.init(label: label, last: last, wraps: wraps) {
            AnyView(HStack(spacing: 6) {
                if let glyph { Image(systemName: glyph).font(.system(size: 14, weight: .medium)) }
                Group { if verbatim { Text(verbatim: text) } else { Text(LocalizedStringKey(text)) } }
                    .noahText(NoahTokens.Typography.label)
                    .truncationMode(.middle)
            })
        }
    }

    /// A value with its own copy button (a preimage or transaction ID, shortened). `source` is the label's English text.
    init(source: String, copy value: String, shown: String, last: Bool = false, id: String = "", onCopy: @escaping () -> Void) {
        self.init(label: LocalizedStringKey(source), last: last) {
            AnyView(Button {
                // Sensitive proofs stay on this device and expire, as today's detail rows do.
                UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: value]],
                                              options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
                onCopy()
            } label: {
                HStack(spacing: 6) {
                    Text(verbatim: shown).noahText(SendText.rowNumber).monospacedDigit()
                    Image(systemName: "doc.on.doc").font(.system(size: 14, weight: .medium))
                }
                .frame(minHeight: NoahTokens.Size.hitTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(L10n.text("Copy {label}", L10n.text(source))))
            .accessibilityIdentifier(id))
        }
    }
}

/// The section labels "Recent", "Results", "Show" (prototype `.sec`).
struct SendSectionTitle: View {
    let title: LocalizedStringKey
    var body: some View {
        Text(title).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The number pad shared by Send and Receive's amount sheet (prototype `buildKp`):
/// Space Grotesk digits; a pressed key grows briefly. Today's input rules: at most 16
/// characters, no leading zero and one decimal separator in fiat.
struct SendKeypad: View {
    @Binding var amount: String
    var decimal = false
    var keyHeight: CGFloat = 60
    var onKey: (String) -> Void = { _ in }

    var body: some View {
        Grid(horizontalSpacing: 0, verticalSpacing: 2) {
            ForEach([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], [decimal ? "." : "", "0", "⌫"]], id: \.self) { row in
                GridRow {
                    ForEach(row, id: \.self) { key in
                        KeypadKey(key: key, height: keyHeight) { press(key) }
                    }
                }
            }
        }
        .padding(.horizontal, 6)
    }

    static func apply(_ key: String, to amount: inout String) {
        if key == "⌫" {
            if !amount.isEmpty { amount.removeLast() }
        } else if key == "." {
            if !amount.contains(".") { amount += amount.isEmpty ? "0." : "." }
        } else if !key.isEmpty && amount.count < 16 {
            amount = amount == "0" ? key : amount + key
        }
    }

    private func press(_ key: String) {
        guard !key.isEmpty else { return }
        Self.apply(key, to: &amount)
        onKey(key)
    }
}

private struct KeypadKey: View {
    let key: String
    let height: CGFloat
    let action: () -> Void
    @State private var bump = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let still = reduceMotion
        return Button {
            bump += 1
            action()
        } label: {
            Group {
                if key == "⌫" {
                    Image(systemName: "delete.left").accessibilityHidden(true).font(.system(size: 24, weight: .regular))
                } else {
                    Text(verbatim: key).noahText(SendText.key)
                }
            }
            .keyframeAnimator(initialValue: 1.0, trigger: bump) { content, scale in
                content.scaleEffect(still ? 1 : scale)
            } keyframes: { _ in
                KeyframeTrack {
                    CubicKeyframe(1.28, duration: 0.09)
                    CubicKeyframe(1.0, duration: 0.13)
                }
            }
            .foregroundStyle(NoahTokens.Colors.foreground)
            .frame(maxWidth: .infinity, minHeight: height)
            .contentShape(Capsule())
        }
        .buttonStyle(KeypadPressStyle())
        .disabled(key.isEmpty)
        .opacity(key.isEmpty ? 0 : 1)
        .accessibilityLabel(Text(LocalizedStringKey(key == "⌫" ? "Delete digit" : key)))
        .accessibilityHidden(key.isEmpty)
        .accessibilityIdentifier("send-key-\(key)")
    }
}

/// The soft surface shows under a key while it's pressed.
private struct KeypadPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Capsule().fill(NoahTokens.Colors.soft).opacity(configuration.isPressed ? 1 : 0))
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// The keypad amount: grey "₿0" when empty, digits roll in as they're typed, and an
/// amount over the balance shakes.
struct SendAmountDisplay: View {
    let text: String
    let empty: Bool
    let value: Double
    var style = SendText.amount
    var shake = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let still = reduceMotion
        return Text(verbatim: text)
            .noahText(style)
            .foregroundStyle(empty ? NoahTokens.Colors.muted : NoahTokens.Colors.foreground)
            .lineLimit(1)
            .minimumScaleFactor(0.4)
            .contentTransition(reduceMotion ? .opacity : .numericText(value: value))
            .animation(NoahTokens.Motion.travel(reduceMotion), value: text)
            .keyframeAnimator(initialValue: 0.0, trigger: shake) { content, x in
                content.offset(x: still ? 0 : x)
            } keyframes: { _ in
                KeyframeTrack {
                    LinearKeyframe(-10, duration: 0.076)
                    LinearKeyframe(9, duration: 0.076)
                    LinearKeyframe(-6, duration: 0.076)
                    LinearKeyframe(3, duration: 0.076)
                    LinearKeyframe(0, duration: 0.076)
                }
            }
    }
}
