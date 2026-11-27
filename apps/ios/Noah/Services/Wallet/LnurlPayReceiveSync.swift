import BarkBoltFFI
import Foundation

/// Moves the payer data the server matched to Ark receives into Bark's movements (docs/lnurl_pay.md): lists ready
/// records, finds the successful Arkoor receive with exactly each record's output VTXOs, writes `noah.lnurl_pay`, reads
/// it back, makes sure a backup follows, and only then acknowledges, which deletes the server's copy. A record whose
/// movement hasn't synced yet, or whose target this version doesn't handle, waits for a later pass. Never matched by
/// amount or time. One pass at a time; a pass already running makes another return.
actor LnurlPayReceiveSync {
    typealias Record = Components.Schemas.LnurlPayReceiveMetadata

    struct Dependencies: Sendable {
        var list: @Sendable () async throws -> [Record]
        var movements: @Sendable () async throws -> [Movement]
        var write: @Sendable (_ movementId: UInt32, _ patchJSON: String) async throws -> Void
        var backup: @Sendable () async -> Void
        var acknowledge: @Sendable (_ ids: [String]) async throws -> Void
    }

    private let dependencies: Dependencies
    private var running = false
    /// The records acknowledged lately, newest last, so a push's retries know when theirs is done.
    private var acknowledged: [String] = []

    init(_ dependencies: Dependencies) {
        self.dependencies = dependencies
    }

    func hasAcknowledged(_ id: String) -> Bool {
        acknowledged.contains(id)
    }

    /// The records this pass acknowledged.
    @discardableResult
    func run() async throws -> [String] {
        guard !running else { return [] }
        running = true
        defer { running = false }
        let records = try await dependencies.list()
        guard !records.isEmpty else { return [] }
        var history = try await dependencies.movements()
        var wanted: [String: (movementId: UInt32, metadata: LnurlPayReceive)] = [:]
        var wrote = false
        for record in records {
            guard let movement = Self.receive(record, in: history) else { continue }
            let metadata = LnurlPayReceive.checked(name: record.payerData?.name, identifier: record.payerData?.identifier,
                                                   comment: record.comment)
            if LnurlPayReceive.parse(movement.metadataJson) != metadata {
                try await dependencies.write(movement.id, metadata.patch())
                wrote = true
            }
            wanted[record.id] = (movement.id, metadata)
        }
        if wrote { history = try await dependencies.movements() }
        let stored = wanted.filter { _, value in
            LnurlPayReceive.parse(history.first { $0.id == value.movementId }?.metadataJson) == value.metadata
        }.keys.sorted()
        guard !stored.isEmpty else { return [] }
        if wrote { await dependencies.backup() }
        try await dependencies.acknowledge(stored)
        acknowledged = Array((acknowledged + stored).suffix(100))
        return stored
    }

    /// The record's `ark_output_vtxos` target; empty for one this version doesn't handle.
    static func arkOutputs(_ record: Record) -> Set<String> {
        guard case .case2(let target) = record.target else { return [] }
        return Set(target.vtxoIds)
    }

    /// The successful Arkoor receive with exactly the record's output VTXOs.
    private static func receive(_ record: Record, in history: [Movement]) -> Movement? {
        let outputs = arkOutputs(record)
        guard !outputs.isEmpty else { return nil }
        let matches = history.filter {
            $0.subsystemName.lowercased() == "bark.arkoor" && $0.subsystemKind.lowercased() == "receive"
                && $0.status == "successful" && Set($0.outputVtxoIds) == outputs
        }
        return matches.count == 1 ? matches[0] : nil
    }
}

extension WalletSession {
    /// bark's own records, for the LNURL-pay receive pass.
    func movements() async throws -> [Movement] {
        try await withOpenWallet { try await $0.wallet.history() }
    }

    /// Merges `patchJSON` into one movement's metadata, as Send does for `noah.repeat_payment`.
    func updateHistoryMetadata(movementId: UInt32, patchJSON: String) async throws {
        try await withOpenWallet { try await $0.wallet.updateHistoryMetadata(movementId: movementId, patchJson: patchJSON) }
    }
}
