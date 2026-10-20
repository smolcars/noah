import SwiftUI
import UIKit
import Foundation
import OpenAPIRuntime
import Testing

@testable import Noah

struct RecoveryPhraseInputTests {
    // Public BIP39 test vector, never a funded wallet.
    private let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test func normalizesPastedPhraseInAnyField() {
        var input = RecoveryPhraseInput()
        input.update(" \n" + phrase.uppercased().replacingOccurrences(of: " ", with: "\t\n") + " ", at: 7)
        #expect(input.phrase == phrase)
        #expect(input.wordCount == 12)
        #expect(input.isComplete)
        #expect(RecoveryPhraseInput.isValid(input.phrase))
    }

    @Test func countsIndividualWordsAndRejectsOversizedPastes() {
        var input = RecoveryPhraseInput()
        input.update(" ABOUT ", at: 11)
        #expect(input.wordCount == 1)
        #expect(!input.isComplete)
        input.update(phrase + " abandon", at: 0)
        #expect(input.wrongWordCount)
        #expect(input.wordCount == 1)
        input.update("abandon about", at: 0)
        #expect(input.wrongWordCount)
        input.update(phrase, at: 0)
        #expect(input.isComplete)
        input.update("", at: 4)
        #expect(input.wordCount == 11)
        #expect(!input.isComplete)
    }

    @Test func suggestionsUseTheBundledBIP39List() {
        #expect(RecoveryPhraseInput.wordList.count == 2048)
        #expect(Set(RecoveryPhraseInput.wordList).count == 2048)
        var input = RecoveryPhraseInput()
        #expect(input.suggestions(at: 0).isEmpty)
        input.update("aban", at: 0)
        #expect(input.suggestions(at: 0) == ["abandon"])
        input.update("abandon", at: 0)
        #expect(input.suggestions(at: 0).isEmpty)
        input.update("a", at: 0)
        #expect(input.suggestions(at: 0).count == 5)
        input.update("zzzz", at: 0)
        #expect(input.suggestions(at: 0).isEmpty)
    }

    @Test func validatesWordCountDictionaryAndChecksum() {
        #expect(RecoveryPhraseInput.isValid(phrase))
        #expect(!RecoveryPhraseInput.isValid(Array(repeating: "abandon", count: 12).joined(separator: " ")))
        #expect(!RecoveryPhraseInput.isValid(phrase.replacingOccurrences(of: "about", with: "notaword")))
        #expect(!RecoveryPhraseInput.isValid(phrase + " about"))
        #expect(!RecoveryPhraseInput.isValid(""))
        #expect(RecoveryPhraseInput.normalizedWords("ＡＢＯＵＴ\u{00a0}abandon") == ["about", "abandon"])
    }
}

@MainActor
struct RestoreCompletionTests {
    @MainActor
    final class Fake {
        var state = PersistedWalletState(isInitialized: false, isWalletLoaded: false, staticVtxoPubkey: "keep")
        var events: [String] = []
        var seeded: (String, String?)?
        var recovery = RestoreTransaction.Recovery.completed
        var failSave = false
        var failRecovery = false
        struct Failed: Error {}

        var completion: RestoreCompletion {
            RestoreCompletion(
                loadWalletState: { self.state },
                saveWalletState: {
                    if self.failSave { throw Failed() }
                    self.state = $0; self.events.append("flags")
                },
                seedRestored: { self.seeded = ($0, $1); self.events.append("seed") },
                recover: {
                    self.events.append("recover")
                    if self.failRecovery { throw Failed() }
                    return self.recovery
                },
                openWallet: { self.events.append("open") })
        }
    }

    @Test func savesFlagsSeedsBackupAndRecoversBeforeOpening() async throws {
        let fake = Fake()
        try await fake.completion.finish(.init(backupId: "b1", snapshotSha256: "hash", cleanupPending: true))
        #expect(fake.events == ["recover", "flags", "seed", "open"])
        #expect(fake.state.isInitialized == true)
        #expect(fake.state.isWalletLoaded == true)
        #expect(fake.state.staticVtxoPubkey == "keep")
        #expect(fake.seeded?.0 == "hash")
        #expect(fake.seeded?.1 == "b1")
    }

    @Test func legacyDoesNotSeedOrRecover() async throws {
        let fake = Fake()
        try await fake.completion.finish(.init(backupId: nil, snapshotSha256: nil))
        #expect(fake.events == ["flags", "open"])
        #expect(fake.seeded == nil)
    }

    @Test func snapshotWithoutBackupIdStillSeeds() async throws {
        let fake = Fake()
        try await fake.completion.finish(.init(backupId: nil, snapshotSha256: "hash"))
        #expect(fake.events == ["flags", "seed", "open"])
        #expect(fake.seeded?.0 == "hash")
        #expect(fake.seeded?.1 == nil)
    }

