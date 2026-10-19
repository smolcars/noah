import Foundation
import Observation

@MainActor
@Observable
final class ReceiveModel {
    struct Dependencies: Sendable {
        var addresses: @Sendable () async throws -> ReceiveRequest
        var invoice: @Sendable (ReceiveRequest, Int64, String) async throws -> ReceiveRequest
        var completions: @Sendable (ReceiveRequest) async throws -> AsyncThrowingStream<ReceiveCompletion, Error>

        static func live(session: WalletSession) -> Self {
            .init(addresses: { try await session.receiveRequest() },
                  invoice: { try await session.receiveInvoice(for: $0, amountSat: $1, description: $2) },
                  completions: { try await session.receiveCompletions(for: $0) })
        }
    }

    private(set) var request: ReceiveRequest?
    private(set) var isGenerating = false
    private(set) var isSaving = false
    private(set) var receivedSats: Int64?
    private(set) var baseError: String?
    private(set) var baseErrorTitle = String(localized: "Couldn’t create a request")
    var invoiceError: String?
    private let dependencies: Dependencies
    private var generation = UUID()
    private var invoiceGeneration = UUID()
    private var observer: Task<Void, Never>?
    private let log = AppLog("receive")

    init(dependencies: Dependencies = .live(session: .shared)) { self.dependencies = dependencies }

    func start() async {
        stop()
        receivedSats = nil
        baseError = nil
        baseErrorTitle = String(localized: "Couldn’t create a request")
        isGenerating = true
        let generation = generation
        defer { if self.generation == generation { isGenerating = false } }
        do {
            let request = try await dependencies.addresses()
            guard self.generation == generation, !Task.isCancelled else { return }
            self.request = request
            observe(request)
        } catch {
            guard self.generation == generation, !Task.isCancelled else { return }
            recordFailure(error)
            baseError = error is WalletSession.SessionError
                ? String(localized: "Your wallet isn't ready. Please try again.")
                : String(localized: "Could not generate a receive request. Please try again.")
        }
    }

    func stop() {
        generation = UUID()
        invoiceGeneration = UUID()
        observer?.cancel()
        observer = nil
        request = nil
        isGenerating = false
        isSaving = false
        invoiceError = nil
    }

    func save(amountSat: Int64, description: String) async -> Bool {
        guard let current = request, !isSaving else { return false }
        let generation = generation
        invoiceGeneration = UUID()
        let invoiceGeneration = invoiceGeneration
        isSaving = true
        invoiceError = nil
        defer { if self.invoiceGeneration == invoiceGeneration { isSaving = false } }
        do {
            let next = try await dependencies.invoice(current, amountSat, description)
            guard self.generation == generation, self.invoiceGeneration == invoiceGeneration,
                  !Task.isCancelled else { return false }
            request = next
            observe(next)
            return true
        } catch {
            guard self.generation == generation, self.invoiceGeneration == invoiceGeneration else { return false }
            recordFailure(error)
            invoiceError = String(localized: "Could not generate a Lightning invoice. Please try again.")
            return false
        }
    }

    func removeAmount() {
        guard let request else { return }
        invoiceGeneration = UUID()
        isSaving = false
        let next = ReceiveRequest(arkAddress: request.arkAddress, onchainAddress: request.onchainAddress)
        self.request = next
        observe(next)
    }

    private func observe(_ request: ReceiveRequest) {
        observer?.cancel()
        let generation = generation
        observer = Task { [weak self, dependencies] in
            while !Task.isCancelled {
                do {
                    let events = try await dependencies.completions(request)
                    for try await event in events {
                        guard let self, !Task.isCancelled, self.generation == generation else { return }
                        self.stop()
                        switch event {
                        case .received(let sats): self.receivedSats = sats
                        case .lightningFailed(let canceled):
                            self.baseErrorTitle = canceled ? String(localized: "Lightning Receive Canceled") : String(localized: "Lightning Receive Failed")
                            self.baseError = String(localized: "The Lightning payment could not be received. Generate a new payment request and try again.")
                        }
                        return
                    }
                } catch {
                    guard !Task.isCancelled else { return }
                    self?.recordFailure(error)
                    if error is WalletSession.SessionError {
                        guard let self, self.generation == generation else { return }
                        self.stop()
                        self.baseError = String(localized: "Your wallet isn't ready. Please try again.")
                        return
                    }
                }
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
    }

    private func recordFailure(_ error: Error) {
        // SDK errors can embed a full invoice/address; log the type, never its message.
        struct ReceiveOperationFailed: Error {}
        log.w("Receive operation failed (\(String(describing: type(of: error)))): \(SafeErrorDescription.of(ReceiveOperationFailed()))")
    }
}
