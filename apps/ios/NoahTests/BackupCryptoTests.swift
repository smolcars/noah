import Foundation
import Testing

@testable import Noah

struct BackupCryptoTests {
    struct Input: Decodable {
        let version: UInt8
        let mnemonic: String
        let saltHex: String
        let nonceHex: String
        let plaintextHex: String
    }

    struct Expected: Decodable {
        let envelopeHex: String
    }

    private static let mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test func matchesSharedVectors() throws {
        let cases = try Fixtures.load("backup-crypto", input: Input.self, expected: Expected.self)
        #expect(cases.count == 4)
        for testCase in cases {
            let input = testCase.input
            let version = try #require(BackupCrypto.Version(rawValue: input.version))
            let plaintext = try Data(hex: input.plaintextHex)
            let envelope = try Data(hex: testCase.expected.envelopeHex)

            let encrypted = try BackupCrypto.encrypt(
                plaintext, mnemonic: input.mnemonic, version: version,
                salt: try Data(hex: input.saltHex), nonce: try Data(hex: input.nonceHex))
            #expect(encrypted == envelope, "encrypt: \(testCase.name)")

            let decrypted = try BackupCrypto.decrypt(envelope, mnemonic: input.mnemonic, expecting: version)
            #expect(decrypted == plaintext, "decrypt: \(testCase.name)")
        }
    }

    @Test func randomSaltAndNonceRoundTrip() throws {
        let plaintext = Data("snapshot".utf8)
        let first = try BackupCrypto.encrypt(plaintext, mnemonic: Self.mnemonic, version: .snapshot)
        let second = try BackupCrypto.encrypt(plaintext, mnemonic: Self.mnemonic, version: .snapshot)
        #expect(first != second)
        #expect(first.first == 2)
        #expect(try BackupCrypto.decrypt(first, mnemonic: Self.mnemonic, expecting: .snapshot) == plaintext)
    }

    @Test func rejectsWrongMnemonicTamperingVersionAndShortInput() throws {
        let envelope = try BackupCrypto.encrypt(Data("x".utf8), mnemonic: Self.mnemonic, version: .snapshot)

        #expect(throws: BackupCrypto.Failure.authenticationFailed) {
            try BackupCrypto.decrypt(envelope, mnemonic: "legal winner thank year wave sausage worth useful legal winner thank yellow", expecting: .snapshot)
        }

        var tampered = envelope
        tampered[tampered.count - 1] ^= 0x01
        #expect(throws: BackupCrypto.Failure.authenticationFailed) {
            try BackupCrypto.decrypt(tampered, mnemonic: Self.mnemonic, expecting: .snapshot)
        }

        #expect(throws: BackupCrypto.Failure.unexpectedVersion(2)) {
            try BackupCrypto.decrypt(envelope, mnemonic: Self.mnemonic, expecting: .legacyDirectory)
        }

        #expect(throws: BackupCrypto.Failure.tooShort) {
            try BackupCrypto.decrypt(envelope.prefix(44), mnemonic: Self.mnemonic, expecting: .snapshot)
        }
    }

    @Test func decryptsSlicedData() throws {
        let envelope = try BackupCrypto.encrypt(Data("slice".utf8), mnemonic: Self.mnemonic, version: .snapshot)
        let padded = Data([0xff]) + envelope
        #expect(try BackupCrypto.decrypt(padded.dropFirst(), mnemonic: Self.mnemonic, expecting: .snapshot) == Data("slice".utf8))
    }
}

extension Data {
    init(hex: String) throws {
        struct InvalidHex: Error {}
        guard hex.count.isMultiple(of: 2) else { throw InvalidHex() }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { throw InvalidHex() }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}
