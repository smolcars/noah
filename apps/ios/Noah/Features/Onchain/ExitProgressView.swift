import SwiftUI

/// One exit in progress (exit.md, "The exit in progress"): the amount and its status, the picture
/// and its phase line, the rows and Details. Move exit forward is today's progress review; Cancel exit
/// shows only while bark allows it. Everything follows the wallet's state as Noah reads it (the
/// page's existing 60 s read and pull to refresh); no new polling.
struct ExitVtxoDetailView: View {
    let id: String
    @State private var model: OnchainModel
    @State private var details = false
    @State private var sheet: ExitSheet?
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.exitPaneHeight) private var paneHeight

    init(id: String, model: OnchainModel? = nil) {
        self.id = id
        _model = State(initialValue: model ?? OnchainModel(detailID: id))
    }

    private var language: String { locale.identifier }
    private var exit: ExitModel? { model.exit ?? model.overview?.exits.first { $0.id == id } }
    private var tip: UInt32 { model.overview?.tip ?? exit?.details.tip_height ?? 0 }
    /// Today's progress review covers every live exit.
    private var live: [String] { (model.overview?.exits ?? []).filter { ExitRules.live($0.state) }.map(\.id) }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        Group {
            if let exit {
                let progress = model.progress(exit)
                let text = ExitText(progress: progress, exit: exit, tip: tip, overview: model.overview, attempts: model.attempts,
                                    money: money, language: language)
                ExitPanes {
                    if !settings.wide {
                        ExitHeadline(amount: money(text.amount))
                        ExitStatusLine(text: text.status, kind: text.statusKind)
                    }
                    if progress.picture != .none {
                        ExitStageView(scene: text.scene)
                            .frame(height: settings.wide ? max(280, paneHeight - 110) : 300).padding(.top, 8)
                            .padding(.horizontal, settings.wide ? -12 : -NoahTokens.Space.screen)
                    }
                    Text(verbatim: text.phase).noahText(NoahTokens.Typography.control).monospacedDigit()
                        .foregroundStyle(text.statusKind == .failure ? NoahTokens.Colors.failure : text.statusKind == .live ? NoahTokens.Colors.muted : NoahTokens.Colors.foreground)
                        .multilineTextAlignment(.center).frame(maxWidth: .infinity)
                        .id(text.phase).transition(.homeRise(reduceMotion, 6))
                        .accessibilityAddTraits(.updatesFrequently)
                        .accessibilityIdentifier("exit-phase")
                    if settings.wide {
                        Text(verbatim: text.explanation).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
                            .multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 4)
                    }
                } trailing: {
                    if settings.wide {
                        ExitHeadline(amount: money(text.amount))
                        ExitStatusLine(text: text.status, kind: text.statusKind)
                    }
                    if progress.phase == .needsFunds, let need = text.need {
                        ExitWarning(text: L10n.text("Add about {amount} on-chain. Noah sends the next transaction once it confirms.", money(need), locale: language))
                    }
                    if progress.stale {
                        ExitWarning(text: L10n.text("This exit is behind the current chain height. Move it forward to catch up; this can also send exit transactions.", locale: language),
                                    symbol: "clock", muted: true)
                    }
                    VStack(spacing: 0) {
                        ForEach(Array(text.rows.enumerated()), id: \.offset) { index, row in
                            SettingsDetailRow(label: row.label, value: row.value, numeric: row.numeric, failure: row.failure,
                                              hairline: index < text.rows.count - 1)
                        }
                    }
                    .padding(.top, 22)
                    if let line = text.batchLine { SettingsFine(text: line) }
                    // The explorer without opening Details: the transaction that says the most about where the exit is.
                    if let url = exit.explorerTxid.flatMap(explorer) {
                        Link(destination: url) {
                            Label(L10n.text("View in explorer", locale: language), systemImage: "arrow.up.right.square").noahText(HomeText.strong)
                        }
                        .foregroundStyle(NoahTokens.Colors.foreground)
                        .frame(minHeight: NoahTokens.Size.hitTarget)
                        .padding(.top, 12)
                        .accessibilityIdentifier("exit-explorer")
                    }
                    SettingsDetails(open: $details) { detailRows(exit, progress, text) }
                    OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
                } footer: {
                    footer(exit, progress, text)
                }
                .animation(NoahTokens.Motion.travel(reduceMotion), value: text.phase)
                .onChange(of: progress) { old, new in feel(old, new) }
            } else {
                SettingsScroll {
                    if model.loading {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                    } else {
                        SettingsFine(text: L10n.text("Exit status is unavailable. Pull to refresh.", locale: language))
                    }
                    OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
                }
            }
        }
        .settingsChrome(nil)
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false))
        .onChange(of: model.quote?.id) { _, _ in
            if model.quote?.action == .progress { sheet = .check }
            if model.quote?.action == .cancel { sheet = .cancel }
        }
        .settingsSheet(item: Binding(get: { sheet }, set: { value in
            if value == nil, [.progress, .cancel].contains(model.quote?.action) { model.invalidate() }
            sheet = value
        }), locked: model.busy) { item in
            switch item {
            case .deposit(let need): ExitDepositSheet(need: need) { sheet = nil }
            case .check: checkSheet(money)
            case .cancel: cancelSheet
            }
        }
    }

    /// Today's cancel, behind a confirmation that stays in the trailing pane on wide windows.
    private var cancelSheet: some View {
        SettingsSheetBody(title: L10n.text("Cancel exit?", locale: language),
                          line: L10n.text("Transactions already on-chain stay there. Your VTXO stays in your wallet and can exit again.", locale: language)) {
            EmptyView()
        } actions: {
            SettingsSheetButtons(cancel: L10n.text("Keep exiting", locale: language), action: L10n.text("Cancel exit", locale: language),
                                 destructive: true, enabled: model.quote?.action == .cancel, busy: model.busy, actionID: "exit-cancel-confirm",
                                 onCancel: { sheet = nil; model.invalidate() }) {
                Task {
                    await model.confirm()
                    sheet = nil
                    haptics.play(model.error == nil ? .success : .error)
                }
            }
        }
    }

    @ViewBuilder private func footer(_ exit: ExitModel, _ progress: ExitProgress, _ text: ExitText) -> some View {
        switch progress.phase {
        case .claimable:
            Button(L10n.text("Claim {amount}", ExitMoney(app: app, locale: locale)(text.claimable), locale: language)) {
                haptics.play(.light)
                settings.open(.exitClaim)
            }
            .buttonStyle(SettingsActionStyle())
            .accessibilityIdentifier("exit-claim")
            // If the daemon stops, this is how bark notices the claim confirming or being evicted.
            checkAndCancel(progress, primary: false)
        case .needsFunds:
            Button(L10n.text("Add on-chain funds", locale: language)) { sheet = .deposit(text.need) }
                .buttonStyle(SettingsActionStyle())
                .accessibilityIdentifier("exit-add-funds")
            checkAndCancel(progress, primary: false)
        case .claimed, .canceled, .alreadySpent, .swept:
            Button(L10n.text("Done", locale: language)) { settings.back() }
                .buttonStyle(SettingsActionStyle())
                .accessibilityIdentifier("exit-done")
        default:
            checkAndCancel(progress, primary: progress.stale)
        }
    }

    /// Move exit forward (the main action when the exit is stale) and, while bark allows it, Cancel exit.
    @ViewBuilder private func checkAndCancel(_ progress: ExitProgress, primary: Bool) -> some View {
        Group {
            if primary {
                Button(L10n.text("Move exit forward", locale: language)) { check() }.buttonStyle(SettingsActionStyle())
            } else {
                Button(L10n.text("Move exit forward", locale: language)) { check() }
                    .buttonStyle(OnboardingSecondaryButtonStyle(height: NoahTokens.Size.button))
            }
        }
        .disabled(model.locked || live.isEmpty)
        .accessibilityIdentifier("exit-check")
        if progress.cancelable {
            Button(L10n.text("Cancel exit", locale: language)) {
                haptics.play(.warning)
                Task { await model.review(.cancel, ids: [id]) }
            }
            .buttonStyle(.plain).noahText(NoahTokens.Typography.control).underline()
            .frame(minHeight: NoahTokens.Size.hitTarget)
            .disabled(model.locked)
            .accessibilityIdentifier("exit-cancel")
        }
    }

    private func check() {
        haptics.play(.light)
        Task { await model.review(.progress, ids: live) }
    }

    private func checkSheet(_ money: ExitMoney) -> some View {
        SettingsSheetBody(title: L10n.text("Move your exit forward?", locale: language),
                          line: L10n.text("Sends the next step of your exit, or raises the fee on a step that’s stuck. Fees come from your on-chain bitcoin. Noah also does this on its own, including in the background when the system lets it.", locale: language)) {
            if let quote = model.quote, quote.action == .progress, let chainFee = quote.chainFee {
                SettingsDetailRow(label: L10n.text("Estimated fees", locale: language), value: money.about(chainFee), numeric: true, hairline: false)
                    .padding(.top, 8)
            }
        } actions: {
            SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Move forward", locale: language),
                                 enabled: model.quote?.action == .progress, busy: model.busy, actionID: "exit-check-confirm",
                                 onCancel: { sheet = nil; model.invalidate() }) {
                let before = exit
                Task {
                    await model.confirm()
                    sheet = nil
                    // bark can't say whether it sent anything, so the exit itself tells; short of funds, the page says what it needs.
                    guard model.error == nil, model.message == OnchainReceipt.progressedMessage, let after = exit,
                          model.progress(after).phase != .needsFunds else { return }
                    settings.toast(L10n.text(after != before ? "Exit moved forward" : "Nothing to send yet", locale: language))
                }
            }
        }
    }

    @ViewBuilder private func detailRows(_ exit: ExitModel, _ progress: ExitProgress, _ text: ExitText) -> some View {
        SettingsDetailRow(label: L10n.text("VTXO ID", locale: language), value: Self.short(exit.id), mono: true, compact: true, copy: exit.id)
        if !exit.path.isEmpty {
            SettingsSection(title: "Transactions, root first")
            ForEach(Array(exit.path.enumerated()), id: \.offset) { index, tx in
                SettingsDetailRow(label: "\(index + 1) · " + text.status(of: tx, index: index), value: Self.short(tx.txid), mono: true, compact: true,
                                  failure: progress.phase == .needsFunds && index == progress.confirmed, copy: tx.txid, explorer: explorer(tx.txid))
            }
        }
        if let claim = exit.details.claimTxid {
            SettingsDetailRow(label: L10n.text("Claim transaction", locale: language), value: Self.short(claim), mono: true, compact: true,
                              copy: claim, explorer: explorer(claim))
        }
        SettingsSection(title: "Exit")
        if let delta = model.overview?.exitDelta {
            SettingsDetailRow(label: L10n.text("Timelock", locale: language), value: L10n.plural("{n} blocks", count: Int(delta), locale: language), compact: true)
        }
        if let checked = progress.checked {
            SettingsDetailRow(label: L10n.text("Last checked", locale: language),
                              value: L10n.text("Block {height}", checked.formatted(.number.locale(locale)), locale: language), compact: true)
        }
        SettingsSection(title: "Timeline")
        ForEach(Array(exit.timeline.enumerated()), id: \.offset) { index, item in
            SettingsDetailRow(label: L10n.text(item.label, locale: language), value: [item.startHeight, item.endHeight].compactMap { $0 }
                                .map { $0.formatted(.number.locale(locale)) }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
                                .joined(separator: "–"),
                              compact: true, hairline: index < exit.timeline.count - 1)
        }
    }

    private func explorer(_ txid: String) -> URL? {
        app.variant == .regtest ? nil : URL(string: "https://mempool.space/" + (app.variant == .signet ? "signet/" : "") + "tx/" + txid)
    }

    static func short(_ value: String) -> String { value.count > 18 ? value.prefix(10) + "…" + value.suffix(6) : value }

    /// Light on each block, success when claimed, error on a failure (as Send).
    private func feel(_ old: ExitProgress, _ new: ExitProgress) {
        if new.phase == .claimed || new.phase == .claimable && old.phase != .claimable { haptics.play(.success) }
        else if [.needsFunds, .alreadySpent, .swept].contains(new.phase) && old.phase != new.phase { haptics.play(.error) }
        else if new.confirmed != old.confirmed || new.phase != old.phase { haptics.play(.light) }
    }
}

