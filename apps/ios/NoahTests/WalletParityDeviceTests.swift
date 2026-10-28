@testable import Noah
import SwiftUI
import Testing
import UIKit

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
}
