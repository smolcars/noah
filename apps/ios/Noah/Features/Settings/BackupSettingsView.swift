import SwiftUI

/// Security → Backup: the status mark, Back up now, the automatic switch, your backups (each
/// deletable after a confirmation), Export file, and how to restore. Behaviour is today's.
struct BackupSettingsView: View {
    private enum Sheet: Identifiable, Equatable {
        case export
        case delete(Components.Schemas.BackupObjectInfo)
        var id: String {
            switch self {
            case .export: "export"
            case .delete(let backup): backup.backupId
            }
        }
    }

    @Environment(AppModel.self) private var model
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var state = PersistedBackupState()
    // swiftlint:disable:next discouraged_optional_collection - nil until the list loads
    @State private var backups: [Components.Schemas.BackupObjectInfo]?
    /// The last list read failed; shown instead of a spinner that never ends.
    @State private var listFailed = false
    @State private var listOpen = false
    @State private var busy = false
    @State private var error: String?
    @State private var sheet: Sheet?
    @State private var file: SharedFile?
    @State private var sharing = false
    @State private var shared = false
    @State private var haptics = OnboardingHaptics()
    @Environment(\.settingsFixture) private var fixture

    private var language: String { locale.identifier }
    private var enabled: Bool { model.server.state.isBackupEnabled == true }
    private var status: BackupStatus { BackupStatus(state, enabled: enabled) }

