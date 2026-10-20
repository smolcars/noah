import Foundation
import Testing

@testable import Noah

struct ExportFilenameTests {
    @Test func filenameMatchesRNUtcTimestampWithMilliseconds() {
        #expect(ExportFilename.database(at: Date(timeIntervalSince1970: 0)) == "noah_backup_1970-01-01T00-00-00-000Z.noahbackup")
        #expect(ExportFilename.database(at: Date(timeIntervalSince1970: 1.234)) == "noah_backup_1970-01-01T00-00-01-234Z.noahbackup")
    }
}
