import Foundation
import Testing
@testable import Noah

struct ReceiveFixtureTests {
    struct RequestInput: Decodable {
        let arkAddress: String
        let onchainAddress: String
        let amountSat: Double?
        let lightningInvoice: String?
    }
    struct RequestExpected: Decodable { let uri: String?; let error: String? }

    @Test func requestsMatchReactNative() throws {
        for fixture in try Fixtures.load("receive-request", input: RequestInput.self, expected: RequestExpected.self) {
            let input = fixture.input
            if let amount = input.amountSat, amount.rounded() != amount {
                // Fractional sats cannot enter the Int64 native API or its amount field.
                #expect(Int64(String(amount)) == nil)
                #expect(fixture.expected.error != nil)
                continue
            }
            let request = ReceiveRequest(arkAddress: input.arkAddress, onchainAddress: input.onchainAddress,
                                         amountSat: input.amountSat.map(Int64.init), lightningInvoice: input.lightningInvoice)
            if fixture.expected.error != nil {
                #expect(throws: ReceiveRequest.ValidationError.invalidAmount) { try request.uri() }
            } else {
                #expect(try request.uri() == fixture.expected.uri, "\(fixture.name)")
            }
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
                actual = String(FiatCurrency.fiatToSats(Double(input.amount ?? "") ?? .nan, btcPrice: input.btcPrice!))
            case "satsToFiat":
                actual = FiatCurrency.display(sats: input.sats!, btcPrice: input.btcPrice!, currency: input.currency,
                                              locale: Locale(identifier: input.locale))
            default:
                actual = FiatCurrency.format(Double(input.amount!)!, currency: input.currency, locale: Locale(identifier: input.locale))
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
