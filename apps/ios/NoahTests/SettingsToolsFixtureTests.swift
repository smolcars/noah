import Foundation
@testable import Noah
import Testing

@MainActor struct SettingsToolsFixtureTests {
    struct Height: Decodable { let variant: String; let override: String?; let host: String; let expected: String }
    struct Filename: Decodable { let milliseconds: Double; let expected: String }
    struct Imports: Decodable { let ids: [String]; let pretty: String }
    struct Logs: Decodable { let lines: [String]; let error: String }
    struct Table: Decodable {
        let blockHeight: [Height]; let filenames: [Filename]; let imports: Imports; let logs: Logs
    }
    @Test func sharedToolsParity() async throws {
        let table = try Fixtures.decode("settings-tools", as: Table.self)
        for row in table.blockHeight {
            let variant = try #require(AppVariant(rawValue: row.variant))
            #expect(ArkInfoEndpoints.blockHeight(variant: variant, esploraOverride: row.override, regtestHost: row.host) == row.expected)
        }
        for row in table.filenames {
            #expect(ExportFilename.database(at: Date(timeIntervalSince1970: row.milliseconds / 1000)) == row.expected)
        }
        #expect(try DebugJSON.imported(table.imports.ids) == table.imports.pretty)
        let logs = LogSnapshot()
        await logs.load(read: { table.logs.lines })
        #expect(logs.lines.last == table.logs.lines.last)
        await logs.load { throw CocoaError(.fileReadNoPermission) }
        #expect(logs.error == table.logs.error)
    }
}
