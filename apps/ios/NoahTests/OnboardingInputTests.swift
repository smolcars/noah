import Foundation
import Testing
@testable import Noah

@MainActor
struct OnboardingNameTests {
    final class Lookups: @unchecked Sendable {
        private let lock = NSLock()
        private var names: [String] = []
        let taken: Set<String>
        var failing = false
        init(taken: Set<String> = []) { self.taken = taken }
        var seen: [String] { lock.withLock { names } }
        func lookup(_ name: String) throws -> Bool {
            lock.withLock { names.append(name) }
            if failing { throw URLError(.notConnectedToInternet) }
            return taken.contains(name)
        }
    }

    private func model(_ lookups: Lookups, assigned: String? = nil) -> NameAvailability {
        let model = NameAvailability(debounce: .zero) { try lookups.lookup($0) }
        model.assigned = assigned
        return model
    }

    private func type(_ text: String, into model: NameAvailability) async {
        model.text = text
        // The view calls this on every change, including the normalizing rewrite.
        while !model.textChanged() {}
        await model.settle()
    }

    @Test func normalizesToLowercaseLettersDigitsAndThreeSymbols() {
        #expect(NameAvailability.normalize("Maya Lopez!") == "mayalopez")
        #expect(NameAvailability.normalize("sat_oshi.nak-amoto") == "sat_oshi.nak-amoto")
        #expect(NameAvailability.normalize("ÉCOLE🙂") == "cole")
        #expect(NameAvailability.normalize(String(repeating: "a", count: 40)).count == 24)
    }

    @Test func walksHintShortCheckingAndAvailable() async {
        let lookups = Lookups()
        let name = model(lookups)
        #expect(name.status == .hint)
        await type("ma", into: name)
        #expect(name.status == .tooShort)
        #expect(!name.canContinue)
        #expect(lookups.seen.isEmpty)
        name.text = "Maya"
        #expect(!name.textChanged()) // Rewritten to lowercase first.
        #expect(name.text == "maya")
        #expect(name.textChanged())
        #expect(name.status == .checking)
        await name.settle()
        #expect(name.status == .available)
        #expect(name.canContinue)
        #expect(lookups.seen == ["maya"])
        await type("", into: name)
        #expect(name.status == .hint)
    }

    @Test func takenOffersThreeSuggestions() async {
        let name = model(Lookups(taken: ["satoshi"]))
        await type("satoshi", into: name)
        #expect(name.status == .taken)
        #expect(!name.canContinue)
        #expect(name.suggestions == ["satoshi21", "satoshi.btc", "itssatoshi"])
        name.use("satoshi21")
        await name.settle()
        #expect(name.text == "satoshi21")
        #expect(name.status == .available)
        #expect(name.suggestions.isEmpty)
    }

    @Test func suggestionsStayWithinTheLengthLimit() {
        let long = String(repeating: "a", count: 22)
        #expect(NameAvailability.suggestions(for: long) == [long + "21"])
    }

    @Test func assignedNameNeedsNoLookupAndFailedLookupsDontBlock() async {
        let lookups = Lookups()
        let name = model(lookups, assigned: "brave-otter")
        await type("brave-otter", into: name)
        #expect(name.status == .available)
        #expect(lookups.seen.isEmpty)
        lookups.failing = true
        await type("maya", into: name)
        #expect(name.status == .available) // Saving is the authority.
    }

    @Test func serverRefusalMarksTheNameTaken() async {
        let name = model(Lookups())
        await type("maya", into: name)
        name.markTaken()
        #expect(name.status == .taken)
        #expect(name.suggestions.count == 3)
    }

    @Test func onlyTheLatestTypingDecides() async {
        let lookups = Lookups(taken: ["noah"])
        let name = NameAvailability(debounce: .milliseconds(200)) { try lookups.lookup($0) }
        name.text = "noah"; name.textChanged()
        name.text = "noahs"; name.textChanged()
        await name.settle()
        #expect(lookups.seen == ["noahs"]) // The first check was debounced away.
        #expect(name.status == .available)
    }
}

