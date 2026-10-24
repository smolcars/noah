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

/// Advanced → Logs: today's redacted log, refreshed by pulling, shared as a file.
struct LogView: View {
    @Environment(\.locale) private var locale
    @Environment(\.settings) private var settings
    @State private var snapshot = LogSnapshot()
    @State private var file: SharedFile?
    @State private var sharing = false
    @State private var error: String?

    private var language: String { locale.identifier }

    var body: some View {
        ScrollViewReader { reader in
            SettingsScroll(line: snapshot.updatedAt.map {
                L10n.text("Updated at {time}", $0.formatted(.dateTime.hour().minute().second().locale(locale)), locale: language)
            }) {
                if snapshot.loading && snapshot.lines.isEmpty { ProgressView().frame(maxWidth: .infinity).padding(.top, 24) }
                if let failure = snapshot.error { SettingsFine(text: L10n.text(failure, locale: language), failure: true) }
                if !snapshot.loading && snapshot.error == nil && snapshot.lines.isEmpty {
                    SettingsFine(text: L10n.text("No logs found.", locale: language))
                }
                if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(snapshot.lines.enumerated()), id: \.offset) { _, line in
                        Text(verbatim: line).font(.system(size: 11.5, design: .monospaced))
                            .foregroundStyle(line.contains(" E [") ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Color.clear.frame(height: 1).id("latest")
                }
                .padding(.top, 14)
                SettingsFine(text: L10n.text("Shared logs never include your phrase or keys.", locale: language))
            }
            .onChange(of: snapshot.updatedAt) { _, _ in
                Task { await Task.yield(); reader.scrollTo("latest", anchor: .bottom) }
            }
        }
        .settingsChrome("Logs") {
            Button(action: share) {
                if settings.wide { ActivityCircleIcon(systemName: "square.and.arrow.up") } else { Label("Share app logs", systemImage: "square.and.arrow.up") }
            }
            .accessibilityLabel(Text("Share app logs"))
        }
        .task { await snapshot.load { try await LogFile.shared.checkedLinesForExport() } }
        .refreshable { await snapshot.load { try await LogFile.shared.checkedLinesForExport() } }
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
            } catch { self.error = "Couldn't prepare the file for sharing. Please try again." }
        }
    }
}
