import Foundation
import Testing

@testable import Noah

struct DeviceInfoTests {
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
}
