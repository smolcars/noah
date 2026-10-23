import CryptoKit
import Foundation

/// Checksum/amount decoding only; Bark validates payment semantics before submission.
enum PaymentEncoding {
    static let alphabet = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    struct Bech32 { let prefix: String; let words: [UInt8]; let isM: Bool }
    static func bech32(_ value: String) -> Bech32? {
        guard value == value.lowercased() || value == value.uppercased(),
              let split = value.lowercased().lastIndex(of: "1") else { return nil }
        let lower = value.lowercased(), prefix = String(value.lowercased()[..<split])
        let encoded = lower[lower.index(after: split)...]
        guard !prefix.isEmpty, encoded.count >= 6, encoded.count <= 8000,
              prefix.utf8.allSatisfy({ (33...126).contains($0) }) else { return nil }
        let words = encoded.compactMap { alphabet.firstIndex(of: $0).map(UInt8.init) }
        guard words.count == encoded.count else { return nil }
        let check = polymod(prefix, words)
        guard check == 1 || check == 0x2bc830a3 else { return nil }
        return Bech32(prefix: prefix, words: Array(words.dropLast(6)), isM: check != 1)
    }
    /// Bech32 (not bech32m), lowercase: how an `lnurl1…` carries its URL.
    static func encodeBech32(_ prefix: String, _ data: [UInt8]) -> String {
        var words: [UInt8] = [], accumulator = 0, bits = 0
        for byte in data {
            accumulator = (accumulator << 8) | Int(byte)
            bits += 8
            while bits >= 5 { bits -= 5; words.append(UInt8((accumulator >> bits) & 31)) }
            accumulator &= (1 << bits) - 1
        }
        if bits > 0 { words.append(UInt8((accumulator << (5 - bits)) & 31)) }
        let check = polymod(prefix, words + [UInt8](repeating: 0, count: 6)) ^ 1
        return prefix + "1" + String((words + (0..<6).map { UInt8((check >> (5 * (5 - $0))) & 31) }).map { alphabet[Int($0)] })
    }
    private static func polymod(_ prefix: String, _ words: [UInt8]) -> UInt32 {
        let expanded = prefix.utf8.map { $0 >> 5 } + [0] + prefix.utf8.map { $0 & 31 }
        let generators: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        var check: UInt32 = 1
        for word in expanded + words {
            let top = check >> 25
            check = ((check & 0x1ffffff) << 5) ^ UInt32(word)
            for i in 0..<5 where (top >> i) & 1 != 0 { check ^= generators[i] }
        }
        return check
    }
    static func bytes(_ words: [UInt8]) -> [UInt8]? {
        var accumulator: UInt32 = 0, bits = 0, output: [UInt8] = []
        for word in words {
            accumulator = ((accumulator << 5) | UInt32(word)) & 0xffffff
            bits += 5
            if bits >= 8 { bits -= 8; output.append(UInt8((accumulator >> bits) & 255)) }
        }
        guard bits < 5, (accumulator << (8 - bits)) & 255 == 0 else { return nil }
        return output
    }
    static func bitcoinNetwork(_ address: String) -> String? {
        if let decoded = bech32(address), address.count <= 90,
           let version = decoded.words.first, version <= 16,
           let program = bytes(Array(decoded.words.dropFirst())), (2...40).contains(program.count),
           version == 0 ? (!decoded.isM && [20,32].contains(program.count)) : decoded.isM {
            return ["bc":"mainnet", "tb":"testnet", "bcrt":"regtest"][decoded.prefix]
        }
        guard let decoded = base58Address(address) else { return nil }
        switch decoded[0] { case 0,5: return "mainnet"; case 111,196: return "testnet"; default: return nil }
    }
    private static func base58Address(_ address: String) -> [UInt8]? {
        let alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
        guard address.count <= 60 else { return nil }
        var bytes = [UInt8](repeating: 0, count: 1)
        for char in address {
            guard var carry = alphabet.firstIndex(of: char) else { return nil }
            for i in bytes.indices { carry += Int(bytes[i]) * 58; bytes[i] = UInt8(carry & 255); carry >>= 8 }
            while carry > 0 { bytes.append(UInt8(carry & 255)); carry >>= 8 }
        }
        let decoded = Array(repeating: UInt8(0), count: address.prefix(while: { $0 == "1" }).count) + bytes.reversed()
        guard decoded.count == 25 else { return nil }
        let checksum = Array(SHA256.hash(data: Data(SHA256.hash(data: Data(decoded.dropLast(4))))).prefix(4))
        guard Array(decoded.suffix(4)) == checksum else { return nil }
        return Array(decoded.dropLast(4))
    }
    static func bitcoinScript(_ address: String) -> [UInt8]? {
        guard bitcoinNetwork(address) != nil else { return nil }
        if let decoded = bech32(address), let version = decoded.words.first,
           let program = bytes(Array(decoded.words.dropFirst())) {
            return [version == 0 ? 0 : 0x50 + version, UInt8(program.count)] + program
        }
        guard let decoded = base58Address(address) else { return nil }
        let hash = Array(decoded.dropFirst())
        return [0,111].contains(decoded[0]) ? [0x76,0xa9,0x14] + hash + [0x88,0xac] : [0xa9,0x14] + hash + [0x87]
    }
    struct Invoice { let network: String; let sats: Decimal?; let hash: String?; let expires: Date; let descriptionHash: String?; var description: String? = nil }
    static func invoice(_ value: String) -> Invoice? {
        guard let decoded = bech32(value), !decoded.isM, decoded.words.count >= 111,
              let networkPrefix = ["lnbcrt", "lntbs", "lnbc", "lntb"].first(where: { decoded.prefix.hasPrefix($0) }) else { return nil }
        let hrpAmount = String(decoded.prefix.dropFirst(networkPrefix.count))
        var amount: Decimal?
        if !hrpAmount.isEmpty {
            let unit = hrpAmount.last!, suffix = ["m","u","n","p"].contains(String(unit))
            let digits = suffix ? String(hrpAmount.dropLast()) : hrpAmount
            guard !digits.isEmpty, digits.allSatisfy(\.isNumber), let value = Decimal(string: digits) else { return nil }
            if suffix && unit == "p" && !digits.hasSuffix("0") { return nil }
            let factors: [Character: Decimal] = ["m":100000,"u":100,"n":Decimal(string:"0.1")!,"p":Decimal(string:"0.0001")!]
            amount = value * (suffix ? factors[unit]! : 100000000)
        }
        let payload = Array(decoded.words.dropLast(104))
        var index = 7, hash: String?, descriptionHash: String?, description: String?, expiry: UInt64 = 3600
        while index + 3 <= payload.count {
            let tag = payload[index], count = Int(payload[index+1]) * 32 + Int(payload[index+2]); index += 3
            guard index + count <= payload.count else { return nil }
            let data = Array(payload[index..<(index+count)]); index += count
            if tag == 1, let raw = bytes(data), raw.count == 32 { hash = raw.map { String(format:"%02x",$0) }.joined() }
            if tag == 23, let raw = bytes(data), raw.count == 32 { descriptionHash = raw.map { String(format:"%02x",$0) }.joined() }
            if tag == 13, let raw = bytes(data) { description = String(bytes: raw, encoding: .utf8) }
            if tag == 6 { guard data.count <= 12 else { return nil }; expiry = data.reduce(0) { $0 * 32 + UInt64($1) } }
        }
        guard hash != nil, index == payload.count else { return nil }
        let timestamp = payload.prefix(7).reduce(UInt64(0)) { $0 * 32 + UInt64($1) }
        return Invoice(network: ["lnbc":"mainnet","lntb":"testnet","lntbs":"signet","lnbcrt":"regtest"][networkPrefix]!, sats: amount, hash: hash, expires: Date(timeIntervalSince1970: Double(timestamp) + Double(expiry)), descriptionHash: descriptionHash, description: description)
    }
    /// A BOLT12 offer's `offer_description` (TLV 10). Offers are bech32 without a checksum.
    static func offerDescription(_ offer: String) -> String? {
        let value = offer.replacingOccurrences(of: "\\+\\s*", with: "", options: .regularExpression).lowercased()
        guard value.hasPrefix("lno1") else { return nil }
        let words = value.dropFirst(4).compactMap { alphabet.firstIndex(of: $0).map(UInt8.init) }
        guard words.count == value.count - 4, let stream = bytes(words) else { return nil }
        var index = 0
        func bigSize() -> UInt64? {
            guard index < stream.count else { return nil }
            let first = stream[index]; index += 1
            let width = first == 0xff ? 8 : first == 0xfe ? 4 : first == 0xfd ? 2 : 0
            guard width > 0 else { return UInt64(first) }
            guard index + width <= stream.count else { return nil }
            defer { index += width }
            return stream[index..<(index + width)].reduce(0) { $0 << 8 | UInt64($1) }
        }
        while index < stream.count {
            guard let type = bigSize(), let length = bigSize(), length <= UInt64(stream.count - index) else { return nil }
            let end = index + Int(length)
            if type == 10 { return String(bytes: stream[index..<end], encoding: .utf8) }
            index = end
        }
        return nil
    }
}
