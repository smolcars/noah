import Foundation
@testable import Noah
import Testing

struct ReceiveFixtureTests {
    struct RequestInput: Decodable {
        let arkAddress: String?
        let onchainAddress: String?
        let lightningAddress: String?
        let amountSat: Double?
        let lightningInvoice: String?
    }
    struct RequestExpected: Decodable { let uri: String?; let error: String?; let lightningAddressQR: String? }

    @Test func requestsMatchFixture() throws {
        for fixture in try Fixtures.load("receive-request", input: RequestInput.self, expected: RequestExpected.self) {
            let input = fixture.input
            guard let arkAddress = input.arkAddress, let onchainAddress = input.onchainAddress else {
                // Invoice amounts: fractional sats cannot enter the Int64 native API or its amount field.
                let valid = try Int64(exactly: #require(input.amountSat)).map { (try? ReceiveRequest.validate(amountSat: $0)) != nil } ?? false
                #expect(valid == (fixture.expected.error == nil), "\(fixture.name)")
                continue
            }
            let request = ReceiveRequest(arkAddress: arkAddress, onchainAddress: onchainAddress,
                                         amountSat: input.amountSat.map(Int64.init), lightningInvoice: input.lightningInvoice)
            #expect(request.uri(lightningAddress: input.lightningAddress) == fixture.expected.uri, "\(fixture.name)")
            #expect(input.lightningAddress.flatMap(LightningIdentity.lnurlQR(address:)) == fixture.expected.lightningAddressQR, "\(fixture.name)")
        }
    }

    struct FiatInput: Decodable {
        let operation: String
        let amount: String?
        let sats: Int64?
        let btcPrice: Double?
        let currency: String
        let locale: String
    }

    @Test func fiatMatchesReactNative() throws {
        for fixture in try Fixtures.load("fiat-currency", input: FiatInput.self, expected: String.self) {
            let input = fixture.input
            let actual: String
            switch input.operation {
            case "fiatToSats":
                actual = try String(FiatCurrency.fiatToSats(Double(input.amount ?? "") ?? .nan, btcPrice: #require(input.btcPrice)))
            case "satsToFiat":
                actual = try FiatCurrency.display(sats: #require(input.sats), btcPrice: #require(input.btcPrice), currency: input.currency,
                                                  locale: Locale(identifier: input.locale))
            default:
                let amount = try #require(input.amount)
                actual = try FiatCurrency.format(#require(Double(amount)), currency: input.currency, locale: Locale(identifier: input.locale))
            }
            #expect(actual == fixture.expected, "\(fixture.name): \(actual)")
        }
    }

    @Test func descriptionUsesUTF16Limit() throws {
        try ReceiveRequest.validate(amountSat: 1, description: String(repeating: "😀", count: 319))
        #expect(throws: ReceiveRequest.ValidationError.descriptionTooLong) {
            try ReceiveRequest.validate(amountSat: 1, description: String(repeating: "😀", count: 320))
        }
    }
}
