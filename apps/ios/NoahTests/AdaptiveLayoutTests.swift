import Observation
import SwiftUI
import Testing
import UIKit
@testable import Noah

@Suite(.serialized)
@MainActor
struct AdaptiveLayoutTests {
    @Test(arguments: [CGFloat(320), 543, 544, 669, 700, 874, 951, 1180])
    func paneThresholdMatchesRN(width: CGFloat) {
        #expect(AdaptiveLayout.expanded(width: width) == (width >= 544))
    }

    @Test func largeTextRequiresTwoReadablePanes() {
        #expect(!AdaptiveLayout.expanded(width: 803, minimumPaneWidth: 390))
        #expect(AdaptiveLayout.expanded(width: 804, minimumPaneWidth: 390))
        #expect(!AdaptiveLayout.expanded(width: 543, minimumPaneWidth: 130))
    }

    @Test func qrFitsShortLandscapeAndStopsGrowingOnTablets() {
        #expect(AdaptiveLayout.qrSide(availableHeight: 260) == 180)
        #expect(AdaptiveLayout.qrSide(availableHeight: 669) == 270)
        #expect(AdaptiveLayout.qrSide(availableHeight: 0) == 0)
    }

    @Observable final class Mounts {
        var ids: [UUID] = []
        var disappeared = 0
    }
    struct Draft: View {
        let mounts: Mounts
        @State private var id = UUID()
        @State private var text = "Rotation draft"
        var body: some View {
            TextField("Name", text: $text)
                .onAppear { mounts.ids.append(id) }
                .onDisappear { mounts.disappeared += 1 }
        }
    }
    struct Columns: View {
        let mounts: Mounts
        var body: some View {
            AdaptiveColumns { _ in
                Draft(mounts: mounts).frame(maxWidth: .infinity)
                Draft(mounts: mounts).frame(maxWidth: .infinity)
            }
        }
    }

    @Test func columnChangesRetainMountedDrafts() async throws {
        let mounts = Mounts()
        let host = UIHostingController(rootView: Columns(mounts: mounts))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        window.rootViewController = host
        window.isHidden = false
        defer { window.isHidden = true; window.rootViewController = nil }
        try await Task.sleep(for: .milliseconds(100))
        let initial = mounts.ids
        #expect(initial.count == 2)
        for size in [CGSize(width: 874, height: 402), .init(width: 669, height: 951),
                     .init(width: 951, height: 669), .init(width: 402, height: 874)] {
            window.frame.size = size
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(mounts.ids == initial)
        #expect(mounts.disappeared == 0)
    }

    final class Responder: UITextField {
        var hasFocus = false
        override var isFirstResponder: Bool { hasFocus }
        override func becomeFirstResponder() -> Bool { hasFocus = true; return true }
        override func resignFirstResponder() -> Bool { hasFocus = false; return true }
    }
    final class Focus { var index: Int? }

    @Test func recoveryFocusChangesRunAfterTheSwiftUIUpdate() async throws {
        let focus = Focus()
        let binding = Binding<Int?>(get: { focus.index }, set: { focus.index = $0 })
        let wrapper = RecoveryWordField(text: .constant(""), index: 0, focusedWord: binding)
        let coordinator = wrapper.makeCoordinator()
        let field = Responder()
        field.hasFocus = true
        coordinator.updateFocus(field)
        #expect(field.hasFocus)
        try await Task.sleep(for: .milliseconds(20))
        #expect(!field.hasFocus)
        focus.index = 0
        coordinator.updateFocus(field)
        focus.index = 1 // A stale queued request must not steal focus back.
        try await Task.sleep(for: .milliseconds(20))
        #expect(!field.hasFocus)
        coordinator.textFieldDidBeginEditing(field)
        #expect(focus.index == 0)
    }

}
