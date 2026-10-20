import BarkBoltFFI
import Foundation
import Security

/// Validation never repairs or rewrites a Keychain item. RN stored the phrase
/// verbatim; only the wallet parser gets BIP39's normalized representation.
enum StoredSeed {
    enum Failure: Error, Equatable { case invalid, conflict }

    static func normalized(_ value: String) throws -> String {
        let words = value.decomposedStringWithCompatibilityMapping.lowercased()
            .split(whereSeparator: \.isWhitespace)
        guard [12, 24].contains(words.count) else { throw Failure.invalid }
        let phrase = words.joined(separator: " ")
        guard (try? BarkBoltFFI.validateMnemonic(mnemonic: phrase)) == true else { throw Failure.invalid }
        return phrase
    }

    /// Each independent source must be readable, valid and byte-identical.
    static func agree(_ sources: [KeychainPassword.ReadResult]) -> KeychainPassword.ReadResult {
        let checked = sources.map { result -> KeychainPassword.ReadResult in
            if case .found(let value) = result, (try? normalized(value)) == nil { return .unavailable(errSecDecode) }
            return result
        }
        if let failure = checked.first(where: { if case .unavailable = $0 { true } else { false } }) { return failure }
        let values = checked.compactMap { if case .found(let value) = $0 { value } else { nil as String? } }
        guard let first = values.first else { return .notFound }
        guard values.allSatisfy({ $0.utf8.elementsEqual(first.utf8) }) else { return .unavailable(errSecDuplicateItem) }
        return .found(first)
    }

    static func requireUsable(_ result: KeychainPassword.ReadResult) throws {
        switch result {
        case .found(let value): _ = try normalized(value)
        case .unavailable(let status) where status == errSecDuplicateItem: throw Failure.conflict
        case .unavailable(let status) where status == errSecDecode: throw Failure.invalid
        case .unavailable(let status): throw KeychainPassword.WriteError(status: status)
        case .notFound: break
        }
    }
}
