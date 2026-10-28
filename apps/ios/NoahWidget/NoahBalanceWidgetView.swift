import SwiftUI
import WidgetKit

struct BalanceEntry: TimelineEntry {
    let date: Date
    /// Nil: no wallet (never set up, or deleted).
    let snapshot: WidgetSnapshot?
}

/// Reads the app's snapshot; the widget never opens the wallet.
struct BalanceProvider: TimelineProvider {
    let appGroup: String
    let variant: String

    func placeholder(in _: Context) -> BalanceEntry {
        BalanceEntry(date: .now, snapshot: WidgetSnapshot(writtenAt: .now, balance: 252_742, arriving: 0, dailyBalances: nil,
                                                          amountUnit: .bip177, hidden: false, fiat: nil, renewBy: nil, variant: variant))
    }

    // swiftlint:disable:next unneeded_escaping - TimelineProvider's requirement
    func getSnapshot(in _: Context, completion: @escaping (BalanceEntry) -> Void) {
        let now = Date()
        completion(BalanceEntry(date: now, snapshot: read(now)))
    }

    // swiftlint:disable:next unneeded_escaping - TimelineProvider's requirement
    func getTimeline(in _: Context, completion: @escaping (Timeline<BalanceEntry>) -> Void) {
        let now = Date()
        let snapshot = read(now)
        let entries = (snapshot?.timelineDates(now: now, calendar: .current) ?? [now]).map { BalanceEntry(date: $0, snapshot: snapshot) }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(15 * 60))))
    }

    private func read(_ now: Date) -> WidgetSnapshot? {
        UserDefaults(suiteName: appGroup).flatMap { WidgetSnapshot.read(from: $0, variant: variant, now: now) }
    }
}

/// The in-app routes the widgets open (`com.noahwallet.<variant>://widget/…`).
struct WidgetLinks {
    let variant: String

    func url(_ path: String) -> URL {
        guard let url = URL(string: "com.noahwallet.\(variant)://widget/\(path)") else { preconditionFailure("a variant and path make a URL") }
        return url
    }
    var home: URL { url("home") }
    var receive: URL { url("receive") }
    var scan: URL { url("scan") }
    var send: URL { url("send") }
}

struct NoahBalanceWidgetView: View {
    let entry: BalanceEntry
    let language: String
    let links: WidgetLinks
    @Environment(\.widgetFamily) private var family
    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        let look = WidgetLook(mode)
        Group {
            if family == .accessoryCircular {
                CircularView(look: look, label: L10n.text("Scan", locale: language))
            } else if let snapshot = entry.snapshot {
                let presentation = WidgetPresentation(snapshot: snapshot, now: entry.date, language: language)
                switch family {
                case .systemMedium: MediumView(presentation: presentation, look: look, links: links, language: language)
                case .accessoryRectangular: RectangularView(presentation: presentation, look: look)
                case .accessoryInline: InlineView(presentation: presentation)
                default: SmallView(presentation: presentation, look: look)
                }
            } else {
                switch family {
                case .accessoryInline: Image(.longshipGlyph).renderingMode(.template).accessibilityHidden(true)
                case .accessoryRectangular: Longship(look: look).frame(maxHeight: 28)
                default: NoWalletView(look: look)
                }
            }
        }
        .widgetURL(family == .accessoryCircular ? links.scan : links.home)
        .containerBackground(for: .widget) {
            switch family {
            case .systemSmall, .systemMedium: NoahTokens.Colors.background
            case .accessoryRectangular: AccessoryWidgetBackground()
            default: Color.clear
            }
        }
    }
}

// MARK: Families

/// Label row, wave, balance, fiat, status.
private struct SmallView: View {
    let presentation: WidgetPresentation
    let look: WidgetLook

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                ChangeLabel(change: presentation.change, look: look)
                Spacer(minLength: 0)
                if let chip = presentation.chip { NetworkChip(text: chip, look: look) }
            }
            .frame(height: 16)
            .padding(.bottom, 3)
            Spacer(minLength: 0)
            BalanceText(presentation: presentation, size: 30, look: look).waveFloor()
            FiatLine(presentation: presentation, look: look).padding(.top, 2)
            if let status = presentation.status {
                StatusLine(status: status, renewTitle: presentation.renewTitle, layout: .twoLines, look: look).padding(.top, 5)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(presentation.accessibilityLabel)
        .padding(EdgeInsets(top: 12, leading: 16, bottom: 13, trailing: 16))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .backgroundPreferenceValue(WaveFloor.self) { floor in
            GeometryReader { proxy in
                // Full-bleed, from under the label row down to 4 pt above the balance.
                let bottom = floor.map { proxy[$0].y - 4 } ?? proxy.size.height
                WaveLayer(values: presentation.wave, band: CGRect(x: 0, y: 28, width: proxy.size.width, height: max(26, bottom - 28)),
                          size: proxy.size, boat: 24, inset: 14, look: look)
            }
        }
    }
}

