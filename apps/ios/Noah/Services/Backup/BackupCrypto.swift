import CommonCrypto
import CryptoKit
import Foundation
import Security

/// Encrypted backup envelope, byte-compatible with the React Native app
/// (`client/nitromodules/noah-tools/{ios,android}/NoahToolsBackup.*`) and
/// `scripts/decrypt_backup.js`:
///
///     [version: 1][salt: 16][nonce: 12][AES-256-GCM ciphertext][tag: 16]
///     key = PBKDF2-HMAC-SHA256(UTF-8 mnemonic, salt, 600_000 rounds, 32 bytes)
///
/// No associated data; the version byte is not authenticated (as in the RN
/// app). Version 1 is the legacy directory backup, version 2 the snapshot
/// `.noahbackup` format.
enum BackupCrypto {
    enum Version: UInt8, Sendable {
        case legacyDirectory = 1
        case snapshot = 2
    }

    enum Failure: Error, Equatable {
        case tooShort
        case newerVersion
        case unexpectedVersion(UInt8)
        case keyDerivationFailed(Int32)
        /// Wrong mnemonic or corrupted data (GCM authentication failed).
        case authenticationFailed
    }

    static let saltLength = 16
    static let nonceLength = 12
    static let tagLength = 16
    static let pbkdf2Rounds: UInt32 = 600_000
    /// Tests that only open envelopes they sealed themselves may derive with fewer rounds. The app never sets it.
    @TaskLocal static var testRounds: UInt32?
    private static let headerLength = 1 + saltLength + nonceLength

    static func encrypt(_ plaintext: Data, mnemonic: String, version: Version) throws -> Data {
        try encrypt(
            plaintext, mnemonic: mnemonic, version: version,
            salt: randomBytes(saltLength), nonce: randomBytes(nonceLength))
    }

    /// Deterministic variant for test vectors. Never reuse a salt/nonce pair.
    static func encrypt(
        _ plaintext: Data, mnemonic: String, version: Version, salt: Data, nonce: Data
    ) throws -> Data {
        precondition(salt.count == saltLength && nonce.count == nonceLength)
        let key = try deriveKey(mnemonic: mnemonic, salt: salt)
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: AES.GCM.Nonce(data: nonce))

        var envelope = Data([version.rawValue])
        envelope.append(salt)
        envelope.append(nonce)
        envelope.append(sealed.ciphertext)
        envelope.append(sealed.tag)
        return envelope
    }

    static func decrypt(_ envelope: Data, mnemonic: String, expecting version: Version) throws -> Data {
        let bytes = Data(envelope)  // rebase indices to 0
        guard bytes.count >= headerLength + tagLength else { throw Failure.tooShort }
        guard bytes[0] <= Version.snapshot.rawValue else { throw Failure.newerVersion }
        guard bytes[0] == version.rawValue else { throw Failure.unexpectedVersion(bytes[0]) }

        let salt = bytes[1..<(1 + saltLength)]
        let nonce = bytes[(1 + saltLength)..<headerLength]
        let ciphertext = bytes[headerLength..<(bytes.count - tagLength)]
        let tag = bytes[(bytes.count - tagLength)...]

        let key = try deriveKey(mnemonic: mnemonic, salt: Data(salt))
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce), ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(box, using: key)
        } catch {
            throw Failure.authenticationFailed
        }
    }

    private static func deriveKey(mnemonic: String, salt: Data) throws(Failure) -> SymmetricKey {
        let password = Data(mnemonic.utf8)
        var key = Data(count: 32)
        let status = key.withUnsafeMutableBytes { keyBytes in
            salt.withUnsafeBytes { saltBytes in
                password.withUnsafeBytes { passwordBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBytes.baseAddress, password.count,
                        saltBytes.baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        testRounds ?? pbkdf2Rounds,
                        keyBytes.baseAddress, 32)
                }
            }
        }
        guard status == kCCSuccess else { throw .keyDerivationFailed(status) }
        return SymmetricKey(data: key)
    }

    private static func randomBytes(_ count: Int) -> Data {
        var bytes = Data(count: count)
        let status = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!)
        }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return bytes
    }
}
