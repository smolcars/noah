import BarkBoltFFI
import Foundation

struct RecoveryPhraseInput {
    private(set) var words = Array(repeating: "", count: 12)
    private(set) var wrongWordCount = false

    var phrase: String { words.joined(separator: " ") }
    var wordCount: Int { words.filter { !$0.isEmpty }.count }
    var isComplete: Bool { wordCount == 12 && !wrongWordCount }

    static func normalizedWords(_ text: String) -> [String] {
        text.decomposedStringWithCompatibilityMapping.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
    }

    /// Whole-phrase paste works in any field. An oversized paste is rejected
    /// visibly, never silently truncated into a different recovery phrase.
    mutating func update(_ text: String, at index: Int) {
        let incoming = Self.normalizedWords(text)
        if incoming.count > 1 {
            guard incoming.count == 12 else { wrongWordCount = true; return }
            words = incoming
        } else {
            words[index] = incoming.first ?? ""
        }
        wrongWordCount = false
    }

    func suggestions(at index: Int) -> [String] {
        let prefix = words[index]
        guard !prefix.isEmpty, !Self.wordList.contains(prefix) else { return [] }
        return Array(Self.wordList.lazy.filter { $0.hasPrefix(prefix) }.prefix(5))
    }

    static func isValid(_ phrase: String) -> Bool {
        let words = normalizedWords(phrase)
        guard words.count == 12 else { return false }
        return (try? BarkBoltFFI.validateMnemonic(mnemonic: words.joined(separator: " "))) == true
    }

    // Standard BIP39 English list (bitcoin/bips, bip-0039/english.txt),
    // bundled locally so suggestions never send entered words to a server.
    static let wordList: [String] = {
        guard let url = Bundle.main.url(forResource: "bip39-english", withExtension: "txt"),
            let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(whereSeparator: \.isWhitespace).map(String.init)
    }()
}
