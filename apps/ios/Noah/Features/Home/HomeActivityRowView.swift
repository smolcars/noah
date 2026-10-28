import SwiftUI

/// A 66-pt history row (prototype `rowHtml`).
struct HomeActivityRowView: View {
    let transaction: HistoryTransaction
    let ownDomain: String
    let unit: BitcoinAmountUnit
    let hidden: Bool
    var isNew = false
    /// Under a day header the subtitle gives the time; otherwise today's time or the date.
    var grouped = false
    @Environment(\.locale) private var locale

    var body: some View {
        let row = HomeActivityRow(transaction, ownDomain: ownDomain)
        HStack(spacing: 14) {
            HomeAvatar(avatar: row.avatar, pending: row.status.isPending, failed: row.status.isFailure, isNew: isNew)
            VStack(alignment: .leading, spacing: 0) {
                Group {
                    if row.titleIsVerbatim { Text(verbatim: row.title) } else { Text(LocalizedStringKey(row.title)) }
                }
                .noahText(NoahTokens.Typography.body)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .lineLimit(1).truncationMode(.middle)
                Text(verbatim: subtitle(row))
                    .noahText(HomeText.footnote)
                    .foregroundStyle(row.status.isUnsuccessful ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(verbatim: row.amount(unit: unit, hidden: hidden, locale: locale))
                .noahText(HomeText.amount)
                .monospacedDigit()
                .strikethrough(row.status.isUnsuccessful)
                .foregroundStyle(row.status == .done ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
                .lineLimit(1)
                .layoutPriority(1)
        }
        .frame(height: 66)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func subtitle(_ row: HomeActivityRow) -> String {
        let language = locale.identifier
        let kind: String? = switch row.kind {
        case .noahUser: L10n.text("Noah user", locale: language)
        case .lightning: L10n.text("Lightning", locale: language)
        case .onchain: L10n.text("On-chain", locale: language)
        case .ark: L10n.text("Ark", locale: language)
        case .recurring: L10n.text("Recurring", locale: language)
        case nil: nil
        }
        func joined(_ parts: String?...) -> String { parts.compactMap(\.self).joined(separator: " · ") }
        let nothingLeft = " " + L10n.text("· nothing left your wallet", locale: language)
        switch row.status {
        case .failed(let none): return L10n.text("Failed", locale: language) + (none ? nothingLeft : "")
        case .refunded: return L10n.text("Refunded · back in your wallet", locale: language)
        case .canceled(let none): return L10n.text("Canceled", locale: language) + (none ? nothingLeft : "")
        case .sending: return joined(L10n.text("Sending", locale: language), kind)
        case .confirming: return joined(L10n.text("Confirming", locale: language), kind)
        case .inRound: return L10n.text("In a round", locale: language)
        case .exiting: return L10n.text("Exiting", locale: language)
        case .done: return joined(kind, time)
        }
    }

    private var time: String? {
        if let label = transaction.confirmationLabel { return L10n.text(label, locale: locale.identifier) }
        guard let date = transaction.timestamp else { return nil }
        if grouped || Calendar.current.isDateInToday(date) {
            return date.formatted(.dateTime.hour().minute().locale(locale))
        }
        return date.formatted(.dateTime.month(.abbreviated).day().locale(locale))
    }
}

/// 42-pt circle on the soft surface: an initial for people and addresses, an icon otherwise.
/// The payment detail's header uses it at 60 pt.
struct HomeAvatar: View {
    let avatar: HomeActivityRow.Avatar
    let pending: Bool
    let failed: Bool
    let isNew: Bool
    var size: CGFloat = 42
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var scale: CGFloat { size / 42 }

    var body: some View {
        ZStack {
            Circle().fill(NoahTokens.Colors.soft)
            switch avatar {
            case .initial(let letter):
                Text(verbatim: letter).noahText(size > 42 ? HomeText.initialLarge : HomeText.initial)
            case .lightning: icon("bolt", 17)
            case .chain: icon("link", 16)
            case .recurring: icon("repeat", 16)
            case .renewal: icon("clock.arrow.circlepath", 16)
            case .incoming: icon("arrow.down", 16)
            case .outgoing: icon("arrow.up", 16)
            case .exit: icon("rectangle.portrait.and.arrow.right", 16)
            }
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .frame(width: size, height: size)
        .overlay {
            if pending {
                // A dashed ring turning once every 8 s; still under Reduce Motion.
                TimelineView(.animation(paused: reduceMotion)) { timeline in
                    Circle()
                        .strokeBorder(NoahTokens.Colors.muted, style: StrokeStyle(lineWidth: size > 42 ? 2 : 1.5, dash: [4, 3]))
                        .rotationEffect(.degrees(reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate
                                .truncatingRemainder(dividingBy: 8) / 8 * 360))
                }
                .padding(size > 42 ? -4 : -3)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if failed {
                let badge: CGFloat = size > 42 ? 22 : 17
                Text(verbatim: "!")
                    .font(.system(size: size > 42 ? 13 : 11, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: badge, height: badge)
                    .background(NoahTokens.Colors.failure, in: Circle())
                    .background(Circle().stroke(NoahTokens.Colors.background, lineWidth: 4))
                    .offset(x: size > 42 ? 2 : 3, y: size > 42 ? 2 : 3)
            }
        }
        .overlay(alignment: .topTrailing) {
            if isNew {
                HomeNewDot().offset(x: 1, y: -1)
                    // It shrinks away once seen (prototype `fadeDot`).
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.01).combined(with: .opacity))
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft), value: isNew)
        .accessibilityHidden(true)
    }

    private func icon(_ name: String, _ points: CGFloat) -> some View {
        Image(systemName: name).accessibilityHidden(true).font(.system(size: points * (size > 42 ? 27.0 / 20 : 1), weight: .medium))
    }
}

/// The small orange dot on an incoming row that arrived since Home was last shown.
private struct HomeNewDot: View {
    @State private var popped = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Circle().fill(NoahTokens.Colors.orange)
            .frame(width: 11, height: 11)
            .background(Circle().stroke(NoahTokens.Colors.background, lineWidth: 4))
            .scaleEffect(popped || reduceMotion ? 1 : 0.01)
            .opacity(popped ? 1 : 0)
            .onAppear {
                withAnimation(reduceMotion ? NoahTokens.Motion.reduceMotionFade : NoahTokens.Motion.bouncy.delay(0.12)) { popped = true }
            }
    }
}

/// Compact Home's latest five, with "See all".
struct HomeRecentList: View {
    let history: HistoryModel
    let ownDomain: String
    let unit: BitcoinAmountUnit
    let hidden: Bool
    let newIDs: Set<String>
    var animateRows = false
    let onSeeAll: () -> Void
    let onRetry: () -> Void
    let onSelect: (HistoryTransaction) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            content
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: history.transactions.prefix(5).map(\.id))
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Recent").noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button("See all", action: onSeeAll)
                .noahText(HomeText.strong)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .frame(minHeight: NoahTokens.Size.hitTarget)
                .accessibilityIdentifier("home-see-all")
        }
        .padding(.bottom, -10)
    }

    @ViewBuilder private var content: some View {
        if history.isLoading {
            ProgressView("Loading activity").frame(maxWidth: .infinity).padding(.vertical, 24)
        } else if let error = history.error {
            VStack(alignment: .leading, spacing: NoahTokens.Space.s) {
                Text(LocalizedStringKey(error)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                Button("Retry", action: onRetry).noahText(HomeText.strong)
            }
            .padding(.vertical, NoahTokens.Space.l)
        } else {
            let rows = Array(history.transactions.prefix(5))
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, transaction in
                Button { onSelect(transaction) } label: {
                    HomeActivityRowView(transaction: transaction, ownDomain: ownDomain, unit: unit, hidden: hidden,
                                        isNew: newIDs.contains(transaction.id))
                }
                .buttonStyle(.plain)
                .overlay(alignment: .bottom) {
                    if index < rows.count - 1 { HomeHairline().padding(.leading, 56) }
                }
                .onboardingAppear(index, base: 0.3, step: 0.04, distance: 10, animated: animateRows)
                .transition(.homeRise(reduceMotion, 10))
            }
        }
    }
}

/// Wide Home's trailing pane: every record, grouped by day, scrolling on its own.
struct HomeActivityList: View {
    let history: HistoryModel
    let ownDomain: String
    let unit: BitcoinAmountUnit
    let hidden: Bool
    let newIDs: Set<String>
    let onRetry: () -> Void
    let onSelect: (HistoryTransaction) -> Void
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            Text("Activity").noahText(HomeText.listTitle).foregroundStyle(NoahTokens.Colors.foreground)
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, NoahTokens.Space.s)
            if history.isLoading {
                ProgressView("Loading activity").frame(maxWidth: .infinity).padding(.vertical, 24)
            } else if let error = history.error {
                Text(LocalizedStringKey(error)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                Button("Retry", action: onRetry).noahText(HomeText.strong).padding(.top, NoahTokens.Space.s)
            } else {
                ForEach(HomeActivityDay.group(history.transactions, now: Date())) { day in
                    Text(verbatim: title(day.title))
                        .noahText(HomeText.section)
                        .foregroundStyle(NoahTokens.Colors.muted)
                        .padding(.top, 14)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(Array(day.transactions.enumerated()), id: \.element.id) { index, transaction in
                        Button { onSelect(transaction) } label: {
                            HomeActivityRowView(transaction: transaction, ownDomain: ownDomain, unit: unit, hidden: hidden,
                                                isNew: newIDs.contains(transaction.id), grouped: true)
                        }
                        .buttonStyle(.plain)
                        .overlay(alignment: .bottom) {
                            if index < day.transactions.count - 1 { HomeHairline().padding(.leading, 56) }
                        }
                        .transition(.homeRise(reduceMotion, 10))
                    }
                }
            }
        }
        // Rows are scroll targets so Home can return to the first visible one after a pose change.
        .scrollTargetLayout()
        .animation(NoahTokens.Motion.travel(reduceMotion), value: history.transactions.map(\.id))
    }

    private func title(_ title: HomeActivityDay.Title) -> String {
        switch title {
        case .today: L10n.text("Today", locale: locale.identifier)
        case .yesterday: L10n.text("Yesterday", locale: locale.identifier)
        case .date(let date):
            if Calendar.current.isDate(date, equalTo: Date(), toGranularity: .year) {
                date.formatted(.dateTime.month(.abbreviated).day().locale(locale))
            } else {
                date.formatted(.dateTime.year().month(.abbreviated).day().locale(locale))
            }
        }
    }
}

