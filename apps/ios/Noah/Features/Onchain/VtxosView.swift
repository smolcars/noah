import BarkBoltFFI
import SwiftUI

/// Bitcoin → VTXOs: the total, an expiry line placing each VTXO by when it expires, then
/// Expiring, Spendable and Locked. ⋯ holds the filter, Select VTXOs and Reload. Renewing is
/// today's refresh, always through its review and an explicit confirmation.
struct VtxosView: View {
    @State private var model: OnchainModel
    @State private var filter = VtxoList.Filter.all
    @State private var selecting = false
    @State private var selected = Set<String>()
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(SettingsSummary.self) private var summary: SettingsSummary?
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }

    private var language: String { locale.identifier }
    private var vtxos: [VtxoModel] { model.overview?.vtxos ?? [] }
    private var due: [VtxoModel] { VtxoList.due(vtxos) }

    var body: some View {
        SettingsScroll {
            if filter != .all {
                VtxoFilterChip(title: L10n.text(filter.title, locale: language)) {
                    haptics.play(.selection)
                    withAnimation(NoahTokens.Motion.travel(reduceMotion)) { filter = .all }
                }
                .padding(.top, 4)
            }
            if model.overview == nil {
                if model.loading { ProgressView().frame(maxWidth: .infinity).padding(.top, 40) }
            } else if vtxos.isEmpty {
                empty
            } else {
                header
                if selecting { selectionControls.padding(.top, 14) }
                ForEach(VtxoList.groups(vtxos, filter: filter)) { section in
                    let rows = section.rows
                    Text(L10n.text(section.group.title, locale: language))
                        .noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
                        .padding(.top, 18).padding(.bottom, 2)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, vtxo in
                        VtxoRow(vtxo: vtxo, selecting: selecting, selected: selected.contains(vtxo.id), hairline: index < rows.count - 1) {
                            tap(vtxo)
                        }
                    }
                }
            }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        }
        .settingsChrome("VTXOs") { menu }
        .settingsFooter {
            if selecting {
                Button(L10n.text("Cancel", locale: language)) { endSelecting() }
                    .buttonStyle(OnboardingSecondaryButtonStyle(height: NoahTokens.Size.button))
                    .frame(maxWidth: 140)
                Button(selected.isEmpty ? L10n.text("Renew", locale: language) : L10n.text("Renew {n}", selected.count, locale: language)) {
                    haptics.play(.light)
                    Task { await model.review(.refresh, ids: VtxoList.renewable(selected, in: vtxos)) }
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(VtxoList.renewable(selected, in: vtxos).isEmpty || model.locked)
                .accessibilityIdentifier("vtxos-renew-selected")
            }
        }
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false))
        .settingsSheet(item: Binding(get: { model.quote }, set: { if $0 == nil { model.invalidate() } })) { quote in
            RenewReviewSheet(quote: quote, cancel: { model.invalidate() }) {
                Task {
                    await model.confirm()
                    if model.error == nil { endSelecting() }
                    await summary?.refresh(suspended: app.isWalletSuspended, force: true)
                }
            }
        }
        .onChange(of: selected) { _, _ in model.invalidate() }
        .accessibilityIdentifier("vtxos")
    }

    // MARK: Header

    @ViewBuilder private var header: some View {
        let total = vtxos.reduce(0) { $0 + $1.amount }
        Text(verbatim: money(total)).noahText(SettingsText.big).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
            .padding(.top, 6)
            .accessibilityIdentifier("vtxos-total")
        Text(verbatim: L10n.plural("In {n} VTXOs", count: vtxos.count, locale: language))
            .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
        VtxoTimeline(vtxos: vtxos, threshold: UInt32(WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold))
            .frame(height: 80)
            .padding(.top, 20)
        if !due.isEmpty {
            HStack(spacing: 8) {
                Circle().fill(NoahTokens.Colors.orange).frame(width: 8, height: 8)
                Text(verbatim: L10n.plural("{n} VTXOs expire within a day", count: due.count, locale: language))
            }
            .noahText(NoahTokens.Typography.control)
            .padding(.top, 6)
            Button(L10n.text("Renew {amount}", money(due.reduce(0) { $0 + $1.amount }), locale: language)) {
                haptics.play(.light)
                Task { await model.review(.refresh, ids: due.map(\.id)) }
            }
            .buttonStyle(HomePillStyle(prominent: false, height: 40, font: HomeText.strong))
            .disabled(model.locked)
            .padding(.top, 14)
            .accessibilityIdentifier("vtxos-renew-due")
        } else if let soonest = vtxos.filter(\.refreshable).map({ Int64($0.expiry) - Int64($0.tip) }).min() {
            HStack(spacing: 8) {
                Image(systemName: "clock").accessibilityHidden(true).font(.system(size: 15))
                Text(verbatim: L10n.text("Next expiry in {time} · renews automatically", VtxoList.timeLeft(soonest, locale: language), locale: language))
            }
            .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
            .padding(.top, 6)
        }
    }

    /// Today's bulk controls while selecting: what the filter shows, the expiring ones, or none.
    private var selectionControls: some View {
        GlassGroup(spacing: NoahTokens.Space.s) {
            WrapLayout(spacing: NoahTokens.Space.s, lineSpacing: NoahTokens.Space.s) {
                Button(L10n.text("Select visible", locale: language)) { choose(VtxoList.visibleSelection(vtxos, filter: filter)) }
                    .accessibilityIdentifier("vtxos-select-visible")
                Button(L10n.text("Select expiring", locale: language)) { choose(VtxoList.preselected(vtxos)) }
                    .accessibilityIdentifier("vtxos-select-expiring")
                Button(L10n.text("Clear selection", locale: language)) { choose([]) }
                    .disabled(selected.isEmpty)
                    .accessibilityIdentifier("vtxos-clear-selection")
            }
            .buttonStyle(OnboardingChipStyle())
        }
        .disabled(model.locked)
    }

    private func choose(_ ids: Set<String>) {
        haptics.play(.selection)
        selected = ids
    }

    private var empty: some View {
        VStack(spacing: 6) {
            Image(systemName: "bitcoinsign").accessibilityHidden(true).font(.system(size: 22, weight: .medium))
                .frame(width: 56, height: 56).background(NoahTokens.Colors.soft, in: Circle())
            Text(L10n.text("No VTXOs yet", locale: language)).noahText(ActivityText.emptyTitle).padding(.top, 20)
            Text(L10n.text("Bitcoin you receive shows up here as VTXOs.", locale: language))
                .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
                .multilineTextAlignment(.center)
            Button(L10n.text("Receive", locale: language)) { settings.receive() }
                .buttonStyle(HomePillStyle(height: 40))
                .padding(.top, 12)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    private var menu: some View {
        Menu {
            Picker(selection: Binding(get: { filter }, set: { value in
                haptics.play(.selection)
                withAnimation(NoahTokens.Motion.travel(reduceMotion)) { filter = value }
            })) {
                ForEach(VtxoList.Filter.allCases) { Text(L10n.text($0.title, locale: language)).tag($0) }
            } label: { Text("Filter") }
                .pickerStyle(.inline)
            Section {
                Button(L10n.text("Select VTXOs", locale: language), systemImage: "checkmark.circle") {
                    haptics.play(.light)
                    selected = VtxoList.preselected(vtxos)
                    withAnimation(NoahTokens.Motion.travel(reduceMotion)) { selecting = true }
                }
                .disabled(vtxos.isEmpty || model.locked)
                .accessibilityIdentifier("vtxos-select")
                // Its own string: "Refresh" is the old word for renewing.
                Button(L10n.text("Refresh list", locale: language), systemImage: "arrow.clockwise") {
                    Task { model.invalidate(); await model.load() }
                }
                .disabled(model.loading)
            }
        } label: {
            if settings.wide { ActivityCircleIcon(systemName: "ellipsis") } else { Label("More", systemImage: "ellipsis") }
        }
        .accessibilityLabel(Text("More"))
        .accessibilityIdentifier("vtxos-menu")
    }

    // MARK: Actions

    private func tap(_ vtxo: VtxoModel) {
        if selecting {
            guard vtxo.refreshable else { return }
            haptics.play(.selection)
            selected = VtxoList.toggle(vtxo.id, in: selected, vtxos: vtxos)
        } else {
            haptics.play(.light)
            settings.open(.vtxo(vtxo.id))
        }
    }

    private func endSelecting() {
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) {
            selecting = false
            selected = []
        }
    }

    private func money(_ sats: UInt64) -> String {
        app.isBalanceHidden ? (app.amountUnit == .bip177 ? "₿••••" : "•••• sats")
            : BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale)
    }
}

