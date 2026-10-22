import BarkBoltFFI
import SwiftUI
import UIKit
import Foundation
import OpenAPIRuntime
import Testing

@testable import Noah

struct RecoveryPhraseInputTests {
    // Public BIP39 test vector, never a funded wallet.
    private let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test func acceptsTwelveAndTwentyFourWordsByPasteAndIndividualEntry() {
        for count in [12, 24] {
            let vector = (Array(repeating: RecoveryPhraseInput.wordList[0], count: count - 1) +
                          [RecoveryPhraseInput.wordList[count == 12 ? 3 : 102]]).joined(separator: " ")
            let valid = RecoveryPhraseInput.isValid(vector)
            #expect(valid)
            var input = RecoveryPhraseInput()
            input.update(vector.uppercased(), at: 4)
            #expect(input.words.count == count)
            #expect(input.isComplete)
            let pasteMatches = input.phrase == vector
            #expect(pasteMatches)
            input.selectWordCount(count)
            #expect(input.wordCount == 0)
            for (index, word) in vector.split(separator: " ").enumerated() { input.update(String(word), at: index) }
            #expect(input.isComplete)
            let entryMatches = input.phrase == vector
            #expect(entryMatches)
            let badChecksum = RecoveryPhraseInput.isValid(Array(repeating: RecoveryPhraseInput.wordList[0], count: count).joined(separator: " "))
            #expect(!badChecksum)
        }
        for count in [11, 13, 23, 25] {
            let vector = Array(repeating: RecoveryPhraseInput.wordList[0], count: count).joined(separator: " ")
            let valid = RecoveryPhraseInput.isValid(vector)
            #expect(!valid)
            var input = RecoveryPhraseInput()
            input.update(vector, at: 0)
            #expect(input.wrongWordCount)
            #expect(input.wordCount == 0)
        }
    }

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
    private let updateRequired = "This backup was created by a newer version of Noah. Update the app, then try restoring again."

    @Test(arguments: [UInt8(3), 255])
    func futureEnvelopeVersionsExplainUpdateRequired(_ version: UInt8) throws {
        var bytes = Data(repeating: 0, count: 45)
        bytes[0] = version
        do {
            _ = try BackupCrypto.decrypt(bytes, mnemonic: "unused", expecting: .snapshot)
            Issue.record("A future envelope was accepted")
        } catch {
            #expect(error as? BackupCrypto.Failure == .newerVersion)
            #expect(RestoreIssue.from(error) == RestoreIssue(message: updateRequired))
        }
    }

    @Test func futureManifestVersionExplainsUpdateRequired() throws {
        let manifest = BackupArchive.Manifest(formatVersion: 3, createdAt: "x", snapshotSha256: "sha",
            network: "regtest", walletFingerprint: "abcd", serverPubkey: nil, mailboxPubkey: nil, barkSchemaVersion: 42)
        do {
            _ = try BackupArchive.parseManifest(BackupArchive.encode(manifest))
            Issue.record("A future manifest was accepted")
        } catch {
            #expect(error as? BackupArchive.Failure == .newerVersion)
            #expect(RestoreIssue.from(error) == RestoreIssue(message: updateRequired))
        }
    }

    @Test func futureSchemaVersionExplainsUpdateRequired() {
        let error = BarkError(message: "Snapshot schema version 9999 is newer than supported version 42")
        #expect(RestoreIssue.from(error) == RestoreIssue(message: updateRequired))
    }

