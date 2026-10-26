import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Opt-in screenshot fixtures for every onboarding state, including ones the
/// simulator can't reach (it never denies notifications). Write a state name
/// to Documents/onboarding-shot; "stop" ends the session. Fakes only: no
/// wallet, server or recovery phrase (the restore states use the public
/// BIP-39 test vector).
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_ONBOARDING_SHOTS"] == "1"))
struct OnboardingShotTests {
    static let vector = (Array(repeating: "abandon", count: 11) + ["about"]).joined(separator: " ")

    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.windows.first { $0.isKeyWindow })
        let original = window.rootViewController
        let app = AppModel()
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "onboarding-shot"), ready = folder.appending(path: "onboarding-shot-ready")
        let host = UIHostingController(rootView: AnyView(Color.clear))
        window.rootViewController = host
        defer {
            window.rootViewController = original
            try? FileManager.default.removeItem(at: ready)
            try? FileManager.default.removeItem(at: request)
        }
        var current = ""
        let deadline = ContinuousClock.now.advanced(by: .seconds(3500))
        while ContinuousClock.now < deadline {
            let state = (try? String(contentsOf: request, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if state == "stop" { return }
            if !state.isEmpty && state != current {
                // A new host each time, so screen state never leaks between fixtures.
                host.rootView = AnyView(Color.clear)
                try await Task.sleep(for: .milliseconds(100))
                host.rootView = AnyView(try await view(state).environment(app).environment(app.walletLock).environment(\.scenePhase, .active))
                try await Task.sleep(for: .milliseconds(400))
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
    }

    private func name(_ text: String, taken: Set<String> = ["satoshi"]) async -> NameAvailability {
        let model = NameAvailability(debounce: .zero) { taken.contains($0) }
        model.text = text
        model.textChanged()
        await model.settle()
        return model
    }

    private func email(_ text: String, stage: EmailVerification.Stage = .entry, code: String = "") async -> EmailVerification {
        let model = EmailVerification(send: { _ in stage == .verified }, verify: { code, _ in
            guard code == "000000" else { throw IdentityValidationError("wrong") }
        }, clearDelay: .seconds(600))
        model.text = text
        if stage != .entry {
            await model.sendCode()
            model.code = code
            // A wrong code holds its red state until `clearDelay`; don't wait for it.
            Task { await model.codeChanged() }
            try? await Task.sleep(for: .milliseconds(200))
        }
        return model
    }

    private func draft(_ phrase: String = "", focused: Int? = nil, checksum: Bool = false) -> RestoreDraft {
        var draft = RestoreDraft()
        if !phrase.isEmpty { draft.input.update(phrase, at: 0) }
        draft.focusedWord = focused
        draft.invalidPhrase = checksum
        return draft
    }

    private func view(_ state: String) async throws -> some View {
        let past = Date().addingTimeInterval(-6)
        let haptics = OnboardingHaptics()
        switch state {
        case "welcome":
            return AnyView(ShotHost(scene: .init(mode: .welcome, start: past)) { layout in
                WelcomeScreen(layout: layout, languageCode: "en", onLanguage: {}, onCreate: {}, onRestore: {})
            })
        case "beta", "beta-error", "language":
            return AnyView(ShotHost(scene: .init(mode: .welcome, start: past), sheet: true) { layout in
                WelcomeScreen(layout: layout, sheetUp: !layout.wide, languageCode: "en", onLanguage: {}, onCreate: {}, onRestore: {})
            } sheetContent: {
                if state == "language" { LanguageSheet(selected: "en") { _ in } }
                else { BetaSheet(busy: false, error: state == "beta-error" ? "Creation failed. Please try again." : nil) {} }
            })
        case "creating":
            return AnyView(ShotHost(scene: .init(mode: .create, start: Date().addingTimeInterval(-1.9))) { layout in
                OnboardingProgressScreen(layout: layout, mode: .create, phase: "Joining the Ark")
            })
        case "creating-ready":
            return AnyView(ShotHost(scene: .init(mode: .create, start: past)) { layout in
                OnboardingProgressScreen(layout: layout, mode: .create, phase: "Ready")
            })
        case "name", "name-short", "name-available", "name-taken":
            let text = ["name": "", "name-short": "ma", "name-available": "maya", "name-taken": "satoshi"][state]!
            let model = await name(text)
            return AnyView(ShotHost { layout in
                NameScreen(layout: layout, name: model, domain: "noahwallet.io", saving: false, error: nil,
                           haptics: haptics, onContinue: {}, onSkip: {})
            })
        case "email", "email-typo", "email-complete", "email-valid":
            let text = ["email": "", "email-typo": "maya@gmial.com", "email-complete": "maya@", "email-valid": "maya@gmail.com"][state]!
            let model = await email(text)
            return AnyView(ShotHost { layout in EmailScreen(layout: layout, email: model, haptics: haptics, onSkip: {}) })
        case "email-code", "email-wrong", "email-verified":
            let model = await email("maya@gmail.com", stage: state == "email-verified" ? .verified : .code,
                                    code: state == "email-code" ? "482" : state == "email-wrong" ? "123456" : "")
            return AnyView(ShotHost { layout in EmailScreen(layout: layout, email: model, haptics: haptics, onSkip: {}) })
        case "notifications", "notifications-required":
            let required = state == "notifications-required"
            return AnyView(ShotHost(scene: .init(mode: .conveyor, start: past, frozenAt: required ? 1.5 : nil)) { layout in
                NotificationsScreen(layout: layout, required: required, busy: false) {}
            })
        case "gate":
            return AnyView(PushNotificationsRequiredView())
        case "done", "done-skipped":
            return AnyView(ShotHost { layout in
                DoneScreen(layout: layout, address: state == "done" ? "maya@noahwallet.io" : nil, finishing: false) {}
            })
        case "restore", "restore-filled", "restore-24", "restore-invalid", "restore-checksum", "restore-keyboard":
            let phrase = switch state {
            case "restore-filled", "restore-keyboard": Self.vector
            case "restore-24": (Array(repeating: "abandon", count: 23) + ["art"]).joined(separator: " ")
            case "restore-invalid": Self.vector.replacingOccurrences(of: "abandon about", with: "abandn about")
            case "restore-checksum": Array(repeating: "abandon", count: 12).joined(separator: " ")
            default: ""
            }
            var value = draft(phrase, checksum: state == "restore-checksum")
            if state == "restore-keyboard" { value.input.update("ab", at: 11); value.focusedWord = 11 }
            if state == "restore-invalid" { value.focusedWord = nil }
            return AnyView(ShotHost { layout in
                RestoreBinding(draft: value) { binding in
                    RestoreWalletScreen(layout: layout, draft: binding, issue: nil, haptics: haptics,
                                        onPaste: { _, _ in }, onRestore: {}, onBack: {})
                }
            })
        case "restoring":
            return AnyView(ShotHost(scene: .init(mode: .restore, start: Date().addingTimeInterval(-1.5))) { layout in
                OnboardingProgressScreen(layout: layout, mode: .restore, phase: "Finding your bitcoin",
                                         count: .init(total: 0, lit: [], reveal: nil, unit: .bip177))
            })
        case "restoring-ready":
            let lit = TreeScene.found.map(\.leaf)
            return AnyView(ShotHost(scene: .init(mode: .restore, start: past, reveal: past, lit: lit)) { layout in
                OnboardingProgressScreen(layout: layout, mode: .restore, phase: "Ready",
                                         count: .init(total: 152_742, lit: lit, reveal: past, unit: .bip177))
            })
        case "restore-error":
            return AnyView(ShotHost { layout in
                RestoreBinding(draft: RestoreDraft()) { binding in
                    RestoreWalletScreen(layout: layout, draft: binding,
                                        issue: RestoreIssue(message: "No backup was found for this recovery phrase. Check the words and make sure this wallet had backups enabled."),
                                        haptics: haptics, onPaste: { _, _ in }, onRestore: {}, onBack: {})
                }
            })
        case "stored-seed":
            return AnyView(ShotHost { layout in StoredSeedRestoreScreen(layout: layout, issue: nil) {} })
        default:
            Issue.record("Unknown onboarding fixture \(state)")
            return AnyView(Color.red)
        }
    }
}

/// The flow's window: background, the tree scene and a screen; sheets stay
/// in the trailing pane when the window is wide, as in `OnboardingFlow`.
private struct ShotHost<Screen: View, Sheet: View>: View {
    var scene: TreeScene.State?
    var sheet = false
    @ViewBuilder var screen: (OnboardingLayout) -> Screen
    @ViewBuilder var sheetContent: Sheet
    @State private var layout = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var height: CGFloat = 0

    var body: some View {
        ZStack {
            NoahTokens.Colors.background.ignoresSafeArea()
            if let scene {
                TreeSceneView(state: scene, visible: true, placement: layout.scene(scene.mode)).ignoresSafeArea()
            }
            screen(layout)
            if sheet && layout.wide {
                OnboardingPaneSheet(pane: CGRect(x: 0, y: 0, width: layout.size.width / 2 - layout.insets.trailing, height: layout.size.height),
                                    onDismiss: nil) { sheetContent }
            }
        }
        .onboardingWindow($layout)
        .sheet(isPresented: .constant(sheet && !layout.wide && layout.size != .zero)) {
            sheetContent
                .padding(.horizontal, NoahTokens.Space.screen).padding(.top, 30).padding(.bottom, NoahTokens.Space.l)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
                .presentationDetents([.height(max(height, 240))])
                .presentationBackground(NoahTokens.Colors.sheet)
                .presentationDragIndicator(.visible)
        }
    }
}

extension ShotHost where Sheet == EmptyView {
    init(scene: TreeScene.State? = nil, @ViewBuilder screen: @escaping (OnboardingLayout) -> Screen) {
        self.init(scene: scene, sheet: false, screen: screen) { EmptyView() }
    }
}

private struct RestoreBinding<Content: View>: View {
    @State var draft: RestoreDraft
    @ViewBuilder var content: (Binding<RestoreDraft>) -> Content
    var body: some View { content($draft) }
}