struct OnboardingEmailEntryTests {
    @Test func validatesAddresses() {
        #expect(EmailEntry.isValid("maya@gmail.com"))
        #expect(EmailEntry.isValid("m.y+tag@sub.example.io"))
        #expect(!EmailEntry.isValid("maya@gmail"))
        #expect(!EmailEntry.isValid("maya@gmail.c"))
        #expect(!EmailEntry.isValid("@gmail.com"))
        #expect(!EmailEntry.isValid("maya gmail.com"))
        #expect(!EmailEntry.isValid("maya@gmial.com")) // A known typo is offered as a fix instead.
    }

    @Test func normalizesSpacesAndCase() {
        #expect(EmailEntry.normalize(" Maya@GMail.com ") == "maya@gmail.com")
    }

    @Test func completesDomains() {
        #expect(EmailEntry.hint(for: "maya") == .none)
        #expect(EmailEntry.hint(for: "@gm") == .none)
        #expect(EmailEntry.hint(for: "maya@") == .complete(["maya@gmail.com", "maya@icloud.com", "maya@outlook.com"]))
        #expect(EmailEntry.hint(for: "maya@g") == .complete(["maya@gmail.com"]))
        #expect(EmailEntry.hint(for: "maya@hot") == .complete(["maya@hotmail.com"]))
        #expect(EmailEntry.hint(for: "maya@gmail.com") == .none)
        #expect(EmailEntry.hint(for: "maya@unknown.org") == .none)
    }

    @Test func suggestsTypoFixes() {
        #expect(EmailEntry.hint(for: "maya@gmial.com") == .didYouMean("maya@gmail.com"))
        #expect(EmailEntry.hint(for: "maya@icloud.co") == .didYouMean("maya@icloud.com"))
        #expect(EmailEntry.hint(for: "maya@hotmial.com") == .didYouMean("maya@hotmail.com"))
    }
}

@MainActor
struct OnboardingCodeTests {
    final class Server {
        var sent: [String] = []
        var verified: [String] = []
        var alreadyVerified = false
        var sendFails = false
        var validCode = "000000"
    }

    final class Clock { var now = Date(timeIntervalSince1970: 1_000) }

    private func model(_ server: Server, clock: Clock = Clock()) -> EmailVerification {
        EmailVerification(send: { email in
            if server.sendFails { throw URLError(.timedOut) }
            server.sent.append(email)
            return server.alreadyVerified
        }, verify: { code, _ in
            server.verified.append(code)
            guard code == server.validCode else { throw IdentityValidationError("Invalid or expired verification code.") }
        }, now: { clock.now }, clearDelay: .zero)
    }

    private func enter(_ code: String, _ email: EmailVerification) async {
        email.code = code
        await email.codeChanged()
    }

    @Test func sendsOnlyValidAddresses() async {
        let server = Server()
        let email = model(server)
        email.text = "maya@gmial.com"
        await email.sendCode()
        #expect(server.sent.isEmpty)
        #expect(email.stage == .entry)
        email.text = " Maya@Gmail.com"
        email.textChanged()
        await email.sendCode()
        #expect(server.sent == ["maya@gmail.com"])
        #expect(email.stage == .code)
        #expect(email.email == "maya@gmail.com")
    }

    @Test func fillsAndVerifiesAtSixDigits() async {
        let server = Server()
        let email = model(server)
        email.text = "maya@gmail.com"
        await email.sendCode()
        await enter("12a3", email)
        #expect(email.code == "123")
        #expect(server.verified.isEmpty)
        await enter("0000001", email) // Autofill can overshoot; six digits verify.
        #expect(server.verified == ["000000"])
        #expect(email.stage == .verified)
    }

