import SwiftUI

/// Owns a scratch folder until the activity controller is dismissed.
final class SharedFile: Sendable {
    let url: URL
    private let folder: URL
    init(url: URL, folder: URL) { self.url = url; self.folder = folder }
    func discard() { ScratchSpace.shared.discard(folder) }
    deinit { discard() }
}

struct FileShareSheet: UIViewControllerRepresentable {
    let file: SharedFile
    var completed: @MainActor @Sendable (Bool) -> Void = { _ in }
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [file.url], applicationActivities: nil)
        controller.completionWithItemsHandler = { _, success, _, error in
            let shared = success && error == nil
            Task { @MainActor in completed(shared) }
        }
        return controller
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ExportDatabaseView: View {
    @State private var file: SharedFile?
    @State private var sharing = false
    @State private var busy = false
    @State private var error: String?
    @State private var sharedSuccessfully = false
    @State private var exported = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("Create an encrypted backup file containing your wallet database.")
                Text("You will need your seed phrase to restore this backup. Keep it safe and never share it.")
                    .foregroundStyle(NoahColor.mutedForeground)
                if let error { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
                NoahButton(title: "Export Database", isLoading: busy) {
                    busy = true; error = nil; sharedSuccessfully = false
                    Task {
                        defer { busy = false }
                        do {
                            file = try await ScratchSpace.shared.withOperation {
                                let folder = try ScratchSpace.shared.makeFolder("share-backup")
                                do {
                                    let url = folder.appending(path: ExportFilename.database())
                                    _ = try await AppServices.shared.backupService.exportEncryptedBackup(to: url)
                                    return SharedFile(url: url, folder: folder)
                                } catch { ScratchSpace.shared.discard(folder); throw error }
                            }
                            sharing = true
                        } catch { self.error = "Couldn't export your database. Please try again." }
                    }
                }
            }.padding(24).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
        .settingsStyle("Export Database")
        .sheet(isPresented: $sharing, onDismiss: { file?.discard(); file = nil; exported = sharedSuccessfully }) {
            if let file { FileShareSheet(file: file) { sharedSuccessfully = $0 } }
        }
        .alert("Export Complete", isPresented: $exported) { Button("OK", role: .cancel) {} }
            message: { Text("Your encrypted database backup was exported.") }
    }
}

struct LogView: View {
    @Environment(\.locale) private var locale
    @State private var snapshot = LogSnapshot()
    @State private var file: SharedFile?
    @State private var sharing = false
    @State private var error: String?
    var body: some View {
        ScrollViewReader { reader in
          ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                if snapshot.loading { ProgressView() }
                if let failure = snapshot.error { Text(LocalizedStringKey(failure)).foregroundStyle(NoahColor.destructive) }
                if !snapshot.loading && snapshot.error == nil && snapshot.lines.isEmpty { Text("No logs found.") }
                if let error { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
                ForEach(Array(snapshot.lines.enumerated()), id: \.offset) { _, line in
                    Text(verbatim: line).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                }
                Color.clear.frame(height: 1).id("latest")
            }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
          }
          .onChange(of: snapshot.updatedAt) { _, _ in
              Task { await Task.yield(); reader.scrollTo("latest", anchor: .bottom) }
          }
        }
        .safeAreaInset(edge: .top) {
            if let updated = snapshot.updatedAt {
                Text(L10n.text("Updated at {time}", updated.formatted(.dateTime.hour().minute().second().locale(locale)), locale: locale.identifier))
                    .font(NoahFont.footnote).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20)
            }
        }
        .settingsStyle("Logs")
        .task { await snapshot.load { try await LogFile.shared.checkedLinesForExport() } }
        .refreshable { await snapshot.load { try await LogFile.shared.checkedLinesForExport() } }
        .toolbar { Button("Share app logs", systemImage: "square.and.arrow.up", action: share) }
        .sheet(isPresented: $sharing, onDismiss: { file?.discard(); file = nil }) {
            if let file { FileShareSheet(file: file) }
        }
    }
    private func share() {
        Task {
            do {
                file = try await ScratchSpace.shared.withOperation {
                    let folder = try ScratchSpace.shared.makeFolder("share-logs")
                    do {
                        let url = folder.appending(path: "noah.log")
                        // Snapshot the redacted rotating files, so sharing cannot race rotation.
                        try await LogFile.shared.checkedLinesForExport().joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
                        return SharedFile(url: url, folder: folder)
                    } catch { ScratchSpace.shared.discard(folder); throw error }
                }
                sharing = true
            } catch { self.error = "Couldn't share logs. Please try again." }
        }
    }
}
