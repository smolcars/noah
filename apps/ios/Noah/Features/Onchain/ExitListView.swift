import SwiftUI

/// Exits (exit.md, "Exits list"): every exit bark tracks (`listAllExits`), grouped Ready to claim,
/// Exiting and Finished, each with its phase on one line. When two or more live exits share a
/// transaction, the shared path is drawn once above them (Decision 6), each connected group in its
/// own picture. Today's status filter is in the bar.
struct ExitListView: View {
    @State private var model: OnchainModel
    @State private var filter: String?
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale

    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }

    private var language: String { locale.identifier }
    private var exits: [ExitModel] { model.overview?.exits ?? [] }
    private var ready: [ExitModel] { exits.filter(\.claimable) }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        let shown = exits.filter { filter == nil || $0.state == filter }
        let shared = ExitSharing.groups(exits)
        ExitPanes {
            Text(verbatim: L10n.text("{n} in progress · {count} ready to claim", exits.filter { ExitRules.live($0.state) && !$0.claimable }.count.formatted(),
                                     ready.count.formatted(), locale: language))
                .noahText(SettingsText.line).foregroundStyle(NoahTokens.Colors.muted)
            // Every exit's fee children, each once, as Activity's "Exit fee" rows.
            let fees = ExitFees.total(exits, childFees: model.overview?.childFees ?? [:])
            if fees > 0 {
                Text(verbatim: L10n.text("{amount} in network fees", money(fees), locale: language))
                    .noahText(SettingsText.line).foregroundStyle(NoahTokens.Colors.muted)
            }
            ForEach(shared, id: \.first?.id) { group in
                ExitSharedPicture(exits: group, amounts: Dictionary(group.map { ($0.id, money($0.amount)) }, uniquingKeysWith: { a, _ in a })).frame(height: 250).padding(.top, 14)
                let paths = group.reduce(0) { $0 + $1.path.count }, unique = Set(group.flatMap { $0.path.map(\.txid) }).count
                SettingsFine(text: L10n.text("{n} exits from one round: {count} transactions, not {total}. Shared ones are sent and paid once.",
                                             group.count.formatted(), unique.formatted(), paths.formatted(), locale: language))
                    .multilineTextAlignment(.center).frame(maxWidth: .infinity)
            }
        } trailing: {
            if exits.isEmpty && !model.loading {
                SettingsFine(text: L10n.text("No exits have been started.", locale: language))
            }
            ForEach(["Ready to claim", "Exiting", "Finished"], id: \.self) { group in
                let rows = shown.filter { Self.group($0) == group }
                if !rows.isEmpty {
                    SettingsSection(title: LocalizedStringKey(group))
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, exit in
                        ExitListRow(exit: exit, progress: model.progress(exit), text: text(exit, money), hairline: index < rows.count - 1) {
                            settings.open(.exitProgress(exit.id))
                        }
                    }
                }
            }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        } footer: {
            if !ready.isEmpty {
                Button(L10n.text("Claim {amount}", money(ready.reduce(0) { $0 + $1.amount }), locale: language)) {
                    haptics.play(.light)
                    settings.open(.exitClaim)
                }
                .buttonStyle(SettingsActionStyle())
                .accessibilityIdentifier("exit-list-claim")
            }
            Button(L10n.text("Exit more VTXOs", locale: language)) { settings.open(.exitChoose) }
                .buttonStyle(.plain).noahText(NoahTokens.Typography.control).underline()
                .frame(minHeight: NoahTokens.Size.hitTarget)
                .accessibilityIdentifier("exit-list-more")
        }
        .settingsChrome("Exits") {
            Menu {
                Picker(selection: $filter) {
                    Text(verbatim: L10n.text("All", locale: language)).tag(nil as String?)
                    ForEach(ExitTimeline.labels.keys.sorted(), id: \.self) {
                        Text(verbatim: L10n.text(ExitTimeline.labels[$0]!, locale: language)).tag(Optional($0))
                    }
                } label: { Text(verbatim: L10n.text("Status", locale: language)) }
            } label: { ActivityCircleIcon(systemName: filter == nil ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill") }
                .accessibilityLabel(Text(verbatim: L10n.text("Status", locale: language)))
                .accessibilityIdentifier("exit-list-filter")
        }
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false))
    }

    private func text(_ exit: ExitModel, _ money: ExitMoney) -> ExitText {
        ExitText(progress: model.progress(exit), exit: exit, tip: model.overview?.tip ?? 0, overview: model.overview,
                 attempts: model.attempts, money: money, language: language)
    }

    static func group(_ exit: ExitModel) -> String {
        exit.claimable ? "Ready to claim" : ExitRules.live(exit.state) ? "Exiting" : "Finished"
    }
}

