import Foundation

/// An Arbitrum address for the USDC beta: `0x` and 40 hex digits, all lower case, all upper case, or mixed case
/// matching its EIP-55 checksum (`apps/fixtures/usdc-address.json`).
enum UsdcAddress {
    static func isValid(_ value: String) -> Bool {
        guard value.count == 42, value.hasPrefix("0x") else { return false }
        let hex = value.dropFirst(2)
        guard hex.allSatisfy(\.isHexDigit) else { return false }
        if hex == hex.lowercased() || hex == hex.uppercased() { return true }
        let hash = keccak256(Array(hex.lowercased().utf8))
        for (index, character) in hex.enumerated() where character.isLetter {
            let nibble = index.isMultiple(of: 2) ? hash[index / 2] >> 4 : hash[index / 2] & 0x0F
            if character.isUppercase != (nibble >= 8) { return false }
        }
        return true
    }

    private static let roundConstants: [UInt64] = [
        0x0000_0000_0000_0001, 0x0000_0000_0000_8082, 0x8000_0000_0000_808A, 0x8000_0000_8000_8000,
        0x0000_0000_0000_808B, 0x0000_0000_8000_0001, 0x8000_0000_8000_8081, 0x8000_0000_0000_8009,
        0x0000_0000_0000_008A, 0x0000_0000_0000_0088, 0x0000_0000_8000_8009, 0x0000_0000_8000_000A,
        0x0000_0000_8000_808B, 0x8000_0000_0000_008B, 0x8000_0000_0000_8089, 0x8000_0000_0000_8003,
        0x8000_0000_0000_8002, 0x8000_0000_0000_0080, 0x0000_0000_0000_800A, 0x8000_0000_8000_000A,
        0x8000_0000_8000_8081, 0x8000_0000_0000_8080, 0x0000_0000_8000_0001, 0x8000_0000_8000_8008,
    ]
    private static let rotations: [UInt64] = [0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14]

    /// Keccak-256 as Ethereum uses it: the original padding (0x01), not SHA-3's.
    static func keccak256(_ message: [UInt8]) -> [UInt8] {
        let rate = 136
        var padded = message
        padded.append(0x01)
        while !padded.count.isMultiple(of: rate) { padded.append(0) }
        padded[padded.count - 1] |= 0x80
        var state = [UInt64](repeating: 0, count: 25)
        for block in stride(from: 0, to: padded.count, by: rate) {
            for lane in 0..<(rate / 8) {
                var word: UInt64 = 0
                for byte in 0..<8 { word |= UInt64(padded[block + lane * 8 + byte]) << (8 * UInt64(byte)) }
                state[lane] ^= word
            }
            permute(&state)
        }
        return (0..<32).map { UInt8(truncatingIfNeeded: state[$0 / 8] >> (8 * UInt64($0 % 8))) }
    }

    private static func permute(_ a: inout [UInt64]) {
        func rotl(_ x: UInt64, _ n: UInt64) -> UInt64 { n == 0 ? x : (x << n) | (x >> (64 - n)) }
        for constant in roundConstants {
            let c = (0..<5).map { a[$0] ^ a[$0 + 5] ^ a[$0 + 10] ^ a[$0 + 15] ^ a[$0 + 20] }
            for x in 0..<5 {
                let d = c[(x + 4) % 5] ^ rotl(c[(x + 1) % 5], 1)
                for y in 0..<5 { a[x + 5 * y] ^= d }
            }
            var b = [UInt64](repeating: 0, count: 25)
            for x in 0..<5 {
                for y in 0..<5 { b[y + 5 * ((2 * x + 3 * y) % 5)] = rotl(a[x + 5 * y], rotations[x + 5 * y]) }
            }
            for x in 0..<5 {
                for y in 0..<5 { a[x + 5 * y] = b[x + 5 * y] ^ (~b[(x + 1) % 5 + 5 * y] & b[(x + 2) % 5 + 5 * y]) }
            }
            a[0] ^= constant
        }
    }
}
