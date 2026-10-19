import Testing

@testable import Noah

struct WalletBalanceTests {
    struct Input: Decodable {
        let ark: WalletBalance.Ark
        let onchain: WalletBalance.Onchain
    }

    struct Expected: Decodable {
        let total: UInt64
        let onchain: UInt64
        let offchain: UInt64
        let pending: UInt64
    }

    @Test func matchesReactNativeCalculation() throws {
        for testCase in try Fixtures.load("balance", input: Input.self, expected: Expected.self) {
            let balance = WalletBalance(ark: testCase.input.ark, onchain: testCase.input.onchain)
            #expect(balance.totalSats == testCase.expected.total, "\(testCase.name)")
            #expect(balance.onchainSats == testCase.expected.onchain, "\(testCase.name)")
            #expect(balance.offchainSats == testCase.expected.offchain, "\(testCase.name)")
            #expect(balance.pendingSats == testCase.expected.pending, "\(testCase.name)")
        }
    }
}
