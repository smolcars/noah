import Foundation

/// The full v4 list measured 7.49 MB / 43,178 records on 2026-10-19 (including deletions).
enum BtcMapLimits {
    static let bytes = 32 * 1024 * 1024
    static let records = 100_000
    static let stringBytes = 16 * 1024
    static let nestingDepth = 64

    static func check(_ data: Data) throws {
        guard data.count <= bytes else { throw BtcMapError.resourceLimit }
    }
    /// Scan before Foundation decoding, including unknown fields, without using the call stack.
    static func checkJSON(_ data: Data) throws {
        try check(data)
        var depth = 0
        var quoted = false
        var escaped = false
        for byte in data {
            if quoted {
                if escaped { escaped = false }
                else if byte == 0x5C { escaped = true }
                else if byte == 0x22 { quoted = false }
            } else {
                switch byte {
                case 0x22: quoted = true
                case 0x5B, 0x7B:
                    depth += 1
                    guard depth <= nestingDepth else { throw BtcMapError.resourceLimit }
                case 0x5D, 0x7D: depth -= 1
                default: break
                }
            }
        }
    }

    static func check(_ string: String) throws {
        guard string.utf8.count <= stringBytes else { throw BtcMapError.resourceLimit }
    }
    /// Count metadata, per-record encodings and commas before allocating the full cache JSON.
    static func encodedSize(_ snapshot: BtcMapSnapshot) throws -> Int {
        let encoder = JSONEncoder()
        var metadata = snapshot
        metadata.places = []
        var size = try encoder.encode(metadata).count
        for (index, place) in snapshot.places.enumerated() {
            size += try encoder.encode(place).count + (index == 0 ? 0 : 1)
            guard size <= bytes else { throw BtcMapError.resourceLimit }
        }
        guard size <= bytes else { throw BtcMapError.resourceLimit }
        return size
    }

    static func read(_ url: URL) throws -> Data {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let data = try file.read(upToCount: bytes + 1) ?? Data()
        try check(data)
        return data
    }

    static func decodeRecords<T: Decodable>(_: T.Type, from data: Data) throws -> [T] {
        try checkJSON(data)
        return try JSONDecoder().decode(Records<T>.self, from: data).values
    }

    struct Records<T: Decodable>: Decodable {
        let values: [T]
        init(from decoder: Decoder) throws {
            var container = try decoder.unkeyedContainer()
            if let count = container.count, count > records { throw BtcMapError.resourceLimit }
            var result: [T] = []
            while !container.isAtEnd {
                guard result.count < records else { throw BtcMapError.resourceLimit }
                result.append(try container.decode(T.self))
            }
            values = result
        }
    }
}
