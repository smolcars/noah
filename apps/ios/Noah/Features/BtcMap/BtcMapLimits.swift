import Foundation

/// The full v4 list measured 7.49 MB / 43,178 records on 2026-10-19 (including deletions).
enum BtcMapLimits {
    static let bytes = 32 * 1024 * 1024
    static let records = 100_000
    static let stringBytes = 16 * 1024

    static func check(_ data: Data) throws {
        guard data.count <= bytes else { throw BtcMapError.resourceLimit }
    }
    static func check(_ string: String) throws {
        guard string.utf8.count <= stringBytes else { throw BtcMapError.resourceLimit }
    }
    static func read(_ url: URL) throws -> Data {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let data = try file.read(upToCount: bytes + 1) ?? Data()
        try check(data)
        return data
    }

    static func decodeRecords<T: Decodable>(_ type: T.Type, from data: Data) throws -> [T] {
        try check(data)
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
