import BarkBoltFFI
import SwiftUI

struct DebugView: View {
    enum Action: String, CaseIterable {
        case ark = "Get Ark Info", debugInfo = "Get Debug Info", push = "Get Push Token", refresh = "Refresh Server"
        case maintenance = "Maintenance", maintenanceRefresh = "Maintenance Refresh", delegated = "Maintenance Delegated"
        case offboard = "Offboard All", decode = "Decode VTXO Hex", importVtxo = "Import VTXO"
        case drop = "Drop VTXO", unlock = "Unlock VTXO"
        var needsInput: Bool { [.offboard, .decode, .importVtxo, .drop, .unlock].contains(self) }
    }
    private enum Sheet: String, Identifiable { case actions, confirm; var id: Self { self } }
    @State private var action = Action.ark
    @State private var input = ""
    @State private var result: String?
    @State private var busy = false
    @State private var sheet: Sheet?
    @State private var confirmation = ""
    @State private var haptics = OnboardingHaptics()
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale

    private var language: String { locale.identifier }
    /// Today's typed-"delete" gate.
    private var destructive: Bool { action == .drop || action == .offboard }

    var body: some View {
        SettingsScroll(line: L10n.text("For developers. Some actions change your wallet.", locale: language)) {
            Text(L10n.text("Action", locale: language)).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 20)
            actionButton
            if action.needsInput {
                SettingsField(label: L10n.text(action == .offboard ? "Bitcoin address" : "VTXO hex or ID", locale: language),
                              placeholder: action == .offboard ? "bcrt1q…" : "…", text: $input, multiline: action == .decode || action == .importVtxo,
                              id: "debug-input")
                    .disabled(busy)
            }
            resultSection
        }
        .settingsChrome("Debug")
        .settingsFooter {
            Button {
                if destructive { confirmation = ""; sheet = .confirm } else { execute() }
            } label: {
                HStack(spacing: 8) {
                    if busy { ProgressView().tint(destructive ? .white : NoahTokens.Colors.background) }
                    Text(L10n.text(busy ? "Running" : "Run", locale: language))
                }
            }
            .buttonStyle(SettingsActionStyle(destructive: destructive))
            .disabled(busy || (action.needsInput && input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
            .accessibilityIdentifier("debug-run")
        }
        .onboardingHaptics(haptics)
        .onChange(of: action) { _, _ in input = ""; result = nil }
        .settingsSheet(item: $sheet) { sheet in
            switch sheet {
            case .actions:
                SettingsSheetBody(title: L10n.text("Action", locale: language)) {
                    VStack(spacing: 0) {
                        ForEach(Action.allCases, id: \.self) { item in
                            SettingsOption(title: L10n.text(item.rawValue, locale: language), on: item == action,
                                           failure: item == .drop || item == .offboard) {
                                haptics.play(.selection)
                                action = item
                                self.sheet = nil
                            }
                        }
                    }
                    .padding(.top, 6)
                } actions: { EmptyView() }
            case .confirm:
                SettingsSheetBody(title: L10n.text("{action}?", L10n.text(action.rawValue, locale: language), locale: language),
                                  line: L10n.text("This changes your wallet and can lose funds.", locale: language)) {
                    SettingsField(placeholder: L10n.text("Type delete to confirm", locale: language), text: $confirmation, id: "debug-delete-input")
                        .onChange(of: DeleteChecklist.confirms(confirmation)) { _, matches in if matches { endEditing() } }
                } actions: {
                    SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Confirm", locale: language), destructive: true,
                                         enabled: DeleteChecklist.confirms(confirmation), actionID: "debug-confirm",
                                         onCancel: { self.sheet = nil }) {
                        self.sheet = nil
                        execute()
                    }
                }
            }
        }
    }

    private var actionButton: some View {
        Button {
            haptics.play(.light)
            sheet = .actions
        } label: {
            HStack {
                Text(LocalizedStringKey(action.rawValue)).noahText(NoahTokens.Typography.body)
                    .foregroundStyle(destructive ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                Spacer()
                Image(systemName: "chevron.right").accessibilityHidden(true).font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NoahTokens.Colors.muted)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 54)
            .glassCompat(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 8)
        .disabled(busy)
        .accessibilityIdentifier("debug-action")
    }

    @ViewBuilder private var resultSection: some View {
        if let result {
            Text(L10n.text("Result", locale: language)).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 20)
            Text(verbatim: result).font(.system(size: 12.5, design: .monospaced)).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16).padding(.vertical, 14)
                .background(NoahTokens.Colors.soft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .padding(.top, 8)
            Button(L10n.text("Copy", locale: language), systemImage: "doc.on.doc") {
                SecureCopy.copy(result)
                haptics.play(.success)
                settings.toast("Copied")
            }
            .buttonStyle(HomePillStyle(prominent: false, height: 40))
            .padding(.top, 10)
        }
    }

    private func execute() {
        guard !busy else { return }; busy = true; result = nil
        if destructive && !DeleteChecklist.confirms(confirmation) { busy = false; return }
        let action = action
        let input = input.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            defer { busy = false }
            do {
                if action == .push {
                    switch try await PushRegistration.shared.register() {
                    case .registered(let token): result = token
                    case .permissionDenied: result = "Push permission not granted"
                    case .deviceNotSupported: result = "Push tokens are only available on a physical device"
                    }
                    return
                }
                result = try await WalletSession.shared.withOpenWallet { handles in try await Self.run(action, input: input, handles: handles) }
            } catch { result = SafeErrorDescription.of(error) }
        }
    }

    private static func run(_ action: Action, input: String, handles: WalletSession.Handles) async throws -> String {
        switch action {
        case .ark: return try DebugJSON.ark(try await handles.wallet.arkInfo())
        // Shown only here for support; the xpub must never reach logs or Sentry.
        case .debugInfo: return try DebugJSON.debugInfo(try await handles.wallet.debugInfo())
        case .refresh: try await handles.wallet.refreshServer(); return "Server refreshed"
        case .maintenance: try await handles.wallet.maintenance(); return "Maintenance completed"
        case .maintenanceRefresh: return try await handles.wallet.maintenanceRefresh() ?? "Maintenance refresh completed"
        case .delegated: try await handles.wallet.maintenanceDelegated(); return "Delegated maintenance completed"
        case .offboard: return String(describing: try await handles.wallet.offboardAll(bitcoinAddress: input))
        case .decode: return try DebugJSON.decoded(try decodeVtxo(hexOrBase64: input))
        case .importVtxo: return try DebugJSON.imported(try await handles.wallet.importVtxos(encodedVtxos: [input]))
        case .drop: try await handles.wallet.dangerousDropVtxo(id: input); return "VTXO removed"
        case .unlock: try await handles.wallet.unlockVtxos(vtxoIds: [input], expectedHolder: nil); return "VTXO unlocked"
        case .push: return ""
        }
    }
}
