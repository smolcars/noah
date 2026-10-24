import SwiftUI

/// Delete wallet: what you'd need to restore, then type "delete" (trimmed, any case). The button
/// turns red only when it matches; while deleting the sheet locks. Errors and the return to
/// onboarding are today's. Launch's blocked states open it too, without a wallet to describe.
struct DeleteWalletSheet: View {
    var locked: Binding<Bool> = .constant(false)
    /// nil closes with `dismiss` (a plain `.sheet`).
    var close: (() -> Void)?
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var confirmation = ""
    @State private var deleting = false
    @State private var failure: String?
    @State private var haptics = OnboardingHaptics()

    private var language: String { locale.identifier }
    private var confirmed: Bool { DeleteChecklist.confirms(confirmation) }
    private var checklist: DeleteChecklist {
        DeleteChecklist(summary: model.summary, needsPhraseBackup: model.needsRecoveryPhraseBackup,
                        backup: SettingsStorage.shared.persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init())
    }

    var body: some View {
        SettingsSheetBody(title: L10n.text("Delete wallet", locale: language),
                          line: L10n.text("This removes the wallet and its recovery phrase from this phone. It can’t be undone.", locale: language)) {
            if model.summary != nil { rows.padding(.top, 12) }
            SettingsField(placeholder: L10n.text("Type delete to confirm", locale: language), text: $confirmation, id: "delete-wallet-input")
                .disabled(deleting)
            if let failure {
                Text(LocalizedStringKey(failure)).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.failure)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            }
        } actions: {
            SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language),
                                 action: L10n.text(deleting ? "Deleting" : "Delete", locale: language),
                                 destructive: true, enabled: confirmed, busy: deleting, actionID: "delete-wallet-confirm",
                                 onCancel: leave, onAction: delete)
        }
        .onChange(of: confirmed) { _, matches in if matches { haptics.play(.selection) } }
        .onboardingHaptics(haptics)
        .interactiveDismissDisabled(deleting)
    }

    @ViewBuilder private var rows: some View {
        let checklist = checklist
        if let balance = checklist.balance {
            SettingsDetailRow(label: L10n.text("In this wallet", locale: language),
                              value: model.isBalanceHidden ? (model.amountUnit == .bip177 ? "₿••••" : "•••• sats")
                                  : BitcoinAmount.format(sats: Int64(clamping: balance), unit: model.amountUnit, locale: locale),
                              numeric: true)
        }
        SettingsDetailRow(label: L10n.text("Recovery phrase", locale: language),
                          value: L10n.text(checklist.phraseSaved ? "Saved" : "Not saved", locale: language),
                          failure: !checklist.phraseSaved,
                          action: checklist.phraseSaved || deleting ? nil : {
                              leave()
                              settings.select(.recovery)
                          })
        SettingsDetailRow(label: L10n.text("Last backup", locale: language), value: backupText(checklist.backup),
                          failure: checklist.backup == .failed, hairline: false)
    }

    private func backupText(_ backup: DeleteChecklist.Backup) -> String {
        switch backup {
        case .never: L10n.text("Never", locale: language)
        case .failed: L10n.text("Failed", locale: language)
        case .at(let date): date.formatted(.dateTime.month(.abbreviated).day().hour().minute().locale(locale))
        }
    }

    private func leave() {
        guard !deleting else { return }
        if let close { close() } else { dismiss() }
    }

    private func delete() {
        guard confirmed, !deleting else { return }
        haptics.play(.warning)
        deleting = true
        locked.wrappedValue = true
        failure = nil
        Task {
            do {
                try await model.deleteWallet()
                locked.wrappedValue = false
                deleting = false
                leave()
            } catch {
                failure = (error as? WalletLifecycle.Failure)?.errorDescription
                    ?? "The wallet couldn't be deleted. Restart Noah and try again."
                deleting = false
                locked.wrappedValue = false
            }
        }
    }
}
