import Foundation
import Testing

@testable import Noah

struct DeviceInfoTests {
    private let now: Double = 1_784_160_000_000
    private let info = Components.Schemas.DeviceInfo(
        appBuild: "27", appVersion: "0.1.4", deviceManufacturer: "Apple",
        deviceModel: "iPhone 12 Pro", osName: "iOS", osVersion: "26.5")

    @MainActor @Test func collectsAppOSAndHardware() {
        let current = DeviceInfo.current()
        #expect(current.deviceManufacturer == "Apple")
        // UIDevice (like RN expo-device) reports "iPadOS" on iPads.
        #expect(["iOS", "iPadOS"].contains(current.osName))
        #expect(current.osVersion?.isEmpty == false)
        #expect(current.appVersion == Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        #expect(current.appBuild == Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
        #expect(current.deviceModel == DeviceModel.current)
        #expect(DeviceModel.name(for: "iPhone13,3") == "iPhone 12 Pro")
        #expect(DeviceModel.name(for: "arm64") == "Simulator iOS")
        #expect(DeviceModel.name(for: "future-model") == "future-model")
    }

    struct FixtureInput: Decodable {
        let device: Components.Schemas.DeviceInfo
        let fingerprint: String?
        let reportedAt: Double?
        let now: Double
    }

    struct FixtureExpected: Decodable {
        let fingerprint: String
        let shouldReport: Bool
    }

    /// Same cases the Android tests run (`apps/fixtures/device-info.json`).
    @Test func matchesTheSharedFixture() throws {
        let cases = try Fixtures.load("device-info", input: FixtureInput.self, expected: FixtureExpected.self)
        #expect(!cases.isEmpty)
        for fixture in cases {
            let input = fixture.input
            #expect(DeviceInfo.fingerprint(input.device) == fixture.expected.fingerprint, "\(fixture.name)")
            #expect(DeviceInfo.shouldReport(
                input.device, fingerprint: input.fingerprint, reportedAt: input.reportedAt, now: input.now
            ) == fixture.expected.shouldReport, "\(fixture.name)")
        }
    }

    @Test func fingerprintMatchesJSONStringifyIncludingNulls() {
        #expect(DeviceInfo.fingerprint(info) == #"["Apple","iPhone 12 Pro","iOS","26.5","0.1.4","27"]"#)
        #expect(DeviceInfo.fingerprint(.init()) == "[null,null,null,null,null,null]")
        #expect(DeviceInfo.fingerprint(.init(deviceModel: "a/b")) == #"[null,"a/b",null,null,null,null]"#)
    }

    @Test func unchangedRecentDetailsAreNotReported() {
        #expect(!DeviceInfo.shouldReport(info, fingerprint: DeviceInfo.fingerprint(info), reportedAt: now - 1_000, now: now))
    }

    @Test func appBuildAndOSChangesAreReported() {
        var app = info; app.appVersion = "0.1.3"
        var build = info; build.appBuild = "26"
        var os = info; os.osVersion = "26.4"
        for old in [app, build, os] {
            #expect(DeviceInfo.shouldReport(info, fingerprint: DeviceInfo.fingerprint(old), reportedAt: now - 1_000, now: now))
        }
    }

    @Test func failedReportsRetryAndMissingTimestampIsRepaired() {
        for time in [now, now + 1_000] {
            #expect(DeviceInfo.shouldReport(info, fingerprint: nil, reportedAt: nil, now: time))
        }
        #expect(DeviceInfo.shouldReport(info, fingerprint: DeviceInfo.fingerprint(info), reportedAt: nil, now: now))
    }

    @Test func refreshesAtThirtyDaysAndRepairsFutureTimestamp() {
        let fingerprint = DeviceInfo.fingerprint(info)
        #expect(!DeviceInfo.shouldReport(info, fingerprint: fingerprint, reportedAt: now - DeviceInfo.refreshIntervalMS + 1, now: now))
        #expect(DeviceInfo.shouldReport(info, fingerprint: fingerprint, reportedAt: now - DeviceInfo.refreshIntervalMS, now: now))
        #expect(DeviceInfo.shouldReport(info, fingerprint: fingerprint, reportedAt: now + 1_000, now: now))
    }
}