/// The status word under the amount, with Activity's pulsing dot while it's live.
struct ExitStatusLine: View {
    enum Kind { case live, ready, done, failure }
    let text: String
    let kind: Kind
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 8) {
            if kind != .done {
                Circle().fill(kind == .failure ? NoahTokens.Colors.failure : NoahTokens.Colors.orange).frame(width: 7, height: 7)
                    .opacity(kind == .live && pulse ? 0.25 : 1).scaleEffect(kind == .live && pulse ? 0.7 : 1)
                    .onAppear {
                        guard !reduceMotion else { return }
                        withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { pulse = true }
                    }
            }
            Text(verbatim: text).noahText(NoahTokens.Typography.label)
                .foregroundStyle(kind == .failure ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
        }
        .padding(.top, 10)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("exit-status")
    }
}

/// Everything the progress page says about one exit, from the same state the picture uses.
@MainActor struct ExitText {
    struct Row { var label: String; var value: String; var numeric = false; var failure = false }

    let progress: ExitProgress
    let exit: ExitModel
    let tip: UInt32
    let overview: OnchainOverview?
    let attempts: [OnchainOperationJournal.Attempt]
    let money: ExitMoney
    let language: String

    /// `L10n.text` in this page's language (the extractor reads `tr(` calls as copy).
    private func tr(_ source: String, _ arguments: Any...) -> String {
        arguments.isEmpty ? L10n.text(source, locale: language)
            : String(format: L10n.text(source, locale: language), locale: Locale(identifier: language), arguments: arguments.map { String(describing: $0) })
    }
    private func block(_ height: UInt32) -> String { height.formatted(.number.locale(Locale(identifier: language))) }

