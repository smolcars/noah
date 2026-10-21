import SwiftUI

struct UnilateralExitView: View {
    @Environment(AppModel.self) private var app
    @State private var model = OnchainModel()
    @State private var selected = Set<String>()
    private var destination: String { model.claimDestination }
    @State private var showDeposit = false
    @State private var depositFee: UInt64 = 0
    @State private var filter: String?
    private var exits: [ExitModel] { model.overview?.exits ?? [] }
    private var eligible: [VtxoModel] { (model.overview?.vtxos ?? []).filter(\.eligible) }
    private var pending: [ExitModel] {
        exits.filter { !["Canceled", "Claimed", "VtxoAlreadySpent", "VtxoSwept"].contains($0.state) }
    }
    var body: some View {
        AdaptiveForm {
            Section {
                Text("Emergency Exit").font(NoahFont.title)
                Text(
                    "Recover your Ark funds onchain if the Ark server is unavailable. Exits require confirmed onchain funds for broadcast fees and a timelock before claiming."
                )
                Text("Reading this screen and estimating fees never broadcasts an exit.").foregroundStyle(
                    NoahColor.mutedForeground)
                if let overview = model.overview {
                    ExitOverviewSummary(overview: overview)
                }
                Button("Deposit Onchain Funds") { showDeposit = true }
            }
        } secondary: {
            Section("Start an exit") {
                Text("VTXOs below 330 sats cannot exit onchain.").font(NoahFont.footnote)
                Button("Select all eligible VTXOs") { selected = Set(eligible.map(\.id)) }
                ForEach(eligible) { vtxo in
                    Toggle(
                        isOn: Binding(
                            get: { selected.contains(vtxo.id) },
                            set: { value in
                                if value { selected.insert(vtxo.id) } else { selected.remove(vtxo.id) }
                            })
                    ) { OnchainAmountRow(title: String(vtxo.id.prefix(12)) + "…", sats: vtxo.amount) }
                }
                if eligible.isEmpty { Text("No eligible spendable VTXOs.") }
                Button("Review selected exit") {
                    Task { await model.review(.startExit, ids: Array(selected)) }
                }
                .disabled(selected.isEmpty || model.locked)
                Button("Review entire wallet exit") {
                    Task { await model.review(.startExit, ids: eligible.map(\.id)) }
                }
                .disabled(eligible.isEmpty || model.locked)
            }
            if !pending.isEmpty {
                Section("Progress") {
                    Text(
                        "Noah progresses exits automatically. You can also progress all pending exits manually."
                    )
                    Button("Progress Exits") {
                        Task { await model.review(.progress, ids: pending.map(\.id)) }
                    }
                    .disabled(model.locked)
                }
            }
            if exits.contains(where: \.claimable) {
                Section("Claim recovered funds") {
                    TextField("Bitcoin destination address", text: Binding(get: { destination }, set: model.editClaimDestination))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    if let error = model.claimDestinationError {
                        Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive)
                    } else if model.claimDestinationIsMine == false {
                        Text("This address isn't in your Noah wallet. The claimed funds will be sent there.")
                            .foregroundStyle(NoahColor.destructive)
                    }
                    Button("Use my Noah wallet address") { Task { await model.useWalletClaimAddress() } }
                        .disabled(model.locked)
                    Button("Review Claim") {
                        Task {
                            await model.review(
                                .claim, ids: exits.filter(\.claimable).map(\.id),
                                destination: destination.trimmingCharacters(in: .whitespacesAndNewlines))
                        }
                    }.disabled(
                        destination.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.locked)
                }
            }
            Section("Exits") {
                Picker("Status", selection: $filter) {
                    Text("All").tag(nil as String?)
                    ForEach(ExitTimeline.labels.keys.sorted(), id: \.self) {
                        Text(LocalizedStringKey(ExitTimeline.labels[$0]!)).tag(Optional($0))
                    }
                }
                if exits.isEmpty { Text("No exits have been started.") }
                ForEach(exits.filter { filter == nil || $0.state == filter }) { exit in
                    NavigationLink {
                        ExitVtxoDetailView(id: exit.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            OnchainAmountRow(
                                title: ExitTimeline.labels[exit.state] ?? exit.state, sats: exit.amount)
                            Text(exit.id).font(NoahFont.footnote).lineLimit(1).truncationMode(.middle)
                        }
                    }
                }
            }
            OnchainStatusSection(model: model)
        }
        .settingsStyle("Emergency Exit", maximumWidth: AdaptiveLayout.pairedWidth)
        .scrollDismissesKeyboard(.interactively)
        .modifier(OnchainScreen(model: model))
        .onChange(of: selected) { _, _ in model.invalidate() }
        .task(id: model.overview?.walletID) {
            if model.overview != nil, destination.isEmpty { await model.useWalletClaimAddress() }
        }
        .task(id: destination + (model.overview?.walletID ?? "") + String(app.isWalletSuspended)) {
            await model.checkClaimDestination()
        }
        .onChange(of: model.quote) { _, quote in depositFee = quote?.chainFee ?? 0 }
        .sheet(isPresented: $showDeposit) { ExitDepositSheet(broadcastFee: depositFee) }
    }
}

