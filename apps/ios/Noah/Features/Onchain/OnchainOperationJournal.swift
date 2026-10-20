import Foundation

/// One durable attempt across all M6 surfaces. A possibly-broadcast call is never retried.
actor OnchainOperationJournal {
    struct Attempt: Codable, Sendable {
        var quote: OnchainQuote
        var receipt: String?
    }
    static let shared = OnchainOperationJournal(
        file: WalletDataLocation(variant: .current).directory.appending(
            path: "noah-onchain-attempt.json"))
    private let file: URL
    private var running = false
    init(file: URL) { self.file = file }

    func load() throws -> Attempt? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try JSONDecoder().decode(Attempt.self, from: Data(contentsOf: file))
    }
    private func save(_ value: Attempt) throws {
        try JSONEncoder().encode(value).write(
            to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var url = file
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
    func perform(_ quote: OnchainQuote, action: @Sendable () async throws -> String) async throws
        -> String
    {
        guard !running, try load() == nil else { throw OnchainFailure.busy }
        running = true
        defer { running = false }
        try save(.init(quote: quote))
        do {
            let receipt = try await action()
            try save(.init(quote: quote, receipt: receipt))
            return receipt
        } catch OnchainFailure.claimFeeChanged {
            // drainExits only constructs a PSBT; this path is known not to broadcast.
            try FileManager.default.removeItem(at: file)
            throw OnchainFailure.claimFeeChanged
        } catch OnchainFailure.cancellationRefused {
            try FileManager.default.removeItem(at: file)
            throw OnchainFailure.cancellationRefused
        } catch {
            throw OnchainFailure.uncertain
        }
    }
    func acknowledge() throws {
        guard !running, let attempt = try load(), attempt.receipt != nil else {
            throw OnchainFailure.uncertain
        }
        try FileManager.default.removeItem(at: file)
    }
}
