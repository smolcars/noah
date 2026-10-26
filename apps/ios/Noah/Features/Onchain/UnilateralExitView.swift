import BarkBoltFFI
import SwiftUI

/// Settings → Emergency exit: the warning (docs/native-rewrite/design/exit.md). One line, then the
/// explainer tree while there are no exits (or the exits already running), four facts with the real
/// timelock and fee estimate. Reading it and estimating never sends anything.
struct UnilateralExitView: View {
    @State private var model: OnchainModel
    @State private var perVtxo: UInt64?
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.exitPaneHeight) private var paneHeight

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
            // The exits already running, or ended: one opens directly, several open the list.
            if exits.count == 1, let exit = exits.first {
                let progress = model.progress(exit), tip = model.overview?.tip ?? 0
                ExitListRow(exit: exit, progress: progress, text: ExitText(progress: progress, exit: exit, tip: tip, overview: model.overview,
                                                                           attempts: model.attempts, money: money, language: language),
                            coin: ExitGutterCoin(progress, tip: tip, delta: model.overview?.exitDelta ?? 144), hairline: false) { settings.open(.exitProgress(exit.id)) }
                    .padding(.top, 14)
            } else if !exits.isEmpty {
                SettingsRow(icon: "list.bullet", title: L10n.text("Exits", locale: language), sub: ExitListView.subtitle(exits, language: language),
                            hairline: false, id: "exit-entry") { settings.open(.exitList) }
                    .padding(.top, 14)
            } else {
                // Where an exit goes: your branch of the round's tree, standing on Bitcoin. Only while there are no exits.
                ExitStageView(scene: .explainer(count: Self.explained(sample)), wide: settings.wide,
                              accessibilityText: L10n.plural("Your VTXO is a leaf of the round’s tree. To reach it, the {n} transactions on your branch go to the chain, root first.",
                                                             count: Self.explained(sample), locale: language))
                    .frame(height: settings.wide ? max(240, paneHeight - 200) : window.size.height > 0 && window.size.height < 700 ? 236 : 268)
                    .padding(.top, 14)
                    .padding(.horizontal, settings.wide ? -12 : -NoahTokens.Space.screen)
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
                .buttonStyle(SettingsActionStyle(wrap: true))
                .accessibilityIdentifier("exit-choose")
        }
        .settingsChrome("Emergency exit")
        .onboardingWindow($window)
        .modifier(OnchainScreen(model: model))
        .task(id: sample?.id) {
            guard let sample else { return }
            perVtxo = await model.exitEstimate([sample.id])?.exitBroadcastFeeSats
        }
    }

    /// The explainer's transactions: your biggest VTXO's path, else a round's usual four.
    nonisolated static func explained(_ sample: VtxoModel?) -> Int {
        sample.map { Int($0.exitDepth) }.flatMap { $0 > 0 ? $0 : nil } ?? 4
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
    @State private var costs: [String: ExitEconomics.Cost] = [:]
    /// Pricing VTXOs again after some failed.
    @State private var retry = 0
    /// Each arrival, back from Review too, estimates the selection again.
    @State private var visits = 0
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
        all ? ExitEconomics.all(vtxos, costs: costs)
            : vtxos.filter { $0.eligible && costs[$0.id]?.refusal == nil && selected.contains($0.id) }
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
            if settings.wide {
                ExitHeadline(amount: money(value), line: estimate.map {
                    ExitText.count(vtxos: selection.count, transactions: $0.txsToBroadcast, language)
                })
                summary(money)
            } else {
                picker
            }
        } trailing: {
            if settings.wide { picker } else { summary(money) }
        } footer: {
            if !all && !settings.wide {
                Text(verbatim: selection.isEmpty ? L10n.text("Choose the VTXOs to exit", locale: language)
                     : estimate.map { L10n.text("{amount} · fees ~{fee}", money(value), money($0.exitBroadcastFeeSats), locale: language) } ?? money(value))
                    .noahText(HomeText.amount).monospacedDigit()
            }
            Button(L10n.text("Review exit", locale: language)) {
                haptics.play(.light)
                settings.open(.exitReview(selection.map(\.id)))
            }
            .buttonStyle(SettingsActionStyle(wrap: true))
            // All waits for every VTXO's classification, so it never freezes one it would leave out.
            .disabled(selection.isEmpty || model.locked || all && !ExitEconomics.classified(vtxos, costs: costs))
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
        .onAppear { visits += 1 }
        .task {
            guard let preset = fixture?.exitSelection else { return }
            all = false
            selected = Set(preset)
        }
        .task(id: vtxos.filter(\.eligible).map(\.id) + [String(retry)]) {
            let eligible = vtxos.filter(\.eligible)
            costs = costs.filter { id, cost in cost != .failed && eligible.contains { $0.id == id } }
            // What bark's whole-wallet exit leaves out needs renewing first, and what it refused this app run
            // stays refused: never in All or the total.
            await model.exitExclusions()
            for (id, refusal) in OnchainModel.refused where eligible.contains(where: { $0.id == id }) {
                costs[id] = .refused(refusal)
            }
            guard !Task.isCancelled else { return }
            let latest = await model.exitCosts(eligible.filter { costs[$0.id] == nil })
            guard !Task.isCancelled else { return }
            costs.merge(latest) { $1 }
        }
        .task(id: selection.map(\.id) + [String(visits)]) {
            estimating = true
            defer { estimating = false }
            do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            let latest = await model.exitEstimate(selection.map(\.id))
            guard !Task.isCancelled else { return }
            estimate = latest
            // bark won't ever exit the VTXO it named: it leaves the selection, and its row says why.
            if let refusal = model.exitRefusal, let id = refusal.vtxoID {
                costs[id] = .refused(refusal)
                selected.remove(id)
            }
        }
    }

    /// All / Choose and the VTXOs: in Choose they're checkboxes; wide windows list them in All too, and
    /// compact All lists the ones it leaves out to renew first.
    @ViewBuilder private var picker: some View {
        ExitSegment(all: $all)
        let rows = ExitEconomics.rows(vtxos, costs: costs, compactAll: all && !settings.wide)
        if !rows.isEmpty {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, vtxo in
                    ExitVtxoRow(vtxo: vtxo, uneconomic: costs[vtxo.id] == .uneconomic, refusal: costs[vtxo.id]?.refusal,
                                selected: all ? nil : selected.contains(vtxo.id), hairline: index < rows.count - 1) { toggle(vtxo) }
                }
            }
            .padding(.top, 6)
        }
    }

    /// The total, the fee estimate and what it means for your on-chain funds.
    @ViewBuilder private func summary(_ money: ExitMoney) -> some View {
            VStack(spacing: 0) {
                ExitDetailRow(label: L10n.text("VTXOs", locale: language), value: estimate.map {
                    selection.count.formatted() + " · " + L10n.plural("{n} transactions", count: Int($0.txsToBroadcast), locale: language)
                } ?? selection.count.formatted(), numeric: true)
                ExitDetailRow(label: L10n.text("Network fees", locale: language), value: estimate.map { money.about($0.exitBroadcastFeeSats) } ?? "—", numeric: true)
                ExitDetailRow(label: L10n.text("Claim fee", locale: language), value: estimate.map { money.about($0.claimFeeSats) } ?? "—", numeric: true)
                ExitDetailRow(label: L10n.text("You get", locale: language),
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
                // A refusal has no "Continue without estimate": the start would fail too.
                if let reason = model.exitRefusal?.errorDescription {
                    SettingsFine(text: L10n.text(reason, locale: language), failure: true)
                } else {
                    SettingsFine(text: L10n.text("Fee estimate unavailable", locale: language))
                }
            }
            if let estimate, value > 0, estimate.totalFeeSats >= value {
                ExitWarning(text: L10n.text("Fees are more than these VTXOs are worth.", locale: language))
            }
            if all { leftOut(money) }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
    }

    /// All leaves out what can't or shouldn't exit, and says so.
    @ViewBuilder private func leftOut(_ money: ExitMoney) -> some View {
        let dust = vtxos.filter { $0.state == "Spendable" && $0.amount < ExitRules.dust }.reduce(0) { $0 + $1.amount }
        let locked = vtxos.filter { $0.state == "Locked" }.reduce(0) { $0 + $1.amount }
        let costly = vtxos.filter { $0.eligible && costs[$0.id] == .uneconomic }.reduce(0) { $0 + $1.amount }
        let lines = [dust > 0 ? L10n.text("Left out: {amount}, too small to exit.", money(dust), locale: language) : nil,
                     locked > 0 ? L10n.text("Left out: {amount}, in use right now.", money(locked), locale: language) : nil,
                     costly > 0 ? L10n.text("Left out: {amount} that costs more in fees than it’s worth. Choose it to exit anyway.", money(costly), locale: language) : nil]
        if lines.contains(where: { $0 != nil }) { SettingsFine(text: lines.compactMap { $0 }.joined(separator: "\n")) }
        let failed = vtxos.filter { $0.eligible && costs[$0.id] == .failed }.count
        if failed > 0 {
            SettingsFine(text: L10n.plural("Couldn’t check the fees of {n} VTXOs, so All waits. Choose them one by one, or try again.",
                                           count: failed, locale: language), failure: true)
            Button(L10n.text("Try again", locale: language)) { retry += 1 }
                .buttonStyle(.plain).noahText(NoahTokens.Typography.control).underline()
                .padding(.top, 4)
                .accessibilityIdentifier("exit-costs-retry")
        }
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
    /// This visit's review finished: a missing quote then offers Review again.
    @State private var reviewed = false
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.exitPaneHeight) private var paneHeight
    @Environment(\.scenePhase) private var scenePhase

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
                         line: estimate.map { ExitText.count(vtxos: ids.count, transactions: $0.txsToBroadcast, language) })
            if settings.wide {
                // One branch: the deepest selected VTXO's, not the batch's total.
                ExitStageView(scene: .explainer(count: UnilateralExitView.explained((model.overview?.vtxos ?? []).filter { ids.contains($0.id) }
                                                                                       .max { $0.exitDepth < $1.exitDepth })), wide: true)
                    .frame(height: max(240, paneHeight - 160)).padding(.top, 10).padding(.horizontal, -12)
            } else if let estimate {
                // The progress picture, previewed as one line: round, transactions, coin, dial, destination.
                ExitPathRail(transactions: Int(estimate.txsToBroadcast), several: ids.count > 1, delta: model.overview?.exitDelta ?? 144)
            }
        } trailing: {
            if let quote {
                VStack(spacing: 0) {
                    if let fee = quote.fee, let chainFee = quote.chainFee {
                        ExitDetailRow(label: L10n.text("Network fees", locale: language), value: money.about(chainFee), numeric: true)
                        ExitDetailRow(label: L10n.text("Paid from", locale: language),
                                          value: L10n.text("On-chain · {amount}", money(quote.confirmed), locale: language))
                        ExitDetailRow(label: L10n.text("Claim fee", locale: language), value: money.about(fee), numeric: true)
                        if let delta = model.overview?.exitDelta, let estimate {
                            // One block per transaction along a path at most, then the timelock.
                            ExitDetailRow(label: L10n.text("Claimable", locale: language),
                                              value: L10n.text("In {time}", ExitText.about(delta + UInt32(estimate.txsToBroadcast), language), locale: language))
                        }
                        if let rate = quote.feeRate {
                            ExitDetailRow(label: L10n.text("Fee rate", locale: language), value: L10n.text("{rate} sat/vB", rate.formatted(), locale: language))
                        }
                        ExitDetailRow(label: L10n.text("You get", locale: language), value: money.about(quote.net), numeric: true, hairline: false)
                    } else {
                        ExitDetailRow(label: L10n.text("Network fees", locale: language), value: L10n.text("Unknown", locale: language))
                        ExitDetailRow(label: L10n.text("Claim fee", locale: language), value: L10n.text("Unknown", locale: language), hairline: false)
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
                SettingsFine(text: L10n.text("Noah then sends and fee-bumps the exit’s transactions on its own, including in the background when the system lets it, paying from your on-chain bitcoin. Fees are estimates. Only the claim fee comes out of the amount. You can cancel until the last transaction is sent.", locale: language))
            } else if model.busy {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
            }
            if expired { SettingsFine(text: L10n.text("The estimate expired, so Noah fetched a new one. Check it and hold again.", locale: language)) }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        } footer: {
            if quote == nil && model.estimateFallback == nil && !model.busy && !model.loading && reviewed {
                Button(L10n.text("Review again", locale: language)) { Task { await review() } }
                    .buttonStyle(SettingsActionStyle(wrap: true))
                    .accessibilityIdentifier("exit-review-again")
            } else {
                SendHoldButton(amount: money(amount), enabled: quote != nil && !model.busy, title: "Hold to start exit",
                               action: "Start exit of {amount}", confirm: "Start exit", id: "exit-hold-start") { start() }
                    // One hold per review: a fresh quote gets a fresh button.
                    .id(quote?.id)
            }
        }
        .settingsChrome(nil)
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false))
        // On arrival, and again whenever the app comes back: leaving drops the review (OnchainScreen).
        .task(id: scenePhase == .active) {
            reviewed = false
            guard scenePhase == .active else { return }
            if model.quote == nil { await review() } else { reviewed = true }
            if estimate == nil { estimate = await model.exitEstimate(ids) }
        }
    }

    /// Today's start review, after the page's first read and any running review: a review invalidates
    /// a read still in flight, and the model drops one asked for while it's busy.
    private func review() async {
        while model.loading || model.busy { do { try await Task.sleep(for: .milliseconds(50)) } catch { return } }
        await model.review(.startExit, ids: ids)
        reviewed = true
    }

    private func start() {
        Task {
            guard let quote = model.quote, quote.action == .startExit else { return }
            guard quote.isCurrent else {
                expired = true
                await review()
                return
            }
            expired = false
            // Only a submitted start leaves; a failed one keeps its error or "Continue without estimate".
            guard await model.confirm() else {
                if model.error != nil { haptics.play(.error) }
                return
            }
            haptics.play(.success)
            settings.swap(.exit, ids.count == 1 ? .exitProgress(ids[0]) : .exitList)
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