struct HomeHairline: View {
    var body: some View {
        Rectangle().fill(NoahTokens.Colors.hairline).frame(height: 1).accessibilityHidden(true)
    }
}

/// "Balance", the total, then today's balance detail rows with their existing labels.
struct HomeBalanceSheet: View {
    let balance: WalletBalance
    let unit: BitcoinAmountUnit
    let hidden: Bool
    @Environment(\.locale) private var locale

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Balance").noahText(NoahTokens.Typography.sheetTitle)
                    .accessibilityAddTraits(.isHeader)
                Text(verbatim: hidden ? HomeBalanceView.hiddenText(unit: unit)
                    : BitcoinAmount.format(sats: Int64(clamping: balance.totalSats), unit: unit, locale: locale))
                    .noahText(NoahTokens.Typography.amount)
                    .lineLimit(1).minimumScaleFactor(0.6)
                    .padding(.top, 6).padding(.bottom, 16)
                ForEach(Array(balance.details.enumerated()), id: \.offset) { index, row in
                    HStack {
                        Text(LocalizedStringKey(row.label)).noahText(NoahTokens.Typography.body)
                            .foregroundStyle(NoahTokens.Colors.muted)
                        Spacer(minLength: NoahTokens.Space.m)
                        Text(verbatim: WalletBalance.display(row.sats, hidden: hidden, unit: unit, locale: locale))
                            .noahText(HomeText.amount).monospacedDigit()
                    }
                    .frame(minHeight: 46)
                    .overlay(alignment: .bottom) { if index < balance.details.count - 1 { HomeHairline() } }
                    .accessibilityElement(children: .combine)
                }
            }
            .foregroundStyle(NoahTokens.Colors.foreground)
            .padding(.horizontal, NoahTokens.Space.screen)
            .padding(.top, 8).padding(.bottom, 28)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}
