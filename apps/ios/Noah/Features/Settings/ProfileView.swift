import Observation
import SwiftUI

/// Profile: you, then Name, Lightning address, Email, your Nostr key, the wallet key and My code.
struct ProfileView: View {
    private enum Sheet: String, Identifiable { case name; var id: Self { self } }

    @Environment(AppModel.self) private var model
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var name = ""
    @State private var key = ""
    @State private var sheet: Sheet?
    /// The name being edited lives here, not in the sheet: rotating rebuilds the sheet.
    @State private var nameDraft = ProfileNameDraft()
    @State private var message: String?
    @State private var haptics = OnboardingHaptics()
    @Environment(\.settingsFixture) private var fixture

    private var language: String { locale.identifier }
    private var server: PersistedServerState { model.server.state }
    private var username: String? { server.lightningAddress?.split(separator: "@").first.map(String.init) }
    private var npub: String { Nostr.npub(from: server.nostrPubkey) }
    private var copies: ProfileCopies { ProfileCopies(server) }

    var body: some View {
        SettingsScroll {
            header
            details
            SettingsFine(text: L10n.text("Your email is only for urgent alerts about your wallet.", locale: language))
            if let message { SettingsFine(text: L10n.text(message, locale: language), failure: true) }
        }
        .settingsChrome(nil)
        .onboardingHaptics(haptics)
        .settingsSheet(item: $sheet, locked: nameDraft.busy) { _ in
            ProfileNameSheet(draft: nameDraft) { saved in
                sheet = nil
                if let saved { name = saved }
            }
        }
        .task {
            name = Self.limitedName((fixture?.storage ?? .shared).persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.displayName ?? "")
            if fixture != nil { key = "03" + String(repeating: "7c", count: 32); return }
            do {
                // RN displays the server identity's offline derivation, not the wallet's hardened key.
                key = try ServerClient.loginKey(variant: model.variant, mnemonic: AppServices.shared.readMnemonic())
                try await model.server.refreshProfile()
            } catch { message = "Couldn't refresh your profile. Please try again." }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsInitial(name: username, size: 84).padding(.top, 6)
            Text(verbatim: name.isEmpty ? (username ?? L10n.text("Profile", locale: language)) : name)
                .noahText(SettingsText.heroName).padding(.top, 16)
            if let address = server.lightningAddress {
                Text(verbatim: address).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 2)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var details: some View {
        VStack(spacing: 0) {
            SettingsDetailRow(label: L10n.text("Name", locale: language), value: name.isEmpty ? L10n.text("Add", locale: language) : name) {
                haptics.play(.light)
                nameDraft.begin(name)
                sheet = .name
            }
            .accessibilityIdentifier("profile-name")
            SettingsDetailRow(label: L10n.text("Lightning address", locale: language), value: server.lightningAddress ?? L10n.text("Add", locale: language),
                              copy: copies.address) {
                open(.lightningAddress)
            }
            .accessibilityIdentifier("profile-lightning-address")
            SettingsDetailRow(label: L10n.text("Email", locale: language), value: emailValue, copy: copies.email,
                              glyph: server.isEmailVerified == true ? "checkmark.circle.fill" : nil,
                              glyphColor: NoahTokens.Colors.orange) { open(.email) }
                .accessibilityIdentifier("profile-email")
            if !npub.isEmpty {
                SettingsDetailRow(label: L10n.text("Nostr key", locale: language), value: npub, mono: true, copy: copies.npub) { open(.lightningAddress) }
            }
            if !key.isEmpty {
                SettingsDetailRow(label: L10n.text("Wallet key", locale: language), value: key, mono: true, copy: key)
            }
            SettingsDetailRow(label: L10n.text("My code", locale: language), value: "", glyph: "qrcode", hairline: false) {
                haptics.play(.light)
                settings.myCode()
            }
        }
        .padding(.top, 22)
    }

    private var emailValue: String {
        guard let email = server.emailAddress, !email.isEmpty else { return L10n.text("Add", locale: language) }
        return server.isEmailVerified == true ? email : L10n.text("Not verified", locale: language)
    }

    private func open(_ page: SettingsPage) {
        haptics.play(.light)
        settings.open(page)
    }

    nonisolated static func limitedName(_ value: String) -> String {
        var length = 0
        return String(value.prefix { character in
            length += String(character).utf16.count
            return length <= 80
        })
    }
}

/// What Profile copies beside editing, as before: your Lightning address, a verified email and your npub.
struct ProfileCopies: Equatable {
    let address: String?
    let email: String?
    let npub: String?

    init(_ server: PersistedServerState) {
        address = server.lightningAddress.flatMap { $0.isEmpty ? nil : $0 }
        email = server.isEmailVerified == true ? server.emailAddress.flatMap { $0.isEmpty ? nil : $0 } : nil
        let npub = Nostr.npub(from: server.nostrPubkey)
        self.npub = npub.isEmpty ? nil : npub
    }
}

/// The name sheet's draft, busy state and error. Profile owns it, so a rotation or a pose change,
/// which rebuilds the sheet, keeps what was typed and a save in progress.
@MainActor @Observable
final class ProfileNameDraft {
    var text = "" {
        didSet { if text != ProfileView.limitedName(text) { text = ProfileView.limitedName(text) } }
    }
    private(set) var busy = false
    private(set) var error: String?

    /// A fresh edit of the current name.
    func begin(_ current: String) {
        guard !busy else { return }
        text = current
        error = nil
    }

    /// Saves once at a time; the saved name (trimmed), or nil after a failure.
    func save(_ action: (String) async throws -> Void) async -> String? {
        guard !busy else { return nil }
        busy = true
        error = nil
        defer { busy = false }
        do {
            try await action(text)
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            self.error = "Couldn't save your name."
            return nil
        }
    }
}

/// Profile → Name: today's 80-character name, saved to the server.
private struct ProfileNameSheet: View {
    let draft: ProfileNameDraft
    /// The saved name, or nil when cancelled.
    let done: (String?) -> Void
    @Environment(AppModel.self) private var model
    @Environment(SettingsSummary.self) private var summary: SettingsSummary?
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var haptics = OnboardingHaptics()

    var body: some View {
        @Bindable var draft = draft
        SettingsSheetBody(title: L10n.text("Name", locale: locale.identifier), line: L10n.text("Shown to people you pay.", locale: locale.identifier)) {
            SettingsField(placeholder: L10n.text("Name", locale: locale.identifier), text: $draft.text, id: "profile-name-input")
                .disabled(draft.busy)
            if let error = draft.error { SettingsFine(text: L10n.text(error, locale: locale.identifier), failure: true) }
        } actions: {
            SettingsSheetButtons(cancel: L10n.text("Cancel", locale: locale.identifier), action: L10n.text("Save", locale: locale.identifier),
                                 busy: draft.busy, actionID: "profile-name-save", onCancel: { done(nil) }, onAction: save)
        }
        .onboardingHaptics(haptics)
        .interactiveDismissDisabled(draft.busy)
    }

    private func save() {
        Task<Void, Never> {
            guard let saved = await draft.save({ try await model.server.saveDisplayName($0) }) else { return }
            summary?.reloadLocal()
            haptics.play(.success)
            settings.toast("Saved")
            done(saved)
        }
    }
}

/// Profile → Lightning address: onboarding's live name field with availability and suggestions,
/// and the optional Nostr key. Saving is the authority; the lookup is only a hint.
struct LightningAddressPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.settings) private var settings
    @Environment(\.settingsPage) private var currentPage
    @Environment(\.locale) private var locale
    @State private var name = NameAvailability(rules: .edit, lookup: NameAvailability.liveLookup(variant: .current))
    @State private var npub = ""
    @State private var busy = false
    @State private var error: String?
    @State private var haptics = OnboardingHaptics()
    @FocusState private var focused: Bool
    @Environment(\.settingsFixture) private var fixture

