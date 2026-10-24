import Testing
@testable import Noah

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
        #expect(draft.text == "New name" && draft.error == "Couldn't save your name. Please try again." && !draft.busy)
    }
}
