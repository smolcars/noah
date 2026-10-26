import Observation
import SwiftUI
import Testing
import UIKit
@testable import Noah

@MainActor struct WalletContentProtectionTests {
    @Observable final class State {
        var covered = false
        var mounts: [UUID] = []
        var unmounts = 0
    }
    struct Draft: View {
        let state: State
        @SwiftUI.State private var draft = UUID()
        var body: some View {
            NavigationStack { Text(draft.uuidString) }
                .onAppear { state.mounts.append(draft) }
                .onDisappear { state.unmounts += 1 }
        }
    }
    struct Shell: View {
        let state: State
        var body: some View { Draft(state: state).modifier(WalletContentProtection(covered: state.covered)) }
    }

    @Test func lockingRetainsNavigationAndDraftState() async throws {
        let state = State()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIHostingController(rootView: Shell(state: state))
        window.isHidden = false
        defer { window.isHidden = true; window.rootViewController = nil }
        try await Task.sleep(for: .milliseconds(100))
        let original = state.mounts
        #expect(original.count == 1)
        for covered in [true, false] {
            state.covered = covered
            try await Task.sleep(for: .milliseconds(100))
        }
        #expect(state.mounts == original)
        #expect(state.unmounts == 0)
    }

    @Test func modalCoverHidesAllPresentedContentAndRestoresWindowAccess() {
        let window = UIWindow()
        window.accessibilityElementsHidden = false
        window.isUserInteractionEnabled = true
        let coordinator = WalletLockOverlay.Coordinator()
        coordinator.cover(window)
        #expect(window.accessibilityElementsHidden)
        #expect(!window.isUserInteractionEnabled)
        coordinator.cover(window) // Repeated renders do not overwrite the original flags.
        coordinator.uncover()
        #expect(!window.accessibilityElementsHidden)
        #expect(window.isUserInteractionEnabled)
    }
}
