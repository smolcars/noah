import Foundation
@testable import Noah
import Testing

struct WalletScreenParityTests {
    struct SelectionRow: Decodable { var id: String; var amount: UInt64 }
    struct Selection: Decodable {
        var vtxos: [SelectionRow]
        var ids: Set<String>
    }
    struct Selected: Decodable { var ids: [String]; var amount: UInt64 }
    @Test func selectionTracksCurrentWalletRows() throws {
        for item in try Fixtures.load("wallet-selection", input: Selection.self, expected: Selected.self) {
            let rows = item.input.vtxos.map { row in
                VtxoModel(id: row.id, amount: row.amount, expiry: 200, state: "Spendable", kind: "Pubkey", exitDepth: 1,
                          exitWeight: 400, registered: true, serverPubkey: "public", exitDelta: 144, anchorPoint: "anchor:0", point: "point:0", expiring: false,
                          tip: 100)
            }
            let selected = OnchainDisplay.currentSelection(rows, ids: item.input.ids)
            #expect(selected.map(\.id) == item.expected.ids, "\(item.name)")
            #expect(selected.reduce(0) { $0 + $1.amount } == item.expected.amount, "\(item.name)")
        }
    }

    @Test func recurringInputLimitsPreserveUnicodeBoundaries() {
        #expect(RecurringPaymentEditorView.limitedText(String(repeating: "a", count: 65), limit: 64).utf16.count == 64)
        #expect(RecurringPaymentEditorView.limitedText(String(repeating: "a", count: 139) + "🌱", limit: 140).utf16.count == 139)
        #expect(RecurringPaymentEditorView.limitedText(String(repeating: "a", count: 62) + "🌱", limit: 64).utf16.count == 64)
    }

    @Test func exitRemainingNeverInventsAnUnknownTip() {
        #expect(OnchainDisplay.remaining(current: nil, target: 101, locale: "en") == "Unknown")
        #expect(OnchainDisplay.remaining(current: 100, target: nil, locale: "en") == "Unknown")
        #expect(OnchainDisplay.remaining(current: 100, target: 101, locale: "en") == "1 block")
        #expect(OnchainDisplay.remaining(current: 100, target: 102, locale: "en") == "2 blocks")
        #expect(OnchainDisplay.remaining(current: 100, target: 100, locale: "en") == "Now")
        #expect(OnchainDisplay.remaining(current: 101, target: 100, locale: "en") == "Now")
    }

    @Test func recurringAmountUsesPreferenceWithoutNarrowingUnsupportedAmounts() {
        var payment = RecurringScheduleTests.schedule()
        payment.amountSat = 1234
        #expect(payment.formattedAmount(unit: .bip177, locale: Locale(identifier: "en_US")) == "₿\u{00A0}1,234")
        #expect(payment.formattedAmount(unit: .sats, locale: Locale(identifier: "en_US")) == "1,234 sats")
        #expect(payment.formattedAmount(unit: .bip177, locale: Locale(identifier: "sv_SE")) == BitcoinAmount.format(
            sats: 1234,
            unit: .bip177,
            locale: Locale(identifier: "sv_SE")))
        payment.amountSat = UInt64.max
        #expect(payment.formattedAmount(unit: .bip177, locale: Locale(identifier: "en_US")) == "₿\u{00A0}18,446,744,073,709,551,615")
        #expect(payment.amountSat == UInt64.max)
    }
}