/// One exit: its coin, amount, phase line and a five-step rail.
struct ExitListRow: View {
    let exit: ExitModel
    let progress: ExitProgress
    let text: ExitText
    var hairline = true
    let action: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                ExitCoin(mark: text.statusKind == .failure ? .failure : progress.phase == .claimed ? .done : progress.live ? .live : .none)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: ExitMoney(app: app, locale: locale)(exit.amount)).noahText(HomeText.amount).monospacedDigit()
                    Text(verbatim: text.listLine).noahText(HomeText.footnote).monospacedDigit()
                        .foregroundStyle(text.statusKind == .failure ? NoahTokens.Colors.failure : text.statusKind == .ready ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
                    HStack(spacing: 3) {
                        ForEach(Array(progress.rail.enumerated()), id: \.offset) { _, step in
                            Capsule().fill(step == .done ? NoahTokens.Colors.foreground : step == .now ? NoahTokens.Colors.orange
                                           : step == .failure ? NoahTokens.Colors.failure : NoahTokens.Colors.hairline)
                                .frame(width: 14, height: 4)
                        }
                    }
                    .padding(.top, 5)
                    .accessibilityHidden(true)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) { if hairline { HomeHairline().padding(.leading, 54) } }
        }
        .buttonStyle(SettingsRowPressStyle())
        .opacity(progress.phase == .canceled || progress.phase == .alreadySpent || progress.phase == .swept ? 0.55 : 1)
        .accessibilityIdentifier("exit-row-\(exit.id)")
    }
}

extension ExitProgress {
    enum Step { case off, done, now, failure }

    /// Started, sending, on-chain and waiting, ready, claimed.
    var rail: [Step] {
        let at: Int
        switch phase {
        case .starting, .publishing: at = 0
        case .sending, .needsFunds, .mempool, .shared, .confirmed: at = 1
        case .onchain, .waiting: at = 2
        case .claimable: at = 3
        case .claiming: at = 4
        case .claimed: return Array(repeating: .done, count: 5)
        case .canceled, .alreadySpent, .swept: return [.done, .off, .off, .off, .off]
        }
        return (0..<5).map { $0 < at ? .done : $0 == at ? (phase == .needsFunds ? .failure : .now) : .off }
    }
}

extension ExitText {
    /// The list's one line.
    var listLine: String {
        switch progress.phase {
        case .claimed: return L10n.text("Claimed · block {height}", (progress.claimedHeight ?? tip).formatted(.number.locale(Locale(identifier: language))), locale: language)
        case .canceled: return L10n.text("Canceled · still in Ark", locale: language)
        case .waiting:
            let left = progress.left(tip: tip) ?? 0
            guard left > 0 else {
                return L10n.text("Unlocks at block {height}", (progress.claimableHeight ?? tip).formatted(.number.locale(Locale(identifier: language))), locale: language)
            }
            return L10n.plural("Waiting · {n} blocks · {time}", count: Int(left), ExitText.about(left, language), locale: language)
        case .sending, .mempool, .shared, .confirmed:
            let shared = ExitSharing.groups(overview?.exits ?? []).contains { $0.contains { $0.id == exit.id } }
            let line = L10n.text("Sending {n} of {total}", (progress.step ?? progress.confirmed + 1).formatted(), progress.total.formatted(), locale: language)
            return shared ? L10n.text("{status} · shared", line, locale: language) : line
        case .needsFunds: return L10n.text("Needs on-chain funds", locale: language)
        default: return status
        }
    }
}

/// Several exits from one round: their shared transactions drawn (and paid) once (prototype `drawShared`).
struct ExitSharedPicture: View {
    let exits: [ExitModel]
    /// Each exit's amount, formatted.
    let amounts: [String: String]
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale

    private struct Node { var txid: String; var depth: Int; var status: ExitDetails.Transaction.Status; var children: [Int] = []; var x = 0.0 }

    /// The round's block line and the spacing between levels, from the deepest path (`depth`
    /// transactions): the coins and their labels stay inside however long the paths.
    static func levels(height: Double, depth: Int) -> (y0: Double, gap: Double) {
        let y0 = min(height - 44, (height + 190) / 2)
        return (y0, min(32, (y0 - 60) / Double(max(1, depth))))
    }

