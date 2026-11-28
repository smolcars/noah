import BarkBoltFFI
import CryptoKit
import Foundation
@testable import Noah
import Testing
import UserNotifications

/// LNURL-pay payer data on received payments: the shared fixtures, the push the extension rewrites, the pass that
/// stores the data on Bark's movements, and History's rows.
struct LnurlPayReceiveTests {
    private static func json(_ name: String) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: Fixtures.data(name)) as? [String: Any])
    }

    /// The notification fixture's key.
    private static func fixtureKey(_ fixture: [String: Any]) throws -> SymmetricKey {
        let encoded = try #require(fixture["key"] as? String)
        return try SymmetricKey(data: #require(Data(base64Encoded: encoded)))
    }

    private static func sameJSON(_ lhs: Any, _ rhs: Any) throws -> Bool {
        try JSONSerialization.data(withJSONObject: lhs, options: .sortedKeys) == JSONSerialization.data(withJSONObject: rhs, options: .sortedKeys)
    }

    @Test func metadataFixture() throws {
        let cases = try #require(Self.json("lnurl-pay-receive-metadata")["cases"] as? [[String: Any]])
        #expect(cases.count == 11)
        for fixture in cases {
            let name = try #require(fixture["name"] as? String)
            let input = try #require(fixture["input"] as? [String: Any])
            if input["kind"] as? String == "patch" {
                let payer = input["payer_data"] as? [String: Any]
                let metadata = LnurlPayReceive.checked(name: payer?["name"] as? String, identifier: payer?["identifier"] as? String,
                                                       comment: input["comment"] as? String)
                let patch = try JSONSerialization.jsonObject(with: Data(metadata.patch().utf8))
                #expect(try Self.sameJSON(patch, #require(fixture["expected"])), "\(name)")
            } else {
                let parsed = LnurlPayReceive.parse(input["metadata"] as? String)
                guard let expected = fixture["expected"] as? [String: Any] else {
                    #expect(parsed == nil, "\(name)")
                    continue
                }
                #expect(parsed == LnurlPayReceive(name: expected["name"] as? String, identifier: expected["identifier"] as? String,
                                                  comment: expected["comment"] as? String), "\(name)")
                #expect(parsed?.payerLabel == expected["label"] as? String, "\(name)")
            }
        }
    }

    @Test func notificationFixtureOpensOnlyForItsRecord() throws {
        let fixture = try Self.json("lnurl-pay-receive-notification")
        let key = try Self.fixtureKey(fixture)
        let cases = try #require(fixture["cases"] as? [[String: Any]])
        #expect(cases.count == 6)
        for item in cases {
            let name = try #require(item["name"] as? String)
            let opened = try LnurlPayPush.open(id: #require(item["id"] as? String), sealed: #require(item["sealed"] as? String), key: key)
            if let expected = item["expected"] as? [String: Any] {
                let decoded = try LnurlPayPush.decoder.decode(LnurlPayPush.Content.self,
                                                              from: JSONSerialization.data(withJSONObject: expected))
                #expect(opened == decoded, "\(name)")
            } else {
                #expect(opened == nil, "\(name)")
            }
        }
    }

    @Test func theExtensionNamesThePayerAndPassesEverythingElseThrough() throws {
        let fixture = try Self.json("lnurl-pay-receive-notification")
        let key = try Self.fixtureKey(fixture)
        let first = try #require((fixture["cases"] as? [[String: Any]])?.first)
        let field: [String: Any] = try ["v": 1, "id": #require(first["id"]), "sealed": #require(first["sealed"])]
        let body = try String(json: JSONSerialization.data(withJSONObject: [
            "notification_type": "ark_payment_received", "lnurl_pay_receive": field,
        ]))
        let original = UNMutableNotificationContent()
        original.title = "Payment received"
        original.body = "You received 9880 sats via Ark."
        original.userInfo = ["body": body]

        let rewritten = try #require(LnurlPayPush.rewritten(original, key: key, group: nil, variant: .regtest))
        #expect(rewritten.title == "Alice Payer (⚡ alice@example.com)")
        #expect(rewritten.body.components(separatedBy: "\n").last == "Thanks for the coffee ☕")
        // No key, another key, or a push without the field: shown as it came.
        #expect(LnurlPayPush.rewritten(original, key: nil, group: nil, variant: .regtest) == nil)
        #expect(LnurlPayPush.rewritten(original, key: SymmetricKey(size: .bits256), group: nil, variant: .regtest) == nil)
        original.userInfo = ["body": #"{"notification_type":"ark_payment_received"}"#]
        #expect(LnurlPayPush.rewritten(original, key: key, group: nil, variant: .regtest) == nil)
    }

    @Test func nobodyNamedKeepsTheUsualTitleWithTheComment() {
        let copy = LnurlPayPush.copy(.init(amountSat: 1000, payerData: nil, comment: "From the meetup"), unit: .sats,
                                     defaults: .standard)
        #expect(copy.body.components(separatedBy: "\n").last == "From the meetup")
        #expect(copy.title != "From the meetup")
    }

    // MARK: The pass

    private static let outputs = ["aa:0", "bb:1"]

    private static func movement(_ id: UInt32, outputs: [String], metadata: String = "{}", status: String = "successful") -> Movement {
        Movement(id: id, status: status, subsystemName: "bark.arkoor", subsystemKind: "receive", metadataJson: metadata,
                 intendedBalanceSats: 9880, effectiveBalanceSats: 9880, offchainFeeSats: 0,
                 sentToAddresses: [], receivedOnAddresses: [],
                 inputVtxoIds: [], outputVtxoIds: outputs, exitedVtxoIds: [],
                 createdAt: "2026-11-27T09:12:00Z", updatedAt: "2026-11-27T09:12:00Z", completedAt: nil,
                 paymentHash: nil, lightningInvoice: nil, lightningOffer: nil)
    }

    private static func record(_ id: String, outputs: [String]) -> LnurlPayReceiveSync.Record {
        .init(amountSat: 9880, comment: "Thanks", id: id, payerData: .init(identifier: nil, name: "Alice"),
              target: .case2(.init(_type: .arkOutputVtxos, vtxoIds: outputs)))
    }

    /// Movements that take the patches written, and the backups and acknowledgements the pass made.
    private actor Wallet {
        var movements: [Movement]
        var writes: [UInt32] = []
        var acknowledged: [[String]] = []
        var backups = 0
        var lists = 0
        var waits: [Duration] = []
        func list(_ records: [LnurlPayReceiveSync.Record], readyAfter: Int) -> [LnurlPayReceiveSync.Record] {
            lists += 1
            return lists > readyAfter ? records : []
        }
        func sleep(_ delay: Duration) { waits.append(delay) }

        let keepsWrites: Bool

        init(_ movements: [Movement], keepsWrites: Bool = true) {
            self.movements = movements
            self.keepsWrites = keepsWrites
        }

        func write(_ id: UInt32, _ patch: String) {
            writes.append(id)
            guard keepsWrites else { return }
            movements = movements.map {
                $0.id == id ? LnurlPayReceiveTests.movement($0.id, outputs: $0.outputVtxoIds, metadata: patch, status: $0.status) : $0
            }
        }

        func backup() { backups += 1 }
        func acknowledge(_ ids: [String]) { acknowledged.append(ids) }

        nonisolated func sync(_ records: [LnurlPayReceiveSync.Record], readyAfter: Int = 0) -> LnurlPayReceiveSync {
            LnurlPayReceiveSync(.init(
                list: { await self.list(records, readyAfter: readyAfter) }, movements: { await self.movements },
                write: { await self.write($0, $1) }, backup: { await self.backup() },
                acknowledge: { await self.acknowledge($0) }))
        }
    }

    @Test func storesEachRecordOnTheReceiveWithExactlyItsOutputsThenAcknowledges() async throws {
        let wallet = Wallet([Self.movement(1, outputs: ["aa:0"]), Self.movement(2, outputs: Self.outputs.reversed()),
                             Self.movement(3, outputs: Self.outputs, status: "pending")])
        let sync = wallet.sync([Self.record("r1", outputs: Self.outputs)])
        let stored = try await sync.run()

        #expect(stored == ["r1"])
        #expect(await wallet.writes == [2])
        #expect(await LnurlPayReceive.parse(wallet.movements[1].metadataJson) == .init(name: "Alice", comment: "Thanks"))
        #expect(await wallet.backups == 1)
        #expect(await wallet.acknowledged == [["r1"]])
        #expect(try await sync.isStored(Self.movement(2, outputs: Self.outputs)))
    }

    @Test func aRecordWhoseReceiveHasNotSyncedWaits() async throws {
        let wallet = Wallet([Self.movement(1, outputs: ["aa:0"])])
        let sync = wallet.sync([Self.record("r1", outputs: Self.outputs)])
        #expect(try await sync.run().isEmpty)
        #expect(await wallet.writes.isEmpty)
        #expect(await wallet.acknowledged.isEmpty)
        #expect(try await !sync.isStored(Self.movement(2, outputs: Self.outputs)))
        let hash = LnurlPayReceiveSync.Record(amountSat: 1, id: "r2",
                                              target: .case1(.init(paymentHash: "00", _type: .lightningPaymentHash)))
        #expect(LnurlPayReceiveSync.arkOutputs(hash).isEmpty)
    }

    @Test func anAlreadyStoredRecordIsAcknowledgedWithoutAnotherWrite() async throws {
        let stored = try LnurlPayReceive.checked(name: "Alice", identifier: nil, comment: "Thanks").patch()
        let wallet = Wallet([Self.movement(2, outputs: Self.outputs, metadata: stored)])
        #expect(try await wallet.sync([Self.record("r1", outputs: Self.outputs)]).run() == ["r1"])
        #expect(await wallet.writes.isEmpty)
        #expect(await wallet.backups == 0)
    }

    @Test func aWriteThatDidNotStickIsNotAcknowledged() async throws {
        let wallet = Wallet([Self.movement(2, outputs: Self.outputs)], keepsWrites: false)
        #expect(try await wallet.sync([Self.record("r1", outputs: Self.outputs)]).run().isEmpty)
        #expect(await wallet.acknowledged.isEmpty)
    }

    @Test func walletReceiveRetriesUntilServerRecordIsReadyWithoutAPush() async throws {
        let movement = Self.movement(2, outputs: Self.outputs)
        let wallet = Wallet([movement])
        let sync = wallet.sync([Self.record("r1", outputs: Self.outputs)], readyAfter: 1)
        await LnurlPayReceiveSync.retryReceive(sleep: { await wallet.sleep($0) }) {
            _ = try? await sync.run()
            return (try? await sync.isStored(movement)) == true
        }
        #expect(await wallet.waits == [.zero, .seconds(2)])
        #expect(await wallet.writes == [2])
        #expect(try await sync.isStored(movement))
        // IDs can be reused by a replacement wallet: output identity must match too.
        #expect(try await !sync.isStored(Self.movement(2, outputs: ["other:0"])))
    }

    @Test(.timeLimit(.minutes(1))) func aBusyPassDoesNotConsumeTheReceiveRetries() async throws {
        let movement = Self.movement(2, outputs: Self.outputs)
        let wallet = Wallet([movement])
        let gate = RecurringReservationTests.Gate()
        let sync = LnurlPayReceiveSync(.init(
            list: { if !(await gate.entered) { await gate.wait() }; return [Self.record("r1", outputs: Self.outputs)] },
            movements: { await wallet.movements }, write: { await wallet.write($0, $1) },
            backup: { await wallet.backup() }, acknowledge: { await wallet.acknowledge($0) }))
        let running = Task { try await sync.run() }
        while !(await gate.entered) { await Task.yield() }
        await LnurlPayReceiveSync.retryReceive(sleep: { delay in
            await wallet.sleep(delay)
            if delay != .zero {
                await gate.release(); _ = try await running.value
            }
        }) {
            _ = try? await sync.run()
            return (try? await sync.isStored(movement)) == true
        }
        #expect(await wallet.waits == [.zero, .seconds(2)])
        #expect(await wallet.writes == [2])
        #expect(try await sync.isStored(movement))
    }

    @Test func unmatchedReceiveStopsAfterTheShortRetries() async {
        let wallet = Wallet([Self.movement(2, outputs: Self.outputs)])
        let sync = wallet.sync([])
        await LnurlPayReceiveSync.retryReceive(sleep: { await wallet.sleep($0) }) {
            _ = try? await sync.run()
            return false
        }
        #expect(await wallet.waits == [.zero, .seconds(2), .seconds(5)])
        #expect(await wallet.lists == 3)
        #expect(await wallet.writes.isEmpty)
    }

    @Test func cancellationStopsReceiveRetries() async {
        let wallet = Wallet([])
        await LnurlPayReceiveSync.retryReceive(sleep: { _ in throw CancellationError() }) {
            await wallet.backup()
            return false
        }
        #expect(await wallet.backups == 0)
    }

    @Test func onlySuccessfulUnlabelledArkoorReceivesTrigger() throws {
        let movement = Self.movement(2, outputs: Self.outputs)
        #expect(LnurlPayReceiveSync.needsMetadata(movement))
        #expect(!LnurlPayReceiveSync.needsMetadata(Self.movement(2, outputs: Self.outputs, status: "pending")))
        #expect(!LnurlPayReceiveSync.needsMetadata(Self.movement(2, outputs: [])))
        let metadata = try LnurlPayReceive.checked(name: nil, identifier: nil, comment: nil).patch()
        #expect(!LnurlPayReceiveSync.needsMetadata(Self.movement(2, outputs: Self.outputs, metadata: metadata)))
        var send = movement
        send.subsystemKind = "send"
        #expect(!LnurlPayReceiveSync.needsMetadata(send))
        send.subsystemKind = "receive"
        send.subsystemName = "bark.lightning_receive"
        #expect(!LnurlPayReceiveSync.needsMetadata(send))
    }

    // MARK: History

    @Test func historyNamesThePayerOnIncomingRows() throws {
        let metadata = try LnurlPayReceive.checked(name: "Alice Payer", identifier: "alice@example.com", comment: "Thanks").patch()
        let row = HistoryMapping.transaction(Self.movement(2, outputs: Self.outputs, metadata: metadata))
        #expect(row.lnurlPay == .init(name: "Alice Payer", identifier: "alice@example.com", comment: "Thanks"))
        // Details show the name and the Lightning address on their own rows.
        let detail = ActivityDetail(row, ownDomain: "noahwallet.io")
        #expect(detail.payer == row.lnurlPay)
        // It arrived over Ark: the row and Details' "Via" agree.
        let home = HomeActivityRow(row, ownDomain: "noahwallet.io")
        #expect(home.title == "Alice Payer")
        #expect(home.kind == .ark)
        #expect(detail.rail == .ark)
        #expect(HistoryMapping.transaction(Self.movement(2, outputs: Self.outputs)).lnurlPay == nil)
    }
}