    /// This exit's claim, as journaled (its reviewed destination and fee).
    private var claim: OnchainQuote? { attempts.first { $0.quote.action == .claim && $0.quote.ids.contains(exit.id) }?.quote }
    private var claimTxid: String? { exit.details.claimTxid }
    /// What Noah recorded when it built that claim; kept after the journal entry is acknowledged.
    private var record: ExitClaimRecord? { claimTxid.flatMap { overview?.claimRecords[$0] } }
    /// Every exit the same claim transaction covered (bark records its txid on each).
    var batch: [ExitModel] {
        guard let claimTxid else { return [exit] }
        let covered = (overview?.exits ?? []).filter { $0.details.claimTxid == claimTxid || record?.ids.contains($0.id) == true }
        return covered.contains { $0.id == exit.id } ? covered : covered + [exit]
    }
    private var batchAmount: UInt64 { batch.reduce(0) { $0 + $1.amount } }
    /// What the whole claim paid this wallet (a claim to this phone).
    private var received: UInt64? { claimTxid.flatMap { overview?.received[$0] } }
    /// The claim's one fee: the drain's, as reviewed, or what didn't arrive here.
    var batchFee: UInt64? { record?.fee ?? claim?.fee ?? received.map { batchAmount - min(batchAmount, $0) } }
    /// This exit's part of that fee, so the parts add up to the claim.
    var claimFee: UInt64? {
        batchFee.map { ExitClaimSplit.fees(Dictionary(batch.map { ($0.id, $0.amount) }, uniquingKeysWith: { a, _ in a }), fee: $0)[exit.id] ?? 0 }
    }
    /// What the Claim button names for this exit; Claim itself prices every ready exit together.
    var claimable: UInt64 { exit.amount }
    var amount: UInt64 { progress.phase == .claimed ? claimFee.map { exit.amount - min(exit.amount, $0) } ?? exit.amount : exit.amount }

