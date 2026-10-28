import BarkBoltFFI
@testable import Noah
import Testing

struct RecurringFeePolicyTests {
    struct Tier: Decodable { let expiryBlocksThreshold: UInt32; let ppm: UInt64 }
    struct Schedule: Decodable {
        let minFeeSats: UInt64
        let baseFeeSats: UInt64
        let ppmExpiryTable: [Tier]
        var fees: LightningSendFees {
            .init(minFeeSats: minFeeSats, baseFeeSats: baseFeeSats, ppmExpiryTable: ppmExpiryTable.map {
                .init(expiryBlocksThreshold: $0.expiryBlocksThreshold, ppm: $0.ppm)
            })
        }
    }
    struct Input: Decodable { let amountSat: UInt64; let method: String; let quoteFeeSat: UInt64; let schedule: Schedule? }
    struct Expected: Decodable { let worstCaseFeeSat: UInt64?; let capSat: UInt64?; let autoPay: Bool; let reason: String }
    @Test func sharedFeeContract() throws {
        let cases = try Fixtures.load("recurring-fee-cap", input: Input.self, expected: Expected.self)
        #expect(cases.count == 24)
        for fixture in cases {
            let i = fixture.input, e = fixture.expected
            let result = RecurringFeePolicy.evaluate(amount: i.amountSat, method: i.method, quoteFee: i.quoteFeeSat, schedule: i.schedule?.fees)
            #expect(result.worstCaseFee == e.worstCaseFeeSat, "\(fixture.name)")
            #expect(result.cap == e.capSat, "\(fixture.name)")
            #expect(result.autoPay == e.autoPay, "\(fixture.name)")
            #expect(result.reason.rawValue == e.reason, "\(fixture.name)")
        }
    }
    @Test func wideProductDoesNotSaturateWhenFinalFeeFits() {
        let result = RecurringFeePolicy.evaluate(amount: .max, method: "lightning", quoteFee: 0, schedule: nil)
        #expect(result.worstCaseFee == 92_233_720_368_547_759)
        #expect(result.cap == 184_467_440_737_095_518)
        #expect(result.autoPay)
    }
    @Test(arguments: [UInt64(1_000_001), UInt64.max]) func proportionalOverflowSaturates(ppm: UInt64) {
        let result = RecurringFeePolicy.evaluate(amount: .max, method: "lightning", quoteFee: 0,
                                                 schedule: .init(minFeeSats: 0, baseFeeSats: 0, ppmExpiryTable: [.init(expiryBlocksThreshold: 0, ppm: ppm)]))
        #expect(result.worstCaseFee == .max && result.cap == UInt64.max / 50)
        #expect(!result.autoPay && result.reason == .scheduleExceedsCap)
    }
    @Test func baseAdditionAndDoublingSaturate() {
        let result = RecurringFeePolicy.evaluate(amount: .max, method: "lightning", quoteFee: .max,
                                                 schedule: .init(minFeeSats: 0, baseFeeSats: .max, ppmExpiryTable: [.init(expiryBlocksThreshold: 0, ppm: 1)]))
        #expect(result.worstCaseFee == .max && result.cap == UInt64.max / 50)
        #expect(!result.autoPay && result.reason == .quoteExceedsCap)
        let doubled = RecurringFeePolicy.evaluate(amount: .max, method: "lightning", quoteFee: 0,
                                                  schedule: .init(minFeeSats: UInt64.max / 2 + 1, baseFeeSats: 0, ppmExpiryTable: []))
        #expect(doubled.cap == UInt64.max / 50 && !doubled.autoPay)
    }
}
