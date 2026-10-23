import SwiftUI
import UIKit

/// ⋯ → Export CSV (activity.md, "Export"): how many payments the file holds, its name, then
/// the system share sheet. The file holds exactly today's export rows, narrowed by the filter.
struct ActivityExportSheet: View {
    let transactions: [HistoryTransaction]
    let filter: HistoryFilter
    let currency: String
    let onClose: () -> Void
    @Environment(\.locale) private var locale
    @State private var working = false
    @State private var error: String?
    @State private var anchor: CGRect?
    @State private var haptics = OnboardingHaptics()

    static let fileName = "noah_transactions.csv"

    private var count: Int { transactions.filter(HistoryCSV.exports).count }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Export activity").noahText(NoahTokens.Typography.sheetTitle).accessibilityAddTraits(.isHeader)
                .padding(.bottom, 6)
            Text(verbatim: filter.exportSentence(count: count, locale: locale.identifier))
                .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
            HStack(spacing: 14) {
                Image(systemName: "doc.text").font(.system(size: 24, weight: .regular)).frame(width: 26)
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: Self.fileName).font(.system(size: 15, weight: .medium, design: .monospaced))
                    Text("Dates, amounts, fees, prices, IDs and destinations").noahText(HomeText.footnote)
                        .foregroundStyle(NoahTokens.Colors.muted)
                }
            }
            .padding(.vertical, 14).padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NoahTokens.Colors.foreground.opacity(0.07), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(.top, 20)
            .accessibilityElement(children: .combine)
            if let error {
                Text(LocalizedStringKey(error)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.failure)
                    .padding(.top, 12)
                    .accessibilityIdentifier("activity-export-error")
            }
            HStack(spacing: 10) {
                Button("Cancel", action: onClose)
                    .buttonStyle(HomePillStyle(prominent: false, height: 52, font: NoahTokens.Typography.button))
                    .frame(maxWidth: .infinity)
                Button(action: export) {
                    Text(working ? "Exporting…" : "Export").frame(maxWidth: .infinity)
                }
                .buttonStyle(HomePillStyle(height: 52, font: NoahTokens.Typography.button))
                .disabled(count == 0 || working)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { anchor = $0 }
                .accessibilityIdentifier("activity-export-confirm")
            }
            .padding(.top, 22)
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .padding(.horizontal, NoahTokens.Space.screen)
        .padding(.top, 26).padding(.bottom, 26)
        .onboardingHaptics(haptics)
    }

    private func export() {
        guard !working else { return }
        haptics.play(.success)
        working = true
        error = nil
        let rows = transactions, currency = currency
        Task {
            do {
                let file = try await Task.detached { try ActivityShare.writeCSV(HistoryCSV(transactions: rows, currency: currency)) }.value
                working = false
                let anchor = anchor
                onClose()
                // The share sheet opens once this sheet has gone, so closing it can't take the share sheet along.
                try? await Task.sleep(for: .milliseconds(450))
                ActivityShare.present([file], anchor: anchor) { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
            } catch {
                working = false
                // The file can name destinations; keep it out of logs.
                AppLog("history").w("History export failed")
                self.error = "Couldn't export transactions. Please try again."
            }
        }
    }
}

/// The system share sheet. On wide iPhone windows it opens as a popover from its button, so
/// it stays in the trailing pane instead of spanning the fold.
enum ActivityShare {
    /// A private copy under its own folder, removed once the share sheet closes.
    nonisolated static func writeCSV(_ csv: HistoryCSV) throws -> URL {
        let temporary = FileManager.default.temporaryDirectory
        // A share sheet that closed without its completion leaves its copy behind; one export is shared at a time.
        for old in (try? FileManager.default.contentsOfDirectory(atPath: temporary.path)) ?? [] where old.hasPrefix("history-export-") {
            try? FileManager.default.removeItem(at: temporary.appending(path: old))
        }
        let folder = temporary.appending(path: "history-export-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appending(path: ActivityExportSheet.fileName)
        try Data(csv.content.utf8).write(to: file, options: [.atomic, .completeFileProtection])
        return file
    }

    @MainActor static func present(_ items: [Any], anchor: CGRect?, completion: @escaping () -> Void = {}) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive }) ?? UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = scene.windows.first(where: \.isKeyWindow) ?? scene.windows.first,
              var top = window.rootViewController else { completion(); return }
        while let presented = top.presentedViewController, !presented.isBeingDismissed { top = presented }
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in completion() }
        let wide = OnboardingLayout(size: window.bounds.size, insets: EdgeInsets()).wide
        if let anchor, wide || window.traitCollection.userInterfaceIdiom == .pad {
            controller.modalPresentationStyle = .popover
            controller.popoverPresentationController?.sourceView = window
            controller.popoverPresentationController?.sourceRect = anchor
            controller.popoverPresentationController?.delegate = PopoverKeeper.shared
        }
        top.present(controller, animated: true)
    }

    /// Keeps the popover a popover on iPhone-sized traits.
    private final class PopoverKeeper: NSObject, UIPopoverPresentationControllerDelegate {
        static let shared = PopoverKeeper()
        func adaptivePresentationStyle(for controller: UIPresentationController, traitCollection: UITraitCollection) -> UIModalPresentationStyle { .none }
    }
}
