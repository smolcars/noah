import Foundation
import Testing

@testable import Noah

@MainActor struct SendOverrideAuditTests {
    @Test func corruptAuditIsPreservedBeforeFreshConfirmationAndJournalRemoval() throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "send-r8-audit-\(UUID())")
        defer { try? FileManager.default.removeItem(at: documents) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: documents)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var dependencies = SendPaymentController.Dependencies.live(location: location)
        dependencies.identity = nil
        let file = location.directory.appending(path: "noah-send-overrides.json")
        let corrupt = Data("corrupt audit evidence".utf8)
        try corrupt.write(to: file)
        try dependencies.write(.init(broadcastingTxid: "original", payment: SendPreparedTests.payment,
                                      snapshot: .init(movementIDs: [])))
        let write = dependencies.write
        var removals = 0
        dependencies.write = { attempt in
            if attempt == nil {
                let audit = try #require(JSONDecoder().decode([SendOverrideAudit].self, from: Data(contentsOf: file)).last)
                #expect(audit.txid == "original")
                let files = try FileManager.default.contentsOfDirectory(at: location.directory, includingPropertiesForKeys: nil)
                let preserved = try #require(files.first { $0.lastPathComponent.hasPrefix("noah-send-overrides.json.unreadable-") })
                #expect(try Data(contentsOf: preserved) == corrupt)
                #expect(try preserved.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
                removals += 1
            }
            try write(attempt)
        }
        let controller = SendPaymentController(dependencies: dependencies)
        controller.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(controller.locked && removals == 0)
        // Relaunch must not make corruption a permanent lock.
        let restarted = SendPaymentController(dependencies: dependencies)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!restarted.locked && removals == 1)
        #expect(try dependencies.read() == nil)
        #expect(!SendPaymentController(dependencies: dependencies).locked)
    }

    @Test(arguments: [false, true])
    func auditStorageFailureKeepsAttemptAcrossRestart(quarantineFailure: Bool) throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "send-r8-failure-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        // An overlong quarantine name fails its move; a missing parent fails the fresh atomic write.
        let file = quarantineFailure
            ? directory.appending(path: String(repeating: "a", count: 240))
            : directory.appending(path: "missing/audit.json")
        let corrupt = Data("corrupt".utf8)
        if quarantineFailure { try corrupt.write(to: file) }
        let storage = SendPaymentTests.Storage()
        storage.attempt = .init(broadcastingTxid: "original", payment: SendPreparedTests.payment,
                                snapshot: .init(movementIDs: []))
        let dependencies = SendPaymentController.Dependencies(
            prepare: { _, _ in Issue.record("Must not prepare"); throw SendFailure.unresolved },
            pay: { _, _, _ in Issue.record("Must not pay"); throw SendFailure.unresolved },
            reconcile: { _ in Issue.record("Must not reconcile"); return .uncertain },
            read: { storage.attempt }, write: { try storage.write($0) },
            auditOverride: { try SendOverrideAudit.append($0, to: file) })
        for _ in 0..<2 {
            let controller = SendPaymentController(dependencies: dependencies)
            controller.startNewPaymentAfterHistoryCheck(confirmed: true)
            #expect(controller.locked && storage.attempt?.broadcastingTxid == "original")
            #expect(controller.message?.contains("Unable to save the override confirmation") == true)
            if quarantineFailure { #expect(try Data(contentsOf: file) == corrupt) }
        }
    }

    @Test(arguments: [false, true])
    func legacyJournalEvidenceIsAuditedWithoutClaimingOwnership(broadcasting: Bool) async throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "send-r8-legacy-\(UUID())")
        defer { try? FileManager.default.removeItem(at: documents) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: documents)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var dependencies = SendPaymentController.Dependencies.live(location: location)
        dependencies.identity = { .init(fingerprint: "current", network: "regtest") }
        dependencies.reconcile = { _ in Issue.record("Must not reconcile a legacy journal"); return .uncertain }
        let candidates = [SendCandidate(id: 7, status: "pending", amount: 1000, createdAt: "2026-10-19")]
        try dependencies.write(.init(broadcastingTxid: broadcasting ? "legacy-txid" : nil,
                                      payment: SendPreparedTests.payment, snapshot: .init(movementIDs: []),
                                      receipt: broadcasting ? nil : .init(amount: 1000, txid: "legacy-txid"),
                                      candidates: candidates, possibleTxids: ["possible-txid"]))
        let controller = SendPaymentController(dependencies: dependencies)
        #expect(controller.locked && controller.attempt == nil)
        await controller.checkStatus()
        controller.acknowledge()
        controller.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(try dependencies.read() != nil)
        let restarted = SendPaymentController(dependencies: dependencies)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!restarted.locked && restarted.attempt == nil)
        let file = location.directory.appending(path: "noah-send-overrides.json")
        let audit = try #require(JSONDecoder().decode([SendOverrideAudit].self, from: Data(contentsOf: file)).last)
        #expect(audit.txid == "legacy-txid" && audit.walletIdentity == nil)
        #expect(audit.ownershipUnverified == true)
        #expect(audit.candidates == candidates)
        #expect(audit.possibleTxids == ["possible-txid"])
    }
}
