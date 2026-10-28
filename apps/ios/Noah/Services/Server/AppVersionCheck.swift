import Foundation
import Observation

/// RN useAppVersionCheck: one request per client version/process, no retries,
/// fail open, and warn on Home without blocking wallet operations.
@MainActor
@Observable
final class AppVersionCheck {
    let currentVersion: String
    private(set) var minimumVersion: String?
    private(set) var updateRequired = false
    private(set) var isChecking = false
    private var checked = false
    private let check: (String) async throws -> Components.Schemas.AppVersionInfo

    init(currentVersion: String = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.1",
         check: @escaping (String) async throws -> Components.Schemas.AppVersionInfo = {
             try await AppServices.shared.client.checkAppVersion(body: .json(.init(clientVersion: $0))).ok.body.json
         }) {
        self.currentVersion = currentVersion
        self.check = check
    }

    func checkOnce() async {
        guard !checked else { return }
        checked = true
        isChecking = true
        defer { isChecking = false }
        do {
            let result = try await check(currentVersion)
            minimumVersion = result.minimumRequiredVersion
            updateRequired = result.updateRequired
        } catch {
            minimumVersion = "0.0.1"
            updateRequired = false
            AppLog("appVersion").w("Version check failed; allowing app to continue")
        }
    }

    static func updateURL(variant: AppVariant) -> URL {
        variant == .mainnet
            ? URL(literal: "itms-beta://testflight.apple.com/join/YQW6fu9w")
            : URL(literal: "itms-beta://testflight.apple.com/join/E4P44dXF")
    }
}
