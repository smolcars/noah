import SwiftUI

struct LightningAddressView: View {
    let onContinue: () -> Void
    private enum Field: Hashable { case username, npub }
    @Environment(AppModel.self) private var model
    @FocusState private var focus: Field?
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    private var compactKeyboard: Bool { verticalSizeClass == .compact && focus != nil }
    @State private var username = ""
    @State private var npub = ""
    @State private var busy = false
    @State private var success = false
    @State private var errorMessage: String?

    private var domain: String { LightningIdentity.domain(for: model.variant) }
    private var normalizedKey: String { npub.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var address: String { "\(LightningIdentity.normalizeUsername(username))@\(domain)" }
    private var assignedUsername: String { model.server.state.lightningAddress?.components(separatedBy: "@").first ?? "" }
    private var unchanged: Bool {
        address == model.server.state.lightningAddress && normalizedKey == Nostr.npub(from: model.server.state.nostrPubkey)
    }

    var body: some View {
        ScrollViewReader { scroll in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text("Choose your Lightning Address").font(NoahFont.title)
                    if success {
                        Label("Success!", systemImage: "checkmark.circle").foregroundStyle(NoahColor.success)
                        Text(LocalizedStringKey(normalizedKey.isEmpty
                             ? "Your Lightning address has been updated without NIP-05."
                             : "Your Lightning address and NIP-05 identity have been updated."))
                    }
                    Text("Choose a Lightning username. You can optionally link a Nostr public key so the same address also works as a NIP-05 identifier.")
                        .foregroundStyle(NoahColor.mutedForeground)
                    NoahCard {
                        VStack(alignment: .leading, spacing: 20) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Username").font(NoahFont.footnote)
                                TextField("fiatjaf", text: $username,
                                          prompt: Text("fiatjaf").foregroundStyle(NoahColor.mutedForeground))
                                    .keyboardType(.emailAddress)
                                    .focused($focus, equals: .username)
                                    .id(Field.username)
                                    .submitLabel(.next)
                                    .onSubmit { focus = .npub }
                                    .onChange(of: username) { _, value in username = LightningIdentity.normalizeUsername(value) }
                                    .frame(minHeight: 44)
                                    .accessibilityIdentifier("lightning-username-input")
                                // RN's suggestions endpoint finds existing recipients, not available names.
                                // Registration supplies a suggestion that is already reserved for this wallet.
                                if !assignedUsername.isEmpty && username != assignedUsername {
                                    Button { username = assignedUsername } label: {
                                        Text("Use \(assignedUsername)")
                                            .font(NoahFont.callout)
                                            .padding(.horizontal, 12)
                                            .frame(minHeight: 44)
                                            .glassCompat(in: Capsule())
                                    }
                                }
                            }
                            VStack(alignment: .leading, spacing: 8) {
                                Text(LocalizedStringKey(normalizedKey.isEmpty ? "Your Lightning address will be" : "Your Lightning address and NIP-05 identifier will be"))
                                    .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                                Text(address).font(NoahFont.callout).textSelection(.enabled)
                            }
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Nostr public key (optional)").font(NoahFont.footnote)
                                TextField("npub1...", text: $npub,
                                          prompt: Text("npub1...").foregroundStyle(NoahColor.mutedForeground))
                                    .focused($focus, equals: .npub)
                                    .id(Field.npub)
                                    .submitLabel(.done)
                                    .onSubmit { focus = nil }
                                    .frame(minHeight: 44)
                                    .accessibilityIdentifier("nostr-public-key-input")
                                Text("Leave this blank to use Lightning without NIP-05. Only npub public keys are accepted; never paste an nsec private key.")
                                    .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                            }
                        }
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(busy)
                    }
                    if compactKeyboard { actions }
                }
                .font(NoahFont.body)
                .padding(20)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .scrollFocusedField(focus, using: scroll)
            .foregroundStyle(NoahColor.foreground)
            .background(NoahColor.background.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                if !compactKeyboard { actions }
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focus = nil }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(busy)
            .task {
                do {
                    try await model.server.ensureRegistered()
                    username = assignedUsername
                    npub = Nostr.npub(from: model.server.state.nostrPubkey)
                } catch { errorMessage = "Failed to register with server. Please try again." }
            }
            .alert("Error", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(LocalizedStringKey(errorMessage ?? "")) }
        }
    }

    private var actions: some View {
        HStack(spacing: 16) {
            NoahButton(title: "Skip", kind: .secondary) { focus = nil; onContinue() }.disabled(busy)
            NoahButton(title: unchanged ? "Continue" : "Save", isLoading: busy, action: save)
                .disabled(username.isEmpty)
        }
        .padding(20)
        .frame(maxWidth: 640)
        .frame(maxWidth: .infinity)
        .background(NoahColor.background)
    }

    private func save() {
        guard !busy else { return }
        focus = nil
        if unchanged { onContinue(); return }
        busy = true
        Task {
            defer { busy = false }
            do {
                try await model.server.saveIdentity(username: username, npub: npub)
                success = true
                try await Task.sleep(for: .seconds(2))
                onContinue()
            } catch {
                errorMessage = ServerFailure.message(for: error, fallback: "Failed to update Lightning address. Please try again.")
            }
        }
    }
}
