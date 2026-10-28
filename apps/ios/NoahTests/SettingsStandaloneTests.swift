@testable import Noah
import Testing

/// A Settings page pushed outside Home (Receive's Profile link) still navigates: My code included.
@MainActor struct SettingsStandaloneTests {
    @Test func myCodeAndPagesPushAndBackDismisses() {
        var pushed: [SettingsPage] = []
        var myCode = 0
        var dismissed = 0
        var open = false
        let navigator = SettingsNavigator.standalone(push: { pushed.append($0) }, myCode: { myCode += 1; open = true },
                                                     pushed: { open || !pushed.isEmpty }, dismiss: { dismissed += 1 })
        #expect(!navigator.live)
        navigator.leave(.profile)
        #expect(dismissed == 1, "a finished save on the top page goes back")
        navigator.myCode()
        #expect(myCode == 1)
        navigator.leave(.profile)
        #expect(dismissed == 1, "not while My code is open over it")
        navigator.open(.lightningAddress)
        navigator.select(.email)
        #expect(pushed == [.lightningAddress, .email])
        navigator.back()
        navigator.close()
        #expect(dismissed == 3)
    }
}
