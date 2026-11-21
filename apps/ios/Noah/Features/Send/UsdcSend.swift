import Foundation
import Observation
import SatoraFFI

// BoltFFI's Rust object is internally synchronized, as bark's are (BarkBoltFFI's Concurrency.swift).
extension SatoraClient: @retroactive @unchecked Sendable {}

/// The Satora calls the USDC beta makes, so tests use a fake instead of the SDK.
struct UsdcSwapClient: Sendable {
    var quote: @Sendable (UInt64) async throws -> SatoraQuote
    var createSwap: @Sendable (UInt64, String) async throws -> SatoraSwap
    var refresh: @Sendable (String) async throws -> SatoraSwap
    var claim: @Sendable (String) async throws -> String?
    var list: @Sendable () throws -> [SatoraSwap]

    static func live(_ client: SatoraClient) -> Self {
        Self(quote: { try await client.quote(sats: $0) }, createSwap: { try await client.createSwap(sats: $0, destination: $1) },
             refresh: { try await client.refreshSwap(id: $0) }, claim: { try await client.claim(id: $0) }, list: { try client.listSwaps() })
    }
}

/// The Satora client in `<wallet datadir>/satora`: inside the datadir, so it shares its file treatment and wallet
/// deletion removes it. Opening it the first time creates the phrase.
@MainActor final class SatoraStore {
    static let shared = SatoraStore()
    static let enabledKey = "usdcSendsEnabled"
    private var client: SatoraClient?

    var directory: URL { WalletDataLocation(variant: .current).directory.appending(path: "satora", directoryHint: .isDirectory) }
    var exists: Bool { FileManager.default.fileExists(atPath: directory.path) }

    func open() throws -> SatoraClient {
        if let client { return client }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let opened = try SatoraClient(open: directory.path, mnemonic: nil)
        client = opened
        return opened
    }

    /// The wallet was deleted: its files went with the datadir.
    func close() { client = nil }

    /// Beta → Send USDC; settings are wiped with the wallet.
    static func enabled(_ settings: SettingsStorage = .shared) -> Bool { settings.string(forKey: enabledKey) == "true" }
    /// The SDK is mainnet only.
    static var offered: Bool { AppVariant.current == .mainnet && enabled() }
}

enum UsdcSend {
    enum LimitError: String { case min, max, balance }

    /// Before a swap is created (`apps/fixtures/usdc-amount.json`). `total` includes the Lightning fee.
    static func check(sats: UInt64, total: UInt64, spendable: UInt64, min: UInt64, max: UInt64) -> LimitError? {
        if sats < min { return .min }
        if sats > max { return .max }
        return total > spendable ? .balance : nil
    }

    /// USDC base units (6 decimals) floored to cents, e.g. "5839905" → "5.83".
    static func usdc(_ units: String) -> String {
        let cents = (UInt64(units) ?? 0) / 10000
        return "\(cents / 100)." + String(format: "%02llu", cents % 100)
    }

    static func feeSats(_ quote: SatoraQuote) -> UInt64 { quote.protocolFeeSats + quote.networkFeeSats + quote.gaslessClaimFeeSats }

    static func reviewLine(_ quote: SatoraQuote, locale: String) -> String {
        L10n.text("Recipient gets about {usdc} USDC on Arbitrum · Swap fee {fee} sats", usdc(quote.netTargetUsdc), String(feeSats(quote)),
                  locale: locale)
    }

    static func limitMessage(_ error: LimitError, quote: SatoraQuote, locale: String) -> String {
        switch error {
        case .min: L10n.text("Send at least {amount} sats to a USDC address.", String(quote.minSats), locale: locale)
        case .max: L10n.text("Send at most {amount} sats to a USDC address.", String(quote.maxSats), locale: locale)
        case .balance: L10n.text(SendFailure.insufficient.errorDescription ?? "", locale: locale)
        }
    }

    /// The swap would pay less than review showed: nothing was paid. `usdc`: what it would pay, in base units.
    struct RateChanged: Error { let usdc: String }

    /// Review's amount is a quote, and the rate moves before the swap is created: a swap paying at least 99% of it is
    /// paid, anything less is refused (`apps/fixtures/usdc-payout.json`).
    static func accepts(swapUsdc: String, reviewedUsdc: String) -> Bool {
        guard let swap = UInt64(swapUsdc), let reviewed = UInt64(reviewedUsdc) else { return false }
        return swap * 100 >= reviewed * 99
    }

    static func createFailure(_ error: any Error, locale: String) -> String {
        if let changed = error as? RateChanged {
            return L10n.text("The rate changed. The recipient now gets about {amount} USDC. Hold to send again.", usdc(changed.usdc), locale: locale)
        }
        return (error as? SatoraError)?.code == .unknownContract
            ? L10n.text("USDC sends are paused until you update Noah.", locale: locale)
            : L10n.text("Couldn't start the USDC swap. Nothing was sent.", locale: locale)
    }

