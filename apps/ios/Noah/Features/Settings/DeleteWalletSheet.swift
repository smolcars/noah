import Observation
import SwiftUI

/// A delete in progress, its typed word and its error. Settings keeps it outside the sheet, so a
/// rotation or a pose change (which rebuilds the sheet) can't unlock Cancel or Delete mid-way.
@MainActor @Observable
final class WalletDeletion {
    var confirmation = ""
    private(set) var deleting = false
    private(set) var failure: String?

    var confirmed: Bool { DeleteChecklist.confirms(confirmation) }

    /// One delete at a time, only once the word matches. True when the wallet is gone.
    @discardableResult
    func delete(_ action: () async throws -> Void) async -> Bool {
        guard confirmed, !deleting else { return false }
        deleting = true
        failure = nil
        defer { deleting = false }
        do {
            try await action()
            return true
        } catch {
            failure = (error as? WalletLifecycle.Failure)?.errorDescription ?? "Couldn't delete wallet. Restart Noah and try again."
            return false
        }
    }
}

/// Delete wallet: what you'd need to restore, then type "delete" (trimmed, any case). The button
/// turns red only when it matches; while deleting the sheet locks. Errors and the return to
/// onboarding are today's. Launch's blocked states open it too, without a wallet to describe.
struct DeleteWalletSheet: View {
    /// Settings passes its own; launch's blocked states use one of their own.
    var deletion: WalletDeletion?
    /// nil closes with `dismiss` (a plain `.sheet`).
    var close: (() -> Void)?
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var own = WalletDeletion()
    @State private var haptics = OnboardingHaptics()
    @Environment(\.settingsFixture) private var fixture

    private var language: String { locale.identifier }
    private var progress: WalletDeletion { deletion ?? own }
    private var deleting: Bool { progress.deleting }
    private var confirmed: Bool { progress.confirmed }
    private var summary: WalletSession.Summary? { fixture?.summary ?? model.summary }
    private var checklist: DeleteChecklist {
        DeleteChecklist(summary: summary, needsPhraseBackup: model.needsRecoveryPhraseBackup,
                        backup: (fixture?.storage ?? .shared).persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init())
    }

    var body: some View {
        SettingsSheetBody(title: L10n.text("Delete wallet", locale: language),
                          line: L10n.text("This removes the wallet and its recovery phrase from this phone. It can’t be undone.", locale: language)) {
            if summary != nil { rows.padding(.top, 12) }
            SettingsField(placeholder: L10n.text("Type delete to confirm", locale: language),
                          text: Binding(get: { progress.confirmation }, set: { progress.confirmation = $0 }), id: "delete-wallet-input")
                .disabled(deleting)
            if let failure = progress.failure {
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
        // The word typed: the keyboard steps aside so the red Delete is in reach.
        .onChange(of: confirmed) { _, matches in
            guard matches else { return }
            haptics.play(.selection)
            endEditing()
        }
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
        Task {
            if await progress.delete({ try await model.deleteWallet() }) { leave() }
        }
    }
}
