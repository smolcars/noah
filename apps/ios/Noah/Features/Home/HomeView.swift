import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(HistoryModel.self) private var history
    @Environment(\.scenePhase) private var scenePhase
    @State private var home = HomeModel()
    @State private var showEmail = false
    @State private var showMap = false
    @State private var showQR = false
    @State private var selectedID: String?
    let isActive: Bool
    let onScan: (String) -> Void
    let onOpenHistory: () -> Void
    let onRepeatPayment: (RepeatPayment) -> Void

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            ScrollView {
                VStack(spacing: 28) {
                    if !model.isWalletSuspended { UpdateWarningView() }
                    if let error = model.walletError {
                        NoahCard {
                            Label(error, systemImage: "exclamationmark.triangle")
                                .font(NoahFont.callout)
                                .foregroundStyle(NoahColor.destructive)
                        }
                    }

                    if model.isWalletSuspended {
                        SuspendedWalletCard()
                            .padding(.top, 24)
                    } else {
                        BalanceHeader(
                            sats: model.summary.map { Int64($0.balance.totalSats) },
                            unit: model.amountUnit,
                            btcPrice: home.btcPrice, currency: model.preferredCurrency,
                            isHidden: $model.isBalanceHidden)
                            .padding(.top, 24)
                    }

                    if !model.isWalletSuspended {
                        HomeStatusBanners(home: home)
                        AutoBoardingService()
                    }

                    // Like RN, a suspended wallet shows only the suspended card.
                    if !model.isWalletSuspended && model.server.state.isEmailVerified != true
                        && model.server.state.isEmailPromptDismissed != true
                    {
                        NoahCard {
                            HStack(alignment: .top, spacing: 12) {
                                Button { showEmail = true } label: {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Label("Add Emergency Email", systemImage: "envelope")
                                            .font(NoahFont.headline)
                                        Text("Optional alerts can help us reach you if your VTXOs need attention before they expire.")
                                            .font(NoahFont.footnote)
                                            .foregroundStyle(NoahColor.mutedForeground)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .buttonStyle(.plain)
                                Button("Dismiss emergency email prompt", systemImage: "xmark") {
                                    model.server.dismissEmailPrompt()
                                }
                                .labelStyle(.iconOnly)
                                .frame(minWidth: 44, minHeight: 44)
                            }
                        }
                    }

                    if !model.isWalletSuspended {
                        NoahCard {
                            VStack(alignment: .leading, spacing: 16) {
                                HStack {
                                    Text("Recent Activity").font(NoahFont.headline)
                                    Spacer()
                                    Button("View all", action: onOpenHistory).font(NoahFont.footnote)
                                }
                                if history.isLoading {
                                    ProgressView("Loading transactions")
                                } else if let error = history.error {
                                    Text(error).font(NoahFont.footnote)
                                    Button("Retry") { Task { await history.refresh(currency: model.preferredCurrency) } }
                                } else if history.transactions.isEmpty {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text("No activity yet").font(NoahFont.callout)
                                        Text("Receive or send bitcoin to start your history.")
                                            .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                                    }
                                } else {
                                    ForEach(Array(history.transactions.prefix(3))) { transaction in
                                        Button { selectedID = transaction.id } label: {
                                            TransactionRow(transaction: transaction, unit: model.amountUnit, recent: true)
                                                .padding(.vertical, 8)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }

                    if !model.isWalletSuspended {
                        Text(home.fact)
                        .font(NoahFont.footnote)
                        .foregroundStyle(NoahColor.mutedForeground)
                        .padding(.top, 24)
                    }
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .task { await model.versionCheck.checkOnce() }
            .task(id: model.summary?.fingerprint) {
                guard model.summary != nil, !model.isWalletSuspended else { return }
                await home.refresh(currency: model.preferredCurrency)
                while !Task.isCancelled {
                    do { try await Task.sleep(for: .seconds(1)) } catch { return }
                    if scenePhase == .active { await home.tick() }
                }
            }
            .task(id: model.summary?.fingerprint) {
                guard model.summary != nil, !model.isWalletSuspended else { return }
                await home.observeBackground()
            }
            .task(id: model.summary?.fingerprint) {
                guard model.summary != nil, !model.isWalletSuspended else { return }
                await home.observeWallet()
            }
            .task(id: model.preferredCurrency) { await home.loadPrice(currency: model.preferredCurrency) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active && !model.isWalletSuspended {
                    Task {
                        await home.tick()
                        await home.refreshRounds(force: true)
                        await home.loadPrice(currency: model.preferredCurrency)
                    }
                }
            }
            .refreshable {
                guard !model.isWalletSuspended else { return }
                await model.sync()
                guard !model.isWalletSuspended else { return }
                await history.refresh(currency: model.preferredCurrency)
                await home.refresh(currency: model.preferredCurrency)
            }
            .sheet(isPresented: Binding(get: { selectedID != nil }, set: { if !$0 { selectedID = nil } })) {
                if let transaction = history.transactions.first(where: { $0.id == selectedID }) {
                    NavigationStack {
                        TransactionDetailView(transaction: transaction, onRepeatPayment: { details in
                            selectedID = nil
                            onRepeatPayment(details)
                        }, onClose: { selectedID = nil })
                    }
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                }
            }
            .background(NoahColor.background.ignoresSafeArea())
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    NavigationLink { SettingsView() } label: { Label("Open settings", systemImage: "gearshape") }
                        .accessibilityIdentifier("home-settings-button")
                    Button("Find places that accept bitcoin", systemImage: "map") { showMap = true }
                        .accessibilityIdentifier("home-btc-map-button")
                }
                ToolbarItem(placement: .principal) {
                    VariantBadge(variant: model.variant)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Open QR code", systemImage: "qrcode") { showQR = true }
                        .accessibilityIdentifier("home-qr-button")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(isPresented: $showQR) { QRHubView(isActive: isActive, onScan: onScan) }
            .navigationDestination(isPresented: $showMap) { BtcMapView() }
            .navigationDestination(isPresented: $showEmail) {
                EmergencyEmailView(fromOnboarding: false, onContinue: { showEmail = false })
            }
        }
    }
}

private struct BalanceHeader: View {
    let sats: Int64?
    let unit: BitcoinAmountUnit
    let btcPrice: Double?
    let currency: String
    @Environment(\.locale) private var locale
    @Binding var isHidden: Bool

    var body: some View {
        VStack(spacing: 12) {
            if isHidden {
                Text(HomeModel.hiddenFiat(currency: currency))
                    .font(NoahFont.title).foregroundStyle(NoahColor.mutedForeground)
            } else if let sats, let btcPrice {
                Text(FiatCurrency.display(sats: sats, btcPrice: btcPrice, currency: currency, locale: locale))
                    .font(NoahFont.title).foregroundStyle(NoahColor.mutedForeground)
            } else { ProgressView().frame(height: 32) }
            HStack(spacing: 14) {
            Group {
                if isHidden {
                    Text(verbatim: "••••")
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
            .accessibilityLabel(isHidden ? "Show wallet balance" : "Hide wallet balance")
        }
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

/// RN Home's suspended state: every wallet operation is off until resumed.
private struct SuspendedWalletCard: View {
    var body: some View {
        NoahCard {
            VStack(spacing: 12) {
                Image(systemName: "pause.circle")
                    .font(.system(size: 44))
                    .foregroundStyle(NoahColor.destructive)
                Text("Wallet Suspended")
                    .font(NoahFont.title)
                    .foregroundStyle(NoahColor.foreground)
                Text("Your wallet is currently suspended. All wallet operations are disabled.")
                    .font(NoahFont.body)
                    .foregroundStyle(NoahColor.mutedForeground)
                    .multilineTextAlignment(.center)
                Text("Go to Settings → Danger Zone to resume your wallet.")
                    .font(NoahFont.footnote)
                    .foregroundStyle(NoahColor.mutedForeground)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
        }
    }
}
