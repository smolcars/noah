import Foundation
@testable import Noah
import Testing

struct WalletDataLocationTests {
    @Test func usesReactNativeDatadir() throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "docs-\(UUID().uuidString)")
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: documents)
        #expect(location.directory.lastPathComponent == "noah-data-regtest")
        #expect(location.database.lastPathComponent == "db.sqlite")
        #expect(!location.databaseExists)

        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        #expect(!location.databaseExists, "an empty datadir is not a wallet")
        try Data().write(to: location.database)
        #expect(location.databaseExists)
    }
}
