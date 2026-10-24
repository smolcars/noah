import Testing
@testable import Noah

/// Settings → Lightning address keeps a migrated wallet's name exactly as the server has it.
@MainActor struct LightningAddressEditTests {
    static let long = "satoshi.nakamoto.the.first.miner"
    static let npub = "npub1" + String(repeating: "q", count: 58)

    @Test func theAssignedNameIsKeptWhateverItsLength() async {
        for assigned in [Self.long, "ab", "Ab"] {
            let name = NameAvailability(debounce: .zero, rules: .edit) { _ in true }
            name.assigned = assigned
            name.text = assigned
            name.textChanged()
            #expect(name.text == assigned, "never cut down or rewritten: \(assigned)")
            #expect(name.status == .available)
        }
    }

    @Test func onboardingStillLimitsNewNames() {
        let name = NameAvailability(debounce: .zero) { _ in false }
        name.text = Self.long
        name.textChanged()
        #expect(name.text.count == NameAvailability.maxLength)
        name.text = "ab"
        name.textChanged()
        #expect(name.status == .tooShort)
    }

    @Test func aKeyChangeSavesTheExistingNameExactly() {
        for assigned in [Self.long, "ab"] {
            let edit = LightningAddressEdit(name: assigned, assigned: assigned, status: .available, npub: Self.npub, currentNpub: "")
            #expect(edit.saves && edit.username == assigned && !edit.renames)
            #expect(!LightningAddressEdit(name: assigned, assigned: assigned, status: .available, npub: "", currentNpub: "").saves,
                    "nothing changed")
        }
    }

    @Test func editsUseTodaysValidation() {
        func edit(_ name: String, _ status: NameAvailability.Status = .available, npub: String = "") -> LightningAddressEdit {
            LightningAddressEdit(name: name, assigned: "maya", status: status, npub: npub, currentNpub: "")
        }
        #expect(edit(Self.long).saves, "longer than onboarding allows, still valid for the server")
        #expect(edit("m").saves)
        #expect(!edit("maya2", .checking).saves && !edit("maya2", .taken).saves)
        #expect(edit("a b").problem == "Username may only contain a-z, 0-9, dash, underscore, and period" && !edit("a b").saves)
        #expect(!edit("maya2", npub: "nsec1abc").saves)
        #expect(!edit("").saves && edit("").problem == nil)
    }
}
