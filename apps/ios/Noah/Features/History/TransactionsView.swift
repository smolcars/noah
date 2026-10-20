import SwiftUI

struct TransactionsView: View {
    @Environment(AppModel.self) private var app
    @Environment(HistoryModel.self) private var history
    @State private var filter = HistoryFilter.all
    @State private var selectedID: String?
    @State private var showSheet = false
    let onRepeatPayment: (RepeatPayment) -> Void

    private var filtered: [HistoryTransaction] { history.transactions.filter(filter.includes) }
    private var selected: HistoryTransaction? { history.transactions.first { $0.id == selectedID } }

    var body: some View {
        GeometryReader { geometry in
            let expanded = geometry.size.width >= 700
            HStack(spacing: 0) {
                VStack(spacing: 16) {
                    Picker("Transaction filter", selection: $filter) {
                        ForEach(HistoryFilter.allCases) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("transaction-filter")
                    .padding(.horizontal, 20)

                    ScrollView {
                        LazyVStack(spacing: 0) {
                            if history.isLoading {
                                ProgressView("Loading transactions").padding(40)
                            } else if let error = history.error {
                                ContentUnavailableView {
                                    Label("Failed to load transactions", systemImage: "exclamationmark.triangle")
                                } description: { Text(LocalizedStringKey(error)) } actions: {
                                    Button("Retry") { Task { await history.refresh(currency: app.preferredCurrency) } }
                                }
                            } else if filtered.isEmpty {
                                ContentUnavailableView("No transactions yet", systemImage: "clock.arrow.circlepath",
                                                       description: Text("Your payments will appear here."))
                            } else {
                                ForEach(filtered) { transaction in
                                    Button { selectedID = transaction.id; showSheet = true } label: {
                                        TransactionRow(transaction: transaction, unit: app.amountUnit)
                                            .padding(.horizontal, 20).padding(.vertical, 16)
                                            .background(selectedID == transaction.id && expanded ? NoahColor.card : Color.clear)
                                    }
                                    .buttonStyle(.plain)
                                    Divider().padding(.leading, 64)
                                }
                            }
                        }
                    }
                    .refreshable { await history.refresh(currency: app.preferredCurrency, sync: true) }
                }
                .padding(.top, 12)
                .frame(maxWidth: .infinity)
                if expanded {
                    Divider()
                    Group {
                        if let selected {
                            TransactionDetailView(transaction: selected, onRepeatPayment: repeatPayment, onClose: { selectedID = nil })
                        } else {
                            ContentUnavailableView("Select a transaction", systemImage: "doc.text.magnifyingglass",
                                                   description: Text("Choose a payment to see its details."))
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityIdentifier("history-detail-pane")
                }
            }
            // Selection survives the system dismissing the phone sheet during rotation.
            .sheet(isPresented: Binding(get: { !expanded && showSheet && selected != nil }, set: { showSheet = $0 })) {
                if let selected {
                    NavigationStack {
                        TransactionDetailView(transaction: selected, onRepeatPayment: repeatPayment, onClose: { selectedID = nil })
                    }
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                }
            }
        }
        .background(NoahColor.background.ignoresSafeArea())
        .navigationTitle("Transactions")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Refresh transaction history", systemImage: "arrow.clockwise") {
                    Task { await history.refresh(currency: app.preferredCurrency, sync: true) }
                }
                .disabled(history.isRefreshing)
                ShareLink(item: HistoryCSV(transactions: filtered, currency: app.preferredCurrency),
                          preview: SharePreview("Noah Wallet Transaction Export")) {
                    Label("Export transactions", systemImage: "square.and.arrow.up")
                }
                .disabled(history.isRefreshing)
                .accessibilityIdentifier("transactions-share-button")
            }
        }
    }

    private func repeatPayment(_ details: RepeatPayment) {
        selectedID = nil
        onRepeatPayment(details)
    }
}

struct TransactionRow: View {
    @Environment(\.locale) private var locale
    let transaction: HistoryTransaction
    let unit: BitcoinAmountUnit
    var recent = false

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            Image(systemName: transaction.symbol)
                .font(.title3).frame(width: 28)
                .foregroundStyle(transaction.amountColor)
            VStack(alignment: .leading, spacing: 5) {
                Text(LocalizedStringKey(transaction.displayLabel)).font(NoahFont.headline)
                Text(recent ? transaction.recentDate : transaction.formattedDate).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 5) {
                Text(transaction.signedAmount(unit: unit, locale: locale))
                    .font(NoahFont.headline).monospacedDigit().foregroundStyle(transaction.amountColor)
                if !recent, let status = transaction.statusLabel {
                    Text(LocalizedStringKey(status)).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                }
            }
        }
        .foregroundStyle(NoahColor.foreground)
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
    }
}

extension HistoryTransaction {
    var symbol: String {
        if movementKind == "onboard" { return "arrow.right.to.line" }
        if movementKind == "offboard" { return "arrow.left.to.line" }
        switch type {
        case "Bolt11", "Lnurl": return "bolt"
        case "Arkoor": return "sailboat"
        default: return "cube"
        }
    }
    var amountColor: Color {
        if isCanceled { return NoahColor.mutedForeground }
        let light: UInt32 = isInternalTransfer ? 0x945200 : direction == "outgoing" ? 0xB42318 : 0x137A36
        let dark: UInt32 = isInternalTransfer ? 0xFDBA74 : direction == "outgoing" ? 0xFF8080 : 0x4ADE80
        return Color(uiColor: UIColor { UIColor(hex: $0.userInterfaceStyle == .dark ? dark : light) })
    }
    var recentDate: String {
        confirmationLabel ?? timestamp?.formatted(.dateTime.month(.abbreviated).day()) ?? date
    }
    var formattedDate: String {
        confirmationLabel ?? timestamp?.formatted(date: .abbreviated, time: .shortened) ?? date
    }
    func signedAmount(unit: BitcoinAmountUnit, locale: Locale = .current) -> String {
        let prefix = isCanceled || isInternalTransfer ? "" : direction == "outgoing" ? "−" : "+"
        return prefix + BitcoinAmount.format(sats: amount, unit: unit, locale: locale)
    }
}
