import Foundation

/// When the wave's on-chain transactions confirmed, per txid (bark-bolt-ffi gives a height, not a time): the block's
/// time once History has read it from Esplora, else an estimate made once, the first time a writer sees the
/// transaction confirmed (tip − height × 10 min), so a date never drifts. App-private, cleared with the wallet,
/// never logged.
final class WidgetChainDates: Sendable {
    struct Entry: Codable, Equatable, Sendable {
        var at: Date
        /// The block's own time, which an estimate never replaces.
        var exact: Bool
    }

    static let shared = WidgetChainDates(url: .applicationSupportDirectory.appending(path: "widget-chain-dates.json"))

    private let url: URL
    private let lock = NSLock()

    init(url: URL) { self.url = url }

    /// The date of each confirmed transaction in `estimates`, its first estimate kept from then on. Entries for
    /// transactions the wallet no longer lists (`keeping`) are dropped.
    func dates(_ estimates: [String: Date], keeping: Set<String>) -> [String: Date] {
        lock.withLock {
            let stored = load()
            var entries = stored.filter { keeping.contains($0.key) }
            for (txid, estimate) in estimates where entries[txid] == nil { entries[txid] = Entry(at: estimate, exact: false) }
            if entries != stored { save(entries) }
            return entries.filter { estimates[$0.key] != nil }.mapValues(\.at)
        }
    }

    /// History read the block's time (Esplora `block_time`).
    func record(txid: String, at date: Date) {
        lock.withLock {
            var entries = load()
            let entry = Entry(at: date, exact: true)
            guard entries[txid] != entry else { return }
            entries[txid] = entry
            save(entries)
        }
    }

    func clear() {
        lock.withLock { try? FileManager.default.removeItem(at: url) }
    }

    private func load() -> [String: Entry] {
        (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode([String: Entry].self, from: $0) } ?? [:]
    }

    private func save(_ entries: [String: Entry]) {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