extension VtxoList {
    /// A short span for the expiry line's ticks and the next-expiry line: "6 h", "3 d", "2 wk".
    static func span(_ blocks: Int64, locale: String) -> String {
        if blocks < 6 { return L10n.text("under an hour", locale: locale) }
        if blocks < 144 { return L10n.text("{n} h", blocks / 6, locale: locale) }
        if blocks < 1_008 { return L10n.text("{n} d", blocks / 144, locale: locale) }
        return L10n.text("{n} wk", blocks / 1_008, locale: locale)
    }

    /// The next-expiry line's span, as Android words it: "under an hour", "6 hours", "3 days".
    static func timeLeft(_ blocks: Int64, locale: String) -> String {
        if blocks < 6 { return L10n.text("under an hour", locale: locale) }
        if blocks < 144 { return L10n.plural("{n} hours", count: Int((Double(blocks) / 6).rounded()), locale: locale) }
        return L10n.plural("{n} days", count: Int((Double(blocks) / 144).rounded()), locale: locale)
    }

    /// The row's status words.
    static func status(_ vtxo: VtxoModel, locale: String) -> String {
        if vtxo.state == "Locked" { return L10n.text("Locked", locale: locale) }
        if vtxo.state != "Spendable" { return L10n.text(vtxo.state, locale: locale) }
        return expiresIn(Int64(vtxo.expiry) - Int64(vtxo.tip), locale: locale)
    }
}

