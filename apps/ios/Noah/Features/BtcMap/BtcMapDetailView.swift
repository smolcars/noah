import SwiftUI

struct BtcMapDetailView: View {
    @Environment(\.locale) private var locale

    let place: BtcMapPlace
    // swiftlint:disable:next discouraged_optional_collection - nil without a location fix
    let location: [Double]?
    let repository: BtcMapRepository
    var onClose: () -> Void
    @Environment(\.openURL) private var openURL
    @State private var detail: BtcMapDetail?
    @State private var loading = true
    @State private var failed = false
    @State private var linkFailed = false
    @State private var copied = false
    private var fields: [String: String] { detail?.fields ?? [:] }
    private var merchant: BtcMapPlace { detail?.place ?? place }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { actions }
                    VStack(alignment: .leading, spacing: 12) { actions }
                }
                .labelStyle(BtcMapActionLabelStyle())
                if loading { ProgressView("Loading details…") }
                if failed { Text("Live details are unavailable. The bundled places still work offline.").font(NoahFont.callout).foregroundStyle(.secondary) }
                facts
            }.padding(20).readableWidth()
        }
        .task(id: place.id) {
            detail = nil; loading = true; failed = false; copied = false
            do {
                let result = try await repository.detail(for: place)
                guard !Task.isCancelled else { return }
                detail = result
            } catch { if !Task.isCancelled { failed = true } }
            if !Task.isCancelled { loading = false }
        }
        .alert("Cannot open link", isPresented: $linkFailed) {
            Button("OK", role: .cancel) {}
        } message: { Text("No app is available to open this link.") }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 8) {
                Text(merchant.name).font(NoahFont.title)
                Label(LocalizedStringKey(place.category.label), systemImage: place.category.symbol).font(NoahFont.footnote).foregroundStyle(.secondary)
                if let boost = place.boosted_until, let date = parseDate(boost), date > .now {
                    Text("Featured").font(NoahFont.footnote).foregroundStyle(NoahColor.brand)
                }
                if let location {
                    Text(BtcMapDistance.format(BtcMapDistance.kilometers(from: location, to: place.center), locale: locale)).font(NoahFont.footnote)
                }
                if let verified = merchant
                    .verified_at {
                    Text(L10n.text("Verified {date}", dateLabel(verified), locale: locale.identifier)).font(NoahFont.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 4)
            Button("Close", systemImage: "xmark", action: onClose)
                .labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44).glassButtonStyle()
        }
    }

    @ViewBuilder private var facts: some View {
        if let address = fields["address"] {
            Button {
                UIPasteboard.general.string = address
                copied = true
            } label: {
                Label(address, systemImage: copied ? "checkmark" : "doc.on.doc")
                    .multilineTextAlignment(.leading)
            }.buttonStyle(.plain).accessibilityLabel(Text(LocalizedStringKey(copied ? "Address copied" : L10n.text(
                "Copy address: {value1}",
                address,
                locale: locale.identifier))))
        }
        if let hours = fields["opening_hours"] { Label(hours, systemImage: "clock").font(NoahFont.callout) }
        if let description = fields["description"] { Text(description).font(NoahFont.callout) }
        if !paymentMethods.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Pay with").font(NoahFont.headline)
                Text(paymentMethods.joined(separator: " · ")).font(NoahFont.callout)
            }
        }
        if let app = fields["required_app_url"] {
            Button("This place may require another payment app") { open(app) }.font(NoahFont.callout)
        }
        ForEach(["twitter", "facebook", "instagram", "telegram"], id: \.self) { key in
            if let url = fields[key] {
                Button(key == "twitter" ? "X" : key.capitalized, systemImage: "arrow.up.right.square") { open(url) }
            }
        }
        ForEach(detail?.comments ?? []) { comment in
            VStack(alignment: .leading, spacing: 8) {
                Divider()
                Text(comment.text).font(NoahFont.callout)
                Text(dateLabel(comment.created_at)).font(NoahFont.footnote).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder private var actions: some View {
        Button("Directions", systemImage: "arrow.triangle.turn.up.right.diamond") {
            var url = URL(literal: "https://maps.apple.com/")
            url.append(queryItems: [URLQueryItem(name: "daddr", value: "\(place.lat),\(place.lon)"), URLQueryItem(name: "q", value: place.name)])
            openURL(url) { if !$0 { linkFailed = true } }
        }
        if let website = fields["website"] { Button("Website", systemImage: "globe") { open(website) } }
        if let phone = fields["phone"] {
            Button("Call", systemImage: "phone") {
                guard let url = BtcMapLinks.phone(phone) else { linkFailed = true; return }
                openURL(url) { if !$0 { linkFailed = true } }
            }
        }
        ShareLink(item: "\(place.name)\nhttps://btcmap.org/merchant/\(place.id)") { Label("Share", systemImage: "square.and.arrow.up") }
    }

    private var paymentMethods: [String] {
        [("osm:payment:lightning", "Lightning"), ("osm:payment:lightning_contactless", "Contactless"), ("osm:payment:onchain", "On-chain")]
            .compactMap { fields[$0.0] == "yes" ? $0.1 : nil } + [fields["payment_provider"]].compactMap(\.self)
    }
    private func open(_ text: String) {
        guard let url = BtcMapLinks.web(text) else { linkFailed = true; return }
        openURL(url) { if !$0 { linkFailed = true } }
    }
    private func parseDate(_ text: String) -> Date? {
        (try? Date(text, strategy: .iso8601)) ?? (try? Date(text, strategy: .iso8601.year().month().day()))
    }
    private func dateLabel(_ text: String) -> String {
        parseDate(text)?.formatted(.dateTime.month(.abbreviated).year()) ?? text
    }
}

private struct BtcMapActionLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: 6) {
            configuration.icon.font(.body)
            configuration.title.font(.caption)
        }
        .frame(minWidth: 60, minHeight: 48)
        .contentShape(Rectangle())
    }
}
