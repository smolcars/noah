import Testing
import UIKit
@testable import Noah

/// The recovery phrase's shield: hidden until the scene's capture state is known, and held for
/// `SceneCapture.hold` after a recording, mirror or share ends, on a manual clock.
@MainActor struct SecretShieldTests {
    @MainActor final class Sleeps {
        private(set) var requested: [Duration] = []
        private var waiters: [CheckedContinuation<Void, Never>] = []
        func sleep(_ duration: Duration) async {
            requested.append(duration)
            await withCheckedContinuation { waiters.append($0) }
        }
        /// Ends every pending sleep, cancelled holds included.
        func wake() {
            let pending = waiters
            waiters.removeAll()
            for waiter in pending { waiter.resume() }
        }
    }

    private func capture(_ sleeps: Sleeps) -> SceneCapture { SceneCapture(sleep: { await sleeps.sleep($0) }) }
    private func hidden(_ capture: SceneCapture?) -> Bool { SecretShield.hidden(phase: .active, capture: capture) }
    /// Lets started or woken hold tasks run on the main actor.
    private func settle() async { for _ in 0..<20 { await Task.yield() } }

    @Test func hiddenUntilTheCaptureStateIsKnownOrWhileInactive() {
        let clear = capture(Sleeps())
        clear.report(.inactive)
        #expect(hidden(nil))
        #expect(SecretShield.hidden(phase: .inactive, capture: clear))
        #expect(SecretShield.hidden(phase: .background, capture: clear))
    }

    @Test func aFirstReadingThatIsNotCapturedRevealsAtOnce() async {
        let sleeps = Sleeps(), capture = capture(sleeps)
        capture.report(.inactive)
        #expect(!hidden(capture))
        await settle()
        #expect(sleeps.requested.isEmpty && !capture.holding)
        capture.report(.unspecified)
        #expect(!hidden(capture))
    }

    @Test func endingARecordingHoldsThenReveals() async {
        let sleeps = Sleeps(), capture = capture(sleeps)
        capture.report(.inactive)
        capture.report(.active)
        #expect(hidden(capture) && capture.captured)
        capture.report(.inactive)
        await settle()
        #expect(hidden(capture) && capture.holding && sleeps.requested == [.seconds(3)])
        // Another clear reading doesn't restart the hold.
        capture.report(.inactive)
        await settle()
        #expect(sleeps.requested.count == 1)
        sleeps.wake()
        await settle()
        #expect(!hidden(capture) && !capture.holding)
    }

    @Test func recordingAgainDuringTheHoldStaysHidden() async {
        let sleeps = Sleeps(), capture = capture(sleeps)
        capture.report(.active)
        capture.report(.inactive)
        await settle()
        capture.report(.active)
        #expect(!capture.holding)
        // The cancelled hold's sleep ending late reveals nothing.
        sleeps.wake()
        await settle()
        #expect(hidden(capture) && capture.captured)
        capture.report(.inactive)
        await settle()
        #expect(hidden(capture) && sleeps.requested.count == 2)
        sleeps.wake()
        await settle()
        #expect(!hidden(capture))
    }

    /// The host app follows its scene from launch: a shield that appears during the hold reads the same state.
    @Test func aShieldThatAppearsDuringTheHoldStaysHidden() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.lazy.compactMap { $0 as? UIWindowScene }.first)
        let capture = SceneCapture.of(scene)
        defer { scene.traitOverrides.remove(UITraitSceneCaptureState.self) }
        scene.traitOverrides.sceneCaptureState = .active
        try await waitUntil { capture.captured && !capture.holding }
        scene.traitOverrides.sceneCaptureState = .inactive
        try await waitUntil { capture.holding }
        #expect(SceneCapture.of(scene) === capture && hidden(SceneCapture.of(scene)))
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !condition(), ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(10)) }
        try #require(condition())
    }
}