struct ExitOverviewSummary: View {
    let overview: OnchainOverview
    private var exits: [ExitModel] { overview.exits }
    private var eligible: [VtxoModel] { overview.vtxos.filter(\.eligible) }
    private var pending: [ExitModel] { exits.filter { !["Canceled", "Claimed", "VtxoAlreadySpent", "VtxoSwept"].contains($0.state) } }
    var body: some View {
        LabeledContent("Tracked", value: "\(exits.count)")
        LabeledContent("Pending", value: "\(pending.count)")
        LabeledContent("Claimable", value: "\(exits.filter(\.claimable).count)")
        LabeledContent("Claiming", value: "\(exits.filter { $0.state == "ClaimInProgress" }.count)")
        LabeledContent("Claimed", value: "\(exits.filter { $0.state == "Claimed" }.count)")
        LabeledContent("Available", value: "\(eligible.count)")
        OnchainAmountRow(title: "Available Value", sats: eligible.reduce(0) { $0 + $1.amount })
        OnchainAmountRow(title: "Pending exit value", sats: overview.pendingTotal)
        OnchainAmountRow(title: "Confirmed onchain funds", sats: overview.confirmed)
        LabeledContent("Block height", value: "\(overview.tip)")
        if let syncedTip = exits.compactMap({ $0.details.tip_height }).max() {
            LabeledContent("Exit Synced Tip", value: "\(syncedTip)")
        }
        let staleCount = exits.filter { $0.details.tip_height.map { $0 < overview.tip } == true }.count
        if staleCount > 0 {
            Text(L10n.plural("{_gt_value_4} exits are behind the current chain height. Use Progress to check the chain; this can also broadcast exit transactions.", count: staleCount))
        }
        let belowDust = overview.vtxos.filter { $0.state == "Spendable" && $0.amount < ExitRules.dust }
        if !belowDust.isEmpty {
            LabeledContent("Below dust limit", value: "\(belowDust.count)")
            OnchainAmountRow(title: "Below dust limit", sats: belowDust.reduce(0) { $0 + $1.amount })
        }
        if let height = overview.claimableHeight {
            LabeledContent("All claimable at", value: "Block \(height)")
        }
    }
}

struct ExitVtxoDetailView: View {
    let id: String
    @State private var model: OnchainModel
    init(id: String, model: OnchainModel? = nil) {
        self.id = id
        _model = State(initialValue: model ?? OnchainModel(detailID: id))
    }
    var body: some View {
        List {
            if let exit = model.exit {
                Section {
                    OnchainAmountRow(title: "Amount", sats: exit.amount)
                    LabeledContent("VTXO ID") { Text(id).textSelection(.enabled) }
                    LabeledContent("State") { Text(LocalizedStringKey(ExitTimeline.labels[exit.state] ?? exit.state)) }
                    ExitBlockStatusRows(details: exit.details, tip: model.overview?.tip)
                    if exit.cancelable {
                        Button("Cancel Exit", role: .destructive) {
                            Task { await model.review(.cancel, ids: [id]) }
                        }.disabled(model.locked)
                    }
                }
                Section("Current State") { ExitDetailsRows(details: exit.details) }
                ForEach(Array(exit.timeline.enumerated()), id: \.offset) { _, item in
                    Section(item.label + (item.isCurrent ? " · Current" : "")) {
                        Text(LocalizedStringKey(item.description))
                        if item.count > 1 { LabeledContent("Updates", value: "\(item.count)") }
                        if let height = item.startHeight { LabeledContent("First height", value: "\(height)") }
                        if let height = item.endHeight { LabeledContent("Latest height", value: "\(height)") }
                        if let details = item.details { ExitDetailsRows(details: details) }
                    }
                }
            } else if !model.loading {
                Text("Exit status is unavailable. Pull to refresh.")
            }
            OnchainStatusSection(model: model)
        }
        .settingsStyle("Exit Details")
        .modifier(OnchainScreen(model: model))
    }
}