/// The balance column leading, the label top trailing, the wave in the trailing half, Actions along the bottom.
private struct MediumView: View {
    let presentation: WidgetPresentation
    let look: WidgetLook
    let links: WidgetLinks
    let language: String

    var body: some View {
        GeometryReader { proxy in
            VStack(alignment: .leading, spacing: 0) {
                summary(width: proxy.size.width)
                status(width: proxy.size.width)
                Spacer(minLength: 0)
                actions.waveFloor()
            }
            .padding(EdgeInsets(top: 15, leading: 16, bottom: 14, trailing: 16))
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
            .backgroundPreferenceValue(WaveFloor.self) { floor in
                // The trailing ~52%, from under the label to 8 pt above the Actions, fading in from the left.
                let start = proxy.size.width * 0.48
                let bottom = floor.map { proxy[$0].y - 8 } ?? proxy.size.height
                WaveLayer(values: presentation.wave,
                          band: CGRect(x: start, y: 32, width: proxy.size.width - start, height: max(26, bottom - 32)),
                          size: proxy.size, boat: 26, inset: 16, look: look)
                    .mask(LinearGradient(stops: [.init(color: .clear, location: 0.48), .init(color: .black, location: 0.48 + 0.52 * 0.34)],
                                         startPoint: .leading, endPoint: .trailing))
            }
        }
    }

    private func summary(width: CGFloat) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 0) {
                BalanceText(presentation: presentation, size: 34, look: look)
                FiatLine(presentation: presentation, look: look).padding(.top, 2)
            }
            .frame(width: max(0, width * 0.57 - 16), alignment: .leading)
            HStack(spacing: 6) {
                if let chip = presentation.chip { NetworkChip(text: chip, look: look) }
                ChangeLabel(change: presentation.change, look: look)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            .frame(height: 16)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(presentation.accessibilityLabel)
    }

    @ViewBuilder private func status(width: CGFloat) -> some View {
        if let status = presentation.status {
            // One line ("Renew · Expires in 18 hours") over the faded sea, clear of the boat;
            // two only when a language doesn't fit.
            ViewThatFits(in: .horizontal) {
                StatusLine(status: status, renewTitle: presentation.renewTitle, layout: .oneLine, look: look)
                StatusLine(status: status, renewTitle: presentation.renewTitle, layout: .twoLines, look: look)
            }
            .frame(width: max(0, width - 32 - 48), alignment: .leading)
            .padding(.top, 5)
            .accessibilityHidden(true)
        }
    }

    private var actions: some View {
        HStack(spacing: 8) {
            ActionCapsule(title: L10n.text("Receive", locale: language), glyph: "arrow.down.left", url: links.receive, look: look)
            ActionCapsule(title: L10n.text("Scan", locale: language), glyph: "qrcode.viewfinder", url: links.scan, look: look)
            ActionCapsule(title: L10n.text("Send", locale: language), glyph: "arrow.up.right", url: links.send, look: look)
        }
    }
}

/// Lock Screen: the balance, then the status line, or the fiat when there's none.
private struct RectangularView: View {
    let presentation: WidgetPresentation
    let look: WidgetLook

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            BalanceText(presentation: presentation, size: 22, look: look)
            if let status = presentation.status {
                StatusLine(status: status, renewTitle: presentation.renewTitle, layout: .short, look: look, size: 12)
            } else if let fiat = presentation.fiat {
                Text(verbatim: fiat).font(.system(size: 12, weight: .medium)).foregroundStyle(look.muted).lineLimit(1).privacySensitive()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(presentation.accessibilityLabel)
    }
}

/// Lock Screen: the scan glyph; opens Scan.
private struct CircularView: View {
    let look: WidgetLook
    let label: String

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            Image(systemName: "qrcode.viewfinder").font(.system(size: 26, weight: .medium)).foregroundStyle(look.foreground)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}

/// Lock Screen: the longship glyph and the balance.
private struct InlineView: View {
    let presentation: WidgetPresentation

    var body: some View {
        Label {
            Text(verbatim: presentation.balance).privacySensitive()
        } icon: {
            Image(.longshipGlyph).renderingMode(.template)
        }
        .accessibilityLabel(presentation.accessibilityLabel)
    }
}

// MARK: Pieces

/// Space Grotesk 600, tabular digits, shrinking to fit down to half size. In sats, "sats" at about half size.
private struct BalanceText: View {
    let presentation: WidgetPresentation
    let size: CGFloat
    let look: WidgetLook

    var body: some View {
        Text(text)
            .font(.custom("SpaceGrotesk-SemiBold", fixedSize: size))
            .monospacedDigit()
            .tracking(-0.03 * size)
            .foregroundStyle(look.foreground)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .widgetAccentable()
            .privacySensitive()
    }

    private var text: AttributedString {
        guard presentation.unit == .sats else { return AttributedString(presentation.balance) }
        var unit = AttributedString(" sats")
        unit.font = .custom("SpaceGrotesk-Medium", fixedSize: size * 0.52)
        return AttributedString(presentation.number) + unit
    }
}