    private var language: String { locale.identifier }
    private var assigned: String { model.server.state.lightningAddress?.components(separatedBy: "@").first ?? "" }
    private var domain: String {
        model.server.state.lightningAddress?.split(separator: "@").last.map(String.init) ?? LightningIdentity.domain(for: model.variant)
    }
    private var key: String { npub.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var privateKeyPasted: Bool { key.lowercased().hasPrefix("nsec") }
    private var edit: LightningAddressEdit {
        LightningAddressEdit(name: name.text, assigned: assigned, status: name.status, npub: npub,
                             currentNpub: Nostr.npub(from: model.server.state.nostrPubkey))
    }
    private var canSave: Bool { !busy && edit.saves }

    var body: some View {
        SettingsScroll(line: L10n.text("How people pay you.", locale: language)) {
            NameHandleField(name: name, domain: domain, focused: $focused, label: "Lightning address") { if canSave { save() } }
                .padding(.top, 30)
                .disabled(busy)
            NameStatusLine(status: name.status, message: statusMessage, failure: privateKeyPasted || error != nil || edit.problem != nil)
                .padding(.top, 14)
            NameSuggestionChips(name: name, haptics: haptics).padding(.top, 12)
            // The name this wallet already has, after editing it (today's reserved-name chip).
            if !assigned.isEmpty && name.text != assigned {
                Button(L10n.text("Use {name}", assigned, locale: language)) {
                    name.use(assigned)
                    haptics.play(.selection)
                }
                .buttonStyle(OnboardingChipStyle())
            }
            SettingsField(label: L10n.text("Nostr key (optional)", locale: language), placeholder: "npub1…", text: $npub,
                          failure: privateKeyPasted, id: "nostr-public-key-input")
                .disabled(busy)
            SettingsFine(text: L10n.text("Adds a NIP-05 identity to the same address. Never paste an nsec.", locale: language))
        }
        .settingsChrome("Lightning address")
        .settingsFooter {
            Button(action: save) {
                HStack(spacing: 8) {
                    if busy { ProgressView().tint(NoahTokens.Colors.background) }
                    Text(L10n.text("Save", locale: language))
                }
            }
            .buttonStyle(SettingsActionStyle())
            .disabled(!canSave)
            .accessibilityIdentifier("lightning-address-save")
        }
        .onboardingHaptics(haptics)
        .onChange(of: name.text) { _, _ in
            error = nil
            name.textChanged()
        }
        .onChange(of: npub) { _, _ in error = nil }
        .onChange(of: name.status) { _, status in
            if status == .available && name.text != assigned { haptics.play(.selection) }
            if status == .taken { haptics.play(.warning) }
        }
        .task {
            do {
                if fixture == nil { try await model.server.ensureRegistered() }
                name.assigned = assigned
                name.text = assigned
                name.textChanged()
                npub = Nostr.npub(from: model.server.state.nostrPubkey)
            } catch { self.error = "Failed to register with server. Please try again." }
        }
    }

    private var statusMessage: String? {
        if privateKeyPasted { return L10n.text("Never paste a private key. Use your npub.", locale: language) }
        if let error { return L10n.text(error, locale: language) }
        if let problem = edit.problem { return L10n.text(problem, locale: language) }
        if name.text == assigned && name.status == .available { return L10n.text("Your address", locale: language) }
        return nil
    }

    private func save() {
        guard canSave else { return }
        focused = false
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                try await model.server.saveIdentity(username: edit.username, npub: key)
                haptics.play(.success)
                settings.toast("Saved")
                settings.leave(currentPage)
            } catch {
                haptics.play(.error)
                self.error = ServerFailure.message(for: error, fallback: "Failed to update Lightning address. Please try again.")
            }
        }
    }
}

