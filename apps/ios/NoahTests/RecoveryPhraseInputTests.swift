import BarkBoltFFI
import Foundation
@testable import Noah
import OpenAPIRuntime
import SwiftUI
import Testing
import UIKit
import UniformTypeIdentifiers

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

    /// The shapes a phrase copied from Notes or another app arrives in fill every word, 12 or 24.
    @Test func everyCopiedShapeFillsEveryWord() {
        for count in [12, 24] {
            let words = (0..<count).map { RecoveryPhraseInput.wordList[($0 * 89 + 7) % RecoveryPhraseInput.wordList.count] }
            let line = words.joined(separator: " ")
            let shapes = [line, line + "\n", line + "\r\n", " " + line.capitalized + " \n", words.joined(separator: "\n"),
                          words.joined(separator: "\r\n"), words.joined(separator: "\u{00A0}"), words.joined(separator: "\u{202F}"),
                          words.joined(separator: "\u{2009}"), words.joined(separator: "\u{3000}"), words.joined(separator: "\u{2028}")]
            for shape in shapes {
                var input = RecoveryPhraseInput()
                input.update(shape, at: 3)
                let filled = input.words == words && input.isComplete
                #expect(filled)
            }
        }
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
    struct Failed: Error {}
    @MainActor
    final class Fake {
        var state = PersistedWalletState(isInitialized: false, isWalletLoaded: false, staticVtxoPubkey: "keep")
        var events: [String] = []
        var seeded: (String, String?)?
        var recovery = RestoreTransaction.Recovery.completed
        var failSave = false
        var failRecovery = false

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
        await #expect(throws: Failed.self) {
            try await fake.completion.finish(.init(backupId: nil, snapshotSha256: nil, cleanupPending: true))
        }
        #expect(!fake.events.contains("open"))
        fake.events = []
        fake.failSave = true
        await #expect(throws: Failed.self) {
            try await fake.completion.finish(.init(backupId: "b1", snapshotSha256: "hash"))
        }
        #expect(fake.events.isEmpty)
    }
}

struct RestoreIssueTests {
    /// Future envelopes (any version byte), manifests and bark schemas all ask for an update.
    @Test func futureVersionsExplainUpdateRequired() throws {
        for version in [UInt8(3), 255] {
            var bytes = Data(repeating: 0, count: 45)
            bytes[0] = version
            #expect(throws: BackupCrypto.Failure.newerVersion) { try BackupCrypto.decrypt(bytes, mnemonic: "unused", expecting: .snapshot) }
            #expect(RestoreIssue.from(BackupCrypto.Failure.newerVersion) == .newerBackup)
        }
        let manifest = BackupArchive.Manifest(formatVersion: 3, createdAt: "x", snapshotSha256: "sha",
                                              network: "regtest", walletFingerprint: "abcd", serverPubkey: nil, mailboxPubkey: nil, barkSchemaVersion: 42)
        #expect(throws: BackupArchive.Failure.newerVersion) { try BackupArchive.parseManifest(BackupArchive.encode(manifest)) }
        #expect(RestoreIssue.from(BackupArchive.Failure.newerVersion) == .newerBackup)
        #expect(RestoreIssue.from(BarkError(message: "Snapshot schema version 9999 is newer than supported version 42")) == .newerBackup)
    }