    /// One claim for several exits: the total received and the fee once.
    var batchLine: String? {
        guard progress.phase == .claimed, batch.count > 1, let fee = batchFee else { return nil }
        return L10n.plural("One claim for {n} exits: {amount} received after a {fee} fee.", count: batch.count,
                           money(batchAmount - min(batchAmount, fee)), money(fee), locale: language)
    }

    /// What this exit's whole path cost in this wallet's fee children, and the part shared with its other exits.
    var fees: (total: UInt64, shared: UInt64) { ExitFees.paid(exit, among: overview?.exits ?? [], childFees: overview?.childFees ?? [:]) }
    /// "₿961 · ₿933 shared with your other exits", or the total alone.
    private var feesLine: String {
        let fees = fees
        return fees.shared > 0 ? tr("{amount} · {shared} shared with your other exits", money(fees.total), money(fees.shared)) : money(fees.total)
    }
    /// What's missing for the parked package.
    var need: UInt64? {
        guard let fee = overview?.packageFees[exit.id], let confirmed = overview?.confirmed else { return nil }
        return fee > confirmed ? fee - confirmed : nil
    }
    private var current: ExitDetails.Transaction? { progress.step.flatMap { exit.path[safe: $0 - 1] } }

    var statusKind: ExitStatusLine.Kind {
        switch progress.phase {
        case .needsFunds, .alreadySpent, .swept: .failure
        case .claimable: .ready
        case .claimed, .canceled: .done
        default: .live
        }
    }

