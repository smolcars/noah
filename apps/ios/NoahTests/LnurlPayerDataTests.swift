import Foundation
@testable import Noah
import Testing

/// LUD-18 payer data on Send: the shared fixtures, the callback request and To's stop and notice.
@MainActor struct LnurlPayerDataTests {
    struct Probe: Decodable {
        let payerData: LnurlPayerData.Advertised?
    }
    struct CallbackInput: Decodable {
        let callback: String
        let amountMsat: UInt64
        let comment: String
        // swiftlint:disable:next discouraged_optional_collection - null means no payer data, as the fixture says
        let payerData: [String: String]?
        let paymentOption: String?
    }
    struct CallbackExpected: Decodable {
        let params: [String: String]
        // swiftlint:disable:next discouraged_optional_collection - null means no payerdata parameter
        let payerData: [String: String]?
    }

    /// The fixture's outcome for one case: the payer data sent, or which error stopped it.
    static func outcome(_ input: [String: Any]) throws -> (payerData: [String: String], error: String?) {
        // As a pay request's `payerData` is decoded: never failing the lookup.
        let probe = try JSONDecoder().decode(Probe.self, from: JSONSerialization.data(withJSONObject: input.filter { $0.key == "payerData" }))
        let identity = input["identity"] as? [String: Any]
        let saved = LnurlPayerIdentity(name: identity?["name"] as? String, identifier: identity?["identifier"] as? String)
        do {
            return try (LnurlPayerData.build(probe.payerData?.requirements ?? [:], available: saved.fields), nil)
        } catch SendFailure.payerDataUnsupported {
            return ([:], "unsupported")
        } catch SendFailure.payerDataMissing {
            return ([:], "missing")
        }
    }

    @Test func payerDataFixture() throws {
        let file = try #require(JSONSerialization.jsonObject(with: Fixtures.data("lnurl-pay-payer-data")) as? [String: Any])
        let cases = try #require(file["cases"] as? [[String: Any]])
        #expect(cases.count == 30)
        for fixture in cases {
            let name = try #require(fixture["name"] as? String)
            let input = try #require(fixture["input"] as? [String: Any])
            let expected = try #require(fixture["expected"] as? [String: Any])
            let outcome = try Self.outcome(input)
            #expect(outcome.error == expected["error"] as? String, "\(name)")
            #expect(outcome.payerData == (expected["payerData"] as? [String: String] ?? [:]), "\(name)")
        }
    }

    /// A pay request's amount range and `paymentOptions`, decoded as discovery decodes them.
    struct OptionsProbe: Decodable {
        let minSendable: UInt64
        let maxSendable: UInt64
        let paymentOptions: SendArkOption.Advertised?
    }

    @Test func paymentOptionsFixture() throws {
        let file = try #require(JSONSerialization.jsonObject(with: Fixtures.data("lnurl-pay-options")) as? [String: Any])
        let cases = try #require(file["cases"] as? [[String: Any]])
        #expect(cases.count == 17)
        for fixture in cases {
            let name = try #require(fixture["name"] as? String)
            let input = try #require(fixture["input"] as? [String: Any])
            let probe = try JSONDecoder().decode(OptionsProbe.self, from: JSONSerialization.data(withJSONObject: input))
            let option = SendArkOption.first(probe.paymentOptions, minMsat: probe.minSendable, maxMsat: probe.maxSendable)
            let expected = (fixture["expected"] as? [String: Any]).map {
                SendArkOption(id: $0["id"] as? String ?? "", minMsat: $0["minMsat"] as? UInt64 ?? 0,
                              maxMsat: $0["maxMsat"] as? UInt64 ?? 0)
            }
            #expect(option == expected, "\(name)")
        }
    }