    /// Creates the swap (stored by the SDK before anything is paid), then hands its invoice to Send's journal as an
    /// ordinary Lightning payment. Returns the swap id, or throws with nothing paid. `reviewedUsdc`: review's amount.
    @MainActor static func start(_ payment: SendPayment, reviewedUsdc: String, quote: SendQuote, client: UsdcSwapClient,
                                 submit: (SendPayment, SendQuote) -> Void) async throws -> SatoraSwap {
        let sats = payment.amount
        let swap = try await client.createSwap(sats, payment.destination)
        guard accepts(swapUsdc: swap.targetUsdc, reviewedUsdc: reviewedUsdc) else { throw RateChanged(usdc: swap.targetUsdc) }
        guard let invoice = PaymentEncoding.invoice(swap.bolt11Invoice), invoice.sats == Decimal(sats) else {
            throw SatoraError(message: "invoice amount differs", code: .invalidInput)
        }
        submit(SendPayment(destination: swap.bolt11Invoice, method: "lightning", amount: sats), quote)
        return swap
    }
}

/// Claims open swaps, one Satora operation at a time: right after a send, and on each foreground pass for swaps an
/// earlier run left (the app died between paying and claiming).
@MainActor @Observable final class UsdcSwaps {
    enum Status: Equatable { case waiting, claiming, sent, retryLater, ended }

    static let shared = UsdcSwaps(client: {
        guard SatoraStore.shared.exists else { return nil }
        return .live(try SatoraStore.shared.open())
    })

    /// By invoice, for Send's result.
    private(set) var status: [String: Status] = [:]
    @ObservationIgnored private let client: @MainActor () throws -> UsdcSwapClient?
    @ObservationIgnored private var busy = false
    @ObservationIgnored private let log = AppLog("usdc")

    init(client: @escaping @MainActor () throws -> UsdcSwapClient?) { self.client = client }

    /// Waits for Satora to lock the USDC and claims it, for up to five minutes; the foreground pass takes over after.
    func watch(_ swap: SatoraSwap, pollEvery: Duration = .seconds(2), retryAfter: Duration = .seconds(15),
               until deadline: ContinuousClock.Instant = .now + .seconds(300)) async {
        status[swap.bolt11Invoice] = .waiting
        var nextClaim = ContinuousClock.now
        while ContinuousClock.now < deadline {
            if let status = await finish(swap, claim: ContinuousClock.now >= nextClaim), status != .waiting {
                self.status[swap.bolt11Invoice] = status
                if status == .sent || status == .ended { return }
                if status == .retryLater { nextClaim = .now + retryAfter }
            }
            try? await Task.sleep(for: pollEvery)
        }
    }

    /// Every stored swap that may still need a claim. Never creates the Satora phrase.
    func finishOpenSwaps() async {
        do {
            guard let open = try client()?.list().filter({ !$0.terminal && $0.claimTx == nil }) else { return }
            for swap in open { _ = await finish(swap, claim: true) }
        } catch { log.w("listing USDC swaps failed: \(Self.describe(error))") }
    }

    /// Refreshes the swap and claims it once Satora has funded it. Nil: busy, or the swap couldn't be read.
    private func finish(_ swap: SatoraSwap, claim: Bool) async -> Status? {
        guard !busy else { return nil }
        busy = true
        defer { busy = false }
        let client: UsdcSwapClient, current: SatoraSwap
        do {
            guard let opened = try self.client() else { return nil }
            client = opened
            current = try await client.refresh(swap.id)
        } catch {
            log.w("swap \(swap.id) not refreshed: \(Self.describe(error))")
            return nil
        }
        if current.claimTx != nil { return .sent }
        if current.terminal { return .ended }
        guard current.status == "serverfunded", claim else { return .waiting }
        status[swap.bolt11Invoice] = .claiming
        do { return try await client.claim(swap.id) == nil ? .claiming : .sent } catch {
            log.w("swap \(swap.id) not claimed: \(Self.describe(error))")
            return .retryLater
        }
    }

    /// SDK errors carry no secrets; anything else is named by type only.
    private static func describe(_ error: any Error) -> String {
        (error as? SatoraError).map { "\($0.code) \($0.message)" } ?? String(describing: type(of: error))
    }

    static func message(_ status: Status, locale: String) -> String {
        switch status {
        case .waiting: L10n.text("Waiting for the swap to lock the USDC…", locale: locale)
        case .claiming: L10n.text("Claiming the USDC…", locale: locale)
        case .sent: L10n.text("USDC sent.", locale: locale)
        case .retryLater: L10n.text("The USDC claim hasn't gone through yet. Noah tries again whenever you open it.", locale: locale)
        case .ended: L10n.text("The swap ended without sending USDC. The payment stays pending until Satora releases it, which can take hours.", locale: locale)
        }
    }
}
