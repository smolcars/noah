import SwiftUI

struct ReceiveSuccessView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let amountSat: Int64
    let btcPrice: Double?
    let done: () -> Void
    @State private var appeared = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                ZStack {
                    Circle().fill(NoahColor.brand.opacity(0.12)).frame(width: 190, height: 190)
                    Circle().stroke(NoahColor.brand.opacity(0.3), lineWidth: 2)
                        .frame(width: 150, height: 150)
                        .scaleEffect(appeared ? 1.15 : 0.8)
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 90)).foregroundStyle(NoahColor.brand)
                        .scaleEffect(appeared ? 1 : 0.4)
                }
                .accessibilityHidden(true)
                Text(BitcoinAmount.format(sats: amountSat, unit: app.amountUnit, locale: locale))
                    .font(NoahFont.largeTitle).minimumScaleFactor(0.5).lineLimit(1)
                    .accessibilityIdentifier("receive-success-amount")
                if let btcPrice {
                    Text("≈ " + FiatCurrency.display(sats: amountSat, btcPrice: btcPrice, currency: app.preferredCurrency, locale: locale))
                        .font(NoahFont.callout).foregroundStyle(NoahColor.mutedForeground)
                }
                Text("Funds received").font(NoahFont.title).accessibilityIdentifier("receive-success")
                VStack(spacing: 20) {
                    Divider()
                    HStack {
                        Text("Status").foregroundStyle(NoahColor.mutedForeground)
                        Spacer()
                        Text("Settled").foregroundStyle(NoahColor.brand)
                    }
                    Divider()
                    HStack {
                        Text("Wallet balance").foregroundStyle(NoahColor.mutedForeground)
                        Spacer()
                        Text(app.summary.map {
                            BitcoinAmount.format(sats: Int64(clamping: $0.balance.totalSats), unit: app.amountUnit, locale: locale)
                        } ?? "…")
                    }
                }.font(NoahFont.callout)
                NoahButton(title: "Done", action: done).padding(.top, 16)
                    .accessibilityIdentifier("receive-success-done")
            }
            .padding(24).padding(.top, 24).frame(maxWidth: 460).frame(maxWidth: .infinity)
            .opacity(appeared ? 1 : 0)
        }
        .navigationBarBackButtonHidden()
        .task {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            withAnimation(reduceMotion ? nil : .spring(duration: 0.52, bounce: 0.25)) { appeared = true }
        }
    }
}
