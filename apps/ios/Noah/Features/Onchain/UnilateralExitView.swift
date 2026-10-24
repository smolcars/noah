import BarkBoltFFI
import SwiftUI

/// Settings → Emergency exit: the warning (docs/native-rewrite/design/exit.md). One line, four
/// facts with the real timelock and fee estimate, and the exits already running. Reading it and
/// estimating never sends anything.
struct UnilateralExitView: View {
    @State private var model: OnchainModel
    @State private var perVtxo: UInt64?
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale

    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }

    private var language: String { locale.identifier }
    private var exits: [ExitModel] { model.overview?.exits ?? [] }
    /// The biggest VTXO that can exit: the per-VTXO fee the warning quotes.
    private var sample: VtxoModel? { (model.overview?.vtxos ?? []).filter(\.eligible).max { $0.amount < $1.amount } }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        ExitPanes {
            Text(verbatim: L10n.text("Emergency use only. It takes your bitcoin out of Ark without Ark’s server.", locale: language))
                .noahText(SettingsText.line).foregroundStyle(NoahTokens.Colors.muted)
                .fixedSize(horizontal: false, vertical: true)
            if exits.count == 1, let exit = exits.first {
                SettingsRow(title: money(exit.amount), value: L10n.text(ExitTimeline.labels[exit.state] ?? exit.state, locale: language),
                            hairline: false, id: "exit-entry") { settings.open(.exitProgress(exit.id)) }
                    .padding(.top, 14)
            } else if !exits.isEmpty {
                SettingsRow(title: L10n.text("Exits", locale: language), value: "\(exits.count)", hairline: false, id: "exit-entry") {
                    settings.open(.exitList)
                }
                .padding(.top, 14)
            }
        } trailing: {
            VStack(spacing: 0) {
                fact("clock", "Takes a day or more", model.overview?.exitDelta.map {
                    L10n.text("Then a {n}-block wait before you can claim.", $0.formatted(.number.locale(locale)), locale: language)
                } ?? L10n.text("Then a wait of about a day before you can claim.", locale: language))
                fact("bitcoinsign.circle", "Costs on-chain fees", perVtxo.map {
                    L10n.text("About {amount} a VTXO now, from your on-chain bitcoin.", money($0), locale: language)
                } ?? L10n.text("Paid from your on-chain bitcoin.", locale: language))
                fact("nosign", "Can’t be undone", L10n.text("Once its last transaction is sent.", locale: language))
                fact("sailboat", "Ark is faster and cheaper", L10n.text("If Ark works, send to a Bitcoin address instead.", locale: language), last: true)
            }
            .padding(.top, 20)
            SettingsFine(text: L10n.text("Reading this screen and estimating fees never broadcasts an exit.", locale: language))
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        } footer: {
            Button(L10n.text("Choose VTXOs", locale: language)) { settings.open(.exitChoose) }
                .buttonStyle(SettingsActionStyle())
                .accessibilityIdentifier("exit-choose")
        }
        .settingsChrome("Emergency exit")
        .modifier(OnchainScreen(model: model))
        .task(id: sample?.id) {
            guard let sample else { return }
            perVtxo = await model.exitEstimate([sample.id])?.exitBroadcastFeeSats
        }
    }

    private func fact(_ icon: String, _ title: String, _ line: String, last: Bool = false) -> some View {
        SettingsRowLabel(icon: icon, title: L10n.text(title, locale: language), sub: line, hairline: !last) { EmptyView() }
            .accessibilityElement(children: .combine)
    }
}

