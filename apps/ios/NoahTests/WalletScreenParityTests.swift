import Foundation
@testable import Noah
import Testing

struct WalletScreenParityTests {
    @Test func recurringInputLimitsPreserveUnicodeBoundaries() {
        #expect(RecurringPaymentEditorView.limitedText(String(repeating: "a", count: 65), limit: 64).utf16.count == 64)
        #expect(RecurringPaymentEditorView.limitedText(String(repeating: "a", count: 139) + "🌱", limit: 140).utf16.count == 139)
        #expect(RecurringPaymentEditorView.limitedText(String(repeating: "a", count: 62) + "🌱", limit: 64).utf16.count == 64)
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