    var body: some View {
        SettingsScroll {
            SettingsStatusHeader(mark: mark, title: L10n.text(status.title, locale: language), detail: detail, failure: status.mark == .failed)
                .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft), value: status.mark)
            SettingsGroup {
                SettingsRow(icon: "arrow.clockwise", title: L10n.text(status.mark == .failed ? "Try again" : "Back up now", locale: language),
                            bold: true, trailing: .empty, id: "backup-now", action: backUp)
                    .disabled(busy || status.mark == .running)
                SettingsToggleRow(icon: "icloud", title: L10n.text("Automatic backups", locale: language),
                                  isOn: Binding(get: { enabled }, set: setEnabled), id: "backup-automatic")
                    .disabled(busy)
                SettingsRow(icon: "doc.text", title: L10n.text("Backups", locale: language), value: backups.map { $0.count.formatted(.number.locale(locale)) },
                            expanded: listOpen, hairline: !listOpen, id: "backup-list") {
                    haptics.play(.selection)
                    withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft)) { listOpen.toggle() }
                    if listOpen && backups == nil { Task { await refreshList() } }
                }
                if listOpen { list.transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top))) }
                SettingsRow(icon: "square.and.arrow.up", title: L10n.text("Export file", locale: language), hairline: false, id: "backup-export") {
                    haptics.play(.light)
                    sheet = .export
                }
                .disabled(busy)
            }
            if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
            SettingsFine(text: L10n.text(
                "Encrypted with your recovery phrase before it leaves this phone. To restore, enter your recovery phrase when you set up Noah on a phone.",
                locale: language))
        }
        .settingsChrome("Backup")
        .onboardingHaptics(haptics)
        .settingsSheet(item: $sheet, locked: busy) { sheet in
            switch sheet {
            case .export: exportSheet
            case .delete(let backup): deleteSheet(backup)
            }
        }
        .sheet(isPresented: $sharing, onDismiss: {
            file?.discard()
            file = nil
            if shared { settings.toast("Exported") }
        }) {
            if let file { FileShareSheet(file: file) { shared = $0 } }
        }
        .task {
            while !Task.isCancelled {
                reloadState()
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
        .task { await refreshList() }
    }

    // MARK: Status

    private var mark: SettingsStatusMark.Kind {
        switch status.mark {
        case .done: .done
        case .running: .running
        case .failed: .failed
        case .pending: .idle("clock")
        case .off, .never: .idle("icloud")
        }
    }

    private var detail: String {
        let last = status.lastBackup.map(when)
        switch status.mark {
        case .done: return last ?? ""
        case .running: return L10n.text("Encrypting and uploading", locale: language)
        case .failed: return last.map { L10n.text("Last good backup {date}", $0, locale: language) } ?? L10n.text("Try again", locale: language)
        case .pending: return L10n.text("Backup pending", locale: language)
        case .off: return last.map { L10n.text("Last backup {date}", $0, locale: language) } ?? ""
        case .never: return L10n.text(enabled ? "Backs up when your wallet changes" : "Turn it on or back up now", locale: language)
        }
    }

    /// "Today at 7:12", "Oct 20 at 18:03".
    private func when(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        formatter.doesRelativeDateFormatting = true
        return formatter.string(from: date)
    }

    // MARK: Backups

    private var list: some View {
        VStack(spacing: 0) {
            if let backups {
                backupRows(backups)
            } else if listFailed {
                HStack {
                    Text(L10n.text("Couldn't list backups.", locale: language)).noahText(NoahTokens.Typography.sub)
                        .foregroundStyle(NoahTokens.Colors.failure)
                    Spacer()
                    Button(L10n.text("Try again", locale: language)) { Task { await refreshList() } }
                        .noahText(HomeText.strong).foregroundStyle(NoahTokens.Colors.foreground)
                }
                .frame(minHeight: 44)
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 44)
            }
        }
        .padding(.leading, 48)
    }

    @ViewBuilder private func backupRows(_ backups: [Components.Schemas.BackupObjectInfo]) -> some View {
        if backups.isEmpty {
            SettingsDetailRow(label: L10n.text("No backups found", locale: language), value: "", compact: true)
        }
        ForEach(backups, id: \.backupId) { backup in
            HStack(spacing: 8) {
                DetailLine(label: backupDate(backup.createdAt), value: size(backup), compact: true, hairline: false)
                Button {
                    haptics.play(.light)
                    sheet = .delete(backup)
                } label: {
                    Image(systemName: "trash").font(.system(size: 15)).foregroundStyle(NoahTokens.Colors.muted).frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
                .disabled(busy)
                .accessibilityLabel(Text("Delete backup"))
            }
            .overlay(alignment: .bottom) { HomeHairline() }
        }
        if listFailed {
            Text(L10n.text("Couldn't list backups.", locale: language)).noahText(HomeText.footnote)
                .foregroundStyle(NoahTokens.Colors.failure).frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
        }
    }

    private func size(_ backup: Components.Schemas.BackupObjectInfo) -> String {
        ByteCountFormatter.string(fromByteCount: backup.encryptedSize, countStyle: .file)
    }

    private func backupDate(_ timestamp: String) -> String {
        guard let date = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(timestamp) else { return timestamp }
        return when(date)
    }

    // MARK: Sheets

    private var exportSheet: some View {
        SettingsSheetBody(title: L10n.text("Export wallet file", locale: language),
                          line: L10n.text("An encrypted copy of this wallet. Restoring it needs your recovery phrase.", locale: language)) {
            HStack(spacing: 14) {
                Image(systemName: "doc.text").accessibilityHidden(true).font(.system(size: 24))
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: ExportFilename.database()).font(.system(size: 13.5, weight: .medium, design: .monospaced))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(L10n.text("Wallet database, encrypted", locale: language)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NoahTokens.Colors.soft, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(.top, 20)
        } actions: {
            SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Export", locale: language), busy: busy,
                                 actionID: "backup-export-confirm", onCancel: { sheet = nil }, onAction: export)
        }
    }

    private func deleteSheet(_ backup: Components.Schemas.BackupObjectInfo) -> some View {
        SettingsSheetBody(title: L10n.text("Delete this backup?", locale: language),
                          line: L10n.text("{date} · {size}. Your other backups stay.", backupDate(backup.createdAt), size(backup), locale: language)) {
            EmptyView()
        } actions: {
            SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Delete", locale: language), destructive: true,
                                 busy: busy, actionID: "backup-delete-confirm", onCancel: { sheet = nil }) {
                run("Couldn't delete backup.") {
                    _ = try await AppServices.shared.client.deleteBackupObject(body: .json(.init(backupId: backup.backupId))).ok
                    await AppServices.shared.backupCoordinator.forgetUploadedBackup(backup.backupId)
                    sheet = nil
                    haptics.play(.success)
                    settings.toast("Backup deleted")
                    try await list()
                }
            }
        }
    }

    // MARK: Actions

    private func backUp() {
        haptics.play(.light)
        run("Couldn't back up your wallet. Please try again.") {
            let refreshed = try await Self.backUpAndRefresh(backup: {
                try await AppServices.shared.backupCoordinator.flush(.manual, requireEnabled: false)
            }, refresh: { try await list() })
            haptics.play(.success)
            settings.toast("Backed up")
            listFailed = !refreshed
        }
    }

    private func setEnabled(_ enabled: Bool) {
        run("Couldn't update backup settings.") {
            try await model.server.setBackupEnabled(enabled)
            await model.backupSettingsChanged()
        }
    }

    /// The export is today's: an encrypted database in a scratch folder, then the share sheet.
    private func export() {
        run("Couldn't prepare the file for sharing. Please try again.") {
            file = try await ScratchSpace.shared.withOperation {
                let folder = try ScratchSpace.shared.makeFolder("share-backup")
                do {
                    let url = folder.appending(path: ExportFilename.database())
                    _ = try await AppServices.shared.backupService.exportEncryptedBackup(to: url)
                    return SharedFile(url: url, folder: folder)
                } catch { ScratchSpace.shared.discard(folder); throw error }
            }
            sheet = nil
            shared = false
            try? await Task.sleep(for: .milliseconds(350))
            sharing = true
        }
    }

    private func reloadState() {
        state = (fixture?.storage ?? .shared).persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init()
    }

    private func list() async throws {
        if let fixture { backups = fixture.backups; listFailed = false; return }
        backups = try await AppServices.shared.client.listBackupObjects().ok.body.json
        listFailed = false
    }

    private func refreshList() async {
        listFailed = false
        do { try await list() } catch { listFailed = backups == nil }
    }

    /// The upload is the operation; a stale list cannot turn it into a failed backup.
    static func backUpAndRefresh(backup: () async throws -> Void, refresh: () async throws -> Void) async throws -> Bool {
        try await backup()
        do { try await refresh(); return true } catch { return false }
    }

    private func run(_ failure: String, _ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false; reloadState() }
            do { try await action() } catch {
                if !BackupCoordinator.isCancellation(error), !Task.isCancelled {
                    haptics.play(.error)
                    self.error = failure
                }
            }
        }
    }
}