    var status: String {
        switch progress.phase {
        case .starting, .publishing: tr("Starting")
        case .sending, .mempool, .shared, .confirmed: tr("Sending to the chain")
        case .needsFunds: tr("Waiting for on-chain funds")
        case .onchain, .waiting: tr("Waiting for the timelock")
        case .claimable: tr("Ready to claim")
        case .claiming: tr("Claiming")
        case .claimed: tr("Claimed")
        case .canceled: tr("Canceled")
        case .alreadySpent: tr("Already spent")
        case .swept: tr("Swept")
        }
    }

    var phase: String {
        let n = progress.step ?? progress.confirmed, total = progress.total
        switch progress.phase {
        case .starting: return tr("Leaving Ark")
        case .publishing: return L10n.plural("{n} transactions to the chain", count: max(total, 1), locale: language)
        case .sending: return tr("Sending {n} of {total}", n, total)
        case .needsFunds: return tr("Needs on-chain funds")
        case .mempool:
            if progress.bumped { return tr("Fees rose. Paying more · waiting for block {height}", block(tip + 1)) }
            if progress.stuck > 0 { return L10n.plural("Not in a block yet · {n} blocks", count: Int(progress.stuck), locale: language) }
            if let child = current?.status.child_txid, let fee = overview?.childFees[child] {
                return tr("Anchor + {fee} fee · {n} of {total}", money(fee), n, total)
            }
            return tr("Waiting for block {height} · {n} of {total}", block(tip + 1), n, total)
        case .shared: return tr("Already sent by another exit · {n} of {total}", n, total)
        case .confirmed:
            let last = progress.heights.prefix(progress.confirmed).compactMap { $0 }.last ?? tip
            return tr("Confirmed in block {height} · {n} of {total}", block(last), progress.confirmed, total)
        case .onchain: return tr("On-chain in block {height}", block(progress.heights.compactMap { $0 }.last ?? tip))
        case .waiting:
            let left = progress.left(tip: tip) ?? 0
            guard left > 0 else { return tr("Unlocks at block {height}", block(progress.claimableHeight ?? tip)) }
            return L10n.plural("Claimable in {n} blocks · {time}", count: Int(left), ExitText.about(left, language), locale: language)
        case .claimable: return tr("Unlocked at block {height}", block(progress.claimableHeight ?? tip))
        case .claiming: return tr("Claiming · waiting for block {height}", block(tip + 1))
        case .claimed: return tr("Claimed in block {height}", block(progress.claimedHeight ?? tip))
        case .canceled: return tr("Exit canceled")
        case .alreadySpent: return tr("This VTXO was already spent, so it can’t exit.")
        case .swept: return tr("An output this exit needs was spent on-chain, so it can’t finish.")
        }
    }

    /// One sentence per phase for the wide layout's leading pane.
    var explanation: String {
        if progress.stale { return tr("Noah hasn’t checked on this exit in a while. Moving it forward can also send its next step or raise a fee.") }
        switch progress.phase {
        case .starting: return tr("Your VTXO leaves Ark. Nothing from here on needs Ark’s server.")
        case .publishing: return L10n.plural("Your bitcoin sits at the end of a path of {n} transactions in the round’s tree. The round’s own transaction is already on-chain.",
                                             count: max(progress.total, 1), locale: language)
        case .sending: return tr("Each one is sent only after the one before it is in a block.")
        case .needsFunds: return tr("Each transaction’s fee comes from your confirmed on-chain bitcoin. Noah sends the next one once a deposit confirms.")
        case .mempool:
            if progress.bumped { return tr("A block came without it. Noah pays more when the fee rate rises, so it gets into the next one.") }
            if progress.stuck > 0 { return tr("Blocks are full right now. Noah raises the fee when rates rise. Move your exit forward to try again now.") }
            return tr("Every exit transaction has a fee anchor. Noah attaches a fee to it, paid from your on-chain bitcoin.")
        case .shared: return tr("Another exit from your round already sent this one, with its own fee. You don’t pay for it.")
        case .confirmed: return tr("One transaction per block: about ten minutes each.")
        case .onchain, .waiting:
            return L10n.plural("Your bitcoin is on-chain, locked for {n} blocks. The lock gives Ark time to stop a VTXO that was already spent.",
                               count: Int(overview?.exitDelta ?? 144), locale: language)
        case .claimable: return tr("The lock is open. Claim it to another wallet or to this phone’s on-chain wallet.")
        case .claiming: return tr("One last transaction. Its fee comes out of the amount.")
        case .claimed: return tr("It’s ordinary bitcoin now.")
        case .canceled: return tr("The transactions already on-chain stay there. Your VTXO stays in your wallet and can exit again.")
        case .alreadySpent: return tr("This VTXO was already spent, so it can’t exit.")
        case .swept: return tr("An output this exit needs was spent on-chain, so it can’t finish.")
        }
    }

