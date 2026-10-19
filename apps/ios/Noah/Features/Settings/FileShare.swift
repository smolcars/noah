import SwiftUI

/// Owns a scratch folder until the activity controller is dismissed.
final class SharedFile {
    let url: URL
    private let folder: URL
    init(url: URL, folder: URL) { self.url = url; self.folder = folder }
    func discard() { ScratchSpace.shared.discard(folder) }
    deinit { discard() }
}

struct FileShareSheet: UIViewControllerRepresentable {
    let file: SharedFile
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [file.url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ExportDatabaseView: View {
    @State private var file: SharedFile?
    @State private var sharing = false
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("Create an encrypted backup file containing your wallet database.")
                Text("You will need your seed phrase to restore this backup. Keep it safe and never share it.")
                    .foregroundStyle(NoahColor.mutedForeground)
                if let error { Text(error).foregroundStyle(NoahColor.destructive) }
                NoahButton(title: "Export Database", isLoading: busy) {
                    busy = true; error = nil
                    Task {
                        defer { busy = false }
                        do {
                            let folder = try ScratchSpace.shared.makeFolder("share-backup")
                            do {
                                let url = folder.appending(path: "noah-\(AppVariant.current.rawValue).noahbackup")
                                _ = try await AppServices.shared.backupService.exportEncryptedBackup(to: url)
                                file = SharedFile(url: url, folder: folder)
                                sharing = true
                            } catch { ScratchSpace.shared.discard(folder); throw error }
                        } catch { self.error = "Couldn't export your database. Please try again." }
                    }
                }
            }.padding(24).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
        .settingsStyle("Export Database")
        .sheet(isPresented: $sharing, onDismiss: { file?.discard(); file = nil }) {
            if let file { FileShareSheet(file: file) }
        }
    }
}

struct LogView: View {
    @State private var lines: [String] = []
    @State private var file: SharedFile?
    @State private var sharing = false
    @State private var error: String?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                if lines.isEmpty { Text("No logs found") }
                if let error { Text(error).foregroundStyle(NoahColor.destructive) }
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(verbatim: line).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                }
            }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
        }
        .settingsStyle("Logs")
        .task { lines = LogFile.shared.lines() }
        .refreshable { lines = LogFile.shared.lines() }
        .toolbar { Button("Share Logs", systemImage: "square.and.arrow.up", action: share) }
        .sheet(isPresented: $sharing, onDismiss: { file?.discard(); file = nil }) {
            if let file { FileShareSheet(file: file) }
        }
    }
    private func share() {
        do {
            let folder = try ScratchSpace.shared.makeFolder("share-logs")
            do {
                let url = folder.appending(path: "noah.log")
                // Snapshot the redacted rotating files, so sharing cannot race rotation.
                try LogFile.shared.lines().joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
                file = SharedFile(url: url, folder: folder)
                sharing = true
            } catch { ScratchSpace.shared.discard(folder); throw error }
        } catch { self.error = "Couldn't share logs. Please try again." }
    }
}