/// Choose VTXOs: All (every eligible VTXO worth its fees) or Choose; amounts and expiry, the
/// live total and fee estimate, and Add on-chain funds when the broadcast fees don't fit.
struct ExitChooseView: View {
    @State private var model: OnchainModel
    @State private var all = true
    @State private var selected = Set<String>()
    @State private var uneconomic = Set<String>()
    @State private var estimate: EmergencyExitFeeEstimate?
    @State private var estimating = false
    @State private var sheet: ExitSheet?
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.settingsFixture) private var fixture

    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }

    private var language: String { locale.identifier }
    /// Spendable and locked VTXOs, biggest first; dust and locked ones show disabled.
    private var vtxos: [VtxoModel] {
        (model.overview?.vtxos ?? []).filter { $0.state == "Spendable" || $0.state == "Locked" }.sorted { $0.amount > $1.amount }
    }
    private var selection: [VtxoModel] {
        all ? ExitEconomics.all(vtxos, uneconomic: uneconomic) : vtxos.filter { $0.eligible && selected.contains($0.id) }
    }
    private var value: UInt64 { selection.reduce(0) { $0 + $1.amount } }
    /// What the confirmed on-chain balance is short of the broadcast fees.
    private var short: UInt64 {
        guard let estimate, let confirmed = model.overview?.confirmed else { return 0 }
        return estimate.exitBroadcastFeeSats > confirmed ? estimate.exitBroadcastFeeSats - confirmed : 0
    }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        ExitPanes {
            ExitSegment(all: $all)
            if !all {
                VStack(spacing: 0) {
                    ForEach(Array(vtxos.enumerated()), id: \.element.id) { index, vtxo in
                        ExitVtxoRow(vtxo: vtxo, uneconomic: uneconomic.contains(vtxo.id), selected: selected.contains(vtxo.id),
                                    hairline: index < vtxos.count - 1) { toggle(vtxo) }
                    }
                }
                .padding(.top, 6)
            }
        } trailing: {
            VStack(spacing: 0) {
                SettingsDetailRow(label: L10n.text("VTXOs", locale: language), value: estimate.map {
                    L10n.text("{n} · {count} transactions", selection.count.formatted(), $0.txsToBroadcast.formatted(), locale: language)
                } ?? selection.count.formatted(), numeric: true)
                SettingsDetailRow(label: L10n.text("Network fees", locale: language), value: estimate.map { money.about($0.exitBroadcastFeeSats) } ?? "—", numeric: true)
                SettingsDetailRow(label: L10n.text("Claim fee", locale: language), value: estimate.map { money.about($0.claimFeeSats) } ?? "—", numeric: true)
                SettingsDetailRow(label: L10n.text("You get", locale: language),
                                  value: estimate.map { money.about(ExitRules.received(amount: value, claimFee: $0.claimFeeSats)) } ?? money(value),
                                  numeric: true, hairline: false)
            }
            .padding(.top, 22)
            .opacity(estimating ? 0.5 : 1)
            if short > 0 {
                ExitWarning(text: L10n.text("Add about {amount} on-chain first. Each exit transaction’s fee comes from there.", money(short), locale: language))
                Button(L10n.text("Add on-chain funds", locale: language)) { sheet = .deposit(short) }
                    .buttonStyle(.plain).noahText(NoahTokens.Typography.control).foregroundStyle(NoahTokens.Colors.failure).underline()
                    .padding(.top, 4)
                    .accessibilityIdentifier("exit-add-funds")
            } else if let estimate, let confirmed = model.overview?.confirmed {
                SettingsFine(text: L10n.text("Network fees come from your on-chain {amount} at {rate} sat/vB. Transactions your VTXOs share are paid once. Fees may change.",
                                             money(confirmed), estimate.feeRateSatPerVb.formatted(), locale: language))
            } else if !selection.isEmpty && !estimating {
                SettingsFine(text: L10n.text("Fee estimate unavailable", locale: language))
            }
            if let estimate, value > 0, estimate.totalFeeSats >= value {
                ExitWarning(text: L10n.text("Fees are more than these VTXOs are worth.", locale: language))
            }
            if all { leftOut(money) }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        } footer: {
            if !all {
                Text(verbatim: selection.isEmpty ? L10n.text("Choose the VTXOs to exit", locale: language)
                     : L10n.text("{amount} · fees {fee}", money(value), estimate.map { money.about($0.exitBroadcastFeeSats) } ?? "—", locale: language))
                    .noahText(HomeText.amount).monospacedDigit()
            }
            Button(L10n.text("Review exit", locale: language)) {
                haptics.play(.light)
                Task { await model.review(.startExit, ids: selection.map(\.id)) }
            }
            .buttonStyle(SettingsActionStyle())
            .disabled(selection.isEmpty || model.locked)
            .accessibilityIdentifier("exit-review")
        }
        .settingsChrome("Choose VTXOs")
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model))
        .settingsSheet(item: $sheet) { item in
            if case .deposit(let need) = item { ExitDepositSheet(need: need) { sheet = nil } }
        }
        .onChange(of: all) { _, _ in haptics.play(.selection); model.invalidate() }
        .onChange(of: selected) { _, _ in model.invalidate() }
        .task {
            guard let preset = fixture?.exitSelection else { return }
            all = false
            selected = Set(preset)
        }
        .task(id: vtxos.filter(\.eligible).map(\.id)) { uneconomic = await model.uneconomic(vtxos.filter(\.eligible)) }
        .task(id: selection.map(\.id)) {
            estimating = true
            defer { estimating = false }
            do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            let latest = await model.exitEstimate(selection.map(\.id))
            if !Task.isCancelled { estimate = latest }
        }
    }

    /// All leaves out what can't or shouldn't exit, and says so.
    @ViewBuilder private func leftOut(_ money: ExitMoney) -> some View {
        let dust = vtxos.filter { $0.state == "Spendable" && $0.amount < ExitRules.dust }.reduce(0) { $0 + $1.amount }
        let locked = vtxos.filter { $0.state == "Locked" }.reduce(0) { $0 + $1.amount }
        let costly = vtxos.filter { $0.eligible && uneconomic.contains($0.id) }.reduce(0) { $0 + $1.amount }
        let lines = [dust > 0 ? L10n.text("Left out: {amount}, too small to exit.", money(dust), locale: language) : nil,
                     locked > 0 ? L10n.text("Left out: {amount}, in use right now.", money(locked), locale: language) : nil,
                     costly > 0 ? L10n.text("Left out: {amount} that costs more in fees than it’s worth. Choose it to exit anyway.", money(costly), locale: language) : nil]
        if lines.contains(where: { $0 != nil }) { SettingsFine(text: lines.compactMap { $0 }.joined(separator: "\n")) }
    }

    private func toggle(_ vtxo: VtxoModel) {
        haptics.play(.selection)
        if selected.contains(vtxo.id) { selected.remove(vtxo.id) } else { selected.insert(vtxo.id) }
    }
}

