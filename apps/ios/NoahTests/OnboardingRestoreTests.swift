import Foundation
import Testing
@testable import Noah

struct OnboardingRestoreTests {
    static let vector = Array(repeating: "abandon", count: 11) + ["about"]

    @Test func suggestionsComeFromTheBIP39List() {
        var draft = RestoreDraft()
        draft.input.update("aba", at: 0)
        #expect(Array(draft.input.suggestions(at: 0).prefix(3)) == ["abandon"])
        draft.input.update("ab", at: 1)
        #expect(Array(draft.input.suggestions(at: 1).prefix(3)) == ["abandon", "ability", "able"])
        draft.input.update("zoo", at: 2)
        #expect(draft.input.suggestions(at: 2).isEmpty) // Already a word.
    }

    @Test func flagsWordsOutsideTheListExceptTheOneBeingTyped() {
        var draft = RestoreDraft()
        draft.input.update("abandn", at: 4)
        draft.focusedWord = 4
        #expect(!draft.isInvalid(4))
        draft.focusedWord = 5
        #expect(draft.isInvalid(4))
        #expect(draft.firstInvalid == 4)
        #expect(!draft.isInvalid(0)) // Empty is not invalid.
    }

    @Test func restoreNeedsEveryWordInTheListAndChecksumIsSeparate() {
        var draft = RestoreDraft()
        draft.input.update(Self.vector.joined(separator: " "), at: 3)
        #expect(draft.input.words == Self.vector)
        #expect(draft.canRestore)
        #expect(RecoveryPhraseInput.isValid(draft.input.phrase))

        draft.input.update("abandn", at: 4)
        #expect(!draft.canRestore)

        // Every word is listed, but the checksum fails.
        draft.input.update(Array(repeating: "abandon", count: 12).joined(separator: " "), at: 0)
        #expect(draft.canRestore)
        #expect(!RecoveryPhraseInput.isValid(draft.input.phrase))
    }

    @Test func twentyFourWordsAndWrongCounts() {
        var draft = RestoreDraft()
        let words = Array(repeating: "abandon", count: 23) + ["art"]
        draft.input.update(words.joined(separator: " "), at: 0)
        #expect(draft.input.words.count == 24)
        #expect(RecoveryPhraseInput.isValid(draft.input.phrase))
        draft.input.update("abandon abandon abandon", at: 0)
        #expect(draft.input.wrongWordCount)
        #expect(draft.input.words.count == 24) // Never silently replaced.
    }

    @Test func interruptedOnboardingResumesAtName() {
        #expect(OnboardingFlow.Step.initial(resuming: true, storedSeed: false) == .name)
        #expect(OnboardingFlow.Step.initial(resuming: false, storedSeed: false) == .welcome)
        #expect(OnboardingFlow.Step.initial(resuming: false, storedSeed: true) == .storedSeed)
        #expect(OnboardingFlow.Step.name.scene == nil) // No tree on Name.
        #expect(OnboardingFlow.Step.welcome.scene == .welcome)
        #expect(OnboardingFlow.Step.notifications.scene == .conveyor)
    }
}