    var rows: [Row] {
        let total = progress.total
        switch progress.phase {
        case .claimed:
            var rows = [Row(label: tr("Claimed in"), value: tr("Block {height}", block(progress.claimedHeight ?? tip)))]
            rows.append(.init(label: tr("Network fees"), value: feesLine, numeric: true))
            if let fee = claimFee { rows.append(.init(label: tr("Claim fee"), value: money(fee), numeric: true)) }
            if let took {
                rows.append(.init(label: tr("Took"), value: L10n.plural("{n} blocks · {time}", count: Int(took), ExitText.about(took, language), locale: language)))
            }
            return rows
        case .canceled:
            return [.init(label: tr("VTXO"), value: tr("{amount} · in Ark", money(exit.amount)), numeric: true),
                    .init(label: tr("Already on-chain"), value: tr("{n} of {total}", progress.confirmed, total))]
        case .alreadySpent, .swept:
            return [.init(label: tr("Status"), value: status, failure: true)]
        default:
            var rows: [Row] = []
            if total > 0 { rows.append(.init(label: tr("Transactions"), value: tr("{n} of {total} in blocks", progress.confirmed, total))) }
            rows.append(progress.phase == .needsFunds
                        ? .init(label: tr("Network fees"), value: tr("{amount} · {left} left", money(fees.total), money(overview?.confirmed ?? 0)), numeric: true, failure: true)
                        : .init(label: tr("Network fees"), value: feesLine, numeric: true))
            if let height = progress.claimableHeight {
                rows.append(.init(label: tr("Claimable"), value: tr("Block {height}", block(height))))
            } else if let delta = overview?.exitDelta {
                let about = tip + UInt32(max(0, total - progress.confirmed)) + delta
                rows.append(.init(label: tr("Claimable"), value: tr("About block {height}", block(about))))
            }
            if progress.phase == .waiting, let left = progress.left(tip: tip) {
                rows.append(.init(label: tr("Left"), value: L10n.plural("{n} blocks", count: Int(left), locale: language), numeric: true))
            }
            return rows
        }
    }

    /// "about 16 h", or "under an hour" for a few blocks.
    static func about(_ blocks: UInt32, _ language: String) -> String {
        blocks < 6 ? L10n.text("under an hour", locale: language) : L10n.text("about {time}", VtxoList.span(Int64(blocks), locale: language), locale: language)
    }

    /// "1 VTXO · 4 transactions".
    static func count(vtxos: Int, transactions: UInt64, _ language: String) -> String {
        L10n.plural("{n} VTXOs", count: vtxos, locale: language) + " · " + L10n.plural("{n} transactions", count: Int(transactions), locale: language)
    }

    /// Blocks from start to the claim's block (bark keeps heights, not times).
    var took: UInt32? {
        guard let end = progress.claimedHeight, let start = exit.records.compactMap(\.tip_height).min(), end > start else { return nil }
        return end - start
    }

    func status(of tx: ExitDetails.Transaction, index: Int) -> String {
        if let height = tx.status.block?.height { return tr("Block {height}", block(height)) }
        // Later states don't list transactions: the last list bark recorded can predate the last one's block (as Android).
        if [.onchain, .waiting, .claimable, .claiming, .claimed].contains(progress.phase) { return tr("In a block") }
        switch tx.status.kind {
        case "awaiting-confirmation": return tx.status.origin == "wallet" || tx.status.origin == nil ? tr("Waiting for a block") : tr("Sent by another exit")
        case "awaiting-cpfp-broadcast": return progress.phase == .needsFunds ? tr("Needs on-chain funds") : tr("Ready to send")
        default: return tr("Not sent yet")
        }
    }

    var scene: ExitStageScene {
        var wallet: String? = overview.map { money($0.confirmed) }
        if [.claiming, .claimed].contains(progress.phase), claim != nil || record != nil {
            wallet = (record?.toPhone ?? claim?.destinationIsMine) == true ? tr("This phone")
                : claim?.destination.map { $0.prefix(6) + "…" + $0.suffix(4) } ?? tr("Another wallet")
        }
        return ExitStageScene(progress, tip: tip, wallet: wallet, delta: overview?.exitDelta, txids: exit.path.map(\.txid))
    }
}
