import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// BOLT12 offers that fix their amount (ffi-phase-b.md row 12): Bark's parser supplies the amount, the quote enforces it.
@MainActor struct SendOfferTests {
    struct ParseInput: Decodable { let destination: String }
    struct Ignored: Decodable {}
    /// The mainnet send-utils offer, which fixes 1,000 sats.
    let offer: String
    let onchain = "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
    let unableToResolve = "Unable to resolve this recipient. Please check the address."

    init() throws {
        offer = try #require(try Fixtures.load("send-utils", input: ParseInput.self, expected: Ignored.self)
            .map(\.input.destination).first { $0.hasPrefix("lno1") })
    }
    private func request(_ amount: UInt64?, _ errors: PaymentMethodParsingError...) -> PaymentRequest {
        let option = PaymentOption(method: PaymentMethod(methodType: "offer", value: offer), errors: errors,
                                   requiresAmount: amount == nil, supportsComment: false)
        return PaymentRequest(amountSats: amount, label: nil, message: nil, options: [option], defaultOption: errors.isEmpty ? option : nil)
    }
    private let fiatPriced: @Sendable (String) async throws -> PaymentRequest = { _ in
        throw BarkError(message: "unsupported payment request (fiat-priced BOLT12 offer?)", code: .invalidPaymentRequest)
    }
    private func model(parse: @escaping @Sendable (String) async throws -> PaymentRequest,
                       quote: @escaping (SendPayment) async throws -> SendQuote) -> SendModel {
        let model = SendModel(controller: SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
                              resolveRecipient: { _ in throw SendFailure.invalid }, quotePayment: quote,
                              offerAmount: { try await SendOfferAmount.fixed($0, parse: parse) })
        model.arkBalance = 10_000
        return model
    }
    private func reviewing(_ payment: SendPayment) -> SendQuote { .init(amount: payment.amount, fee: 0, total: payment.amount) }

    @Test func aFixedOfferLocksTheKeypadAndReviewsItsAmount() async {
        let fixed = request(1000)
        for typed in ["", "700"] {
            var reviewed: [SendPayment] = []
            let model = model(parse: { _ in fixed }) { reviewed.append($0); return reviewing($0) }
            model.amount = typed
            // SendView takes the typed amount before a paste, for the review's note.
            let noted = SendView.typedAmount(model, keeping: nil)
            await model.importRecipient(offer)
            #expect(model.stage == .review, "\(typed)")
            #expect(!model.parsed.isAmountEditable && model.amount == "1000")
            #expect(reviewed == [SendPayment(destination: offer, method: "offer", amount: 1000)])
            #expect(SendView.showsInvoiceNote(typed: noted, fixed: model.parsed.amount) == !typed.isEmpty)
            // Another recipient unlocks the keypad again.
            model.destination = onchain
            #expect(model.amount.isEmpty && model.parsed.isAmountEditable)
        }
        // Recipient-first from To: the same.
        var reviewed: [SendPayment] = []
        let model = model(parse: { _ in fixed }) { reviewed.append($0); return reviewing($0) }
        model.recipientFirst()
        model.destination = offer
        await model.continueStage()
        #expect(model.stage == .review)
        #expect(reviewed == [SendPayment(destination: offer, method: "offer", amount: 1000)])
    }

    @Test func theQuoteRefusesAnotherAmountForAFixedOffer() async throws {
        let thousand = request(1000), amountless = request(nil)
        let fixed: @Sendable (String) async throws -> PaymentRequest = { _ in thousand }
        try await SendOfferAmount.require(SendPayment(destination: offer, method: "offer", amount: 1000), parse: fixed)
        for amount: UInt64 in [999, 1001] {
            await #expect(throws: SendFailure.invalid) {
                try await SendOfferAmount.require(SendPayment(destination: offer, method: "offer", amount: amount), parse: fixed)
            }
        }
        // An offer without an amount takes any amount; other methods never reach Bark's parser.
        try await SendOfferAmount.require(SendPayment(destination: offer, method: "offer", amount: 700)) { _ in amountless }
        try await SendOfferAmount.require(SendPayment(destination: onchain, method: "onchain", amount: 700)) { _ in
            Issue.record("parsed"); return amountless
        }
    }

    @Test func anOfferForAnotherNetworkIsRefused() async {
        var quotes = 0
        let mismatched = request(1000, .networkMismatch)
        let model = model(parse: { _ in mismatched }) { quotes += 1; return reviewing($0) }
        model.amount = "700"
        await model.importRecipient(offer)
        #expect(model.stage == .amount)
        #expect(model.error == unableToResolve)
        #expect(!model.progress.recipientConfirmed && model.amount == "700")
        #expect(quotes == 0)
        await #expect(throws: SendFailure.invalid) {
            try await SendOfferAmount.require(SendPayment(destination: offer, method: "offer", amount: 1000)) { _ in mismatched }
        }
    }

    @Test func anOfferBarkCannotParseKeepsTheTypedAmount() async throws {
        var reviewed: [SendPayment] = []
        let model = model(parse: fiatPriced) { reviewed.append($0); return reviewing($0) }
        model.amount = "700"
        await model.importRecipient(offer)
        // Today's path: the keypad keeps its turn until Next, then the typed amount is reviewed.
        #expect(model.stage == .amount)
        #expect(model.parsed.isAmountEditable && model.progress.recipientConfirmed)
        await model.continueStage()
        #expect(model.stage == .review)
        #expect(reviewed == [SendPayment(destination: offer, method: "offer", amount: 700)])
        try await SendOfferAmount.require(SendPayment(destination: offer, method: "offer", amount: 700), parse: fiatPriced)
        // Only Bark's parse failure means "no amount": any other error refuses the offer.
        await #expect(throws: BarkError(message: "persistence", code: .persistence)) {
            try await SendOfferAmount.fixed(offer) { _ in throw BarkError(message: "persistence", code: .persistence) }
        }
        let refused = self.model(parse: { _ in throw BarkError(message: "persistence", code: .persistence) }) { reviewing($0) }
        refused.amount = "700"
        await refused.importRecipient(offer)
        #expect(refused.stage == .amount && refused.error == unableToResolve && !refused.progress.recipientConfirmed)
    }

    @Test func anEditWhileTheParseIsInFlightNeverAttachesItsAmount() async {
        let (gate, open) = AsyncStream<Void>.makeStream()
        let fixed = request(1000)
        var reviewed: [SendPayment] = []
        let model = model(parse: { _ in
            for await _ in gate { break }
            return fixed
        }) { reviewed.append($0); return reviewing($0) }
        model.amount = "700"
        let pending = Task { await model.importRecipient(offer) }
        while !model.busy { await Task.yield() }
        model.destination = onchain
        open.yield()
        await pending.value
        #expect(model.parsed.amount == nil && model.parsed.isAmountEditable)
        #expect(model.amount == "700" && model.error == nil && !model.progress.recipientConfirmed)
        #expect(reviewed.isEmpty && model.stage == .amount)
        // The new recipient keeps the typed amount.
        model.recipientFirst()
        await model.continueStage()
        #expect(reviewed == [SendPayment(destination: onchain, method: "onchain", amount: 700, source: .offchain)])
    }

    /// The real SDK on an offline regtest wallet (no Ark server): it reads the fixture offer's 1,000 sats, and refuses it as
    /// another network's.
    @Test(.timeLimit(.minutes(1))) func realSDKRefusesTheMainnetOfferOnRegtest() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let server = try LocalEsploraServer(.notFound)
        defer { server.stop() }
        let config = Config(serverAddress: "http://127.0.0.1:1", esploraAddress: server.url)
        let session = WalletSession(variant: .regtest, location: WalletDataLocation(variant: .regtest, documentsDirectory: root)) {
            mnemonic, datadir, _, _, create in
            let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                args: WalletOpenArgs(runDaemon: false, datadir: datadir, createIfNotExists: create,
                    createWithoutServer: true, skipRecovery: true), onchain: onchain)
            return .init(wallet: wallet, onchain: onchain)
        }
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
        let offer = offer
        let parsed = try await session.withOpenWallet { try await $0.wallet.parsePaymentRequest(input: offer) }
        #expect(parsed.amountSats == 1000)
        #expect(parsed.defaultOption == nil && parsed.options.first?.errors == [.networkMismatch])
        await #expect(throws: SendFailure.invalid) { try await session.sendOfferAmount(offer) }
        try await session.close()
    }
}
