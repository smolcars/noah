import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// Activity's search in the real tab shell. There, on iOS 27, the system's minimized toolbar search
/// reopened as soon as its × closed it, so the phone uses Noah's own field instead. Closing it is
/// checked on the simulator: a unit-test host has no accessibility tree to tap Cancel through.
@MainActor
struct ActivitySearchTests {
    @Test func searchIsNoahsFieldNotTheSystemSearchBar() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.keyWindow)
        let original = window.rootViewController
        let root = FileManager.default.temporaryDirectory.appending(path: "activity-search-\(UUID())")
        defer {
            window.rootViewController = original
            try? FileManager.default.removeItem(at: root)
        }
        let app = AppModel(settings: try SettingsStorage(rootPath: root.path))
        let rows = ActivityShotTests.everyday
        let history = HistoryModel(dependencies: .init(
            load: { rows }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
            blockTime: { _ in nil }, price: { _, _ in nil }))
        await history.refresh(currency: app.preferredCurrency)
        let home = HomeModel(dependencies: .init(
            price: { _ in 112_000 }, rounds: { [] }, backup: { PersistedBackupState() }, retryBackup: {},
            autoBoards: { [] }, backgroundSnapshot: { .init(isRunning: false, completionRevision: 0) }, renewal: { nil }))
        // An empty fixture query opens search, as the Search button does.
        window.rootViewController = UIHostingController(rootView: WalletTabs(history: history, home: home, tab: .history)
            .environment(app)
            .environment(app.walletLock)
            .environment(\.homeFixture, HomeFixture(summary: HomeShotTests.summary(ark: 10_000), walletError: nil))
            .environment(\.activityFixture, ActivityFixture(query: ""))
            .environment(\.scenePhase, .active))

        try await SendPaymentTests.waitUntil { Self.find(in: window) { $0 is UITextField } != nil }
        #expect(Self.find(in: window) { $0 is UISearchBar || $0 is UISearchTextField } == nil)
    }

    private static func find(in view: UIView, where match: (UIView) -> Bool) -> UIView? {
        if match(view) { return view }
        for child in view.subviews {
            if let found = find(in: child, where: match) { return found }
        }
        return nil
    }
}
