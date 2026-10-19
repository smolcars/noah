import Foundation
import UIKit

/// Device reporting wire format and refresh policy from `client/src/lib/deviceInfo.ts`.
enum DeviceInfo {
    static let refreshIntervalMS: Double = 30 * 24 * 60 * 60 * 1000

    @MainActor
    static func current() -> Components.Schemas.DeviceInfo {
        .init(
            appBuild: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
            deviceManufacturer: "Apple", deviceModel: DeviceModel.current,
            osName: UIDevice.current.systemName, osVersion: UIDevice.current.systemVersion)
    }

    static func fingerprint(_ info: Components.Schemas.DeviceInfo) -> String {
        let values = [info.deviceManufacturer, info.deviceModel, info.osName, info.osVersion,
                      info.appVersion, info.appBuild]
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        // Arrays of optional strings always encode, including null entries.
        return String(decoding: try! encoder.encode(values), as: UTF8.self)
    }

    static func shouldReport(_ info: Components.Schemas.DeviceInfo, fingerprint previous: String?,
                             reportedAt: Double?, now: Double = Date().timeIntervalSince1970 * 1000) -> Bool {
        if previous != fingerprint(info) { return true }
        guard let reportedAt, reportedAt <= now else { return true }
        return now - reportedAt >= refreshIntervalMS
    }
}

extension Components.Schemas.DeviceInfo {
    var registrationPayload: Components.Schemas.RegisterPayload.DeviceInfoPayload {
        .init(appBuild: appBuild, appVersion: appVersion, deviceManufacturer: deviceManufacturer,
              deviceModel: deviceModel, osName: osName, osVersion: osVersion)
    }

    var loginPayload: Components.Schemas.ReportLastLoginPayload.DeviceInfoPayload {
        .init(appBuild: appBuild, appVersion: appVersion, deviceManufacturer: deviceManufacturer,
              deviceModel: deviceModel, osName: osName, osVersion: osVersion)
    }
}
