import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

@MainActor struct SendLnurlSafetyTests {
    // Decoder-only invoice; the payer is always a spy, never the SDK.
    func invoice() throws -> String {
        let original = try SendLightningSafetyTests().payment().destination
        let decoded = try #require(PaymentEncoding.bech32(original))
        var words = decoded.words
        let timestamp = UInt64(Date().timeIntervalSince1970)
        for i in 0..<7 { words[i] = UInt8((timestamp >> ((6 - i) * 5)) & 31) }
        return encode(prefix: "lnbcrt10u", words: words) // 1,000 sats
    }
    func encode(prefix: String, words: [UInt8]) -> String {
        var check: UInt32 = 1
        let generators: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        for word in prefix.utf8.map({ $0 >> 5 }) + [0] + prefix.utf8.map({ $0 & 31 }) + words + [UInt8](repeating: 0, count: 6) {
            let top = check >> 25
            check = ((check & 0x1ffffff) << 5) ^ UInt32(word)
            for i in 0..<5 where (top >> i) & 1 != 0 { check ^= generators[i] }
        }
        check ^= 1
        let checksum = (0..<6).map { UInt8((check >> ((5 - $0) * 5)) & 31) }
        return prefix + "1" + String((words + checksum).map { PaymentEncoding.alphabet[Int($0)] })
    }
    actor Endpoint {
        let invoice: String
        var urls: [URL] = []
        init(_ invoice: String) { self.invoice = invoice }
        func fetch(_ request: URLRequest) throws -> (Data, URLResponse) {
            let url = request.url!
            urls.append(url)
            let body: [String: Any] = urls.count == 1
                ? ["tag": "payRequest", "callback": "https://pay.example.com/callback?token=keep", "minSendable": 1000,
                   "maxSendable": 10_000_000, "commentAllowed": 100, "metadata": "[]"]
                : ["pr": invoice]
            return (try JSONSerialization.data(withJSONObject: body), HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
    }
    @Test func resolvedInvoiceHitsCheckpointGuardBeforePay() async throws {
        let invoice = try invoice()
        let endpoint = Endpoint(invoice)
        let payment = SendPayment(destination: "alice@example.com", method: "lnurl", amount: 1000, comment: "Lunch & coffee")
        let resolved = try await SendRecipientService.invoice(payment, session: WalletSession(variant: .regtest), fetch: { try await endpoint.fetch($0) })
        #expect(resolved == invoice)
        let urls = await endpoint.urls
        #expect(urls[0].path == "/.well-known/lnurlp/alice")
        let query = URLComponents(url: urls[1], resolvingAgainstBaseURL: false)?.queryItems
        #expect(query?.contains(.init(name: "amount", value: "1000000")) == true)
        #expect(query?.contains(.init(name: "comment", value: "Lunch & coffee")) == true)
        #expect(query?.contains(.init(name: "token", value: "keep")) == true)
        let pending = LightningSend(paymentHash: try #require(PaymentEncoding.invoice(resolved)?.hash),
                                    htlcVtxoIds: [], movementId: nil, invoice: resolved, amountSats: 2000,
                                    feeSats: 83, htlcVtxoCount: 1, hasFailedRevocation: false)
        let payer = SendLightningSafetyTests.Payer()
        do {
            _ = try await SendLightningSubmission.submit(payment: .init(destination: resolved, method: "lightning", amount: 1000), state: .inProgress(send: pending), movements: []) { await payer.pay() }
            Issue.record("Resolved LNURL must inspect inherited checkpoints")
        } catch is SendExistingLightning {}
        #expect(await payer.calls == 0)
    }
    @Test func normalResolvedInvoicePaysOnceAndRejectsWrongAmountOrAmountless() async throws {
        let invoice = try invoice()
        try SendRecipientService.validateInvoice(invoice, amount: 1000, metadata: "[]")
        #expect(throws: SendFailure.invalid) { try SendRecipientService.validateInvoice(invoice, amount: 999, metadata: nil) }
        let decoded = try #require(PaymentEncoding.bech32(invoice))
        let amountless = encode(prefix: "lnbcrt", words: decoded.words)
        #expect(throws: SendFailure.invalid) { try SendRecipientService.validateInvoice(amountless, amount: 1000, metadata: nil) }
        let payer = SendLightningSafetyTests.Payer()
        _ = try await SendLightningSubmission.submit(payment: .init(destination: invoice, method: "lightning", amount: 1000), state: .unknown, movements: []) { await payer.pay() }
        #expect(await payer.calls == 1)
    }
    @Test(arguments: [false, true])
    func offerNeverPaysWithAnyPendingCheckpoint(pending: Bool) async throws {
        let send = LightningSend(paymentHash: "unrelated", htlcVtxoIds: [], movementId: nil, invoice: "old invoice",
                                 amountSats: 2000, feeSats: 83, htlcVtxoCount: 1, hasFailedRevocation: false)
        let payer = SendLightningSafetyTests.Payer()
        do {
            _ = try await SendLightningSubmission.offer(pending: pending ? [send] : []) { await payer.pay() }
            #expect(!pending)
        } catch SendFailure.lightningInProgress { #expect(pending) }
        #expect(await payer.calls == (pending ? 0 : 1))
    }
    @Test func blockedOfferStaysLockedAcrossRestart() async throws {
        let storage = SendPaymentTests.Storage()
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) }, pay: { _, _, _ in throw SendFailure.lightningInProgress },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: { try storage.write($0) }))
        subject.submit(.init(destination: "offer", method: "offer", amount: 1000), quote: .init(amount: 1000, fee: 83, total: 1083))
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .uncertain && subject.message == SendFailure.lightningInProgress.errorDescription)
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = SendSafetyTests().controller(storage) { _ in .uncertain }
        #expect(restarted.locked && restarted.message == SendFailure.lightningInProgress.errorDescription)
        restarted.acknowledge()
        #expect(restarted.locked)
    }
}
