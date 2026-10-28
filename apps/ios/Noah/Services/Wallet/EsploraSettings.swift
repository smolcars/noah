import Foundation

/// A single durable native-settings value is the write-ahead record. Until commit,
/// launch uses active (the previous working selection), never the pending candidate.
struct EsploraSettings: Sendable {
    static let key = "native-esplora-selection"
    // Unknown history stays unknown for the installation lifetime, including resets.
    static let historyUnavailableKey = "native-esplora-history-unavailable"
    struct HistoryUnavailable: Error {}
    struct Selection: Codable, Equatable, Sendable {
        var endpoint: String?
        var variant: String
        var input: String?
        var attention: String?
    }
    struct Record: Codable, Equatable, Sendable {
        var active: Selection
        var pending: Selection?
        // swiftlint:disable:next discouraged_optional_collection - settings written before log endpoints lack it
        var logEndpoints: [String]?
    }
    let read: @Sendable (String) -> String?
    let write: @Sendable (String, String) throws -> Void
    init(storage: SettingsStorage = .shared) {
        read = { storage.string(forKey: $0) }
        write = { try storage.setDurably($1, forKey: $0) }
    }
    init(read: @escaping @Sendable (String) -> String?, write: @escaping @Sendable (String, String) throws -> Void) {
        self.read = read; self.write = write
    }
    func record() throws -> Record? {
        try read(Self.key).map { try JSONDecoder().decode(Record.self, from: Data($0.utf8)) }
    }
    private func endpoints(_ record: Record?) -> Set<String> {
        guard let record else { return [] }
        return Set((record.logEndpoints ?? []) + [record.active.endpoint, record.pending?.endpoint].compactMap(\.self))
    }
    func save(_ record: Record) throws {
        var protected = record
        protected.logEndpoints = endpoints(try self.record()).union(endpoints(record)).sorted()
        try write(Self.key, String(json: JSONEncoder().encode(protected)))
        endpoints(protected).forEach(EndpointLogPrivacy.register)
    }
    func restoreLogPrivacy() throws {
        guard read(Self.historyUnavailableKey) == nil else { throw HistoryUnavailable() }
        endpoints(try record()).forEach(EndpointLogPrivacy.register)
    }
    func protectLogPrivacy(_ endpoint: String?) throws {
        guard let endpoint else { return }
        guard var record = try record() else { throw SettingsStorage.OpenError() }
        if !(record.logEndpoints ?? []).contains(endpoint) {
            record.logEndpoints = (record.logEndpoints ?? []) + [endpoint]
            try save(record)
        }
        EndpointLogPrivacy.register(endpoint)
    }
    /// Reset the selection while retaining protection for every retained log generation.
    static func retainingLogPrivacy(_ values: [String: String]) throws -> [String: String] {
        var retained: [String: String] = [:]
        retained[historyUnavailableKey] = values[historyUnavailableKey]
        guard let raw = values[key] else { return retained }
        let old = try JSONDecoder().decode(Record.self, from: Data(raw.utf8))
        let history = Set((old.logEndpoints ?? []) + [old.active.endpoint, old.pending?.endpoint].compactMap(\.self))
        let record = Record(active: Selection(variant: old.active.variant), logEndpoints: history.sorted())
        retained[key] = try String(json: JSONEncoder().encode(record))
        return retained
    }
    var importedInput: String? {
        struct State: Decodable { var endpointOverride: String? }
        return read("esplora-storage").flatMap {
            try? JSONDecoder().decode(PersistedEnvelope<State>.self, from: Data($0.utf8)).state.endpointOverride
        }
    }
    func selection(for variant: AppVariant) -> Selection {
        guard let record = try? record(), record.active.variant == variant.rawValue else {
            return Selection(variant: variant.rawValue, input: importedInput,
                             attention: importedInput == nil ? nil : "This imported endpoint needs attention. Test and save it before use.")
        }
        return record.active
    }
    func resolve(for variant: AppVariant, fetch: EsploraEndpoint.Fetch = EsploraEndpoint.fetch) async throws -> String? {
        if let record = try record() {
            if record.pending != nil { try? save(Record(active: record.active)) }
            return record.active.variant == variant.rawValue && variant != .regtest
                ? record.active.endpoint.flatMap { try? EsploraEndpoint.normalize($0) } : nil
        }
        var selection = Selection(variant: variant.rawValue)
        if let input = importedInput {
            selection.input = input
            do { selection.endpoint = try await EsploraEndpoint.validate(input, variant: variant, fetch: fetch) }
            catch { selection.attention = "This imported endpoint needs attention. Test and save it before use." }
        }
        do { try save(Record(active: selection)) } catch { return nil }
        return selection.endpoint
    }
    /// Write ahead before closing any handles. A failed write leaves the live wallet alone.
    func prepare(_ candidate: Selection) throws -> Record {
        guard let variant = AppVariant(rawValue: candidate.variant) else {
            throw EsploraEndpoint.Failure(message: "Endpoint not changed. The wallet is using the previous endpoint.")
        }
        let record = Record(active: selection(for: variant), pending: candidate)
        try save(record)
        return record
    }
    /// Called under the lifecycle gate and a closed-session reservation.
    func change(to candidate: Selection, prepared: Record? = nil,
                commitCandidate: @escaping @Sendable (@Sendable () throws -> Void) throws -> Void = { try $0() },
                reopen: @Sendable (Selection, @escaping @Sendable () throws -> Void) async throws -> Void) async throws {
        let previous = try (prepared ?? prepare(candidate)).active
        do {
            try await reopen(candidate) { try commitCandidate { try save(Record(active: candidate)) } }
        } catch {
            // Keep pending if rollback storage fails: cold launch still selects active.
            try? save(Record(active: previous))
            do { try await reopen(previous) {} }
            catch { throw EsploraEndpoint.Failure(message: "Endpoint not changed. Restart Noah to reopen the previous endpoint.") }
            throw EsploraEndpoint.Failure(message: "Endpoint not changed. The wallet is using the previous endpoint.")
        }
    }
}

/// Only the reservation holder may open a pending candidate; ordinary opens use active.
enum EsploraOpening {
    @TaskLocal static var selection: EsploraSettings.Selection?
}