/// Today's exits, progress and claim sections, kept until the redesigned list and claim replace them.
struct ExitListView: View {
    @Environment(AppModel.self) private var app
    @State private var model = OnchainModel()
    private var destination: String { model.claimDestination }
    @State private var filter: String?
    private var exits: [ExitModel] { model.overview?.exits ?? [] }
    private var pending: [ExitModel] { exits.filter { ExitRules.live($0.state) } }
    var body: some View {
        Form {
            if !pending.isEmpty {
                Section("Progress") {
                    Text("Noah progresses exits automatically. You can also progress all pending exits manually.")
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
                    }.disabled(destination.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.locked)
                }
            }
            Section("Exits") {
                Picker("Status", selection: $filter) {
                    Text("All").tag(nil as String?)
                    ForEach(ExitTimeline.labels.keys.sorted(), id: \.self) {
                        Text(LocalizedStringKey(ExitTimeline.labels[$0]!)).tag(Optional($0))
                    }
                }
                ForEach(exits.filter { filter == nil || $0.state == filter }) { exit in
                    NavigationLink {
                        ExitVtxoDetailView(id: exit.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            OnchainAmountRow(title: ExitTimeline.labels[exit.state] ?? exit.state, sats: exit.amount)
                            Text(exit.id).font(NoahFont.footnote).lineLimit(1).truncationMode(.middle)
                        }
                    }
                }
            }
            OnchainStatusSection(model: model)
        }
        .settingsStyle("Exits")
        .modifier(OnchainScreen(model: model))
        .task(id: model.overview?.walletID) {
            if model.overview != nil, destination.isEmpty { await model.useWalletClaimAddress() }
        }
        .task(id: destination + (model.overview?.walletID ?? "") + String(app.isWalletSuspended)) {
            await model.checkClaimDestination()
        }
    }
}

struct ExitOverviewSummary: View {
    let overview: OnchainOverview
    private var exits: [ExitModel] { overview.exits }
    private var eligible: [VtxoModel] { overview.vtxos.filter(\.eligible) }
    private var pending: [ExitModel] { exits.filter { ExitRules.live($0.state) } }
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
