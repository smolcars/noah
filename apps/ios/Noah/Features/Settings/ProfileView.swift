import SwiftUI

struct PreferenceView: View {
    enum Kind { case language, currency, unit }
    let kind: Kind
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var error: String?
    private var title: LocalizedStringKey {
        switch kind { case .language: "Language"; case .currency: "Currency"; case .unit: "Bitcoin Unit" }
    }
    private var options: [String] {
        switch kind { case .language: ["en", "es", "ja", "ko", "sv"]; case .currency: FiatCurrency.supported; case .unit: BitcoinAmountUnit.allCases.map(\.rawValue) }
    }
    private var selected: String {
        switch kind { case .language: model.localeIdentifier; case .currency: model.preferredCurrency; case .unit: model.amountUnit.rawValue }
    }
    var body: some View {
        List {
            ForEach(options, id: \.self) { option in
                Button { select(option) } label: {
                    HStack {
                        SettingsLabel(title: name(option), detail: detail(option))
                        Spacer()
                        if selected == option { Image(systemName: "checkmark").accessibilityLabel("Selected") }
                    }.frame(minHeight: 44)
                }.accessibilityIdentifier("preference-\(option)")
            }
            if let error { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
        }.settingsStyle(title)
    }
    private func name(_ code: String) -> String {
        switch kind {
        case .language: Locale(identifier: code).localizedString(forLanguageCode: code)?.localizedCapitalized ?? code
        case .currency: locale.localizedString(forCurrencyCode: code) ?? code
        case .unit: code == "bip177" ? "Bitcoin (BIP-177)" : "Satoshis"
        }
    }
    static func summary(kind: Kind, code: String, locale: Locale) -> String {
        switch kind {
        case .language: Locale(identifier: code).localizedString(forLanguageCode: code)?.localizedCapitalized ?? code
        case .currency: "\(code) · \(locale.localizedString(forCurrencyCode: code) ?? code)"
        case .unit: "\(code == "sats" ? "Satoshis" : "Bitcoin (BIP-177)") · \(BitcoinAmount.format(sats: 1234, unit: BitcoinAmountUnit(rawValue: code) ?? .bip177, locale: locale))"
        }
    }
    private func detail(_ code: String) -> String {
        kind == .unit ? BitcoinAmount.format(sats: 1234, unit: BitcoinAmountUnit(rawValue: code) ?? .bip177, locale: locale) : code.uppercased()
    }
    private func select(_ code: String) {
        do {
            switch kind {
            case .language: model.setLanguage(code)
            case .currency: try model.updateProfile { $0.preferredCurrency = code }
            case .unit: try model.updateProfile { $0.bitcoinAmountUnit = code }
            }
            dismiss()
        } catch { self.error = "Couldn't save your preference. Please try again." }
    }
}

struct ProfileView: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var key = ""
    @State private var busy = false
    @State private var message: String?
    @State private var showEmail = false
    @State private var showIdentity = false
    private enum Field { case name }
    @FocusState private var focus: Field?

    var body: some View {
        Form {
            Section("Name") {
                TextField("Name", text: $name).focused($focus, equals: .name).submitLabel(.done)
                    .onChange(of: name) { _, value in name = Self.limitedName(value) }
                    .onSubmit { save() }.accessibilityIdentifier("profile-name-input")
                Button(busy ? "Saving..." : "Save", action: save).disabled(busy)
                if let message { Text(LocalizedStringKey(message)).font(NoahFont.footnote) }
            }
            Section("Lightning Address") {
                if let address = model.server.state.lightningAddress { CopySettingRow(title: "Lightning Address", value: address) }
                Button("Edit Lightning Address") { showIdentity = true }
            }
            Section("Emergency Email") {
                if model.server.state.isEmailVerified == true, let email = model.server.state.emailAddress {
                    CopySettingRow(title: "Address", value: email)
                } else {
                    Text(LocalizedStringKey(model.server.state.emailAddress ?? "No emergency email set"))
                }
                Text(LocalizedStringKey(model.server.state.isEmailVerified == true ? "Verified" : "Not verified")).font(NoahFont.footnote)
                Button("Edit Emergency Email") { showEmail = true }
            }
            Section("Public Keys") {
                if !key.isEmpty { CopySettingRow(title: "Wallet public key", value: key) }
                if !Nostr.npub(from: model.server.state.nostrPubkey).isEmpty {
                    CopySettingRow(title: "Nostr Public Key", value: Nostr.npub(from: model.server.state.nostrPubkey))
                }
            }
        }
        .settingsStyle("Profile")
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focus = nil } } }
        .navigationDestination(isPresented: $showEmail) { EmergencyEmailView { showEmail = false } }
        .navigationDestination(isPresented: $showIdentity) { LightningAddressView { showIdentity = false } }
        .task {
            name = Self.limitedName(SettingsStorage.shared.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.displayName ?? "")
            do {
                // RN displays the server identity's offline derivation, not the wallet's hardened key.
                key = try ServerClient.loginKey(variant: model.variant, mnemonic: AppServices.shared.readMnemonic())
                try await model.server.refreshProfile()
            } catch { message = "Couldn't refresh your profile. Please try again." }
        }
    }
    nonisolated static func limitedName(_ value: String) -> String {
        var length = 0
        return String(value.prefix { character in
            length += String(character).utf16.count
            return length <= 80
        })
    }

    private func save() {
        guard !busy else { return }; busy = true; message = nil; focus = nil
        Task {
            defer { busy = false }
            do { try await model.server.saveDisplayName(name); name = name.trimmingCharacters(in: .whitespacesAndNewlines); message = "Saved" }
            catch { message = "Couldn't save your name. Please try again." }
        }
    }
}

struct CopySettingRow: View {
    let title: String
    let value: String
    @State private var copied = false
    var body: some View {
        Button {
            UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: value]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
            copied = true
        } label: {
            HStack {
                SettingsLabel(title: title, detail: value).lineLimit(2).truncationMode(.middle)
                Spacer()
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
            }
        }.accessibilityLabel("Copy \(title)").accessibilityValue(copied ? "Copied" : value)
    }
}
