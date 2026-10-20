import SwiftUI
import UIKit

extension View {
    /// Hides secret content (recovery phrase) while the app is not active,
    /// so the app-switcher snapshot never contains it, and while the screen
    /// is being recorded, mirrored or shared. Screenshots themselves cannot
    /// be blocked on iOS. While visible, system VoiceOver reads only the same
    /// content shown on screen; shielding also removes it from accessibility.
    func secretShield() -> some View {
        modifier(SecretShield())
    }
}

private struct SecretShield: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase
    @State private var isCaptured = false

    func body(content: Content) -> some View {
        let hidden = scenePhase != .active || isCaptured
        content
            .blur(radius: hidden ? 18 : 0)
            .overlay {
                if hidden {
                    Label("Hidden for your security", systemImage: "eye.slash")
                        .font(NoahFont.callout)
                        .foregroundStyle(NoahColor.mutedForeground)
                }
            }
            .accessibilityHidden(hidden)
            .background(CaptureStateReader(isCaptured: $isCaptured))
    }
}

/// Reads the window scene's capture state (iOS 17+) and follows changes.
private struct CaptureStateReader: UIViewRepresentable {
    @Binding var isCaptured: Bool

    func makeUIView(context: Context) -> CaptureView {
        let view = CaptureView()
        view.onChange = { captured in
            if isCaptured != captured { isCaptured = captured }
        }
        return view
    }

    func updateUIView(_ uiView: CaptureView, context: Context) {}

    final class CaptureView: UIView {
        var onChange: ((Bool) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            registerForTraitChanges([UITraitSceneCaptureState.self]) { (view: CaptureView, _) in
                view.report()
            }
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            report()
        }

        private func report() {
            onChange?(traitCollection.sceneCaptureState == .active)
        }
    }
}