struct ExitBlockStatusRows: View {
    @Environment(\.locale) private var locale
    let details: ExitDetails
    let tip: UInt32?
    var body: some View {
        LabeledContent("Current Height", value: tip.map(String.init) ?? L10n.text("Unknown", locale: locale.identifier))
        LabeledContent("Exit Tip", value: details.tip_height.map(String.init) ?? L10n.text("Unknown", locale: locale.identifier))
        LabeledContent("Claimable Height", value: details.displayClaimableHeight.map(String.init) ?? L10n.text("Unknown", locale: locale.identifier))
        LabeledContent("Remaining", value: OnchainDisplay.remaining(current: tip, target: details.displayClaimableHeight, locale: locale.identifier))
    }
}

struct ExitDetailsRows: View {
    let details: ExitDetails
    var body: some View {
        if let tip = details.tip_height { LabeledContent("Synced tip", value: "\(tip)") }
        if let block = details.confirmed_block {
            LabeledContent("Confirmed block") { Text(OnchainDisplay.block(block)).textSelection(.enabled) }
        }
        if let block = details.claimable_since {
            LabeledContent("Claimable since") { Text(OnchainDisplay.block(block)).textSelection(.enabled) }
        }
        if let block = details.last_scanned_block {
            LabeledContent("Last scanned") { Text(OnchainDisplay.block(block)).textSelection(.enabled) }
        }
        if let block = details.block {
            LabeledContent("Claimed block") { Text(OnchainDisplay.block(block)).textSelection(.enabled) }
        }
        if let height = details.displayClaimableHeight { LabeledContent("Claimable at", value: "\(height)") }
        if let kind = details.kind { LabeledContent("State detail", value: kind) }
        if let txid = details.claim_txid ?? details.txid {
            ExitTransactionRow(title: "Claim transaction", txid: txid)
        }
        ForEach(Array((details.transactions ?? []).enumerated()), id: \.offset) { index, tx in
            ExitTransactionRow(title: "Exit transaction \(index + 1)", txid: tx.txid)
            Text(LocalizedStringKey(tx.status.kind.replacingOccurrences(of: "-", with: " ").capitalized))
            if let child = tx.status.child_txid {
                ExitTransactionRow(title: "Child transaction", txid: child)
            }
            if let block = tx.status.block { LabeledContent("Confirmed") { Text(OnchainDisplay.block(block)).textSelection(.enabled) } }
        }
    }
}

struct ExitTransactionRow: View {
    @Environment(AppModel.self) private var app
    let title: String
    let txid: String
    var body: some View {
        LabeledContent(title) {
            Text(txid).textSelection(.enabled).lineLimit(2).truncationMode(.middle)
        }
        if app.variant != .regtest,
            let url = URL(
                string: "https://mempool.space/" + (app.variant == .signet ? "signet/" : "") + "tx/" + txid)
        {
            Link("View in explorer", destination: url)
        }
    }
}

struct ExitDepositSheet: View {
    @Environment(AppModel.self) private var app
    let broadcastFee: UInt64
    @Environment(\.dismiss) private var dismiss
    @State private var address: String?
    @State private var error: String?
    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                List {
                    if broadcastFee > 0 { OnchainAmountRow(title: "Suggested deposit", sats: broadcastFee) }
                    Text(
                        "Broadcast fees are paid separately from confirmed onchain funds. Deposit bitcoin and wait for confirmation before progressing an exit. The amount required may vary."
                    )
                    if let address {
                        ReceiveQRCode(uri: address).aspectRatio(1, contentMode: .fit).frame(maxWidth: AdaptiveLayout.qrSide(availableHeight: geometry.size.height))
                            .padding(16).background(.white, in: RoundedRectangle(cornerRadius: 24))
                            .frame(maxWidth: .infinity).accessibilityLabel(
                            "Bitcoin deposit address QR code")
                        Text(address).textSelection(.enabled)
                        Button("Copy Bitcoin address") { UIPasteboard.general.string = address }
                        ShareLink(item: address)
                    } else if let error {
                        Text(LocalizedStringKey(error))
                    } else {
                        ProgressView()
                    }
                }
            }
            .settingsStyle("Deposit Onchain Funds")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task {
                do {
                    guard !app.isWalletSuspended else { throw OnchainFailure.unavailable }
                    address = try await app.session.exitDepositAddress()
                } catch {
                    self.error =
                        "Couldn't load a deposit address. Close and try again when the wallet is ready."
                }
            }
        }
        .presentationDetents([.large])
    }
}