    @Test func wrongCodeFailsThenClears() async {
        let server = Server()
        let email = model(server)
        email.text = "maya@gmail.com"
        await email.sendCode()
        await enter("123456", email)
        #expect(email.failures == 1)
        #expect(email.error == EmailVerification.wrongCodeMessage)
        #expect(email.code.isEmpty) // Cleared after the shake.
        #expect(email.stage == .wrongCode)
        await enter("0", email)
        #expect(email.stage == .code)
        #expect(email.error == nil)
        await enter("000000", email)
        #expect(email.stage == .verified)
    }

    @Test func resendCountsDownFromThirty() async {
        let server = Server(), clock = Clock()
        let email = model(server, clock: clock)
        email.text = "maya@gmail.com"
        await email.sendCode()
        #expect(email.resendRemaining(at: clock.now) == 30)
        #expect(email.resendRemaining(at: clock.now.addingTimeInterval(0.4)) == 30)
        #expect(email.resendRemaining(at: clock.now.addingTimeInterval(29.5)) == 1)
        await email.resend()
        #expect(server.sent.count == 1) // Too early.
        clock.now = clock.now.addingTimeInterval(30)
        #expect(email.resendRemaining(at: clock.now) == 0)
        await email.resend()
        #expect(server.sent.count == 2)
        #expect(email.resendRemaining(at: clock.now) == 30)
    }

    @Test func alreadyVerifiedSkipsTheCode() async {
        let server = Server()
        server.alreadyVerified = true
        let email = model(server)
        email.text = "maya@gmail.com"
        await email.sendCode()
        #expect(email.stage == .verified)
        #expect(email.resendAvailable == nil)
        #expect(server.verified.isEmpty)
    }

    @Test func sendFailureStaysOnEntryWithAMessage() async {
        let server = Server()
        server.sendFails = true
        let email = model(server)
        email.text = "maya@gmail.com"
        await email.sendCode()
        #expect(email.stage == .entry)
        #expect(email.error == "Failed to send verification code. Please try again.")
    }

    /// Holds a request until the test opens it.
    @MainActor final class Gate {
        private var continuation: CheckedContinuation<Void, Never>?
        private var opened = false
        func wait() async {
            if opened { return }
            await withCheckedContinuation { continuation = $0 }
        }
        func open() {
            opened = true
            continuation?.resume()
            continuation = nil
        }
    }

    @Test func aResendInFlightBlocksEditOtherSendsAndVerification() async {
        let gate = Gate(), clock = Clock()
        var sent: [String] = [], verified: [String] = []
        var holdSends = false
        let email = EmailVerification(send: { address in
            sent.append(address)
            if holdSends { await gate.wait() }
            return false
        }, verify: { code, _ in verified.append(code) }, now: { clock.now }, clearDelay: .zero)
        email.text = "first@gmail.com"
        await email.sendCode()
        clock.now = clock.now.addingTimeInterval(30)

        holdSends = true
        let resend = Task { await email.resend() }
        for _ in 0..<1000 where !email.isSending { await Task.yield() }
        #expect(email.isSending)

        // Edit can't start another address while the first request is pending…
        email.edit()
        #expect(email.stage == .code)
        email.text = "second@gmail.com"
        await email.sendCode()
        // …nor can a second resend or a verification overlap it.
        await email.resend()
        email.code = "000000"
        await email.codeChanged()
        #expect(sent == ["first@gmail.com", "first@gmail.com"])
        #expect(verified.isEmpty)
        #expect(email.stage == .code)

        gate.open()
        await resend.value
        #expect(!email.isSending)
        #expect(verified == ["000000"]) // Checked once the resend settled.
        #expect(email.stage == .verified)
        #expect(sent == ["first@gmail.com", "first@gmail.com"])
    }

    @Test func editReturnsToTheAddress() async {
        let email = model(Server())
        email.text = "maya@gmail.com"
        await email.sendCode()
        await enter("12", email)
        email.edit()
        #expect(email.stage == .entry)
        #expect(email.code.isEmpty)
        #expect(email.text == "maya@gmail.com")
    }
}
