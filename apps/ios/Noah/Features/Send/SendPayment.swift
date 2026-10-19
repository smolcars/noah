import Foundation
import Observation

struct SendPayment: Codable, Equatable, Sendable {
    var destination: String
    var method: String
    var amount: UInt64
    var source: SendSource?
    var max = false
    var comment = ""
    var repeatMetadata: String?

    static func metadata(destination: String, comment: String, amount: String, fiatCurrency: String?) throws -> String {
        var details: [String: Any] = [
            "version": 1, "destination": SendDestination.cleaned(destination).lowercased(), "comment": comment,
            "amount_mode": fiatCurrency == nil ? "sats" : "fiat", "amount_value": amount,
        ]
        if let fiatCurrency { details["fiat_currency"] = fiatCurrency }
        return String(
            decoding: try JSONSerialization.data(withJSONObject: ["noah": ["repeat_payment": details]]), as: UTF8.self)
    }
}
struct SendQuote: Sendable, Equatable {
    var amount: UInt64
    var fee: UInt64
    var total: UInt64
    var feeRate: UInt64?
    var approximate = false
}
struct SendReceipt: Codable, Equatable, Sendable {
    var amount: UInt64
    var txid: String?
    var paymentHash: String?
    var movementID: UInt32?
    var amountIsEstimated: Bool?
}
struct SendSnapshot: Codable, Sendable {
    var movementIDs: Set<UInt32>
}
enum SendFailure: LocalizedError {
    case invalid, insufficient, expired, ownAddress, feeChanged, unresolved
    var errorDescription: String? {
        switch self {
        case .invalid: "Enter a valid destination and a whole-satoshi amount greater than zero."
        case .insufficient: "The selected balance cannot cover this payment and its fee."
        case .expired: "This Lightning invoice has expired. Request a new invoice."
        case .ownAddress:
            "Your Ark balance cannot be swept to this wallet's own onchain address. Use an external Bitcoin address."
        case .feeChanged: "The amount or fee changed. Review the payment again."
        case .unresolved: "This payment may have been sent. Check its status before making another payment."
        }
    }
}

/// Stored before funds can move. An absent history entry is never proof of failure.
@MainActor @Observable final class SendPaymentController {
    enum State: Equatable {
        case ready, submitting, uncertain
        case succeeded(SendReceipt)
        case failed, storageBlocked
    }
    struct Attempt: Codable, Sendable {
        var payment: SendPayment
        var snapshot: SendSnapshot
        var receipt: SendReceipt?
        var failed = false
    }
    struct Dependencies {
        var prepare: @Sendable (SendPayment, SendQuote) async throws -> SendSnapshot
        var pay: @Sendable (SendPayment, SendQuote, SendSnapshot) async throws -> SendReceipt
        var reconcile: @Sendable (Attempt) async throws -> Resolution
        var read: () throws -> Attempt?
        var write: (Attempt?) throws -> Void
    }
    enum Resolution: Sendable {
        case uncertain
        case paid(SendReceipt)
        case failed
    }
    static let shared = SendPaymentController(dependencies: .live())
    private let dependencies: Dependencies
    private(set) var state: State = .ready
    private(set) var attempt: Attempt?
    private(set) var message: String?
    private(set) var checking = false
    private var task: Task<Void, Never>?
    var locked: Bool { state != .ready }

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
        do {
            attempt = try dependencies.read()
            if let attempt { state = attempt.receipt.map(State.succeeded) ?? (attempt.failed ? .failed : .uncertain) }
        } catch { state = .storageBlocked }
    }
    func submit(_ payment: SendPayment, quote: SendQuote) {
        guard state == .ready else { return }
        state = .submitting
        message = nil
        // This task belongs to the controller, not a sheet's cancellable .task.
        task = Task {
            do {
                let snapshot = try await dependencies.prepare(payment, quote)
                let pending = Attempt(payment: payment, snapshot: snapshot)
                try dependencies.write(pending)
                attempt = pending
            } catch {
                state = .ready
                message =
                    (error as? SendFailure)?.errorDescription ?? "Unable to prepare payment. No payment was submitted."
                return
            }
            do {
                let receipt = try await dependencies.pay(payment, quote, attempt!.snapshot)
                finish(.paid(receipt))
            } catch {
                state = .uncertain
                await checkStatus()
            }
        }
    }
    func checkStatus() async {
        guard state == .uncertain, !checking, let attempt else { return }
        checking = true
        defer { checking = false }
        do { finish(try await dependencies.reconcile(attempt)) } catch {
            message = "Unable to check payment status. It may have been sent. Do not pay again."
        }
    }
    private func finish(_ resolution: Resolution) {
        guard var attempt else { return }
        switch resolution {
        case .uncertain: state = .uncertain
        case .paid(let receipt):
            attempt.receipt = receipt
            state = .succeeded(receipt)
        case .failed:
            attempt.failed = true
            state = .failed
        }
        self.attempt = attempt
        do { try dependencies.write(attempt) } catch {
            message = "Payment status could not be saved. Check status again after restarting Noah."
        }
    }
    func acknowledge() {
        switch state {
        case .succeeded, .failed: break
        default: return
        }
        do {
            try dependencies.write(nil)
            attempt = nil
            state = .ready
            message = nil
        } catch { message = "Unable to save payment status." }
    }
}

extension SendPaymentController.Dependencies {
    static func live(session: WalletSession = .shared, variant: AppVariant = .current) -> Self {
        let file = WalletDataLocation(variant: variant).directory.appending(path: "noah-send-attempt.json")
        return Self(
            prepare: { try await session.prepareSend($0, quote: $1) },
            pay: { try await session.submitSend($0, quote: $1, snapshot: $2) },
            reconcile: { try await session.reconcileSend($0) },
            read: {
                guard FileManager.default.fileExists(atPath: file.path) else { return nil }
                return try JSONDecoder().decode(SendPaymentController.Attempt.self, from: Data(contentsOf: file))
            },
            write: { attempt in
                if let attempt {
                    try JSONEncoder().encode(attempt).write(
                        to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                    var excluded = file
                    var values = URLResourceValues()
                    values.isExcludedFromBackup = true
                    try excluded.setResourceValues(values)
                } else if FileManager.default.fileExists(atPath: file.path) {
                    try FileManager.default.removeItem(at: file)
                }
            })
    }
}
