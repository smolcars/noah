import SwiftUI
import WidgetKit

struct BalanceEntry: TimelineEntry {
    let date: Date
    let totalBalance: Double
    let onchainBalance: Double
    let offchainBalance: Double
    let pendingBalance: Double
    let closestExpiryBlocks: Double
    let expiryThreshold: Double
}

class BalanceProvider: TimelineProvider {
    let appGroup: String

    init(appGroup: String) {
        self.appGroup = appGroup
    }

    func placeholder(in context: Context) -> BalanceEntry {
        BalanceEntry(
            date: Date(),
            totalBalance: 0,
            onchainBalance: 0,
            offchainBalance: 0,
            pendingBalance: 0,
            closestExpiryBlocks: -999,
            expiryThreshold: 288
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (BalanceEntry) -> Void) {
        let entry = loadBalanceEntry()
        completion(entry)
    }

    func getTimeline(
        in context: Context, completion: @escaping (Timeline<BalanceEntry>) -> Void
    ) {
        let entry = loadBalanceEntry()

        // Refresh every 15 minutes
        let nextUpdate =
            Calendar.current.date(byAdding: .minute, value: 15, to: Date())
            ?? Date().addingTimeInterval(15 * 60)
        let timeline = Timeline(entries: [entry], policy: .after(nextUpdate))

        completion(timeline)
    }

    private func loadBalanceEntry() -> BalanceEntry {
        guard let userDefaults = UserDefaults(suiteName: appGroup),
            let balanceData = userDefaults.dictionary(forKey: "widgetBalance")
        else {
            return BalanceEntry(
                date: Date(),
                totalBalance: 0,
                onchainBalance: 0,
                offchainBalance: 0,
                pendingBalance: 0,
                closestExpiryBlocks: -999,
                expiryThreshold: 288
            )
        }

        return BalanceEntry(
            date: Date(),
            totalBalance: balanceData["totalBalance"] as? Double ?? 0,
            onchainBalance: balanceData["onchainBalance"] as? Double ?? 0,
            offchainBalance: balanceData["offchainBalance"] as? Double ?? 0,
            pendingBalance: balanceData["pendingBalance"] as? Double ?? 0,
            closestExpiryBlocks: balanceData["closestExpiryBlocks"] as? Double ?? -999,
            expiryThreshold: balanceData["expiryThreshold"] as? Double ?? 288
        )
    }
}

struct NoahBalanceWidgetView: View {
    var entry: BalanceEntry
    var variantName: String?
    var variantColor: Color?
    @Environment(\.widgetFamily) var family
    @Environment(\.widgetRenderingMode) var renderingMode

    private static let numberFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        return formatter
    }()

    var body: some View {
        if family == .systemSmall {
            smallWidgetLayout
        } else {
            mediumWidgetLayout
        }
    }

    private func getExpiryStatus() -> (icon: String, color: Color) {
        let blocks = entry.closestExpiryBlocks
        let threshold = entry.expiryThreshold

        // Critical: within 20% of threshold (e.g., < 58 blocks if threshold is 288)
        // Also includes expired VTXOs (negative blocks).
        let criticalThreshold = threshold * 0.2

        if blocks <= criticalThreshold {
            return ("exclamationmark.triangle.fill", .red)
        } else if blocks <= threshold {
            return ("bell.fill", .orange)
        } else {
            return ("checkmark.circle.fill", .green)
        }
    }

    private var shouldShowExpiry: Bool {
        // Hide expiry section if no VTXOs (-999 sentinel value)
        return entry.closestExpiryBlocks != -999
    }

    private var shouldUseCompactSmallLayout: Bool {
        // When pending and/or expiry rows are visible we tighten spacing
        // so content stays comfortably within the small widget bounds.
        return entry.pendingBalance > 0 || shouldShowExpiry
    }

