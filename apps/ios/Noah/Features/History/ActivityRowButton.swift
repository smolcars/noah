import SwiftUI

/// Home's row, with the wide pane's soft selection and the zoom's source rectangle.
struct ActivityRowButton: View {
    let transaction: HistoryTransaction
    let ownDomain: String
    let unit: BitcoinAmountUnit
    let hidden: Bool
    let isNew: Bool
    let selected: Bool
    let hairline: Bool
    let zoom: Namespace.ID?
    let onSeen: () -> Void
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HomeActivityRowView(transaction: transaction, ownDomain: ownDomain, unit: unit, hidden: hidden,
                                isNew: isNew, grouped: true)
                .background {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(NoahTokens.Colors.soft)
                        .padding(.vertical, 3).padding(.horizontal, -12)
                        .opacity(selected ? 1 : 0)
                }
        }
        .buttonStyle(ActivityRowPressStyle())
        .overlay(alignment: .bottom) {
            if hairline { HomeHairline().padding(.leading, 56) }
        }
        .modifier(ZoomSource(id: transaction.id, namespace: zoom))
        // The dot shrinks away once the row has been on screen for a moment (cancelled if it
        // scrolls away first). Not `onScrollVisibilityChange`: in the lazy list it left rows
        // showing stale records.
        .task(id: isNew) {
            guard isNew else { return }
            do { try await Task.sleep(for: .seconds(1.4)) } catch { return }
            onSeen()
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("activity-row-\(transaction.id)")
    }
}

struct ZoomSource: ViewModifier {
    let id: String
    let namespace: Namespace.ID?

    func body(content: Content) -> some View {
        if let namespace {
            // The source clips even at rest: outset it so the pending ring and the pressed highlight (12 pt past the row) stay whole.
            content.padding(.horizontal, 12).matchedTransitionSource(id: id, in: namespace) {
                $0.background(NoahTokens.Colors.background).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(.horizontal, -12)
        } else {
            content
        }
    }
}

/// The prototype's `.rw:active`: the soft rounded surface while pressed.
struct ActivityRowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(NoahTokens.Colors.soft)
                    .padding(.vertical, 3).padding(.horizontal, -12)
                    .opacity(configuration.isPressed ? 0.7 : 0)
            }
            .contentShape(Rectangle())
    }
}

struct ActivityDayHeader: View {
    let title: String
    let first: Bool
    let pad: CGFloat

    var body: some View {
        Text(verbatim: title)
            .noahText(HomeText.section)
            .foregroundStyle(NoahTokens.Colors.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, first ? 8 : 14).padding(.bottom, 4)
            .padding(.horizontal, pad)
            .background(NoahTokens.Colors.background)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Nine pulsing skeleton rows; still under Reduce Motion.
struct ActivitySkeleton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private static let widths: [(CGFloat, CGFloat)] = [(0.58, 0.30), (0.44, 0.36), (0.66, 0.26), (0.50, 0.40), (0.38, 0.32),
                                                       (0.62, 0.28), (0.47, 0.34), (0.55, 0.24), (0.41, 0.38)]

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { timeline in
            VStack(spacing: 0) {
                ForEach(Self.widths.indices, id: \.self) { index in
                    row(Self.widths[index])
                        .opacity(reduceMotion ? 1 : pulse(timeline.date, delay: Double(index) * 0.08))
                }
            }
        }
        .padding(.top, 8)
        .accessibilityElement()
        .accessibilityLabel(Text("Loading activity"))
    }

    /// 1.2 s each way between full and 45%, eased (prototype `skp`).
    private func pulse(_ date: Date, delay: Double) -> Double {
        let phase = (date.timeIntervalSinceReferenceDate - delay).truncatingRemainder(dividingBy: 2.4) / 1.2
        let t = phase < 1 ? phase : 2 - phase
        return 1 - 0.55 * (0.5 - 0.5 * cos(.pi * t))
    }

    private func row(_ widths: (CGFloat, CGFloat)) -> some View {
        HStack(spacing: 14) {
            Circle().fill(NoahTokens.Colors.soft).frame(width: 42, height: 42)
            GeometryReader { proxy in
                VStack(alignment: .leading, spacing: 8) {
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.0, height: 12)
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.1, height: 12)
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            Capsule().fill(NoahTokens.Colors.soft).frame(width: 64, height: 14)
        }
        .frame(height: 66)
    }
}

/// Ghost rows showing the three avatar kinds, "No activity yet" and Receive.
struct ActivityEmptyView: View {
    let onReceive: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                ghost(avatar: AnyView(Text(verbatim: "a").noahText(HomeText.initial)), widths: (0.62, 0.34)).opacity(1)
                ghost(avatar: AnyView(Image(systemName: "bolt").font(.system(size: 17, weight: .medium))), widths: (0.48, 0.40)).opacity(0.72)
                ghost(avatar: AnyView(Image(systemName: "link").font(.system(size: 16, weight: .medium))), widths: (0.56, 0.28)).opacity(0.44)
            }
            .frame(maxWidth: 300)
            .accessibilityHidden(true)
            Text("No activity yet").noahText(ActivityText.emptyTitle)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .padding(.top, 30).padding(.bottom, 18)
            Button("Receive", action: onReceive)
                .buttonStyle(HomePillStyle(height: 40))
                .accessibilityIdentifier("activity-empty-receive")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 44)
        .padding(.horizontal, NoahTokens.Space.screen)
    }

    private func ghost(avatar: AnyView, widths: (CGFloat, CGFloat)) -> some View {
        HStack(spacing: 14) {
            avatar
                .foregroundStyle(NoahTokens.Colors.muted)
                .frame(width: 42, height: 42)
                .overlay(Circle().strokeBorder(NoahTokens.Colors.hairline, lineWidth: 1.5))
            GeometryReader { proxy in
                VStack(alignment: .leading, spacing: 7) {
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.0, height: 10)
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.1, height: 10)
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            Capsule().fill(NoahTokens.Colors.soft).frame(width: 54, height: 12)
        }
        .frame(height: 60)
    }
}

