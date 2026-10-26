import CryptoKit
import UIKit

/// Keep a digest rather than an extra copy of the pasted recovery phrase. It covers the words alone: a
/// text field turns a phrase's line breaks into spaces, and the paste control joins several items.
@MainActor
final class RestorePasteboard {
    private let pasteboard: UIPasteboard
    private var accepted: SHA256.Digest?

    init(_ pasteboard: UIPasteboard = .general) { self.pasteboard = pasteboard }

    func accepted(_ text: String) {
        accepted = Self.digest(text)
        clearIfUnchanged()
    }

    func clearIfUnchanged() {
        guard let accepted, let current = pasteboard.strings,
              Self.digest(current.joined(separator: "\n")) == accepted else { return }
        pasteboard.items = []
    }

    private static func digest(_ text: String) -> SHA256.Digest {
        SHA256.hash(data: Data(RecoveryPhraseInput.normalizedWords(text).joined(separator: " ").utf8))
    }
}
