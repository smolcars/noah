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
                settings.open(.exitReview(selection.map(\.id)))
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

/// Review: what starting costs and when it becomes claimable, today's consent as one paragraph,
/// then hold to start (VoiceOver gets a standard action with a system confirmation). The review is
/// today's start quote: it expires after 30 s and is fetched again rather than submitted stale.
struct ExitReviewView: View {
    let ids: [String]
    @State private var model: OnchainModel
    @State private var expired = false
    /// For the transaction count and when it becomes claimable; the quote carries the fees.
    @State private var estimate: EmergencyExitFeeEstimate?
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale

    init(ids: [String], model: OnchainModel = OnchainModel()) {
        self.ids = ids
        _model = State(initialValue: model)
    }

    private var language: String { locale.identifier }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        let quote = model.quote?.action == .startExit ? model.quote : nil
        let amount = quote?.amount ?? (model.overview?.vtxos ?? []).filter { ids.contains($0.id) }.reduce(0) { $0 + $1.amount }
        ExitPanes {
            ExitHeadline(amount: L10n.text("Exit {amount}", money(amount), locale: language),
                         line: estimate.map { L10n.plural("{n} VTXOs · {count} transactions", count: ids.count, $0.txsToBroadcast.formatted(), locale: language) })
        } trailing: {
            if let quote {
                VStack(spacing: 0) {
                    if let fee = quote.fee, let chainFee = quote.chainFee {
                        SettingsDetailRow(label: L10n.text("Network fees", locale: language), value: money.about(chainFee), numeric: true)
                        SettingsDetailRow(label: L10n.text("Paid from", locale: language),
                                          value: L10n.text("On-chain · {amount}", money(quote.confirmed), locale: language))
                        SettingsDetailRow(label: L10n.text("Claim fee", locale: language), value: money.about(fee), numeric: true)
                        if let delta = model.overview?.exitDelta, let estimate {
                            // One block per transaction along a path at most, then the timelock.
                            SettingsDetailRow(label: L10n.text("Claimable", locale: language),
                                              value: L10n.text("In about {time}", VtxoList.span(Int64(delta) + Int64(estimate.txsToBroadcast), locale: language), locale: language))
                        }
                        if let rate = quote.feeRate {
                            SettingsDetailRow(label: L10n.text("Fee rate", locale: language), value: L10n.text("{rate} sat/vB", rate.formatted(), locale: language))
                        }
                        SettingsDetailRow(label: L10n.text("You get", locale: language), value: money.about(quote.net), numeric: true, hairline: false)
                    } else {
                        SettingsDetailRow(label: L10n.text("Network fees", locale: language), value: L10n.text("Unknown", locale: language))
                        SettingsDetailRow(label: L10n.text("Claim fee", locale: language), value: L10n.text("Unknown", locale: language), hairline: false)
                    }
                }
                .padding(.top, 22)
                if quote.feesUnknown {
                    ExitWarning(text: L10n.text("Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty.", locale: language))
                    ExitWarning(text: L10n.text("Broadcast funding status is unknown; confirmed onchain funds are required.", locale: language), muted: true)
                } else if !quote.fundable, let chainFee = quote.chainFee {
                    ExitWarning(text: L10n.text("Not enough on-chain bitcoin for the fees. The exit starts, then waits until you add about {amount}.",
                                                money(chainFee - min(chainFee, quote.confirmed)), locale: language))
                }
                if let fee = quote.fee, quote.chainFee != nil,
                   let warning = ExitRules.warning(amount: quote.amount, claimFee: fee, totalFee: quote.totalFee, claimOnly: false) {
                    ExitWarning(text: L10n.text(warning, locale: language))
                }
                SettingsFine(text: L10n.text("Noah then sends and fee-bumps the exit’s transactions by itself, paying from your on-chain bitcoin. Fees are estimates. Only the claim fee comes out of the amount. You can cancel until the last transaction is sent.", locale: language))
            } else if model.busy {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
            }
            if expired { SettingsFine(text: L10n.text("The estimate expired, so Noah fetched a new one. Check it and hold again.", locale: language)) }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        } footer: {
            SendHoldButton(amount: money(amount), enabled: quote != nil && !model.busy, title: "Hold to start exit",
                           action: "Start exit of {amount}", confirm: "Start exit", id: "exit-hold-start") { start() }
                // One hold per review: a fresh quote gets a fresh button.
                .id(quote?.id)
        }
        .settingsChrome(nil)
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false))
        .task {
            // After the page's first read: a review invalidates any read still in flight.
            while model.loading { do { try await Task.sleep(for: .milliseconds(50)) } catch { return } }
            await model.review(.startExit, ids: ids)
            estimate = await model.exitEstimate(ids)
        }
    }

    private func start() {
        Task {
            guard let quote = model.quote, quote.action == .startExit else { return }
            guard quote.isCurrent else {
                expired = true
                await model.review(.startExit, ids: ids)
                return
            }
            expired = false
            await model.confirm()
            guard model.error == nil else { haptics.play(.error); return }
            haptics.play(.success)
            settings.swap(.exit, ids.count == 1 ? .exitProgress(ids[0]) : .exitList)
        }
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
