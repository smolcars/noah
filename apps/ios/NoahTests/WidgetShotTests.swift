import Foundation
@testable import Noah
import Testing
import WidgetKit

/// Opt-in screenshot fixtures for the placed widgets: writes a scripted snapshot to the app group and
/// redraws them (no wallet, server or recovery phrase). Use a simulator without a wallet, or the app's own
/// refresh replaces the fixture. Write a state to Documents/widget-shot and wait for widget-shot-ready;
/// "stop" ends the session and restores the group. A state is a name (everyday, arriving, renew, fresh,
/// hidden, signet, large, legacy, none) plus any of: sats, nofiat, a language (es, ja, ko, sv).
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_WIDGET_SHOTS"] == "1"))
struct WidgetShotTests {
    static let month: [Int64] = [222600, 230600, 228100, 228100, 232942, 232942, 228742, 228742, 243742, 233742, 233742,
                                 230542, 251542, 251542, 245542, 240542, 240542, 250042, 251542, 251542, 243742, 243242,
                                 243242, 255242, 261242, 261242, 257242, 247242, 250642, 252742, 252742]

    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let defaults = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let keys = [WidgetSnapshot.key, WidgetSnapshot.legacyKey, LegacyLocale.key]
        let saved = keys.map { defaults.object(forKey: $0) }
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "widget-shot"), ready = folder.appending(path: "widget-shot-ready")
        defer {
            for (key, value) in zip(keys, saved) { defaults.set(value, forKey: key) }
            WidgetCenter.shared.reloadAllTimelines()
            try? FileManager.default.removeItem(at: ready)
            try? FileManager.default.removeItem(at: request)
        }
        var current = ""
        let deadline = ContinuousClock.now.advanced(by: .seconds(3500))
        while ContinuousClock.now < deadline {
            let state = (try? String(contentsOf: request, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if state == "stop" { return }
            if !state.isEmpty && state != current {
                try Self.write(state, to: defaults)
                WidgetCenter.shared.reloadAllTimelines()
                try await Task.sleep(for: .seconds(2))
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
    }

    static func write(_ state: String, to defaults: UserDefaults) throws {
        let words = Set(state.split(separator: " ").map(String.init))
        let language = LegacyLocale.supported.first { words.contains($0) }
        defaults.set(language, forKey: LegacyLocale.key)
        defaults.removeObject(forKey: WidgetSnapshot.key)
        defaults.removeObject(forKey: WidgetSnapshot.legacyKey)
        let now = Date()
        var snapshot = WidgetSnapshot(writtenAt: now, balance: 252_742, arriving: 0, dailyBalances: month,
                                      amountUnit: words.contains("sats") ? .sats : .bip177, hidden: false,
                                      fiat: words.contains("nofiat") ? nil : .init(currency: "USD", btcPrice: 112_000),
                                      renewBy: nil, variant: "mainnet")
        switch words.first(where: { !["sats", "nofiat"].contains($0) && !LegacyLocale.supported.contains($0) }) ?? "everyday" {
        case "arriving": snapshot.arriving = 100_000
        case "renew": snapshot.renewBy = now.addingTimeInterval(18 * 3600 + 120); snapshot.arriving = 100_000
        case "fresh": snapshot.balance = 0; snapshot.dailyBalances = Array(repeating: 0, count: 31)
        case "hidden": snapshot.hidden = true; snapshot.arriving = 100_000
        case "signet": snapshot.variant = "signet"
        case "regtest": snapshot.variant = "regtest"
        case "large": snapshot.balance = 12_480_315; snapshot.dailyBalances = month.map { $0 * 49 + 95_957 }
        case "legacy":
            defaults.set(["totalBalance": 125_000.0, "onchainBalance": 30_000.0, "offchainBalance": 95_000.0,
                          "pendingBalance": 0.0, "closestExpiryBlocks": 90.0, "expiryThreshold": 288.0], forKey: WidgetSnapshot.legacyKey)
            return
        case "none": return
        default: break
        }
        defaults.set(try JSONEncoder().encode(snapshot), forKey: WidgetSnapshot.key)
    }
}