/// The active filter's name with ×.
private struct VtxoFilterChip: View {
    let title: String
    let onClear: () -> Void

    var body: some View {
        Button(action: onClear) {
            HStack(spacing: 4) {
                Text(verbatim: title).noahText(ActivityText.chip)
                Image(systemName: "xmark").font(.system(size: 11, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
            }
            .padding(.leading, 12).padding(.trailing, 9)
            .frame(minHeight: 30)
            .glassCompat(in: Capsule(), interactive: true)
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text("Clear filter"))
        .accessibilityIdentifier("vtxos-filter-chip")
    }
}

/// One VTXO: ₿ (or a lock), with an orange dot when it's expiring; a checkbox while selecting.
struct VtxoRow: View {
    let vtxo: VtxoModel
    var selecting = false
    var selected = false
    var hairline = true
    let action: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                if selecting {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle").accessibilityHidden(true)
                        .font(.system(size: 24))
                        .foregroundStyle(vtxo.refreshable ? NoahTokens.Colors.foreground : NoahTokens.Colors.hairline)
                        .frame(width: 42, height: 42)
                } else {
                    VtxoAvatar(vtxo: vtxo, unit: app.amountUnit, size: 42)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: VtxoList.status(vtxo, locale: locale.identifier)).noahText(SettingsText.value)
                        .foregroundStyle(vtxo.expired && vtxo.state != "Locked" ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                    Text(verbatim: L10n.text(
                        "Block {height} · {id}",
                        vtxo.expiry.formatted(.number.locale(locale)),
                        String(vtxo.id.prefix(6)) + "…",
                        locale: locale.identifier))
                        .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(verbatim: app.isBalanceHidden ? "••••" : BitcoinAmount.format(sats: Int64(clamping: vtxo.amount), unit: app.amountUnit, locale: locale))
                    .noahText(HomeText.amount).monospacedDigit()
                    .foregroundStyle(vtxo.state == "Locked" ? NoahTokens.Colors.muted : NoahTokens.Colors.foreground)
            }
            .frame(height: 66)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) { if hairline { HomeHairline().padding(.leading, 56) } }
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityAddTraits(selecting && selected ? .isSelected : [])
        .accessibilityIdentifier("vtxo-row-\(vtxo.id)")
    }
}

