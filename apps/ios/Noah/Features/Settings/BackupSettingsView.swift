import SwiftUI

struct BackupSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var state = PersistedBackupState()
    @State private var backups: [Components.Schemas.BackupObjectInfo]?
    @State private var busy = false
    @State private var message: String?
    @State private var listError = false
    @State private var deleting: Components.Schemas.BackupObjectInfo?

    var body: some View {
        List {
            Section {
                Text("Backups are encrypted with your seed phrase and stored securely on our servers. We can never access your funds or data.")
                    .font(NoahFont.callout)
                Toggle("Enable Automatic Backups", isOn: Binding(get: { model.server.state.isBackupEnabled == true }, set: { enabled in
                    run {
                        try await model.server.setBackupEnabled(enabled)
                        await model.backupSettingsChanged()
                    }
                })).disabled(busy)
            }
            Section("Last Backup") {
                if let at = state.lastBackupAt {
                    Text(Date(timeIntervalSince1970: at / 1000), format: .dateTime)
                } else { Text("No backups yet") }
                Text(LocalizedStringKey(status))
                if state.backupPending { Text("Backup pending").font(NoahFont.footnote) }
                Button("Backup Now") {
                    run {
                        let refreshed = try await Self.backUpAndRefresh(backup: {
                            try await AppServices.shared.backupCoordinator.flush(.manual, requireEnabled: false)
                        }, refresh: { if backups != nil { try await list() } })
                        message = "Your wallet has been backed up successfully."
                        listError = !refreshed
                    }
                }.disabled(busy)
                Button("List Backups") { run { try await list() } }.disabled(busy)
                if busy { ProgressView() }
                if let message { Text(LocalizedStringKey(message)).font(NoahFont.footnote) }
            }
            if listError { Text("Couldn't list backups.").font(NoahFont.footnote) }
            if let backups {
                Section("Available Backups") {
                    if backups.isEmpty { Text("No backups found") }
                    ForEach(backups, id: \.backupId) { backup in
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Encrypted wallet snapshot").font(NoahFont.callout)
                            Text("\(backupDate(backup.createdAt)) · \(ByteCountFormatter.string(fromByteCount: backup.encryptedSize, countStyle: .file))")
                                .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                            Button("Delete", role: .destructive) { deleting = backup }.disabled(busy)
                        }.padding(.vertical, 6)
                    }
                }
            }
        }
        .settingsStyle("Backup")
        .confirmationDialog("Delete encrypted backup?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            if let deleting {
                Button("Delete Backup", role: .destructive) {
                    run {
                        _ = try await AppServices.shared.client.deleteBackupObject(body: .json(.init(backupId: deleting.backupId))).ok
                        await AppServices.shared.backupCoordinator.forgetUploadedBackup(deleting.backupId)
                        try await list()
                    }
                }
            }
        }
        .task {
            while !Task.isCancelled {
                reloadState()
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
    }
    private func backupDate(_ timestamp: String) -> String {
        let date = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(timestamp)
        return date?.formatted(date: .abbreviated, time: .shortened) ?? timestamp
    }
    private var status: String {
        switch state.lastBackupStatus {
        case .idle: "Up to date or waiting for changes"
        case .inProgress: "Backing up wallet"
        case .success: "Backup completed"
        case .failed: "Backup failed. Please try again."
        }
    }
    private func reloadState() { state = SettingsStorage.shared.persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init() }
    private func list() async throws {
        backups = try await AppServices.shared.client.listBackupObjects().ok.body.json
        listError = false
    }

    /// The upload is the operation; a stale list cannot turn it into a failed backup.
    static func backUpAndRefresh(backup: () async throws -> Void, refresh: () async throws -> Void) async throws -> Bool {
        try await backup()
        do { try await refresh(); return true } catch { return false }
    }
    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }; busy = true; message = nil
        Task {
            defer { busy = false; reloadState() }
            do { try await action() } catch { message = "Couldn't complete the backup operation. Please try again." }
        }
    }
}
