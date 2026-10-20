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
