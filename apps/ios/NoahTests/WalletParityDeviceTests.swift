import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Read-only public fixtures on an explicitly owned disposable simulator.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_WALLET_PARITY_UI"] == "disposable"))
struct WalletParityDeviceTests {
    @Test func exitUnknownFeeReviews() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        for action in [OnchainAction.startExit, .claim] {
            let window = UIWindow(windowScene: scene)
            var quote = OnchainQuote(action: action, walletID: "public-fixture", ids: ["public-vtxo:0"],
                destination: action == .claim ? ExitClaimExecutionTests.externalAddress : nil,
                destinationIsMine: action == .claim ? false : nil,
                amount: 30000, fee: nil, chainFee: nil, confirmed: 0)
            quote.unknownFeeConsent = quote.id
            window.rootViewController = UIHostingController(rootView: OnchainReviewSheet(quote: quote) {
                Issue.record("Read-only fixture must not confirm")
            }.environment(AppModel()).environment(\.locale, Locale(identifier: "en_US")))
            window.makeKeyAndVisible()
            try await Task.sleep(for: .seconds(2))
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            try #require(image.pngData()).write(to: URL.documentsDirectory.appending(path: "exit-fallback-\(action.rawValue).png"))
            window.isHidden = true
        }
    }
    @Test func exitBlockFields() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let details = ExitDetails(kind: "claimed", tip_height: 150,
            claimable_since: .init(height: 144, hash: "public-claimable-block"),
            block: .init(height: 149, hash: "public-claimed-block"), txid: String(repeating: "a", count: 64))
        window.rootViewController = UIHostingController(rootView: NavigationStack {
            List {
                Section("Block Status") { ExitBlockStatusRows(details: details, tip: 150) }
                Section("Current State") { ExitDetailsRows(details: details) }
            }.navigationTitle("Exit Details")
        }.environment(AppModel()).environment(\.locale, Locale(identifier: "en_US")))
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        try await Task.sleep(for: .seconds(2))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        try #require(image.pngData()).write(to: URL.documentsDirectory.appending(path: "wallet-parity-exit-blocks.png"))
    }
    @Test func exitOverviewFields() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let dust = VtxoModel(id: "public-dust", amount: 100, expiry: 200, state: "Spendable", kind: "Arkoor",
            exitDepth: 1, exitWeight: 400, registered: true, serverPubkey: "public-server", exitDelta: 144,
            anchorPoint: "public-anchor:0", point: "public-point:0", expiring: false, tip: 150)
        let exit = ExitModel(id: "public-exit", amount: 12000, state: "Claimed", details: .init(kind: "claimed", tip_height: 149),
            history: [], historyDetails: [], claimable: false)
        let overview = OnchainOverview(walletID: "public", vtxos: [dust], exits: [exit], confirmed: 50000,
            tip: 150, pendingTotal: 0, claimableHeight: 144)
        window.rootViewController = UIHostingController(rootView: NavigationStack {
            ScrollView {
                VStack(spacing: 12) { ExitOverviewSummary(overview: overview) }.padding(20)
            }.navigationTitle("Emergency Exit")
        }.environment(AppModel()).environment(\.locale, Locale(identifier: "en_US")))
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        try await Task.sleep(for: .seconds(2))
        func shot(_ name: String) throws {
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
            try #require(image.pngData()).write(to: URL.documentsDirectory.appending(path: name))
        }
        try shot("wallet-parity-exit-summary.png")
        func scrollView(_ view: UIView) -> UIScrollView? {
            (view as? UIScrollView) ?? view.subviews.compactMap { scrollView($0) }.first
        }
        let scroll = try #require(scrollView(window))
        scroll.setContentOffset(CGPoint(x: 0, y: max(0, scroll.contentSize.height - scroll.bounds.height)), animated: false)
        try await Task.sleep(for: .seconds(1))
        try shot("wallet-parity-exit-summary-bottom.png")
    }

}
