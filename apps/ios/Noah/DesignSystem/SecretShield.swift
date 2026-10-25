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

struct SecretShield: ViewModifier {
    private static let log = AppLog("shield")
    @Environment(\.scenePhase) private var scenePhase
    /// This view's scene, found once it is in a window. Until then the shield is hidden, so no frame shows the secret.
    @State private var capture: SceneCapture?

    static func hidden(phase: ScenePhase, capture: SceneCapture?) -> Bool {
        phase != .active || capture?.captured ?? true
    }

    func body(content: Content) -> some View {
        let hidden = Self.hidden(phase: scenePhase, capture: capture)
        content
            .blur(radius: hidden ? 18 : 0)
            .overlay {
                if hidden {
                    Group {
                        if capture?.captured == true {
                            Label("Hidden while your screen is recorded or shared", systemImage: "eye.slash")
                        } else {
                            Label("Hidden for your security", systemImage: "eye.slash")
                        }
                    }
                    .font(NoahFont.callout)
                    .foregroundStyle(NoahColor.mutedForeground)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, NoahTokens.Space.m)
                }
            }
            // Hiding is instant (the app-switcher snapshot comes next); showing after Face ID or a recording
            // eases in. The first reading isn't keyed, so a shield that appears clear doesn't fade.
            .animation(hidden ? nil : .easeOut(duration: 0.25), value: scenePhase == .active && capture?.captured != true)
            .accessibilityHidden(hidden)
            .background(CaptureStateReader(capture: $capture))
            .onChange(of: capture == nil ? nil : hidden) { _, hidden in
                guard let hidden, let capture else { return }
                Self.log.i(#"Secret \#(hidden ? "hidden" : "shown"): scenePhase=\#(scenePhase) sceneCaptureState=\#(capture.stateName) hold=\#(capture.holding)"#)
            }
    }
}

/// A window scene's capture state for its secret shields, followed from the moment the scene connects.
/// When a recording, mirror or share ends, the scene stays captured for `hold`, so a brief "not captured"
/// reading can't show the secret, and a shield that appears meanwhile stays hidden too. A scene that
/// hasn't been captured is clear from its first reading.
@MainActor @Observable
final class SceneCapture {
    static let hold: Duration = .seconds(3)
    private static let scenes = NSMapTable<UIWindowScene, SceneCapture>.weakToStrongObjects()

    /// Captured now, or within `hold` of the capture ending.
    private(set) var captured = false
    @ObservationIgnored private var state = UISceneCaptureState.unspecified
    @ObservationIgnored private var holdTask: Task<Void, Never>?
    @ObservationIgnored private let sleep: @MainActor @Sendable (Duration) async throws -> Void

    init(sleep: @escaping @MainActor @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.sleep = sleep
    }

    var holding: Bool { holdTask != nil }

    var stateName: String {
        switch state {
        case .active: "active"
        case .inactive: "inactive"
        default: "unspecified"
        }
    }

    /// The scene's capture, following its trait from the first call (AppDelegate makes it as the scene connects).
    static func of(_ scene: UIWindowScene) -> SceneCapture {
        if let capture = scenes.object(forKey: scene) { return capture }
        let capture = SceneCapture()
        scenes.setObject(capture, forKey: scene)
        capture.report(scene.traitCollection.sceneCaptureState)
        scene.registerForTraitChanges([UITraitSceneCaptureState.self]) { (scene: UIWindowScene, _) in
            SceneCapture.of(scene).report(scene.traitCollection.sceneCaptureState)
        }
        return capture
    }

    func report(_ state: UISceneCaptureState) {
        self.state = state
        if state == .active {
            holdTask?.cancel()
            holdTask = nil
            if !captured { captured = true }
        } else if captured, holdTask == nil {
            holdTask = Task { [weak self, sleep] in
                do { try await sleep(Self.hold) } catch { return }
                guard let self, !Task.isCancelled else { return }
                holdTask = nil
                captured = false
            }
        }
    }
}

/// Hands the shield its window scene's capture once it is in a window, and nil when it leaves.
private struct CaptureStateReader: UIViewRepresentable {
    @Binding var capture: SceneCapture?

    func makeUIView(context: Context) -> CaptureView {
        let view = CaptureView()
        view.onScene = { found in
            if capture !== found { capture = found }
        }
        return view
    }

    func updateUIView(_ uiView: CaptureView, context: Context) {}

    final class CaptureView: UIView {
        var onScene: ((SceneCapture?) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            onScene?(window?.windowScene.map(SceneCapture.of))
        }
    }
}