struct VtxoAvatar: View {
    let vtxo: VtxoModel
    let unit: BitcoinAmountUnit
    var size: CGFloat = 42

    var body: some View {
        ZStack {
            Circle().fill(NoahTokens.Colors.soft)
            if vtxo.state == "Locked" {
                Image(systemName: "lock").font(.system(size: size * 0.4, weight: .medium))
            } else {
                Text(verbatim: unit == .bip177 ? "₿" : "s").noahText(size > 42 ? HomeText.initialLarge : HomeText.initial)
            }
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .frame(width: size, height: size)
        .overlay(alignment: .topTrailing) {
            if VtxoList.isDue(vtxo) {
                Circle().fill(NoahTokens.Colors.orange).frame(width: size > 42 ? 14 : 11, height: size > 42 ? 14 : 11)
                    .background(Circle().stroke(NoahTokens.Colors.background, lineWidth: 4))
                    .offset(x: 1, y: -1)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Every VTXO placed by blocks left, sized by amount; the expiring ones ringed in orange inside
/// the shaded refresh window, locked ones dashed.
private struct VtxoTimeline: View {
    let vtxos: [VtxoModel]
    let threshold: UInt32
    @Environment(\.locale) private var locale

    var body: some View {
        let lefts = vtxos.map { max(0, Int64($0.expiry) - Int64($0.tip)) }
        let span = max(lefts.max() ?? 0, Int64(threshold) * 4, 24)
        let largest = Double(vtxos.map(\.amount).max() ?? 1)
        Canvas { context, size in
            draw(context, size: size, lefts: lefts, span: span, largest: largest)
        }
        .accessibilityElement()
        .accessibilityLabel(Text(L10n.text("When your VTXOs expire", locale: locale.identifier)))
    }

    private func draw(_ context: GraphicsContext, size: CGSize, lefts: [Int64], span: Int64, largest: Double) {
        let x0: CGFloat = 6, x1 = size.width - 6, base: CGFloat = 50
        func x(_ blocks: Int64) -> CGFloat { x0 + (x1 - x0) * CGFloat(min(blocks, span)) / CGFloat(span) }
        if vtxos.contains(where: VtxoList.isDue) {
            context.fill(Path(roundedRect: CGRect(x: x0, y: 6, width: x(Int64(threshold)) - x0 + 4, height: base - 6), cornerRadius: 4),
                         with: .color(NoahTokens.Colors.orangeTint))
        }
        context.fill(Path(CGRect(x: x0, y: base + 6, width: x1 - x0, height: 1)), with: .color(NoahTokens.Colors.hairline))
        for tick in 0...4 {
            let blocks = span * Int64(tick) / 4, at = x(blocks)
            context.fill(Path(CGRect(x: at - 0.5, y: base + 3, width: 1, height: 6)), with: .color(NoahTokens.Colors.muted))
            let label = tick == 0 ? L10n.text("Now", locale: locale.identifier) : VtxoList.span(blocks, locale: locale.identifier)
            context.draw(Text(verbatim: label).font(NoahTokens.Typography.caption.font(size: 11)).foregroundStyle(NoahTokens.Colors.muted),
                         at: CGPoint(x: at, y: base + 22), anchor: tick == 0 ? .leading : tick == 4 ? .trailing : .center)
        }
        var placed: [(CGPoint, CGFloat)] = []
        for (vtxo, left) in zip(vtxos, lefts).sorted(by: { $0.0.amount > $1.0.amount }) {
            let radius = 3 + 7 * CGFloat((Double(vtxo.amount) / largest).squareRoot())
            var center = CGPoint(x: min(max(x(left), x0 + radius), x1 - radius), y: base - radius)
            for _ in 0..<60 where placed.contains(where: { hypot($0.0.x - center.x, $0.0.y - center.y) < $0.1 + radius + 1.5 }) {
                center.y -= 1.5
            }
            placed.append((center, radius))
            let dot = Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
            if vtxo.state == "Locked" {
                context.stroke(dot, with: .color(NoahTokens.Colors.foreground), style: StrokeStyle(lineWidth: 1.4, dash: [2.5, 2]))
            } else {
                let due = VtxoList.isDue(vtxo)
                context.fill(dot, with: .color(NoahTokens.Colors.foreground.opacity(due ? 1 : 0.85)))
                if due {
                    let ring = radius + 2.5
                    context.stroke(Path(ellipseIn: CGRect(x: center.x - ring, y: center.y - ring, width: ring * 2, height: ring * 2)),
                                   with: .color(NoahTokens.Colors.orange), lineWidth: 2)
                }
            }
        }
    }
}

/// One VTXO: amount, status and its lifetime, then State, Expires, Blocks left, VTXO ID and Anchor,
/// everything else under Details, and Renew. Activity's VTXO links open it too, spent ones read by ID.
struct VtxoDetailView: View {
    let id: String
    @State private var model: OnchainModel
    @State private var read = ActivityCoinRead()
    @State private var details = false
    @State private var price: Double?
    @State private var lifetime: UInt32?
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(HistoryModel.self) private var history: HistoryModel?
    @Environment(SettingsSummary.self) private var summary: SettingsSummary?
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.settingsFixture) private var fixture

    init(id: String, model: OnchainModel = OnchainModel()) {
        self.id = id
        _model = State(initialValue: model)
    }

    private var language: String { locale.identifier }
    /// In the wallet's current VTXOs (renewable from here).
    private var current: VtxoModel? { model.overview?.vtxos.first { $0.id == id } }
    private var vtxo: VtxoModel? {
        if let current { return current }
        if case .coin(let coin) = read.state { return coin }
        return nil
    }

    var body: some View {
        SettingsScroll {
            if let vtxo {
                hero(vtxo)
                facts(vtxo)
                moreDetails(vtxo)
            }
            if vtxo == nil {
                // The ID stays, whatever the read says.
                SettingsDetailRow(label: L10n.text("VTXO ID", locale: language), value: id, mono: true, copy: id, hairline: false)
                    .padding(.top, 6)
            }
            if current == nil && !model.loading {
                switch read.state {
                case .failed: SettingsFine(text: L10n.text("Couldn't read wallet status. Pull to refresh.", locale: language), failure: true)
                case .missing, .coin:
                    SettingsFine(text: L10n.text("This VTXO is no longer in the wallet's current VTXOs. Refresh to check its status.", locale: language))
                case .loading: ProgressView().frame(maxWidth: .infinity).padding(.top, 18)
                }
            }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        }
        .settingsChrome(nil)
        .settingsFooter {
            if let current, current.refreshable {
                Button(L10n.text("Renew", locale: language)) {
                    haptics.play(.light)
                    Task { await model.review(.refresh, ids: [id]) }
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(model.locked)
                .accessibilityIdentifier("vtxo-renew")
            }
        }
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false, refresh: { if let history { await read.load(id, history: history) } }))
        .settingsSheet(item: Binding(get: { model.quote }, set: { if $0 == nil { model.invalidate() } })) { quote in
            RenewReviewSheet(quote: quote, cancel: { model.invalidate() }) {
                Task {
                    await model.confirm()
                    await summary?.refresh(suspended: app.isWalletSuspended, force: true)
                }
            }
        }
        .task(id: "\(id):\(history?.revision ?? 0)") {
            if let history { await read.load(id, history: history) }
        }
        .task {
            if let history { price = await history.currentPrice(currency: app.preferredCurrency) }
            if let fixture { lifetime = fixture.vtxoLifetime; return }
            lifetime = (try? await WalletSession.shared.withOpenWallet { try await $0.wallet.arkInfo() })??.vtxoLifetime
        }
        .accessibilityIdentifier("vtxo-detail")
    }

    private func facts(_ vtxo: VtxoModel) -> some View {
        VStack(spacing: 0) {
            SettingsDetailRow(label: L10n.text("State", locale: language), value: L10n.text(vtxo.state, locale: language))
            SettingsDetailRow(label: L10n.text("Expires", locale: language),
                              value: L10n.text("Block {height}", vtxo.expiry.formatted(.number.locale(locale)), locale: language))
            if vtxo.tip > 0 {
                SettingsDetailRow(label: L10n.text("Blocks left", locale: language),
                                  value: vtxo.expiry <= vtxo.tip ? L10n.text("Expired", locale: language) : (vtxo.expiry - vtxo.tip)
                                      .formatted(.number.locale(locale)),
                                  numeric: true)
            }
            SettingsDetailRow(label: L10n.text("VTXO ID", locale: language), value: vtxo.id, mono: true, copy: vtxo.id)
            SettingsDetailRow(label: L10n.text("Anchor", locale: language), value: vtxo.anchorPoint, mono: true, copy: vtxo.anchorPoint,
                              explorer: OnchainDisplay.explorerURL(vtxo.anchorPoint, variant: app.variant), hairline: false)
        }
        .padding(.top, 22)
    }

    private func moreDetails(_ vtxo: VtxoModel) -> some View {
        SettingsDetails(open: $details) {
            SettingsDetailRow(label: L10n.text("Type", locale: language), value: vtxo.kind, compact: true)
            SettingsDetailRow(label: L10n.text("Status", locale: language),
                              value: L10n.text(vtxo.registered ? "Registered" : "Not registered", locale: language), compact: true)
            SettingsDetailRow(
                label: L10n.text("Exit delta", locale: language),
                value: L10n.plural("{n} blocks", count: Int(vtxo.exitDelta), locale: language),
                compact: true)
            SettingsDetailRow(label: L10n.text("Exit depth", locale: language), value: "\(vtxo.exitDepth)", compact: true)
            SettingsDetailRow(
                label: L10n.text("Exit weight", locale: language),
                value: L10n.text("{n} wu", vtxo.exitWeight.formatted(.number.locale(locale)), locale: language),
                compact: true)
            SettingsDetailRow(
                label: L10n.text("Server key", locale: language),
                value: vtxo.serverPubkey,
                mono: true,
                compact: true,
                copy: vtxo.serverPubkey)
            SettingsDetailRow(label: L10n.text("Point", locale: language), value: vtxo.point, mono: true, compact: true, copy: vtxo.point)
            if vtxo.tip > 0 {
                SettingsDetailRow(label: L10n.text("Current block", locale: language), value: vtxo.tip.formatted(.number.locale(locale)),
                                  compact: true, hairline: false)
            }
        }
    }

    @ViewBuilder private func hero(_ vtxo: VtxoModel) -> some View {
        VtxoAvatar(vtxo: vtxo, unit: app.amountUnit, size: 60).padding(.top, 6)
        Text(verbatim: app.isBalanceHidden ? "₿••••" : BitcoinAmount.format(sats: Int64(clamping: vtxo.amount), unit: app.amountUnit, locale: locale))
            .noahText(ActivityDetailText.amount).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
            .padding(.top, 18)
        HStack(spacing: 8) {
            if VtxoList.isDue(vtxo) { Circle().fill(NoahTokens.Colors.orange).frame(width: 7, height: 7) }
            Text(verbatim: vtxo.tip > 0 ? VtxoList.status(vtxo, locale: language) : L10n.text(vtxo.state, locale: language))
                .noahText(NoahTokens.Typography.label)
                .foregroundStyle(vtxo.expired && vtxo.tip > 0 && vtxo.state != "Locked" ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
        }
        .padding(.top, 12)
        if let fiat = fiat(vtxo) {
            Text(verbatim: fiat).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
        }
        if let lifetime, lifetime > 0, vtxo.tip > 0 {
            let start = Int64(vtxo.expiry) - Int64(lifetime)
            let used = min(1, max(0, Double(Int64(vtxo.tip) - start) / Double(lifetime)))
            VStack(alignment: .leading, spacing: 8) {
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(NoahTokens.Colors.soft)
                        Capsule().fill(NoahTokens.Colors.foreground).frame(width: proxy.size.width * used)
                        RoundedRectangle(cornerRadius: 1).fill(NoahTokens.Colors.orange).frame(width: 2, height: 14)
                            .offset(x: proxy.size.width * used - 1)
                    }
                }
                .frame(height: 6)
                HStack {
                    Text(verbatim: L10n.text("Block {height}", max(0, start).formatted(.number.locale(locale)), locale: language))
                    Spacer()
                    Text(verbatim: vtxo.expiry.formatted(.number.locale(locale)))
                }
                .font(NoahTokens.Typography.caption.font(size: 12)).foregroundStyle(NoahTokens.Colors.muted)
            }
            .frame(maxWidth: 340)
            .padding(.top, 18)
            .accessibilityElement(children: .combine)
        }
    }

    private func fiat(_ vtxo: VtxoModel) -> String? {
        if app.isBalanceHidden { return HomeModel.hiddenFiat(currency: app.preferredCurrency) }
        return price.map { FiatCurrency.display(sats: Int64(clamping: vtxo.amount), btcPrice: $0, currency: app.preferredCurrency, locale: locale) }
    }
}

/// Renew's review: today's rows, then Renew to confirm. Nothing is submitted from one tap.
struct RenewReviewSheet: View {
    let quote: OnchainQuote
    let cancel: () -> Void
    let confirm: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale

    private var language: String { locale.identifier }

    var body: some View {
        SettingsSheetBody(title: quote.ids.count == 1 ? L10n.text("Renew this VTXO", locale: language)
            : L10n.plural("Renew {n} VTXOs", count: quote.ids.count, locale: language),
            line: quote.ids.count == 1 ? L10n.text("Joins the next Ark round, so it stays spendable.", locale: language)
                : L10n.text("Joins the next Ark round, so they stay spendable.", locale: language)) {
            VStack(spacing: 0) {
                SettingsDetailRow(label: L10n.text("Amount", locale: language), value: money(quote.amount), numeric: true)
                SettingsDetailRow(label: L10n.text("VTXOs", locale: language), value: "\(quote.ids.count)", numeric: true)
                if let fee = quote.fee, quote.chainFee != nil {
                    SettingsDetailRow(label: L10n.text("Fee", locale: language), value: money(fee), numeric: true)
                    SettingsDetailRow(label: L10n.text("You keep", locale: language), value: money(quote.net), numeric: true, hairline: false)
                }
            }
            .padding(.top, 8)
        } actions: {
            SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Renew", locale: language),
                                 actionID: "onchain-confirm", onCancel: cancel, onAction: confirm)
        }
    }

    private func money(_ sats: UInt64) -> String { BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale) }
}
