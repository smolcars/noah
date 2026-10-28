import Foundation

/// Loads shared fixtures from `apps/fixtures/<name>.json`, the same files the
/// Android tests read, so both apps are held to identical behaviour.
enum Fixtures {
    struct Case<Input: Decodable, Expected: Decodable>: Decodable {
        let name: String
        let input: Input
        let expected: Expected
    }

    struct File<Input: Decodable, Expected: Decodable>: Decodable {
        let cases: [Case<Input, Expected>]
    }

    static func load<Input: Decodable, Expected: Decodable>(
        _ name: String, input _: Input.Type, expected _: Expected.Type) throws -> [Case<Input, Expected>] {
        try decode(name, as: File<Input, Expected>.self).cases
    }

    static func decode<Value: Decodable>(_ name: String, as type: Value.Type) throws -> Value {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // NoahTests
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // apps
            .appending(path: "fixtures/\(name).json")
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(type, from: data)
    }

    /// A JSON object compared the way Foundation compares it (key order ignored, NSNumber equality).
    static func foundationJSON(_ data: Data) throws -> NSDictionary? { // swiftlint:disable:this legacy_objc_type - NSDictionary equality is the point
        try JSONSerialization.jsonObject(with: data) as? NSDictionary // swiftlint:disable:this legacy_objc_type - as above
    }
}
