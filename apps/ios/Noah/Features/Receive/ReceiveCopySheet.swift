import SwiftUI

struct ReceiveCopySheet: View {
    @Environment(\.locale) private var locale

    @Environment(\.dismiss) private var dismiss
    let request: ReceiveRequest
    @State private var copied: String?

    var body: some View {
        NavigationStack {
            List {
                Text("Copy the unified request or a specific payment method.")
                    .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                if let uri = try? request.uri() { row("Payment request", id: "request", value: uri, icon: "qrcode") }
                row("Ark", id: "ark", value: request.arkAddress, icon: "tray.and.arrow.down")
                if let invoice = request.lightningInvoice { row("Lightning", id: "lightning", value: invoice, icon: "bolt") }
                row("On-chain", id: "onchain", value: request.onchainAddress, icon: "bitcoinsign.circle")
            }
            .readableWidth()
            .navigationTitle("Copy payment details").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private func row(_ title: LocalizedStringKey, id: String, value: String, icon: String) -> some View {
        Button {
            UIPasteboard.general.string = value
            copied = id
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            UIAccessibility.post(notification: .announcement, argument: L10n.text("Copied", locale: locale.identifier))
        } label: {
            HStack(spacing: 14) {
                Image(systemName: icon).frame(width: 24).foregroundStyle(NoahColor.brand)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(NoahFont.headline)
                    Text(value).font(NoahFont.footnote).lineLimit(1).truncationMode(.middle)
                        .foregroundStyle(NoahColor.mutedForeground)
                }
                Spacer(minLength: 0)
                Text(LocalizedStringKey(copied == id ? "Copied" : "Copy")).font(NoahFont.footnote)
                    .foregroundStyle(copied == id ? NoahColor.success : NoahColor.brand)
            }
            .foregroundStyle(NoahColor.foreground).padding(.vertical, 4)
        }
        .accessibilityIdentifier("receive-copy-\(id)")
    }
}