    @Test func wrongPhraseDamagedBackupAndUnrelatedDiagnosticsKeepTheirCopy() {
        #expect(RestoreIssue.from(BackupCrypto.Failure.authenticationFailed).message ==
            "This phrase couldn't unlock the backup, or the backup is damaged. Check all 12 or 24 words and their order.")
        #expect(RestoreIssue.from(WalletRestorer.Failure.downloadMismatch).message ==
            "The downloaded backup is incomplete or damaged. Check your connection and try again.")
        for error: any Error in [BackupCrypto.Failure.unexpectedVersion(1), BackupArchive.Failure.invalidManifest,
            BarkError(message: "snapshot is damaged"),
            ServerFailure(message: "Snapshot schema version 9999 is newer than supported version 42")] {
            #expect(RestoreIssue.from(error).message == "Noah couldn't restore your wallet. Please try again later.")
        }
    }

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

    @Test func recoveryFieldRefusesCopyCutAndShare() {
        let field = RecoveryWordTextField()
        field.text = "abandon"
        for action in RecoveryWordTextField.blockedActions {
            #expect(!field.canPerformAction(action, withSender: nil), "\(action)")
        }
        #expect(RecoveryWordTextField.blockedActions.contains(#selector(UIResponderStandardEditActions.copy(_:))))
        #expect(RecoveryWordTextField.blockedActions.contains(#selector(UIResponderStandardEditActions.cut(_:))))
        #expect(field.writingToolsBehavior == .none)
        #expect(field.textDragInteraction?.isEnabled != true)
        // Shortcuts and direct calls change neither the pasteboard nor the field.
        let general = UIPasteboard.general
        let previous = general.items
        defer { general.items = previous }
        general.string = "sentinel"
        field.selectAll(nil)
        field.copy(nil)
        field.cut(nil)
        #expect(general.string == "sentinel")
        #expect(field.text == "abandon")
        // Disabled in menus that validate commands (iPad keyboard shortcuts).
        let copy = UIKeyCommand(title: "Copy", action: #selector(UIResponderStandardEditActions.copy(_:)), input: "c", modifierFlags: .command)
        field.validate(copy)
        #expect(copy.attributes.contains(.disabled))
    }

    @Test func recoveryFieldMenuKeepsOnlyPasteAndSelection() {
        func command(_ title: String, _ action: Selector) -> UICommand { UICommand(title: title, action: action) }
        let suggested: [UIMenuElement] = [
            UIMenu(options: .displayInline, children: [
                command("Cut", #selector(UIResponderStandardEditActions.cut(_:))),
                command("Copy", #selector(UIResponderStandardEditActions.copy(_:))),
                command("Paste", #selector(UIResponderStandardEditActions.paste(_:))),
            ]),
            UIMenu(options: .displayInline, children: [
                command("Select", #selector(UIResponderStandardEditActions.select(_:))),
                command("Select All", #selector(UIResponderStandardEditActions.selectAll(_:))),
            ]),
            UIMenu(options: .displayInline, children: [
                command("Look Up", Selector(("_lookup:"))), command("Translate", Selector(("_translate:"))),
                command("Share…", Selector(("_share:"))),
            ]),
            UIAction(title: "AutoFill") { _ in },
        ]
        let kept = RecoveryWordTextField.editMenu(suggested).flatMap { ($0 as? UIMenu)?.children ?? [$0] }
        #expect(kept.compactMap { ($0 as? UICommand)?.title } == ["Paste", "Select", "Select All"])
        #expect(kept.count == 3)
    }

    @Test func recoveryFieldStillPastesAWholePhrase() throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 200)
        let host = UIViewController()
        window.rootViewController = host
        window.isHidden = false
        defer { window.isHidden = true }
        let field = RecoveryWordTextField(frame: CGRect(x: 0, y: 0, width: 300, height: 44))
        host.view.addSubview(field)
        var received = ""
        let wrapper = RecoveryWordField(text: Binding(get: { received }, set: { received = $0 }), index: 0, focusedWord: .constant(0))
        let coordinator = wrapper.makeCoordinator()
        field.addTarget(coordinator, action: #selector(RecoveryWordField.Coordinator.changed(_:)), for: .editingChanged)
        #expect(field.becomeFirstResponder())
        let general = UIPasteboard.general
        let previous = general.items
        defer { general.items = previous; field.resignFirstResponder() }
        let phrase = (Array(repeating: "abandon", count: 11) + ["about"]).joined(separator: " ")
        general.string = phrase
        let paste = #selector(UIResponderStandardEditActions.paste(_:))
        #expect(field.canPerformAction(paste, withSender: nil))
        let stock = UITextField(frame: CGRect(x: 0, y: 50, width: 300, height: 44))
        host.view.addSubview(stock)
        #expect(stock.becomeFirstResponder())
        let stockCanPaste = stock.canPerformAction(paste, withSender: nil)
        #expect(field.becomeFirstResponder())
        #expect(field.canPerformAction(paste, withSender: nil) == stockCanPaste)
        // A programmatic paste(_:) waits for the system's paste consent in any text field, so
        // insert the pasted text as paste does and follow it into the whole-phrase path.
        field.insertText(phrase)
        #expect(field.text == phrase)
        #expect(received == phrase) // RecoveryPhraseInput and RestorePasteboard take it from here.
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