    /// Unrelated diagnostics, even a server echoing the schema wording, get the generic message.
    @Test func unrelatedDiagnosticsFallBackToTheGenericMessage() {
        let generic = RestoreIssue.from(BarkError(message: "snapshot is damaged"))
        #expect(generic != .newerBackup && !generic.requiresRestart)
        for error: any Error in [BackupCrypto.Failure.unexpectedVersion(1), BackupArchive.Failure.invalidManifest,
                                 ServerFailure(message: "Snapshot schema version 9999 is newer than supported version 42")] {
            #expect(RestoreIssue.from(error) == generic)
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

    /// The paste control passes on every item's text, plain or RTF alone, one per line.
    @Test func pasteControlReadsPlainRTFAndSplitCopies() async throws {
        let words = Array(repeating: "abandon", count: 23) + ["art"]
        let first = words.prefix(12).joined(separator: " "), second = words.suffix(12).joined(separator: " ") + "\n"
        let rtf = try NSAttributedString(string: second).data(from: NSRange(location: 0, length: second.utf16.count),
                                                              documentAttributes: [.documentType: NSAttributedString.DocumentType.rtf])
        let rtfOnly = NSItemProvider()
        rtfOnly.registerDataRepresentation(for: .rtf) { completion in completion(rtf, nil); return nil }
        let target = RestorePasteControl.Target()
        #expect(target.pasteConfiguration?.acceptableTypeIdentifiers.contains(UTType.rtf.identifier) == true)
        let pasted: String = await withCheckedContinuation { continuation in
            target.onPaste = { { continuation.resume(returning: $0) } }
            // swiftlint:disable:next legacy_objc_type - NSItemProvider takes an NSItemProviderWriting object
            let plain = NSItemProvider(object: first as NSString)
            target.paste(itemProviders: [plain, rtfOnly])
        }
        let filled = RecoveryPhraseInput.normalizedWords(pasted) == words
        #expect(filled)
    }

    /// A field turns a copy's line breaks into spaces and the paste control joins items: the same words still clear.
    @Test func acceptedPasteClearsTheSameWordsWhateverTheirSpacing() throws {
        let name = UIPasteboard.Name("noah-restore-test-\(UUID().uuidString)")
        let board = try #require(UIPasteboard(name: name, create: true))
        defer { UIPasteboard.remove(withName: name) }
        let words = Array(repeating: "abandon", count: 11) + ["about"]
        let line = words.joined(separator: " ")
        for copy in [[line + "\n"], [words.joined(separator: "\r\n")], [words.joined(separator: "\u{00A0}")],
                     [words.prefix(6).joined(separator: " "), words.suffix(6).joined(separator: " ")]] {
            board.strings = copy
            RestorePasteboard(board).accepted(line, change: board.changeCount)
            #expect(!board.hasStrings)
        }
        board.strings = [line + " zoo"]
        RestorePasteboard(board).accepted(line, change: board.changeCount)
        #expect(board.hasStrings)
    }

    /// A plain-text item whose data arrives only when `finish` is called.
    final class DelayedText: @unchecked Sendable {
        let provider = NSItemProvider()
        private let lock = NSLock()
        private var completion: ((Data?, (any Error)?) -> Void)?

        init() {
            provider.registerDataRepresentation(for: .utf8PlainText) { completion in
                self.lock.withLock { self.completion = completion }
                return nil
            }
        }

        var loading: Bool { lock.withLock { completion != nil } }
        func finish(_ text: String) { lock.withLock { completion }?(Data(text.utf8), nil) }
    }

    @MainActor final class DraftBox {
        var draft = RestoreDraft()
        var pasted: [String] = []
    }

    private static func pasteControl(in view: UIView) -> UIPasteControl? {
        (view as? UIPasteControl) ?? view.subviews.lazy.compactMap(pasteControl(in:)).first
    }

    private func until(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !condition(), ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(10)) }
        try #require(condition())
    }

    private static func wordField(_ number: Int, in view: UIView) -> RecoveryWordTextField? {
        if let field = view as? RecoveryWordTextField, field.accessibilityIdentifier == "restore-word-\(number)" { return field }
        return view.subviews.lazy.compactMap { wordField(number, in: $0) }.first
    }

    /// Restore in a window, its paste control's target, and the window to hide afterwards.
    private struct HostedRestore {
        let window: UIWindow
        let host: UIHostingController<RestoreWalletScreen>
        let target: RestorePasteControl.Target
    }