    @Test(arguments: [RestoreTransaction.Recovery.waitingForKeychain, .rolledBack])
    func unresolvedCleanupDoesNotOpen(recovery: RestoreTransaction.Recovery) async {
        let fake = Fake()
        fake.recovery = recovery
        await #expect(throws: RestoreCompletion.RestartRequired.self) {
            try await fake.completion.finish(.init(backupId: "b1", snapshotSha256: "hash", cleanupPending: true))
        }
        #expect(!fake.events.contains("open"))
    }

    @Test func thrownCleanupAndFailedStateSaveDoNotOpen() async {
        let fake = Fake()
        fake.failRecovery = true
        await #expect(throws: Fake.Failed.self) {
            try await fake.completion.finish(.init(backupId: nil, snapshotSha256: nil, cleanupPending: true))
        }
        #expect(!fake.events.contains("open"))
        fake.events = []
        fake.failSave = true
        await #expect(throws: Fake.Failed.self) {
            try await fake.completion.finish(.init(backupId: "b1", snapshotSha256: "hash"))
        }
        #expect(fake.events.isEmpty)
    }
}

struct RestoreIssueTests {
    @Test(arguments: [
        WalletRestorer.Failure.wrongNetwork, .identityMismatch, .noUsableBackup, .unsupportedFormat(9),
        .downloadMismatch, .secureStorageUnavailable, .rollbackFailed, .seedStateUnknown,
    ])
    func mapsEveryEngineFailure(_ failure: WalletRestorer.Failure) {
        let issue = RestoreIssue.from(failure)
        #expect(!issue.message.isEmpty)
        #expect(issue.requiresRestart == (failure == .rollbackFailed || failure == .seedStateUnknown))
        if issue.requiresRestart {
            #expect(issue.message.contains("Do not retry"))
            #expect(issue.message.contains("restart Noah"))
        }
    }

    @Test func distinguishesMissingBackupWrongPhraseAndNetworkWithoutDisplayingRawErrors() {
        let secret = "sensitive request data"
        let missing = RestoreIssue.from(ServerFailure(message: secret, statusCode: 404))
        #expect(missing.message.contains("No backup"))
        #expect(!missing.message.contains(secret))
        #expect(RestoreIssue.from(ServerFailure(message: secret, statusCode: 401, code: "USER_NOT_FOUND")).message == missing.message)
        #expect(RestoreIssue.from(BackupCrypto.Failure.authenticationFailed).message.contains("unlock the backup"))
        #expect(RestoreIssue.from(URLError(.notConnectedToInternet)).message.contains("internet connection"))
        #expect(!RestoreIssue.from(ServerFailure(message: secret, statusCode: 500)).message.contains(secret))
        #expect(RestoreIssue.from(RestoreTransaction.Failure.journalUnreadable).requiresRestart)
        #expect(RestoreIssue.committed.requiresRestart)
    }
}

@MainActor
struct RestoreFieldPrivacyTests {
    @Test func visibleRecoveryFieldDisablesPredictionsAndTextTransformations() {
        let field = RecoveryWordTextField()
        #expect(!field.isSecureTextEntry) // Visible words are a deliberate usability decision.
        #expect(field.autocorrectionType == .no)
        #expect(field.spellCheckingType == .no)
        #expect(field.smartQuotesType == .no)
        #expect(field.smartDashesType == .no)
        #expect(field.smartInsertDeleteType == .no)
        #expect(field.inlinePredictionType == .no)
        #expect(field.textContentType == nil)
        #expect(field.keyboardType == .asciiCapable)
        #expect(field.autocapitalizationType == .none)
    }

    @Test func appRejectsOnlyThirdPartyKeyboardExtensions() {
        let delegate = AppDelegate()
        #expect(!delegate.application(UIApplication.shared, shouldAllowExtensionPointIdentifier: .keyboard))
        #expect(delegate.application(UIApplication.shared, shouldAllowExtensionPointIdentifier: .init(rawValue: "unrelated.extension")))
    }

    @Test func recoveryFieldCannotUndoAfterClearingOrDismantling() {
        let field = RecoveryWordTextField()
        field.insertText("abandon")
        #expect(field.undoManager == nil)
        field.text = ""
        field.undoManager?.undo()
        #expect(field.text?.isEmpty == true)
        field.insertText("about")
        let wrapper = RecoveryWordField(text: .constant(""), index: 0, focusedWord: .constant(nil))
        RecoveryWordField.dismantleUIView(field, coordinator: wrapper.makeCoordinator())
        #expect(field.text?.isEmpty == true)
        #expect(field.undoManager == nil)
    }

    @Test func acceptedPasteClearsOnlyMatchingClipboardContent() {
        let name = UIPasteboard.Name("noah-restore-test-\(UUID().uuidString)")
        let board = UIPasteboard(name: name, create: true)!
        defer { UIPasteboard.remove(withName: name) }
        let clipboard = RestorePasteboard(board)
        board.string = "public test content"
        clipboard.accepted("public test content")
        #expect(board.string == nil)
        board.string = "replacement"
        clipboard.clearIfUnchanged()
        #expect(board.string == "replacement")
        board.string = "public test content"
        clipboard.clearIfUnchanged()
        #expect(board.string == nil)
        board.string = "replacement before acceptance"
        clipboard.accepted("public test content")
        #expect(board.string == "replacement before acceptance")
    }
}
