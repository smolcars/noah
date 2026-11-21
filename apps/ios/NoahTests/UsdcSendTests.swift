import Foundation
@testable import Noah
import SatoraFFI
import Testing

/// USDC sends (beta): address recognition, the checks before a swap, the order of a send, and the foreground pass
/// finishing swaps an earlier run left. A fake stands in for the Satora SDK.
@MainActor struct UsdcSendTests {
    struct AddressInput: Decodable { let destination: String; let network: String }
    struct AddressExpected: Decodable { let destinationType: String? }
    struct AmountInput: Decodable { let sats, total, spendable, min, max: UInt64 }
    struct AmountExpected: Decodable { let error: String? }

    @Test func recognizesArbitrumAddresses() throws {
        for fixture in try Fixtures.load("usdc-address", input: AddressInput.self, expected: AddressExpected.self) {
            let parsed = SendDestination.parse(fixture.input.destination, network: fixture.input.network)
            #expect(parsed.destinationType == fixture.expected.destinationType, "\(fixture.name)")
            #expect(parsed.amount == nil && parsed.error == nil, "\(fixture.name)")
        }
    }

    @Test func keccakMatchesEthereum() {
        let hash = UsdcAddress.keccak256([]).map { String(format: "%02x", $0) }.joined()
        #expect(hash == "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
    }

    @Test func checksAmountsInOrder() throws {
        for fixture in try Fixtures.load("usdc-amount", input: AmountInput.self, expected: AmountExpected.self) {
            let input = fixture.input
            let error = UsdcSend.check(sats: input.sats, total: input.total, spendable: input.spendable, min: input.min, max: input.max)
            #expect(error?.rawValue == fixture.expected.error, "\(fixture.name)")
        }
    }

    @Test func reviewLineFloorsUsdcToCents() {
        #expect(UsdcSend.usdc("5839905") == "5.83")
        #expect(UsdcSend.usdc("9999") == "0.00")
        #expect(UsdcSend.usdc("120000000") == "120.00")
        #expect(UsdcSend.reviewLine(Self.quote, locale: "en") == "Recipient gets about 5.83 USDC on Arbitrum · Swap fee 73 sats")
    }

    @Test func createsTheSwapBeforePayingItsInvoice() async throws {
        let calls = Calls()
        let client = Self.client(calls, create: Self.swap("s1", invoice: Self.invoice1500))
        var submitted: SendPayment?
        let swap = try await UsdcSend.start(sats: 1500, destination: Self.address, quote: Self.sendQuote, client: client) { payment, _ in
            calls.log.append("submit")
            submitted = payment
        }
        #expect(calls.log == ["create 1500 \(Self.address)", "submit"])
        #expect(submitted == SendPayment(destination: Self.invoice1500, method: "lightning", amount: 1500))
        #expect(swap.id == "s1")
    }

    @Test func neverPaysAnInvoiceForAnotherAmount() async {
        let calls = Calls()
        let client = Self.client(calls, create: Self.swap("s1", invoice: Self.invoice1500))
        await #expect(throws: SatoraError.self) {
            try await UsdcSend.start(sats: 1000, destination: Self.address, quote: Self.sendQuote, client: client) { _, _ in
                calls.log.append("submit")
            }
        }
        #expect(!calls.log.contains("submit"))
    }

    @Test func foregroundPassFinishesOpenSwaps() async {
        let calls = Calls()
        let stored = [Self.swap("funded"), Self.swap("pending"), Self.swap("done", terminal: true), Self.swap("claimed", claimTx: "0xold")]
        let refreshed = ["funded": Self.swap("funded", status: "serverfunded"), "pending": Self.swap("pending")]
        let swaps = UsdcSwaps { Self.client(calls, list: stored, refreshed: refreshed) }
        await swaps.finishOpenSwaps()
        #expect(calls.log == ["refresh funded", "claim funded", "refresh pending"])
    }

    @Test func foregroundPassWithoutASatoraWalletDoesNothing() async {
        await UsdcSwaps { nil }.finishOpenSwaps()
    }

    // MARK: Fakes

    final class Calls { var log: [String] = [] }

    private static let address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    /// 15u: 1,500 sats (apps/fixtures/send-utils.json).
    private static let invoice1500 = "lnbc15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5"
        + "f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwk"
        + "wcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsq4gj5hs"
    private static let quote = SatoraQuote(protocolFeeSats: 50, protocolFeeRate: 0.005, networkFeeSats: 20, gaslessClaimFeeSats: 3,
                                           exchangeRate: "100000", targetUsdc: "5873384", netTargetUsdc: "5839905", minSats: 356,
                                           maxSats: 20_000_000)
    private static let sendQuote = SendQuote(amount: 1500, fee: 2, total: 1502)

    private static func swap(_ id: String, status: String = "pending", terminal: Bool = false, claimTx: String? = nil,
                             invoice: String = "") -> SatoraSwap {
        SatoraSwap(id: id, status: status, terminal: terminal, bolt11Invoice: invoice, sourceSats: "1500", targetUsdc: "0", feeSats: 0,
                   destination: address, createdAt: "2026-11-20T00:00:00Z", invoiceExpiresAt: nil, claimDeadline: 0,
                   serverFundTx: nil, claimTx: claimTx, claimUserOpHash: nil, claimStarted: false)
    }

    private static func client(_ calls: Calls, create: SatoraSwap = swap("new"), list: [SatoraSwap] = [],
                               refreshed: [String: SatoraSwap] = [:]) -> UsdcSwapClient {
        nonisolated(unsafe) let calls = calls
        return UsdcSwapClient(
            quote: { _ in quote },
            createSwap: { sats, destination in calls.log.append("create \(sats) \(destination)"); return create },
            refresh: { id in calls.log.append("refresh \(id)"); return refreshed[id] ?? swap(id) },
            claim: { id in calls.log.append("claim \(id)"); return "0xtx" },
            list: { list })
    }
}