/// "Couldn't load activity", today's message and "Try again".
struct ActivityErrorView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            Text("Couldn't load activity").noahText(ActivityText.stateTitle)
            Text(LocalizedStringKey(message)).noahText(NoahTokens.Typography.sub)
                .foregroundStyle(NoahTokens.Colors.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 270)
                .padding(.bottom, 16)
            Button("Try again", action: retry)
                .buttonStyle(HomePillStyle(prominent: false, height: 40))
                .accessibilityIdentifier("activity-retry")
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .frame(maxWidth: .infinity)
        .padding(.top, 120)
        .padding(.horizontal, NoahTokens.Space.screen)
    }
}

/// "No matches", or a filter's empty state with "Show all".
struct ActivityNoneView: View {
    let text: String
    let action: (() -> Void)?

    var body: some View {
        VStack(spacing: 10) {
            Text(LocalizedStringKey(text)).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
            if let action {
                Button("Show all", action: action)
                    .noahText(HomeText.strong)
                    .foregroundStyle(NoahTokens.Colors.foreground)
                    .frame(minHeight: NoahTokens.Size.hitTarget)
                    .accessibilityIdentifier("activity-show-all")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 56)
    }
}

/// "Activity" and the filter chip side by side, or the chip on its own line when large text can't fit both:
/// the chip never shrinks to its ×.
struct ActivityTitleRow<Chip: View>: View {
    let style: NoahTokens.TextStyle
    @ViewBuilder let chip: Chip

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { title; chip }
            VStack(alignment: .leading, spacing: 6) { title; chip }
        }
    }

    private var title: some View {
        Text("Activity").noahText(style).accessibilityAddTraits(.isHeader).fixedSize()
    }
}

/// The active filter's name with ×; tapping it clears the filter.
struct ActivityFilterChip: View {
    let filter: HistoryFilter
    let onClear: () -> Void

    var body: some View {
        Button(action: onClear) {
            HStack(spacing: 4) {
                Text(LocalizedStringKey(filter.title)).noahText(ActivityText.chip)
                Image(systemName: "xmark").accessibilityHidden(true).font(.system(size: 11, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
            }
            .foregroundStyle(NoahTokens.Colors.foreground)
            .padding(.leading, 12).padding(.trailing, 9)
            .frame(minHeight: 30)
            .fixedSize()
            .glassCompat(in: Capsule(), interactive: true)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(filter.title)))
        .accessibilityHint(Text("Clear filter"))
        .accessibilityIdentifier("activity-filter-chip")
    }
}

/// A 40-pt glass circle for the wide panes' own bars (Home's top-bar buttons).
struct ActivityCircleIcon: View {
    let systemName: String

    var body: some View {
        // swiftlint:disable:next accessibility_label_for_image - the button it labels names it; otherwise the symbol's own label reads
        Image(systemName: systemName)
            .font(.system(size: 19, weight: .medium))
            .foregroundStyle(NoahTokens.Colors.foreground)
            .frame(width: 40, height: 40)
            .contentShape(Circle())
            .glassCompat(in: Circle(), interactive: true)
            .frame(width: NoahTokens.Size.hitTarget, height: NoahTokens.Size.hitTarget)
    }
}

/// The wide pane's search field (prototype `.srch`), opened over its bar with the keyboard.
struct ActivitySearchField: View {
    @Binding var query: String
    let onCancel: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").accessibilityHidden(true).font(.system(size: 16, weight: .medium)).foregroundStyle(NoahTokens.Colors.muted)
                TextField("Search", text: $query)
                    .noahText(NoahTokens.Typography.body)
                    .focused($focused)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .accessibilityIdentifier("activity-search-field")
                if !query.isEmpty {
                    Button { query = ""; focused = true } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 16)).foregroundStyle(NoahTokens.Colors.muted)
                            .frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Clear search"))
                }
            }
            .padding(.leading, 14).padding(.trailing, 6)
            .frame(height: NoahTokens.Size.hitTarget)
            .glassCompat(in: Capsule())
            Button("Cancel", action: onCancel)
                .noahText(NoahTokens.Typography.body)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .padding(.leading, 6)
        }
        // Opening search brings the keyboard; coming back from a result keeps it down over the results.
        .onAppear { if query.isEmpty { focused = true } }
    }
}

/// The wide trailing pane before a payment is chosen.
struct ActivitySelectPrompt: View {
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "list.bullet").accessibilityHidden(true).font(.system(size: 22, weight: .medium))
                .frame(width: 56, height: 56)
                .background(NoahTokens.Colors.soft, in: Circle())
            Text("Select a payment").noahText(NoahTokens.Typography.control)
        }
        .foregroundStyle(NoahTokens.Colors.muted)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NoahTokens.Colors.background.ignoresSafeArea())
    }
}

extension ToolbarContent {
    @ToolbarContentBuilder
    func sharedBackgroundVisibilityIfAvailable(_ visibility: Visibility) -> some ToolbarContent {
        if #available(iOS 26, *) {
            sharedBackgroundVisibility(visibility)
        } else {
            self
        }
    }
}
