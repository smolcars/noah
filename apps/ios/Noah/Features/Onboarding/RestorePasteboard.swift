import CryptoKit
import UIKit

/// Keep a digest rather than an extra copy of the pasted recovery phrase. It covers the words alone: a
/// text field turns a phrase's line breaks into spaces, and the paste control joins several items.
@MainActor
final class RestorePasteboard {
    private let pasteboard: UIPasteboard
    /// The accepted phrase's digest, and the pasteboard's `changeCount` when it was pasted.
    private var accepted: (digest: SHA256.Digest, change: Int)?

    init(_ pasteboard: UIPasteboard = .general) { self.pasteboard = pasteboard }

    /// `change` is the pasteboard's `changeCount` at the paste, before any loading.
    func accepted(_ text: String, change: Int) {
        accepted = (Self.digest(text), change)
        clearIfUnchanged()
    }

    /// Only while the pasteboard still holds what was pasted. Anything copied since isn't the accepted phrase, and
    /// isn't read: reading another app's copy can ask for paste permission.
    func clearIfUnchanged() {
        guard let accepted, pasteboard.changeCount == accepted.change, let current = pasteboard.strings,
              Self.digest(current.joined(separator: "\n")) == accepted.digest else { return }
        pasteboard.items = []
    }

    private static func digest(_ text: String) -> SHA256.Digest {
        SHA256.hash(data: Data(RecoveryPhraseInput.normalizedWords(text).joined(separator: " ").utf8))
    }
}
