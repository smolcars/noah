import Testing
import UserNotifications
@testable import Noah

@MainActor
struct PushPermissionTests {
    struct Input: Decodable {
        let initialized: Bool
        let physical: Bool
        let authorization: String
        let status: PushPermissionModel.Status
    }

    @Test func matchesRNPermissionGate() async throws {
        let statuses: [String: UNAuthorizationStatus] = [
            "notDetermined": .notDetermined, "denied": .denied, "authorized": .authorized,
            "provisional": .provisional, "ephemeral": .ephemeral]
        let cases = try Fixtures.load("push-gate", input: Input.self, expected: Bool.self)
        #expect(cases.count == 24)
        for fixture in cases {
            let input = fixture.input
            if let authorization = statuses[input.authorization] {
                #expect(PushPermissionModel.Status(authorization) == input.status)
            }
            let model = PushPermissionModel(dependencies: .init(
                isPhysicalDevice: input.physical, status: { input.status }, request: {}))
            await model.refresh()
            #expect(model.blocksWallet(initialized: input.initialized) == fixture.expected, "\(fixture.name)")
        }
    }

    @Test func denialRetryAndSettingsReturn() async {
        var status = PushPermissionModel.Status.denied
        var requests = 0
        let model = PushPermissionModel(dependencies: .init(
            isPhysicalDevice: true, status: { status }, request: { requests += 1 }))
        await model.refresh()
        #expect(model.blocksWallet(initialized: true))
        await model.retry(request: true)
        #expect(requests == 1)
        #expect(model.blocksWallet(initialized: true))
        status = .granted
        await model.retry(request: false)
        #expect(!model.blocksWallet(initialized: true))
        #expect(!model.isRequesting)
        #expect(requests == 1)
    }

    @Test func failedStatusAndRequestKeepLastState() async {
        struct Failure: Error {}
        var fails = false
        let model = PushPermissionModel(dependencies: .init(
            isPhysicalDevice: true, status: { if fails { throw Failure() }; return .denied },
            request: { throw Failure() }))
        await model.refresh()
        fails = true
        await model.refresh()
        await model.retry(request: true)
        #expect(model.status == .denied)
        #expect(!model.isRequesting)
    }
}
