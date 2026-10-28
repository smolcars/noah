@testable import Noah
import Testing
import UserNotifications

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
            "provisional": .provisional, "ephemeral": .ephemeral,
        ]
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

    @Test func matchesSharedPlatformPushGate() async throws {
        struct PlatformInput: Decodable {
            let initialized: Bool
            let physical: Bool
            let permission: PushPermissionModel.Status
        }
        let cases = try Fixtures.load("platform-gates", input: PlatformInput.self, expected: String?.self)
        #expect(cases.count == 64)
        for fixture in cases {
            let input = fixture.input
            let model = PushPermissionModel(dependencies: .init(
                isPhysicalDevice: input.physical, status: { input.permission }, request: {}))
            await model.refresh()
            // Battery outcomes belong to Android; only a push outcome blocks iOS.
            #expect(model.blocksWallet(initialized: input.initialized) == (fixture.expected == "push"), "\(fixture.name)")
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

    @Test func onboardingContinuesOnlyOnceGrantedOnAPhysicalDevice() async {
        struct Failure: Error {}
        var status = PushPermissionModel.Status.undetermined
        var failing = true
        let model = PushPermissionModel(dependencies: .init(
            isPhysicalDevice: true, status: { if failing { throw Failure() }; return status },
            request: { if failing { throw Failure() } }))
        // Checking is the initial status on every device.
        #expect(model.status == .checking)
        #expect(model.onboardingStep == .ask)
        // A thrown authorization request keeps it.
        await model.retry(request: true)
        #expect(model.status == .checking)
        #expect(model.onboardingStep == .ask)
        failing = false
        await model.retry(request: true)
        #expect(model.status == .undetermined)
        #expect(model.onboardingStep == .ask)
        status = .denied
        await model.retry(request: true)
        #expect(model.onboardingStep == .required)
        status = .granted
        await model.refresh()
        #expect(model.onboardingStep == .proceed)
    }

    @Test func simulatorsAreNeverGatedInOnboarding() async {
        let model = PushPermissionModel(dependencies: .init(
            isPhysicalDevice: false, status: { .denied }, request: { struct Failure: Error {}; throw Failure() }))
        #expect(model.onboardingStep == .proceed)
        await model.retry(request: true)
        #expect(model.status == .checking)
        #expect(model.onboardingStep == .proceed)
        #expect(!model.blocksWallet(initialized: true))
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
