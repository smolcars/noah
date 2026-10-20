import CryptoKit
import UIKit

/// Keep a digest rather than an extra copy of the pasted recovery phrase.
@MainActor
final class RestorePasteboard {
    private let pasteboard: UIPasteboard
    private var accepted: SHA256.Digest?

    init(_ pasteboard: UIPasteboard = .general) { self.pasteboard = pasteboard }

    func accepted(_ text: String) {
        accepted = SHA256.hash(data: Data(text.utf8))
        clearIfUnchanged()
    }

    func clearIfUnchanged() {
        guard let accepted, let current = pasteboard.string,
              SHA256.hash(data: Data(current.utf8)) == accepted else { return }
        pasteboard.items = []
    }
}