    /// The query as a form decoder reads it (`+` is a space), as LNURL services do.
    static func formDecoded(_ url: URL) throws -> [String: String] {
        let query = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery)
        var params: [String: String] = [:]
        for pair in query.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).map {
                $0.replacingOccurrences(of: "+", with: " ").removingPercentEncoding
            }
            let key = try #require(parts[0])
            params[key] = try #require(parts.count == 2 ? parts[1] : "")
        }
        return params
    }

    @Test func callbackFixture() throws {
        let cases = try Fixtures.load("lnurl-pay-callback", input: CallbackInput.self, expected: CallbackExpected.self)
        #expect(cases.count == 10)
        for fixture in cases {
            let callback = try #require(URL(string: fixture.input.callback))
            let url = try SendRecipientService.callbackURL(callback, msat: fixture.input.amountMsat, comment: fixture.input.comment,
                                                           payerData: fixture.input.payerData ?? [:], paymentOption: fixture.input.paymentOption)
            var params = try Self.formDecoded(url)
            let payerData = try params.removeValue(forKey: "payerdata").map { try JSONDecoder().decode([String: String].self, from: Data($0.utf8)) }
            #expect(params == fixture.expected.params, "\(fixture.name)")
            #expect(payerData == fixture.expected.payerData, "\(fixture.name)")
        }
    }

    /// A Lightning address asking for `payerData` (field to mandatory), then answering its callback with `invoice`.
    actor Endpoint {
        let payerData: [String: Bool]
        let invoice: String
        var urls: [URL] = []
        init(payerData: [String: Bool], invoice: String) {
            self.payerData = payerData
            self.invoice = invoice
        }
        func fetch(_ request: URLRequest) throws -> (Data, URLResponse) {
            let url = try #require(request.url)
            urls.append(url)
            let discovery: [String: Any] = ["tag": "payRequest", "callback": "https://pay.example.com/callback", "minSendable": 1000,
                                            "maxSendable": 10_000_000, "commentAllowed": 0, "metadata": "[]",
                                            "payerData": payerData.mapValues { ["mandatory": $0] }]
            let body: [String: Any] = urls.count == 1 ? discovery : ["pr": invoice]
            return try (JSONSerialization.data(withJSONObject: body), #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)))
        }
    }
    static let ada = LnurlPayerIdentity(name: "Ada", identifier: "ada@noahwallet.io")

    /// The callback's query after quoting a 1,000-sat `payment` to an address asking for `payerData`, or the quote's error.
    static func quoted(_ payment: SendPayment, payerData: [String: Bool]) async throws -> (params: [String: String], requests: Int) {
        let endpoint = try Endpoint(payerData: payerData, invoice: SendLnurlSafetyTests().invoice())
        do {
            _ = try await SendRecipientService.invoice(payment, session: WalletSession(variant: .regtest), fetch: { try await endpoint.fetch($0) })
        } catch {
            #expect(await endpoint.urls.count == 1, "the callback is never requested")
            throw error
        }
        let urls = await endpoint.urls
        return try (formDecoded(#require(urls.last)), urls.count)
    }
    static func payment(sharing payerData: [String: String]) -> SendPayment {
        var payment = SendPayment(destination: "bob@example.com", method: "lnurl", amount: 1000)
        payment.payerData = payerData.isEmpty ? nil : payerData
        return payment
    }

    @Test func callbackCarriesPayerDataAndNoEmptyComment() async throws {
        let quote = try await Self.quoted(Self.payment(sharing: Self.ada.fields), payerData: ["name": false, "identifier": true])
        #expect(quote.requests == 2)
        var params = quote.params
        let encoded = params.removeValue(forKey: "payerdata")
        let payerData = try JSONDecoder().decode([String: String].self, from: Data(#require(encoded).utf8))
        #expect(payerData == ["name": "Ada", "identifier": "ada@noahwallet.io"])
        #expect(params == ["amount": "1000000"])
    }

    @Test func quoteSendsOnlyWhatToShowed() async throws {
        // A lookup asking for more than To showed gets only what To showed; one with nothing shown gets nothing.
        var (params, _) = try await Self.quoted(Self.payment(sharing: ["name": "Ada"]), payerData: ["name": false, "identifier": false])
        let encoded = params.removeValue(forKey: "payerdata")
        let payerData = try JSONDecoder().decode([String: String].self, from: Data(#require(encoded).utf8))
        #expect(payerData == ["name": "Ada"])
        let (nothingShown, _) = try await Self.quoted(Self.payment(sharing: [:]), payerData: ["name": false, "identifier": false])
        #expect(nothingShown == ["amount": "1000000"])
    }

    @Test func requiredPayerDataNeverReachesTheCallback() async throws {
        await #expect(throws: SendFailure.payerDataUnsupported) {
            _ = try await Self.quoted(Self.payment(sharing: Self.ada.fields), payerData: ["email": true])
        }
        // Mandatory since To's lookup: To never showed the address, so it isn't sent.
        await #expect(throws: SendFailure.payerDataMissing) {
            _ = try await Self.quoted(Self.payment(sharing: ["name": "Ada"]), payerData: ["identifier": true])
        }
    }

    private func makeModel(payerData: LnurlPayerData.Requirements, identity: LnurlPayerIdentity = Self.ada,
                           reviewed: @escaping (SendPayment) -> Void = { _ in }) -> SendModel {
        SendModel(
            controller: SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
            resolveRecipient: { .init(destination: $0, minMsat: 1000, maxMsat: 1_000_000_000, commentAllowed: 0, payerData: payerData) },
            quotePayment: { payment in
                reviewed(payment)
                return .init(amount: payment.amount, fee: 0, total: payment.amount)
            },
            payerIdentity: { identity })
    }

    @Test func payerDataAddressStopsOnToWithItsNotice() async {
        var reviewed: SendPayment?
        let model = makeModel(payerData: ["name": .init(mandatory: false), "identifier": .init(mandatory: false)]) { reviewed = $0 }
        model.amount = "1000"
        model.arkBalance = 10000
        // Scanned or pasted from Amount: To shows what Noah will share before the quote calls the address's callback.
        await model.importRecipient("bob@example.com")
        #expect(model.stage == .recipient && !model.progress.recipientConfirmed && reviewed == nil)
        #expect(model.payerShare == .nameAndAddress && model.error == nil)
        await model.continueStage()
        await model.continueStage()
        #expect(reviewed?.destination == "bob@example.com" && model.showReview)
        // The payment carries what To showed, for the quote to send.
        #expect(reviewed?.payerData == ["name": "Ada", "identifier": "ada@noahwallet.io"])
    }

    @Test func payerShareNamesOnlyWhatIsSent() async {
        let nameOnly = makeModel(payerData: ["name": .init(mandatory: false), "email": .init(mandatory: false)])
        nameOnly.destination = "bob@example.com"
        await nameOnly.previewRecipient()
        #expect(nameOnly.payerShare == .nameOnly)
        let nothingSaved = makeModel(payerData: ["name": .init(mandatory: false)], identity: .init(name: " ", identifier: nil))
        nothingSaved.destination = "bob@example.com"
        await nothingSaved.previewRecipient()
        #expect(nothingSaved.matchingRoute != nil && nothingSaved.payerShare == nil)
    }

    @Test func requiredPayerDataStopsOnToWithItsError() async {
        var reviewed: SendPayment?
        let model = makeModel(payerData: ["identifier": .init(mandatory: true)], identity: .init(name: "Ada", identifier: nil)) { reviewed = $0 }
        model.amount = "1000"
        model.arkBalance = 10000
        await model.importRecipient("bob@example.com")
        #expect(model.stage == .recipient && reviewed == nil && model.payerShare == nil)
        #expect(model.error == SendFailure.payerDataMissing.errorDescription)
        // Next again keeps To and its error.
        await model.continueStage()
        #expect(model.stage == .recipient && reviewed == nil && model.error == SendFailure.payerDataMissing.errorDescription)
    }

    @Test func requiredPayerDataKeepsATypedAddressOnTo() async {
        var reviewed: SendPayment?
        let model = makeModel(payerData: ["identifier": .init(mandatory: true)], identity: .init(name: "Ada", identifier: nil)) { reviewed = $0 }
        model.amount = "1000"
        model.arkBalance = 10000
        model.recipientFirst()
        // Typed: the preview looked it up while typing, so Next doesn't look it up again.
        model.destination = "bob@example.com"
        await model.previewRecipient()
        await model.continueStage()
        #expect(model.stage == .recipient && reviewed == nil && model.error == SendFailure.payerDataMissing.errorDescription)
    }
}