/// What saving the Lightning address page would send. The wallet's own name goes back exactly as
/// the server has it; an edit passes today's validation (`LightningIdentity.payload`), not onboarding's
/// new-name rules; the availability lookup is only a hint.
struct LightningAddressEdit: Equatable {
    let name: String
    let assigned: String
    let status: NameAvailability.Status
    let npub: String
    let currentNpub: String

    private var key: String { npub.trimmingCharacters(in: .whitespacesAndNewlines) }
    var renames: Bool { name != assigned }
    var username: String { renames ? LightningIdentity.normalizeUsername(name) : assigned }

    /// Today's validation message for the name or key, if any.
    var problem: String? {
        guard !username.isEmpty else { return nil }
        do { _ = try LightningIdentity.payload(username: username, npub: key); return nil }
        catch let validation as IdentityValidationError { return validation.errorDescription }
        catch { return nil }
    }

    var saves: Bool {
        guard !username.isEmpty, problem == nil else { return false }
        if renames { return status == .available }
        return key != currentNpub
    }
}

/// Profile → Email: onboarding's flow (address, six code boxes, resend, the verified ring).
/// "Already verified" skips the code; the code's expiry is unchanged, just not written here.
struct EmailPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var email: EmailVerification?
    @State private var haptics = OnboardingHaptics()
    @State private var registrationFailed = false
    @Environment(\.settingsFixture) private var fixture
    @Namespace private var glass
    @FocusState private var focus: Field?
    private enum Field { case email, code }

    private var language: String { locale.identifier }
    private var server: PersistedServerState { model.server.state }

    var body: some View {
        Group {
            if let email { content(email) } else { Color.clear }
        }
        .settingsChrome("Email")
        .onboardingHaptics(haptics)
        .task {
            if email == nil {
                let server = model.server
                email = EmailVerification(send: { try await server.sendEmail($0) }, verify: { try await server.verifyEmail(code: $0, email: $1) })
            }
            guard fixture == nil else { return }
            do { try await model.server.ensureRegistered() } catch { registrationFailed = true }
        }
    }

    private func content(_ email: EmailVerification) -> some View {
        SettingsScroll(line: line(email)) {
            switch email.stage {
            case .entry, .sending: entry(email)
            case .verified: verified(email)
            default: code(email)
            }
            if registrationFailed {
                SettingsFine(text: L10n.text("Failed to register with server. Please try again.", locale: language), failure: true)
            }
        }
        .settingsFooter {
            switch email.stage {
            case .entry, .sending:
                Button {
                    haptics.play(.light)
                    Task { await email.sendCode() }
                } label: {
                    HStack(spacing: 8) {
                        if email.stage == .sending { ProgressView().tint(NoahTokens.Colors.background) }
                        Text(L10n.text("Send code", locale: language))
                    }
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(!email.isValid || email.stage == .sending || registrationFailed)
                .accessibilityIdentifier("onboarding-email-send")
            case .verified:
                Button(L10n.text("Done", locale: language)) { settings.back() }.buttonStyle(SettingsActionStyle())
            default:
                EmptyView()
            }
        }
        .onChange(of: email.text) { _, _ in email.textChanged() }
        .onChange(of: email.code) { _, _ in Task { await email.codeChanged() } }
        .onChange(of: email.stage) { _, stage in
            switch stage {
            case .code: focus = .code
            case .wrongCode: haptics.play(.error)
            case .verified:
                focus = nil
                haptics.play(.success)
            default: break
            }
        }
    }

    private func line(_ email: EmailVerification) -> String? {
        switch email.stage {
        case .entry, .sending: L10n.text("Only for urgent alerts about your wallet.", locale: language)
        case .verified: nil
        default: L10n.text("Enter the code we sent to {email}", email.email, locale: language)
        }
    }

    @ViewBuilder private func entry(_ email: EmailVerification) -> some View {
        @Bindable var email = email
        VStack(alignment: .leading, spacing: 4) {
            if let current = server.emailAddress, !current.isEmpty {
                Text(verbatim: current).noahText(SettingsText.current).lineLimit(1).truncationMode(.middle)
                HStack(spacing: 6) {
                    if server.isEmailVerified == true {
                        Image(systemName: "checkmark.circle.fill").accessibilityHidden(true).foregroundStyle(NoahTokens.Colors.orange)
                        Text(L10n.text("Verified", locale: language))
                    } else {
                        Circle().fill(NoahTokens.Colors.orange).frame(width: 8, height: 8)
                        Text(L10n.text("Not verified", locale: language))
                    }
                }
                .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
            } else {
                Text(L10n.text("No email yet", locale: language)).noahText(SettingsText.current)
            }
        }
        .padding(.top, 20)
        SettingsField(label: L10n.text(server.emailAddress?.isEmpty == false ? "New email" : "Email", locale: language),
                      placeholder: "you@example.com", text: $email.text, keyboard: .emailAddress, id: "email-address-input")
            .disabled(email.stage == .sending)
        EmailHints(email: email, haptics: haptics).padding(.top, 10)
        if let error = email.error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
        SettingsFine(text: L10n.text(server.isEmailVerified == true ? "Your current email stays until the new one is verified."
                : "Optional. We only write when your bitcoin needs you.", locale: language))
    }

    @ViewBuilder private func code(_ email: EmailVerification) -> some View {
        @Bindable var email = email
        CodeBoxes(email: email, focused: focus == .code, namespace: glass)
            .overlay {
                // One invisible field drives the boxes, so one-time-code autofill works.
                TextField("", text: $email.code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .focused($focus, equals: .code)
                    .foregroundStyle(.clear)
                    .tint(.clear)
                    .accessibilityLabel(Text("Verification code"))
                    .accessibilityValue(Text(verbatim: email.code.map(String.init).joined(separator: " ")))
                    .accessibilityIdentifier("verification-code-input")
            }
            .padding(.top, 28)
            .onAppear { focus = .code }
        if let error = email.error {
            Text(LocalizedStringKey(error)).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.failure)
                .padding(.top, 14)
                .accessibilityIdentifier("onboarding-email-error")
        }
        EmailResend(email: email, haptics: haptics).padding(.top, 10)
        Button(L10n.text("Change email", locale: language)) { email.edit() }
            .noahText(HomeText.strong)
            .foregroundStyle(NoahTokens.Colors.foreground)
            .frame(minHeight: NoahTokens.Size.hitTarget)
            .disabled(email.stage == .verifying || email.isSending)
            .accessibilityIdentifier("onboarding-email-edit")
    }

    private func verified(_ email: EmailVerification) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VerifiedCheck().frame(width: 72, height: 72).padding(.top, 10)
            Text(L10n.text("Email verified", locale: language)).noahText(SettingsText.heroName).padding(.top, 16)
            Text(verbatim: email.email).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A copyable value row in today's Ark info and Debug screens.
struct CopySettingRow: View {
    let title: String
    let value: String
    @State private var copied = false
    var body: some View {
        Button {
            SecureCopy.copy(value)
            copied = true
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    Text(LocalizedStringKey(title)).font(NoahFont.callout)
                    Text(verbatim: value).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground).lineLimit(2).truncationMode(.middle)
                }
                Spacer()
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
            }
        }.accessibilityLabel("Copy \(title)").accessibilityValue(copied ? "Copied" : value)
    }
}
