import SwiftUI

/// "Ways to pay you" (send-receive.md): the five formats, each with a value preview, a
/// one-line subtitle and its own copy button. Tapping a row shows that format on the card.
/// Phones open it as a sheet from Copy; the open Duo shows it in the trailing pane.
struct ReceiveFormatList: View {
    let request: ReceiveRequest?
    let address: String?
    let selected: ReceiveFormat
    let amount: String?
    let onSelect: (ReceiveFormat) -> Void
    let onCopy: (ReceiveFormat, String) -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: 0) {
            ForEach(ReceiveFormat.allCases) { format in
                row(format, last: format == ReceiveFormat.allCases.last)
            }
        }
    }

    private func row(_ format: ReceiveFormat, last: Bool) -> some View {
        let payload = format.payload(request: request, address: address)
        let enabled = payload != nil
        return HStack(spacing: 12) {
            Button {
                onSelect(format)
            } label: {
                HStack(spacing: 12) {
                    Circle()
                        .fill(format == selected ? NoahTokens.Colors.foreground : .clear)
                        .overlay { if format != selected { Circle().strokeBorder(NoahTokens.Colors.hairline, lineWidth: 1.5) } }
                        .frame(width: 10, height: 10)
                        .animation(.easeOut(duration: 0.2), value: selected)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(LocalizedStringKey(format.name)).noahText(HomeText.strong).foregroundStyle(NoahTokens.Colors.foreground)
                        Group {
                            if let payload { Text(verbatim: payload) }
                            else if format == .invoice { Text("Add an amount first") }
                            else if format == .lightningAddress { Text("Lightning address unavailable") }
                            else { Text(verbatim: "…") }
                        }
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(NoahTokens.Colors.foreground)
                        .lineLimit(1).truncationMode(.middle)
                        Text(verbatim: format.subtitle(amount: amount, locale: locale.identifier))
                            .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                            .lineLimit(2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(minHeight: 66)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(format == selected ? .isSelected : [])
            .accessibilityHint(Text("Show"))
            .accessibilityIdentifier("receive-format-\(format.rawValue)")
            Button {
                if let payload { onCopy(format, payload) }
            } label: {
                Image(systemName: "doc.on.doc").font(.system(size: 15, weight: .medium))
                    .foregroundStyle(NoahTokens.Colors.foreground)
                    .frame(width: 36, height: 36)
                    .contentShape(Circle())
                    .glassCompat(in: Circle(), interactive: enabled)
                    .frame(width: NoahTokens.Size.hitTarget, height: NoahTokens.Size.hitTarget)
            }
            .buttonStyle(HomePressStyle())
            .disabled(!enabled)
            .accessibilityLabel(Text(L10n.text("Copy {label}", L10n.text(format.name, locale: locale.identifier), locale: locale.identifier)))
            .accessibilityIdentifier("receive-copy-\(format.rawValue)")
        }
        .opacity(enabled ? 1 : 0.35)
        .overlay(alignment: .bottom) { if !last { Rectangle().fill(NoahTokens.Colors.hairline).frame(height: 1) } }
    }
}

/// The phone's sheet around the list.
struct ReceiveCopySheet: View {
    let request: ReceiveRequest?
    let address: String?
    let selected: ReceiveFormat
    let amount: String?
    let onSelect: (ReceiveFormat) -> Void
    let onCopy: (ReceiveFormat, String) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text("Ways to pay you").noahText(HomeText.listTitle).foregroundStyle(NoahTokens.Colors.foreground)
                    .accessibilityAddTraits(.isHeader)
                ReceiveFormatList(request: request, address: address, selected: selected, amount: amount,
                                  onSelect: onSelect, onCopy: onCopy)
            }
            .padding(.horizontal, 22).padding(.top, 26).padding(.bottom, 20)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}
