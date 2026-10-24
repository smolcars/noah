import Testing
@testable import Noah

/// Settings → VTXOs: grouped Expiring / Spendable / Locked, the ⋯ filter, and the renew selection.
struct VtxoListTests {
    static func vtxo(_ id: String, _ amount: UInt64, expiry: UInt32, state: String = "Spendable", expiring: Bool = false,
                     tip: UInt32 = 1_000) -> VtxoModel {
        VtxoModel(id: id, amount: amount, expiry: expiry, state: state, kind: "Arkoor", exitDepth: 1, exitWeight: 500, registered: true,
                  serverPubkey: "02", exitDelta: 144, anchorPoint: "a:0", point: "\(id):0", expiring: expiring, tip: tip)
    }

    static let vtxos = [
        vtxo("soon", 4_200, expiry: 1_120, expiring: true),
        vtxo("later", 31_000, expiry: 4_000),
        vtxo("held", 1_000, expiry: 1_100, state: "Locked", expiring: true),
        vtxo("gone", 500, expiry: 990),
        vtxo("next", 24_500, expiry: 3_800),
    ]

    @Test func groupsAreExpiringSpendableLockedSoonestFirst() {
        let groups = VtxoList.groups(Self.vtxos, filter: .all)
        #expect(groups.map(\.group) == [.expiring, .spendable, .locked])
        #expect(groups[0].rows.map(\.id) == ["gone", "soon"])
        #expect(groups[1].rows.map(\.id) == ["next", "later"])
        // A locked VTXO is never "expiring": it can't be renewed from here.
        #expect(groups[2].rows.map(\.id) == ["held"])
        #expect(VtxoList.due(Self.vtxos).map(\.id) == ["soon", "gone"])
    }

    @Test func filtersKeepTheirGroups() {
        #expect(VtxoList.groups(Self.vtxos, filter: .expiring).map(\.group) == [.expiring])
        #expect(VtxoList.groups(Self.vtxos, filter: .spendable).flatMap(\.rows).map(\.id).sorted() == ["gone", "later", "next", "soon"])
        #expect(VtxoList.groups(Self.vtxos, filter: .locked).flatMap(\.rows).map(\.id) == ["held"])
        #expect(VtxoList.groups([], filter: .all).isEmpty)
    }

    @Test func selectionStartsWithTheExpiringOnesAndNeverTakesALockedOne() {
        var selection = VtxoList.preselected(Self.vtxos)
        #expect(selection == ["soon", "gone"])
        selection = VtxoList.toggle("held", in: selection, vtxos: Self.vtxos)
        #expect(selection == ["soon", "gone"])
        selection = VtxoList.toggle("later", in: selection, vtxos: Self.vtxos)
        selection = VtxoList.toggle("soon", in: selection, vtxos: Self.vtxos)
        #expect(selection == ["gone", "later"])
        // One locks while the list is open: it leaves the renewal.
        let locked = Self.vtxos.map { $0.id == "later" ? Self.vtxo("later", 31_000, expiry: 4_000, state: "Locked") : $0 }
        #expect(VtxoList.renewable(selection, in: locked) == ["gone"])
    }

    @Test func expiryWords() {
        #expect(VtxoList.expiresIn(0, locale: "en") == "Expired")
        #expect(VtxoList.expiresIn(4, locale: "en") == "Expires in under an hour")
        #expect(VtxoList.expiresIn(120, locale: "en") == "Expires in 20 hours")
        #expect(VtxoList.expiresIn(6, locale: "en") == "Expires in 1 hour")
        #expect(VtxoList.expiresIn(4_176, locale: "en") == "Expires in 29 days")
        #expect(VtxoList.span(1_008, locale: "en") == "1 wk")
    }
}
