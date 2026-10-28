import Foundation

/// When the wave's on-chain transactions happened, per txid (bark-bolt-ffi gives a height, not a time): when a writer
/// first saw the transaction unconfirmed, else the block's time once History has read it from Esplora, else an
/// estimate made once, the first time a writer sees the transaction confirmed (tip − height × 10 min), so a date never
/// drifts and confirming never moves it. App-private, cleared with the wallet, never logged.
final class WidgetChainDates: Sendable {
    struct Entry: Codable, Equatable, Sendable {
        var at: Date
        /// The block's own time or when the transaction was first seen unconfirmed: nothing replaces it.
        var exact: Bool
    }

    static let shared = WidgetChainDates(url: .applicationSupportDirectory.appending(path: "widget-chain-dates.json"))

    private let url: URL
    private let lock = NSLock()

    init(url: URL) { self.url = url }

    /// The date of each confirmed transaction in `estimates`, its first estimate kept from then on, and of each
    /// `unconfirmed` one: `now`, the first time it's seen. Entries for transactions the wallet no longer lists
    /// (`keeping`) are dropped.
    func dates(_ estimates: [String: Date], keeping: Set<String>, unconfirmed: Set<String> = [], now: Date = Date()) -> [String: Date] {
        lock.withLock {
            let stored = load()
            var entries = stored.filter { keeping.contains($0.key) }
            for txid in unconfirmed where entries[txid] == nil { entries[txid] = Entry(at: now, exact: true) }
            for (txid, estimate) in estimates where entries[txid] == nil { entries[txid] = Entry(at: estimate, exact: false) }
            if entries != stored { save(entries) }
            return entries.filter { estimates[$0.key] != nil || unconfirmed.contains($0.key) }.mapValues(\.at)
        }
    }

    /// History read the block's time (Esplora `block_time`). It replaces an estimate, never a date seen before.
    func record(txid: String, at date: Date) {
        lock.withLock {
            var entries = load()
            guard entries[txid]?.exact != true else { return }
            entries[txid] = Entry(at: date, exact: true)
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
