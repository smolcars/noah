import Foundation
@testable import Noah
import SQLite3
import Testing

@MainActor struct RecurringStoreTests {
    private func settings() throws -> SettingsStorage {
        SettingsStorage.bootstrap()
        return try .init(rootPath: FileManager.default.temporaryDirectory.appending(path: "recurring-mmkv-\(UUID())").path, instanceID: UUID().uuidString)
    }
    @Test func rnUpgradeAndUnknownFieldsRoundTrip() throws {
        let settings = try settings()
        let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "fixtures/recurring-storage.json")
        var envelope = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as? [String: Any])
        var state = try #require(envelope["state"] as? [String: Any])
        var schedules = try #require(state["schedules"] as? [String: Any])
        var rent = try #require(schedules["rent"] as? [String: Any])
        rent["futureField"] = "preserve me"; schedules["rent"] = rent
        state["schedules"] = schedules; state["futureState"] = true
        envelope["state"] = state; envelope["futureEnvelope"] = 123
        try settings.set(#require(String(bytes: JSONSerialization.data(withJSONObject: envelope), encoding: .utf8)), forKey: RecurringStore.key)
        let store = RecurringStore(settings: settings)
        var payment = try #require(store.load().schedules["rent"])
        #expect(payment.label == "Rent" && payment.amountSat == 50_000)
        payment.inFlight = .init(occurrenceIndex: 0, startedAt: 1)
        try store.upsert(payment)
        payment.inFlight = nil; payment.status = "paused"
        try store.upsert(payment)
        let raw = try #require(settings.string(forKey: RecurringStore.key))
        #expect(raw.contains("futureField") && raw.contains("futureState") && raw.contains("futureEnvelope"))
        #expect(try store.load().schedules["rent"]?.inFlight == nil)
        #expect(try store.load().schedules["rent"]?.status == "paused")
    }
    @Test func unreadableAndFutureVersionsNeverBecomeEmpty() throws {
        for raw in ["not JSON", #"{"version":1,"state":{"schedules":{}}}"#, #"{"version":0,"state":{"schedules":[]}}"#] {
            let settings = try settings()
            let store = RecurringStore(settings: settings)
            settings.set(raw, forKey: RecurringStore.key)
            #expect(throws: (any Error).self) { try store.load() }
            #expect(throws: (any Error).self) { try store.upsert(RecurringScheduleTests.schedule()) }
            #expect(settings.string(forKey: RecurringStore.key) == raw)
        }
    }
    @Test func legacyReplacementCannotAdoptUnboundSchedules() throws {
        let store = RecurringStore(settings: try settings())
        let schedule = RecurringScheduleTests.schedule()
        try store.upsert(schedule)
        try store.protectLegacyBeforeReplacement()
        #expect(try store.load().binding?.wallet.fingerprint == "unverified-previous-wallet")
        #expect(try store.load().schedules[schedule.id] == schedule)
    }
    @Test func realWalletTokenChangesAfterSameSeedRestoreAndPendingMutationsRefuse() throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "recurring-identity-\(UUID())")
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: documents)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var db: OpaquePointer?
        try #require(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        try #require(sqlite3_exec(
            db,
            "CREATE TABLE bark_properties (id TEXT PRIMARY KEY, network TEXT, fingerprint TEXT); INSERT INTO bark"
                + "_properties VALUES ('1', 'regtest', 'same-seed');",
            nil,
            nil,
            nil) == SQLITE_OK)
        sqlite3_close(db)
        let first = try RecurringStore.binding(location: location)
        let token = location.directory.appending(path: ".noah-recurring-instance")
        #expect(try token.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        try FileManager.default.removeItem(at: token)
        let restored = try RecurringStore.binding(location: location)
        #expect(first.wallet == restored.wallet && first.instance != restored.instance)
        try Data().write(to: location.deletionMarker)
        #expect(throws: WalletSession.OpenError.restorePending) { try RecurringStore.binding(location: location) }
    }
    @Test func realMMKVReopenRetainsBeforeSendMarker() throws {
        SettingsStorage.bootstrap()
        let path = FileManager.default.temporaryDirectory.appending(path: "recurring-reopen-\(UUID())").path
        let id = UUID().uuidString
        let store = RecurringStore(settings: try .init(rootPath: path, instanceID: id))
        var schedule = RecurringScheduleTests.schedule()
        schedule.inFlight = .init(occurrenceIndex: 4, startedAt: 12345)
        try store.upsert(schedule)
        let reopened = RecurringStore(settings: try .init(rootPath: path, instanceID: id))
        #expect(try reopened.load().schedules[schedule.id] == schedule)
    }
}
