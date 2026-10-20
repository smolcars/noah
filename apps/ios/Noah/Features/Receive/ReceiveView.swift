import CoreImage.CIFilterBuiltins
import SwiftUI

struct ReceiveView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    let isActive: Bool
    let done: () -> Void
    @State private var model = ReceiveModel()
    @State private var sheet: Sheet?
    @State private var btcPrice: Double?
    private enum Sheet: String, Identifiable { case amount, copy; var id: String { rawValue } }

    var body: some View {
        Group {
            if let sats = model.receivedSats {
                ReceiveSuccessView(amountSat: sats, btcPrice: btcPrice, done: done)
            } else {
                content
                    .navigationTitle("Receive")
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Show payment details", systemImage: "doc.on.doc") { sheet = .copy }
                                .disabled(model.request == nil)
                                .accessibilityIdentifier("receive-copy-button")
                        }
                    }
            }
        }
        .background(NoahColor.background.ignoresSafeArea())
        .task(id: isActive) {
            if isActive {
                async let rate: Void = loadRate()
                await model.start()
                await rate
            } else { model.stop(); sheet = nil }
        }
        .onDisappear { model.stop() }
        .onChange(of: model.receivedSats) { _, sats in
            if sats != nil {
                sheet = nil
                Task { await app.refresh() }
            }
        }
        .onChange(of: model.baseError) { _, error in if error != nil { sheet = nil } }
        .sheet(item: $sheet) { sheet in
            if let request = model.request {
                switch sheet {
                case .amount:
                    ReceiveAmountSheet(request: request, model: model, btcPrice: btcPrice)
                        .presentationDetents([.large])
                case .copy:
                    ReceiveCopySheet(request: request)
                        .presentationDetents([.medium, .large])
                }
            }
        }
    }

    private var content: some View {
        ScrollView {
            VStack(spacing: 28) {
                if let request = model.request, let uri = try? request.uri() {
                    Button { sheet = .copy } label: {
                        VStack(spacing: 0) {
                            ReceiveQRCode(uri: uri)
                                .frame(maxWidth: 270, maxHeight: 270)
                                .padding(16)
                            if let sats = request.amountSat {
                                VStack(spacing: 6) {
                                    Text(BitcoinAmount.format(sats: sats, unit: app.amountUnit, locale: locale))
                                        .font(NoahFont.largeTitle).minimumScaleFactor(0.5).lineLimit(1)
                                    if let btcPrice {
                                        Text(FiatCurrency.display(sats: sats, btcPrice: btcPrice, currency: app.preferredCurrency, locale: locale))
                                            .font(NoahFont.callout)
                                    }
                                }
                                .frame(maxWidth: .infinity).padding(.vertical, 20)
                                .foregroundStyle(Color(hex: 0x1A1A1A)).background(NoahColor.brand)
                            }
                        }
                        .frame(maxWidth: 302)
                        .background(.white, in: RoundedRectangle(cornerRadius: 28))
                        .clipShape(RoundedRectangle(cornerRadius: 28))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Show payment details")
                    .accessibilityHint("Opens options to copy the unified request or an individual payment method")
                    .accessibilityIdentifier("receive-qr-button")
                    Text("Scan to receive bitcoin with Noah")
                        .font(NoahFont.callout).foregroundStyle(NoahColor.mutedForeground).multilineTextAlignment(.center)
                    if request.amountSat == nil {
                        Label("Add an amount to receive over Lightning", systemImage: "bolt")
                            .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                    }
                    if !request.description.isEmpty {
                        Text(request.description).font(NoahFont.body).multilineTextAlignment(.center)
                    }
                    VStack(spacing: 12) {
                        NoahButton(title: request.amountSat == nil ? "Add amount" : "Edit amount") { sheet = .amount }
                            .disabled(model.isSaving).accessibilityIdentifier("receive-amount-button")
                        ShareLink(item: uri) {
                            Label("Share", systemImage: "square.and.arrow.up")
                                .font(NoahFont.headline).frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.bordered).buttonBorderShape(.capsule)
                        .accessibilityIdentifier("receive-share-button")
                    }
                } else if let error = model.baseError {
                    ContentUnavailableView {
                        Label(model.baseErrorTitle, systemImage: "exclamationmark.circle")
                    } description: { Text(LocalizedStringKey(error)) } actions: {
                        NoahButton(title: "Retry") { Task { await model.start() } }
                    }
                } else {
                    ProgressView("Creating your payment request…").padding(60)
                }
            }
            .padding(24).frame(maxWidth: 560).frame(maxWidth: .infinity)
        }
    }

    private func loadRate() async {
        btcPrice = nil
        do {
            let response = try await AppServices.shared.client.fiatPrices(.init(body: .json(.init())))
            let rate = try response.ok.body.json.rates.additionalProperties[app.preferredCurrency]
            if let rate, rate.isFinite, rate > 0 { btcPrice = rate }
        } catch {
            guard !Task.isCancelled else { return }
            AppLog("receive").w("Fiat rate unavailable: \(SafeErrorDescription.forLogging(error))")
        }
    }
}

struct ReceiveQRCode: View {
    let uri: String

    var body: some View {
        if let image = Self.image(uri: uri) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                .overlay {
                    Image("ReceiveLogo").resizable().scaledToFit()
                        .frame(width: 36, height: 36).padding(4)
                        .background(.white, in: RoundedRectangle(cornerRadius: 10))
                }
                .accessibilityHidden(true)
        } else {
            Text("QR code unavailable. Use payment details to copy your request.")
                .foregroundStyle(.black).padding()
        }
    }

    static func image(uri: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(uri.utf8)
        filter.correctionLevel = "H"
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output.transformed(by: .init(scaleX: 6, y: 6)),
                                                   from: output.extent.applying(.init(scaleX: 6, y: 6))) else { return nil }
        return UIImage(cgImage: image)
    }
}