    var body: some View {
        Canvas { context, size in
            let colors = MotionColors.theme(colorScheme)
            let canvas = MotionCanvas(context: context, size: size, viewBox: CGRect(origin: .zero, size: size), colors: colors)
            // A trie of the paths, root first; each exit's coin hangs off its last transaction.
            var nodes: [Node] = [], roots: [Int] = [], coins: [(node: Int, exit: ExitModel)] = []
            for exit in exits {
                var parent: Int?
                // Past Processing every transaction is in a block, whatever bark's last list said.
                let settled = !["Start", "Processing"].contains(exit.state)
                for (depth, tx) in exit.path.enumerated() {
                    let siblings = parent.map { nodes[$0].children } ?? roots
                    if let found = siblings.first(where: { nodes[$0].txid == tx.txid }) { parent = found; continue }
                    let status = settled && tx.status.block == nil
                        ? ExitDetails.Transaction.Status(kind: "confirmed", block: .init(height: 0, hash: "")) : tx.status
                    nodes.append(.init(txid: tx.txid, depth: depth, status: status))
                    if let parent { nodes[parent].children.append(nodes.count - 1) } else { roots.append(nodes.count - 1) }
                    parent = nodes.count - 1
                }
                if let parent { coins.append((parent, exit)) }
            }
            let (y0, gap) = Self.levels(height: size.height, depth: exits.map(\.path.count).max() ?? 1)
            let width = size.width - 60
            // Coins spread evenly; each transaction sits over the middle of what hangs from it.
            var order: [Int] = []
            func visit(_ index: Int) {
                let hanging = coins.indices.filter { coins[$0].node == index }
                order.append(contentsOf: hanging)
                nodes[index].children.forEach(visit)
            }
            roots.forEach(visit)
            var coinX: [Int: Double] = [:]
            for (slot, coin) in order.enumerated() {
                coinX[coin] = 30 + width * (order.count == 1 ? 0.5 : Double(slot) / Double(order.count - 1))
            }
            func place(_ index: Int) -> Double {
                let xs = nodes[index].children.map(place) + coins.indices.filter { coins[$0].node == index }.compactMap { coinX[$0] }
                nodes[index].x = xs.isEmpty ? size.width / 2 : xs.reduce(0, +) / Double(xs.count)
                return nodes[index].x
            }
            roots.forEach { _ = place($0) }
            func y(_ depth: Int) -> Double { y0 - 26 - Double(depth) * gap }
            // The round's block, then the links.
            let block = CGRect(x: size.width / 2 - 13, y: y0, width: 26, height: 26)
            canvas.fill(Path(roundedRect: block, cornerRadius: 6), colors.foreground.opacity(0.4))
            for root in roots { canvas.context.stroke(ExitStagePainter.link(CGPoint(x: nodes[root].x, y: y(0) + 7), CGPoint(x: size.width / 2, y: y0)),
                                                     with: .color(colors.orange), lineWidth: 1.5) }
            for node in nodes {
                for child in node.children {
                    let live = nodes[child].status.kind != "verify-inputs" && nodes[child].status.kind != "awaiting-input-confirmation"
                    canvas.context.stroke(ExitStagePainter.link(CGPoint(x: nodes[child].x, y: y(nodes[child].depth) + 7), CGPoint(x: node.x, y: y(node.depth) - 7)),
                                          with: .color(colors.orange.opacity(live ? 1 : 0.5)), lineWidth: 1.5)
                }
            }
            for coin in coins.indices {
                let x = coinX[coin] ?? 0, from = nodes[coins[coin].node]
                canvas.context.stroke(ExitStagePainter.link(CGPoint(x: x, y: y(from.depth + 1) + 12), CGPoint(x: from.x, y: y(from.depth) - 7)),
                                      with: .color(colors.orange), lineWidth: 1.5)
            }
            for node in nodes {
                let p = CGPoint(x: node.x, y: y(node.depth))
                if node.status.block != nil {
                    canvas.fill(Path(roundedRect: CGRect(x: p.x - 7, y: p.y - 7, width: 14, height: 14), cornerRadius: 3.5), colors.foreground)
                } else if node.status.kind == "awaiting-confirmation" {
                    canvas.stroke(Path(roundedRect: CGRect(x: p.x - 8, y: p.y - 8, width: 16, height: 16), cornerRadius: 4), colors.foreground, 1.5, dash: [3, 3])
                    canvas.fill(canvas.circle(p.x, p.y, 3.4), colors.orange)
                } else {
                    canvas.fill(canvas.circle(p.x, p.y, 5.5), colors.background)
                    canvas.stroke(canvas.circle(p.x, p.y, 5.5), colors.muted, 1.5)
                }
            }
            for coin in coins.indices {
                let x = coinX[coin] ?? 0, cy = y(nodes[coins[coin].node].depth + 1)
                canvas.mineCoin(x, cy, 12, ring: 2.5)
                canvas.text(amounts[coins[coin].exit.id] ?? "", x, cy - 21, size: 10.5, weight: .medium, color: colors.foreground)
            }
        }
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: L10n.text("Shared transactions are sent and paid once.", locale: locale.identifier)))
    }
}
