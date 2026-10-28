import Foundation
@testable import Noah
import Testing
import UIKit

/// Settings → Recovery phrase: no word before authentication, every time; cleared on background;
/// copy is local-only and expires after two minutes.
@MainActor struct RecoveryPhraseGateTests {
    final class Fake {
        var result = RecoveryPhraseAuthentication.authenticated
        var noScreenLock = false
        var generation = 0
        var reads = 0
        var prompts = 0
        let phrase = "sample phrase only never use this example test word copy nothing real".split(separator: " ").map(String.init)

        @MainActor func gate() -> PhraseGate {
            PhraseGate(dependencies: .init(authenticate: { _ in self.prompts += 1; return self.result },
                                           hasNoScreenLock: { self.noScreenLock },
                                           words: { self.reads += 1; return self.phrase },
                                           generation: { self.generation }))
        }
    }

    @Test func wordsAreReadOnlyAfterAuthentication() async {
        let fake = Fake()
        let gate = fake.gate()
        #expect(gate.words.isEmpty && gate.state == .gated && fake.reads == 0)
        fake.result = .denied
        await gate.show(reason: "")
        #expect(gate.words.isEmpty && gate.state == .gated && fake.reads == 0)
        fake.result = .authenticated
        await gate.show(reason: "")
        #expect(gate.words == fake.phrase && gate.state == .shown && fake.reads == 1)
    }

    @Test func backgroundClearsTheWordsAndTheNextVisitAsksAgain() async {
        let fake = Fake()
        let gate = fake.gate()
        await gate.show(reason: "")
        gate.clear()
        #expect(gate.words.isEmpty && gate.state == .gated)
        await gate.show(reason: "")
        #expect(fake.prompts == 2)
    }

    @Test func aResultForAnEarlierVisitIsDropped() async {
        let fake = Fake()
        let gate = fake.gate()
        // The app backgrounds (the lock's generation moves on) while Face ID is up.
        let late = PhraseGate(dependencies: .init(authenticate: { _ in fake.generation += 1; return .authenticated },
                                                  hasNoScreenLock: { false }, words: { fake.phrase }, generation: { fake.generation }))
        await late.show(reason: "")
        #expect(late.words.isEmpty && late.state == .gated)
        await gate.show(reason: "", active: { false })
        #expect(gate.words.isEmpty && gate.state == .gated)
    }

    @Test func noScreenLockWarnsFirstAndShowAnywayRechecks() async {
        let fake = Fake()
        fake.result = .noScreenLock
        fake.noScreenLock = true
        let gate = fake.gate()
        await gate.show(reason: "")
        #expect(gate.state == .noScreenLock && gate.words.isEmpty && fake.reads == 0)
        await gate.showAnyway(reason: "")
        #expect(gate.state == .shown && gate.words == fake.phrase)

        // A screen lock set while the warning was up: authentication is required after all.
        let again = fake.gate()
        await again.show(reason: "")
        fake.noScreenLock = false
        fake.result = .denied
        await again.showAnyway(reason: "")
        #expect(again.words.isEmpty && again.state == .gated)

        // The warning expires with the visit.
        let stale = fake.gate()
        fake.result = .noScreenLock
        await stale.show(reason: "")
        fake.generation += 1
        await stale.showAnyway(reason: "")
        #expect(stale.words.isEmpty && stale.state == .gated)
    }

    @Test func aBackgroundDuringTheScreenLockCheckRevealsNothing() async {
        let fake = Fake()
        let gate = PhraseGate(dependencies: .init(authenticate: { _ in .noScreenLock },
                                                  hasNoScreenLock: { fake.generation += 1; return true },
                                                  words: { fake.phrase }, generation: { fake.generation }))
        await gate.show(reason: "")
        await gate.showAnyway(reason: "")
        #expect(gate.words.isEmpty && gate.state == .gated)
    }

    @Test func copyIsLocalOnlyAndExpiresInTwoMinutes() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let options = SecureCopy.options(now: now)
        #expect(options[.localOnly] as? Bool == true)
        #expect(options[.expirationDate] as? Date == now.addingTimeInterval(120))
        let pasteboard = try #require(UIPasteboard(name: .init("noah-phrase-copy-test"), create: true))
        defer { UIPasteboard.remove(withName: pasteboard.name) }
        SecureCopy.copy("sample phrase", to: pasteboard)
        #expect(pasteboard.string == "sample phrase")
    }
}
