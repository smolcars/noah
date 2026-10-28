@testable import Noah
import Testing

/// Profile → Name keeps its draft, its save and its error outside the sheet, which a rotation rebuilds.
@MainActor struct ProfileNameDraftTests {
    @Test func theDraftOutlivesTheSheetAndKeepsTodaysLimit() {
        let draft = ProfileNameDraft()
        draft.begin("Maya")
        draft.text = "Rotation draft"
        // A rebuilt sheet reads the same draft.
        #expect(draft.text == "Rotation draft")
        draft.text = String(repeating: "n", count: 90)
        #expect(draft.text.utf16.count == 80)
    }

    @Test func oneSaveAtATimeAndTheDraftStaysWhileItRuns() async {
        let draft = ProfileNameDraft()
        draft.begin("Maya")
        draft.text = "  Maya R  "
        let gate = AsyncStream<Void>.makeStream()
        var sent: [String] = []
        let first = Task { await draft.save { sent.append($0); for await _ in gate.stream { break } } }
        while sent.isEmpty { await Task.yield() }
        #expect(draft.busy)
        draft.begin("Maya")
        #expect(draft.text == "  Maya R  ", "a fresh edit can't wipe a save in progress")
        #expect(await draft.save { sent.append($0) } == nil)
        gate.continuation.yield()
        #expect(await first.value == "Maya R")
        #expect(sent == ["  Maya R  "] && !draft.busy)
    }

    @Test func aFailedSaveKeepsTheDraftAndSaysSo() async {
        let draft = ProfileNameDraft()
        draft.begin("Maya")
        draft.text = "New name"
        struct Offline: Error {}
        #expect(await draft.save { _ in throw Offline() } == nil)
        #expect(draft.text == "New name" && draft.error == "Couldn't save your name." && !draft.busy)
    }
}

/// Profile keeps today's copy actions beside editing.
struct ProfileCopiesTests {
    @Test func addressVerifiedEmailAndNpubCopy() {
        var server = PersistedServerState()
        #expect(ProfileCopies(server) == ProfileCopies(PersistedServerState()))
        #expect(ProfileCopies(server).address == nil && ProfileCopies(server).email == nil && ProfileCopies(server).npub == nil)
        server.lightningAddress = "maya@noahwallet.io"
        server.emailAddress = "maya@example.com"
        server.isEmailVerified = false
        server.nostrPubkey = String(repeating: "ab", count: 32)
        let unverified = ProfileCopies(server)
        #expect(unverified.address == "maya@noahwallet.io")
        #expect(unverified.email == nil, "an unverified email isn't offered, as before")
        #expect(unverified.npub?.hasPrefix("npub1") == true)
        server.isEmailVerified = true
        #expect(ProfileCopies(server).email == "maya@example.com")
    }
}