private struct FiatLine: View {
    let presentation: WidgetPresentation
    let look: WidgetLook

    var body: some View {
        if let fiat = presentation.fiat {
            Text(verbatim: fiat)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(look.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .privacySensitive()
        }
    }
}

/// "+₿ 30,142 · 30 days": the amount in the foreground, the rest muted. Short of room it drops " · 30 days", then
/// shrinks a little, then stays out: the amount is never cut.
private struct ChangeLabel: View {
    let change: WidgetPresentation.Change?
    let look: WidgetLook

    var body: some View {
        if let change {
            ViewThatFits(in: .horizontal) {
                Text(text(change, period: true, scale: 1))
                Text(text(change, period: false, scale: 1))
                Text(text(change, period: false, scale: 0.8))
                Color.clear.frame(width: 0, height: 0)
            }
            .lineLimit(1)
            .privacySensitive()
        }
    }

    private func text(_ change: WidgetPresentation.Change, period showsPeriod: Bool, scale: CGFloat) -> AttributedString {
        var amount = AttributedString(change.amount)
        amount.font = .custom("SpaceGrotesk-SemiBold", fixedSize: 12 * scale)
        amount.foregroundColor = look.foreground
        guard showsPeriod else { return amount }
        var period = AttributedString(" · " + change.period)
        period.font = .system(size: 11.5)
        period.foregroundColor = look.muted
        return amount + period
    }
}

/// Home's network chip.
private struct NetworkChip: View {
    let text: String
    let look: WidgetLook

    var body: some View {
        Text(verbatim: text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(look.chipText)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(look.chipFill, in: Capsule())
            .layoutPriority(1)
    }
}

/// At most one line: Renew (wins) or arriving.
private struct StatusLine: View {
    enum Layout {
        /// Small: Renew, then the expiry under it; arriving wraps to two lines.
        case twoLines
        /// Medium: "Renew · Expires in 18 hours".
        case oneLine
        /// Lock Screen: the glyph and the expiry.
        case short
    }

    let status: WidgetPresentation.Status
    let renewTitle: String
    let layout: Layout
    let look: WidgetLook
    var size: CGFloat = 13

    var body: some View {
        Group {
            switch status {
            case .renew(let expiry):
                switch layout {
                case .twoLines:
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 5) { glyph; Text(verbatim: renewTitle).fontWeight(.semibold).foregroundStyle(look.foreground) }
                        Text(verbatim: expiry).foregroundStyle(look.muted).lineLimit(1).minimumScaleFactor(0.75)
                    }
                case .oneLine:
                    HStack(spacing: 5) {
                        glyph
                        Text(oneLine(expiry)).lineLimit(1)
                    }
                case .short:
                    HStack(spacing: 4) { glyph; Text(verbatim: expiry).foregroundStyle(look.muted).lineLimit(1).minimumScaleFactor(0.8) }
                }
            case .arriving(let text):
                HStack(alignment: .firstTextBaseline, spacing: 5) {
                    Circle().fill(look.muted).frame(width: 7, height: 7)
                    Text(verbatim: text).foregroundStyle(look.foreground).lineLimit(layout == .twoLines ? 2 : 1)
                }
            }
        }
        .font(.system(size: size, weight: .medium))
        .fixedSize(horizontal: layout == .oneLine, vertical: true)
        .privacySensitive()
    }

    private var glyph: some View {
        Image(systemName: "clock.arrow.circlepath").accessibilityHidden(true).font(.system(size: size, weight: .semibold))
            .foregroundStyle(look.foreground)
    }

    private func oneLine(_ expiry: String) -> AttributedString {
        var title = AttributedString(renewTitle)
        title.font = .system(size: size, weight: .semibold)
        title.foregroundColor = look.foreground
        var rest = AttributedString(" · " + expiry)
        rest.foregroundColor = look.muted
        return title + rest
    }
}

/// Receive, Scan or Send: a soft capsule that opens that screen.
private struct ActionCapsule: View {
    let title: String
    let glyph: String
    let url: URL
    let look: WidgetLook

    var body: some View {
        Link(destination: url) {
            HStack(spacing: 6) {
                Image(systemName: glyph).accessibilityHidden(true).font(.system(size: 15, weight: .semibold))
                Text(verbatim: title).font(.system(size: 14, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.75)
            }
            .foregroundStyle(look.foreground)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, minHeight: 38, maxHeight: 38)
            .background(look.soft, in: Capsule())
        }
    }
}

/// The top of what sits under the wave: the band ends just above it.
private struct WaveFloor: PreferenceKey {
    static var defaultValue: Anchor<CGPoint>? { nil }
    static func reduce(value: inout Anchor<CGPoint>?, nextValue: () -> Anchor<CGPoint>?) { value = value ?? nextValue() }
}

private extension View {
    func waveFloor() -> some View { anchorPreference(key: WaveFloor.self, value: .top) { $0 } }
}