    private func hostRestore(_ box: DraftBox) async throws -> HostedRestore {
        let scene = try #require(UIApplication.shared.connectedScenes.lazy.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 402, height: 874)
        let host = UIHostingController(rootView: RestoreWalletScreen(
            layout: OnboardingLayout(size: window.frame.size, insets: EdgeInsets()),
            draft: Binding(get: { box.draft }, set: { box.draft = $0 }), issue: nil, haptics: OnboardingHaptics(),
            onPaste: { text, _ in box.pasted.append(text) }, onRestore: {}, onBack: {}))
        window.rootViewController = host
        window.isHidden = false
        try await until { Self.pasteControl(in: host.view) != nil }
        return try HostedRestore(window: window, host: host, target: #require(Self.pasteControl(in: host.view)?.target as? RestorePasteControl.Target))
    }

    /// The items load after the tap. Text that arrives after Back (the flow's reset moves the generation, as the 12/24
    /// switch and Restore do) or after a newer tap is dropped: it never refills the draft or reaches the clipboard.
    @Test func aLatePasteIsDropped() async throws {
        let box = DraftBox()
        let hosted = try await hostRestore(box), window = hosted.window, target = hosted.target
        defer { window.isHidden = true }
        let older = (Array(repeating: "zoo", count: 11) + ["wrong"]).joined(separator: " ")
        let newer = (Array(repeating: "abandon", count: 11) + ["about"]).joined(separator: " ")

        let afterBack = DelayedText()
        target.paste(itemProviders: [afterBack.provider])
        try await until { afterBack.loading }
        box.draft = RestoreDraft(generation: box.draft.generation + 1)
        afterBack.finish(newer)
        try await Task.sleep(for: .milliseconds(100))
        #expect(box.draft.input.wordCount == 0 && box.pasted.isEmpty)

        let first = DelayedText(), second = DelayedText()
        target.paste(itemProviders: [first.provider])
        target.paste(itemProviders: [second.provider])
        try await until { first.loading && second.loading }
        second.finish(newer)
        try await until { box.pasted.count == 1 }
        first.finish(older)
        try await Task.sleep(for: .milliseconds(100))
        let newerKept = box.pasted.count == 1 && box.draft.input.phrase == newer
        #expect(newerKept)
    }

    /// Any edit to a word since the tap drops a paste still loading too: typing a correction, or pasting one word
    /// into a field, neither of which restarts the fields.
    @Test func aLatePasteIsDroppedAfterAnEdit() async throws {
        let box = DraftBox()
        let hosted = try await hostRestore(box), window = hosted.window, host = hosted.host, target = hosted.target
        defer { window.isHidden = true }
        let phrase = (Array(repeating: "abandon", count: 11) + ["about"]).joined(separator: " ")
        for edit in ["zo", "zoo"] {
            let slow = DelayedText()
            target.paste(itemProviders: [slow.provider])
            try await until { slow.loading }
            let field = try #require(Self.wordField(1, in: host.view))
            field.text = edit
            field.sendActions(for: .editingChanged)
            slow.finish(phrase)
            try await Task.sleep(for: .milliseconds(100))
            let editKept = box.draft.input.words[0] == edit && box.draft.input.wordCount == 1 && box.pasted.isEmpty
            #expect(editKept)
        }
    }

    /// RTF alone, from the paste control's decoding to the clipboard being cleared; then a copy made after the paste,
    /// or during its loading, is never cleared.
    @Test func anRTFOnlyPhraseIsPastedThenClearedAndLaterCopiesAreLeft() async throws {
        let name = UIPasteboard.Name("noah-restore-test-\(UUID().uuidString)")
        let board = try #require(UIPasteboard(name: name, create: true))
        defer { UIPasteboard.remove(withName: name) }
        let phrase = (Array(repeating: "abandon", count: 11) + ["about"]).joined(separator: " ") + "\n"
        let rtf = try NSAttributedString(string: phrase).data(from: NSRange(location: 0, length: phrase.utf16.count),
                                                              documentAttributes: [.documentType: NSAttributedString.DocumentType.rtf])
        board.items = [[UTType.rtf.identifier: rtf]]
        let provider = NSItemProvider()
        provider.registerDataRepresentation(for: .rtf) { completion in completion(rtf, nil); return nil }
        let clipboard = RestorePasteboard(board)
        let target = RestorePasteControl.Target()
        var pasted: String?
        target.onPaste = {
            let change = board.changeCount
            return { text in pasted = text; clipboard.accepted(text, change: change) }
        }
        target.paste(itemProviders: [provider])
        try await until { pasted != nil }
        #expect(RecoveryPhraseInput.normalizedWords(pasted ?? "").count == 12)
        #expect(board.numberOfItems == 0)

        board.string = phrase
        clipboard.clearIfUnchanged()
        #expect(board.string == phrase)
        let before = board.changeCount
        board.string = "copied while loading"
        clipboard.accepted(phrase, change: before)
        #expect(board.string == "copied while loading")
    }

    @Test func acceptedPasteClearsOnlyMatchingClipboardContent() throws {
        let name = UIPasteboard.Name("noah-restore-test-\(UUID().uuidString)")
        let board = try #require(UIPasteboard(name: name, create: true))
        defer { UIPasteboard.remove(withName: name) }
        let clipboard = RestorePasteboard(board)
        board.string = "public test content"
        clipboard.accepted("public test content", change: board.changeCount)
        #expect(board.string == nil)
        board.string = "replacement"
        clipboard.clearIfUnchanged()
        #expect(board.string == "replacement")
        // Copied again after the paste: a new copy, never read or cleared.
        board.string = "public test content"
        clipboard.clearIfUnchanged()
        #expect(board.string == "public test content")
        board.string = "replacement before acceptance"
        clipboard.accepted("public test content", change: board.changeCount)
        #expect(board.string == "replacement before acceptance")
    }
}
