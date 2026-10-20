import Foundation

/// Loads shared fixtures from `apps/fixtures/<name>.json`, the same files the
/// Android tests read, so both apps are held to identical behaviour.
enum Fixtures {
    struct File<Input: Decodable, Expected: Decodable>: Decodable {
        struct Case: Decodable {
            let name: String
            let input: Input
            let expected: Expected
        }

        let cases: [Case]
    }

    static func load<Input: Decodable, Expected: Decodable>(
        _ name: String, input: Input.Type, expected: Expected.Type
    ) throws -> [File<Input, Expected>.Case] {
        try decode(name, as: File<Input, Expected>.self).cases
    }

    static func decode<Value: Decodable>(_ name: String, as type: Value.Type) throws -> Value {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // NoahTests
            .deletingLastPathComponent()  // ios
            .deletingLastPathComponent()  // apps
            .appending(path: "fixtures/\(name).json")
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(type, from: data)
    }
}
