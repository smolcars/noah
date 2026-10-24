import Foundation
import Observation

/// Onboarding's "Pick a name". The public LNURL lookup is only a hint (taken
/// when `/.well-known/lnurlp/<name>` answers 200); `update_lightning_identity`
/// stays the authority when the name is saved.
@MainActor
@Observable
final class NameAvailability {
    enum Status: Equatable {
        /// "Letters and numbers"
        case hint
        /// "At least 3 characters"
        case tooShort
        case checking
        case available
        case taken
    }

    nonisolated static let maxLength = 24
    nonisolated static let minLength = 3
    nonisolated private static let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789_.-")

    /// How typed names are cleaned up and how short they may be.
    struct Rules: Sendable {
        var normalize: @Sendable (String) -> String
        var minLength: Int

        /// Onboarding's new names: a-z, 0-9, "_", "." and "-", 3 to 24 characters.
        static let newName = Rules(normalize: { NameAvailability.normalize($0) }, minLength: NameAvailability.minLength)
        /// Settings' edits of an existing address: trimmed and lowercased as before. Saving validates the
        /// rest, so a migrated name the server accepts (longer or shorter) is never cut down.
        static let edit = Rules(normalize: { LightningIdentity.normalizeUsername($0) }, minLength: 1)
    }

    /// Bound to the field; `textChanged()` normalizes it.
    var text = ""
    private(set) var status = Status.hint
    private(set) var suggestions: [String] = []
    /// The username the server assigned at registration. It is this wallet's
    /// already, so it is available without a lookup.
    var assigned: String?

    @ObservationIgnored private let lookup: @Sendable (String) async throws -> Bool
    @ObservationIgnored private let debounce: Duration
    @ObservationIgnored private let rules: Rules
    @ObservationIgnored private var check: Task<Void, Never>?
    @ObservationIgnored private var checked = ""

    /// `lookup` answers whether a name is taken.
    init(debounce: Duration = .milliseconds(500), rules: Rules = .newName, lookup: @escaping @Sendable (String) async throws -> Bool) {
        self.debounce = debounce
        self.rules = rules
        self.lookup = lookup
    }

    var name: String { text }
    var canContinue: Bool { status == .available }

    /// Lowercase `[a-z0-9_.-]`, at most 24 characters.
    nonisolated static func normalize(_ raw: String) -> String {
        String(raw.lowercased().filter { allowed.contains($0) }.prefix(maxLength))
    }

    nonisolated static func suggestions(for name: String) -> [String] {
        [name + "21", name + ".btc", "its" + name].filter { $0.count <= maxLength }
    }

    /// Call when `text` changes. Returns false if it rewrote `text` (the next change does the work).
    @discardableResult
    func textChanged() -> Bool {
        // The name this wallet has is kept exactly as the server has it.
        let normalized = text == assigned ? text : rules.normalize(text)
        if normalized != text {
            text = normalized
            return false
        }
        guard normalized != checked else { return true }
        checked = normalized
        check?.cancel()
        suggestions = []
        if normalized.isEmpty { status = .hint; return true }
        if normalized == assigned { status = .available; return true }
        if normalized.count < rules.minLength { status = .tooShort; return true }
        status = .checking
        check = Task { [debounce, lookup] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            // A failed hint never blocks: saving is the authority.
            let taken = (try? await lookup(normalized)) ?? false
            guard !Task.isCancelled, self.text == normalized else { return }
            self.resolve(taken: taken)
        }
        return true
    }

    /// The server refused the name when saving.
    func markTaken() { resolve(taken: true) }

    func use(_ suggestion: String) {
        text = suggestion
        textChanged()
    }

    /// Waits for a pending lookup (tests).
    func settle() async { await check?.value }

    private func resolve(taken: Bool) {
        status = taken ? .taken : .available
        suggestions = taken ? Self.suggestions(for: text) : []
    }
}

extension NameAvailability {
    /// GET `<server>/.well-known/lnurlp/<name>`: 200 means someone has it.
    nonisolated private static let session = URLSession(configuration: .ephemeral)

    static func liveLookup(variant: AppVariant) -> @Sendable (String) async throws -> Bool {
        let base = ServerEndpoint.baseURL(for: variant)
        return { name in
            var request = URLRequest(url: base.appending(path: ".well-known/lnurlp").appending(path: name), timeoutInterval: 10)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            let (_, response) = try await session.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
        }
    }
}