    private var smallWidgetLayout: some View {
        VStack(alignment: .leading, spacing: shouldUseCompactSmallLayout ? 4 : 6) {
            HStack(alignment: .top) {
                Text("Noah Wallet")
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)
                Spacer(minLength: 4)
                if let variantName = variantName, let variantColor = variantColor {
                    Text(variantName)
                        .font(.caption2)
                        .fontWeight(.bold)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(badgeBackground(color: variantColor))
                        .foregroundColor(badgeForeground(color: variantColor))
                        .cornerRadius(3)
                }
            }

            Spacer(minLength: shouldUseCompactSmallLayout ? 2 : 6)

            VStack(alignment: .leading, spacing: shouldUseCompactSmallLayout ? 2 : 3) {
                Text("₿\u{00A0}\(formatSatsCompact(entry.totalBalance))")
                    .font(.title3)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)

                if entry.pendingBalance > 0 {
                    Text("Pending: ₿\u{00A0}\(formatSatsCompact(entry.pendingBalance))")
                        .font(.caption2)
                        .foregroundColor(.yellow)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }

                // Expiry status - only show if VTXOs exist
                if shouldShowExpiry {
                    HStack(spacing: 3) {
                        let expiryStatus = getExpiryStatus()
                        Image(systemName: expiryStatus.icon)
                            .font(.system(size: 10))
                            .foregroundColor(expiryStatus.color)
                        Text("Expires: \(Int(entry.closestExpiryBlocks))b")
                            .font(.caption2)
                            .foregroundColor(expiryStatus.color)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                }
            }

            Spacer(minLength: shouldUseCompactSmallLayout ? 2 : 6)

            HStack(spacing: shouldUseCompactSmallLayout ? 6 : 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("On")
                        .font(.caption2)
                        .foregroundColor(.gray)
                    Text("\(formatSatsCompact(entry.onchainBalance))")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                        .minimumScaleFactor(0.8)
                        .lineLimit(1)
                }

                Spacer(minLength: 4)

                VStack(alignment: .trailing, spacing: 1) {
                    Text("Off")
                        .font(.caption2)
                        .foregroundColor(.gray)
                    Text("\(formatSatsCompact(entry.offchainBalance))")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                        .minimumScaleFactor(0.8)
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, shouldUseCompactSmallLayout ? 10 : 12)
        .padding(.vertical, shouldUseCompactSmallLayout ? 10 : 12)
    }

    private var mediumWidgetLayout: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Noah Wallet")
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundColor(.white)
                Spacer()
                if let variantName = variantName, let variantColor = variantColor {
                    Text(variantName)
                        .font(.caption2)
                        .fontWeight(.bold)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(badgeBackground(color: variantColor))
                        .foregroundColor(badgeForeground(color: variantColor))
                        .cornerRadius(4)
                }
            }

            Spacer()

            VStack(alignment: .leading, spacing: 4) {
                Text("₿\u{00A0}\(formatSats(entry.totalBalance))")
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(.white)

                if entry.pendingBalance > 0 {
                    Text("Pending: ₿\u{00A0}\(formatSats(entry.pendingBalance))")
                        .font(.caption2)
                        .foregroundColor(.yellow)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }

                // Expiry status - only show if VTXOs exist
                if shouldShowExpiry {
                    HStack(spacing: 4) {
                        let expiryStatus = getExpiryStatus()
                        Image(systemName: expiryStatus.icon)
                            .font(.system(size: 12))
                            .foregroundColor(expiryStatus.color)
                        Text("VTXO expires: \(Int(entry.closestExpiryBlocks)) blocks")
                            .font(.caption)
                            .foregroundColor(expiryStatus.color)
                    }
                }
            }

            Spacer()

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Onchain")
                        .font(.caption2)
                        .foregroundColor(.gray)
                    Text("\(formatSats(entry.onchainBalance))")
                        .font(.caption)
                        .foregroundColor(.white)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text("Offchain")
                        .font(.caption2)
                        .foregroundColor(.gray)
                    Text("\(formatSats(entry.offchainBalance))")
                        .font(.caption)
                        .foregroundColor(.white)
                }
            }
        }
        .padding(14)
    }

    private func formatSats(_ value: Double) -> String {
        return Self.numberFormatter.string(from: NSNumber(value: value)) ?? "0"
    }

    private func formatSatsCompact(_ value: Double) -> String {
        if value >= 1_000_000 {
            // 1M+ sats - show as "1.5M" format
            let millions = value / 1_000_000
            return String(format: "%.1fM", millions)
        } else if value >= 100_000 {
            // 100K+ sats - show as "123K" format
            let thousands = value / 1_000
            return String(format: "%.0fK", thousands)
        } else if value >= 10_000 {
            // 10K+ sats - show as "12.3K" format
            let thousands = value / 1_000
            return String(format: "%.1fK", thousands)
        } else {
            // Less than 10K - show full number
            return formatSats(value)
        }
    }

    private func badgeBackground(color: Color) -> Color {
        if #available(iOS 26.0, *) {
            switch renderingMode {
            case .fullColor:
                return color
            case .accented, .vibrant:
                return color.opacity(0.3)
            default:
                return Color.clear
            }
        } else {
            return color
        }
    }

    private func badgeForeground(color: Color) -> Color {
        if #available(iOS 26.0, *) {
            switch renderingMode {
            case .fullColor:
                return .black
            case .accented, .vibrant:
                return .primary
            default:
                return .primary
            }
        } else {
            return .black
        }
    }
}
