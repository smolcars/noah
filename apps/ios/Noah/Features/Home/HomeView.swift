import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            ScrollView {
                VStack(spacing: 28) {
                    if let error = model.walletError {
                        NoahCard {
                            Label(error, systemImage: "exclamationmark.triangle")
                                .font(NoahFont.callout)
                                .foregroundStyle(NoahColor.destructive)
                        }
                    }

                    BalanceHeader(
                        sats: model.summary.map { Int64($0.balance.totalSats) },
                        unit: model.amountUnit,
                        isHidden: $model.isBalanceHidden)
                        .padding(.top, 24)

                    NoahCard {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("Recent Activity")
                                .font(NoahFont.headline)
                                .foregroundStyle(NoahColor.foreground)
                            if let count = model.summary?.movementCount, count > 0 {
                                Text("\(count) payments")
                                    .font(NoahFont.body)
                                    .foregroundStyle(NoahColor.mutedForeground)
                            } else {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("No activity yet")
                                        .font(NoahFont.callout)
                                        .foregroundStyle(NoahColor.foreground)
                                    Text("Receive or send bitcoin to start your history.")
                                        .font(NoahFont.footnote)
                                        .foregroundStyle(NoahColor.mutedForeground)
                                }
                            }
                        }
                    }

                    Text("In code we trust.")
                        .font(NoahFont.footnote)
                        .foregroundStyle(NoahColor.mutedForeground)
                        .padding(.top, 24)
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .refreshable { await model.sync() }
            .background(NoahColor.background.ignoresSafeArea())
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    Button("Settings", systemImage: "gearshape") {}
                    Button("Bitcoin Map", systemImage: "map") {}
                }
                ToolbarItem(placement: .principal) {
                    VariantBadge(variant: model.variant)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("QR", systemImage: "qrcode") {}
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct BalanceHeader: View {
    let sats: Int64?
    let unit: BitcoinAmountUnit
    @Binding var isHidden: Bool

    var body: some View {
        HStack(spacing: 14) {
            Group {
                if isHidden {
                    Text(verbatim: "••••••")
                } else if let sats {
                    Text(BitcoinAmount.format(sats: sats, unit: unit))
                        .contentTransition(.numericText())
                } else {
                    ProgressView()
                }
            }
            .font(NoahFont.inter(44, .bold, relativeTo: .largeTitle))
            .foregroundStyle(NoahColor.foreground)
            .minimumScaleFactor(0.5)
            .lineLimit(1)

            Button {
                withAnimation(.snappy) { isHidden.toggle() }
            } label: {
                Image(systemName: isHidden ? "eye" : "eye.slash")
                    .font(.title3)
                    .foregroundStyle(NoahColor.foreground)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(isHidden ? "Show balance" : "Hide balance")
        }
        .sensoryFeedback(.selection, trigger: isHidden)
    }
}

struct VariantBadge: View {
    let variant: AppVariant

    var body: some View {
        switch variant {
        case .mainnet:
            EmptyView()
        case .signet:
            badge("SIGNET", color: .orange)
        case .regtest:
            badge("REGTEST", color: .yellow)
        }
    }

    private func badge(_ text: String, color: Color) -> some View {
        Text(verbatim: text)
            .font(NoahFont.inter(13, .bold))
            .foregroundStyle(.black)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(color, in: Capsule())
    }
}
