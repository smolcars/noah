import Foundation

struct IdentityValidationError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

enum LightningIdentity {
    static func normalizeUsername(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func payload(username: String, npub: String) throws -> Components.Schemas.UpdateLightningIdentityPayload {
        let username = normalizeUsername(username)
        let npub = npub.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !username.isEmpty else { throw IdentityValidationError("Enter a username") }
        guard username.range(of: "^[a-z0-9_.-]+$", options: .regularExpression) != nil else {
            throw IdentityValidationError("Username may only contain a-z, 0-9, dash, underscore, and period")
        }
        if npub.lowercased().hasPrefix("nsec1") {
            throw IdentityValidationError("Never paste a Nostr private key. Enter an npub public key.")
        }
        if !npub.isEmpty && !npub.lowercased().hasPrefix("npub1") {
            throw IdentityValidationError("Nostr public keys must use npub encoding")
        }
        // The server validates the bech32 checksum and secp256k1 key, as in RN.
        return .init(nostrPubkey: npub.isEmpty ? nil : npub, username: username)
    }

    static func domain(for variant: AppVariant) -> String {
        switch variant {
        case .mainnet: "noahwallet.io"
        case .signet: "signet.noahwallet.io"
        case .regtest: Bundle.main.object(forInfoDictionaryKey: "NOAH_LNURL_DOMAIN") as? String ?? "localhost.com"
        }
    }
}

enum Nostr {
    /// NIP-19 bech32 encoding, equivalent to RN's `hexPubkeyToNpub`.
    static func npub(from hex: String?) -> String {
        guard let hex, hex.count == 64,
              hex.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil else { return "" }
        let chars = Array(hex)
        let bytes = stride(from: 0, to: 64, by: 2).map { UInt8(String(chars[$0...($0 + 1)]), radix: 16)! }
        var words: [UInt8] = []
        var accumulator: UInt32 = 0
        var bits = 0
        for byte in bytes {
            accumulator = (accumulator << 8) | UInt32(byte)
            bits += 8
            while bits >= 5 {
                bits -= 5
                words.append(UInt8((accumulator >> bits) & 31))
            }
        }
        if bits > 0 { words.append(UInt8((accumulator << (5 - bits)) & 31)) }
        let prefix = Array("npub".utf8)
        let expanded = prefix.map { $0 >> 5 } + [0] + prefix.map { $0 & 31 }
        var checksum: UInt32 = 1
        let generators: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        for value in expanded + words + Array(repeating: 0, count: 6) {
            let top = checksum >> 25
            checksum = ((checksum & 0x1ffffff) << 5) ^ UInt32(value)
            for i in 0..<5 where ((top >> i) & 1) != 0 { checksum ^= generators[i] }
        }
        checksum ^= 1
        words += (0..<6).map { UInt8((checksum >> (5 * (5 - $0))) & 31) }
        let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
        return "npub1" + String(words.map { alphabet[Int($0)] })
    }
}
