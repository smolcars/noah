import SwiftUI

struct NoahDetailRow: View {
    let label: String
    let value: String
    var copyable = false
    var displayedValue: String?
    var copyIdentifier = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(LocalizedStringKey(label)).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                Spacer()
                if copyable {
                    Button {
                        UIPasteboard.general.setItems(
                            [[UIPasteboard.typeAutomatic: value]],
                            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
                    } label: { Image(systemName: "doc.on.doc").frame(minWidth: 44, minHeight: 44) }
                        .accessibilityLabel(Text(L10n.text("Copy {label}", L10n.text(label))))
                        .accessibilityIdentifier(copyIdentifier)
                }
            }
            Text(displayedValue ?? value).font(NoahFont.callout).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            Divider().padding(.top, 8)
        }
        .padding(.vertical, 8)
    }
}
