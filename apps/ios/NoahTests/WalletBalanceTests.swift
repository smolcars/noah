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
    @Test func homeDetailsAndHiding() throws {
        for fixture in try Fixtures.load("home-balance-details", input: Input.self, expected: [WalletBalance.Detail].self) {
            let balance = WalletBalance(ark: fixture.input.ark, onchain: fixture.input.onchain)
            #expect(balance.details == fixture.expected)
            #expect(balance.pendingRoundMessage(amount: "••••")?.hasPrefix("•••• pending") == true)
            var ark = balance.ark
            ark.pendingInRound = 0
            #expect(WalletBalance(ark: ark, onchain: balance.onchain).pendingRoundMessage(amount: "0 sats") == nil)
            for row in balance.details {
                for unit in [BitcoinAmountUnit.sats, .bip177] {
                    #expect(WalletBalance.display(row.sats, hidden: true, unit: unit, locale: .init(identifier: "en_US")) == "••••")
                    #expect(WalletBalance.display(row.sats, hidden: false, unit: unit, locale: .init(identifier: "en_US")) != "••••")
                }
            }
        }
    }
}
