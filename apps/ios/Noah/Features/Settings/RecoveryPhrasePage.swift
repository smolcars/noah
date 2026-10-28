import Observation
import SwiftUI

/// The recovery phrase behind authentication. The words are read only after it succeeds, and
/// are cleared on background, on leaving, and whenever the lock's generation moves on.
@MainActor @Observable
final class PhraseGate {
    struct Dependencies {
        var authenticate: (String) async -> RecoveryPhraseAuthentication
        var hasNoScreenLock: () async -> Bool
        var words: () -> [String]
        /// Moves on whenever the app backgrounds; a result for an older one is dropped.
        var generation: () -> Int

        @MainActor static func live(lock: WalletLock, model: AppModel) -> Self {
            Self(authenticate: { await lock.authenticateRecoveryPhrase($0) }, hasNoScreenLock: { await lock.hasNoScreenLock() },
                 words: { model.currentMnemonicWords() }, generation: { lock.policy.generation })
        }
    }

    enum State: Equatable { case gated, authenticating, noScreenLock, shown }

    private(set) var state = State.gated
    private(set) var words: [String] = []
    @ObservationIgnored private var dependencies: Dependencies
    @ObservationIgnored private var warned: Int?

    init(dependencies: Dependencies) { self.dependencies = dependencies }

    /// Asks for Face ID or the passcode. No screen lock asks first ("Show anyway").
    func show(reason: String, active: () -> Bool = { true }) async {
        guard state == .gated else { return }
        state = .authenticating
        let generation = dependencies.generation()
        let result = await dependencies.authenticate(reason)
        guard state == .authenticating, generation == dependencies.generation(), active() else {
            if state == .authenticating { state = .gated }
            return
        }
        switch result {
        case .authenticated: reveal()
        case .noScreenLock:
            warned = generation
            state = .noScreenLock
        case .denied: state = .gated
        }
    }

    /// "Show anyway" from the No screen lock sheet, for the same visit; a lock set meanwhile asks for it.
    func showAnyway(reason: String, active: () -> Bool = { true }) async {
        let generation = dependencies.generation()
        guard state == .noScreenLock, warned == generation, active() else { clear(); return }
        warned = nil
        let noScreenLock = await dependencies.hasNoScreenLock()
        // A background during the check clears the visit; never reveal after it.
        guard state == .noScreenLock, generation == dependencies.generation(), active() else { clear(); return }
        if noScreenLock {
            reveal()
        } else {
            state = .gated
            await show(reason: reason, active: active)
        }
    }

    func clear() {
        words = []
        warned = nil
        state = .gated
    }

    private func reveal() {
        words = dependencies.words()
        state = words.isEmpty ? .gated : .shown
    }
}

/// Security → Recovery phrase. Before authentication the grid shows bars and Show, and no word
/// is in the view; the words soften in after it. Copy is local-only for two minutes.
struct RecoveryPhrasePage: View {
    @Environment(AppModel.self) private var model
    @Environment(WalletLock.self) private var lock
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var gate: PhraseGate?
    @State private var revealed = false
    @State private var error: String?
    @State private var haptics = OnboardingHaptics()
    @Environment(\.settingsFixture) private var fixture

    private var language: String { locale.identifier }
    private var words: [String] { gate?.words ?? [] }
    private var shown: Bool { gate?.state == .shown }
    private static let bars: [CGFloat] = [0.52, 0.64, 0.40, 0.58, 0.46, 0.70, 0.60, 0.44, 0.56, 0.50, 0.66, 0.48]

    var body: some View {
        SettingsScroll(line: shown ? L10n.text("{n} words that restore your wallet.", words.count, locale: language)
            : L10n.text("The words that restore your wallet.", locale: language)) {
                grid.padding(.top, 22)
                SettingsWarning(text: L10n.text("Never share them. Anyone with these words can take your bitcoin.", locale: language))
                if let failure = lock.error ?? error {
                    SettingsFine(text: L10n.text(failure, locale: language), failure: true)
                }
            }
            .settingsChrome("Recovery phrase")
            .settingsFooter {
                if shown {
                    Button(action: copy) { Label(L10n.text("Copy", locale: language), systemImage: "doc.on.doc") }
                        .buttonStyle(OnboardingSecondaryButtonStyle(height: NoahTokens.Size.button))
                        .accessibilityIdentifier("phrase-copy")
                    if model.needsRecoveryPhraseBackup {
                        Button(L10n.text("I wrote it down", locale: language), action: confirm)
                            .buttonStyle(SettingsActionStyle())
                            .accessibilityIdentifier("backup-confirm")
                    }
                }
            }
            .onboardingHaptics(haptics)
            .settingsSheet(item: Binding(get: { gate?.state == .noScreenLock ? NoScreenLock() : nil },
                                         set: { if $0 == nil, gate?.state == .noScreenLock { gate?.clear() } })) { _ in
                SettingsSheetBody(title: L10n.text("No screen lock", locale: language),
                                  line: L10n.text(
                                      "Anyone with this phone could see your phrase. Set a screen lock in Settings to protect it.",
                                      locale: language)) {
                    EmptyView()
                } actions: {
                    SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Show anyway", locale: language),
                                         actionID: "phrase-show-anyway", onCancel: { gate?.clear() }) {
                        Task { await gate?.showAnyway(reason: reason, active: { scenePhase == .active }) }
                    }
                }
            }
            .task {
                if gate == nil { gate = PhraseGate(dependencies: fixture?.phrase ?? .live(lock: lock, model: model)) }
                try? await Task.sleep(for: .milliseconds(420))
                await authenticate()
            }
            .onChange(of: scenePhase) { _, phase in if phase == .background { hide() } }
            .onChange(of: shown) { _, shown in if shown { haptics.play(.success) } }
            // Face ID answers while its prompt still covers the page; the words soften in once they can be seen.
            .onChange(of: shown && scenePhase == .active) { _, visible in
                withAnimation(reduceMotion ? NoahTokens.Motion.reduceMotionFade : nil) { revealed = visible }
            }
            .onDisappear(perform: hide)
            .accessibilityIdentifier("recovery-phrase")
    }

    private var reason: String { L10n.text("View your seed phrase", locale: language) }

    /// Two columns of numbered cells. Bars until authenticated; then the words, blurring in one after another.
    private var grid: some View {
        let count = shown ? words.count : Self.bars.count
        return LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 8) {
            ForEach(0..<count, id: \.self) { index in
                HStack(spacing: 10) {
                    Text(verbatim: "\(index + 1)").noahText(HomeText.section).fontDesign(.default)
                        .foregroundStyle(NoahTokens.Colors.muted).frame(width: 17, alignment: .trailing)
                    if shown {
                        PhraseWord(word: words[index], index: index, revealed: revealed)
                    } else {
                        GeometryReader { proxy in
                            Capsule().fill(NoahTokens.Colors.hairline)
                                .frame(width: proxy.size.width * Self.bars[index % Self.bars.count], height: 10)
                                .frame(maxHeight: .infinity)
                        }
                        .frame(height: 22)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 14)
                .frame(height: 46)
                .background(NoahTokens.Colors.soft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
        .accessibilityElement(children: shown ? .contain : .ignore)
        .accessibilityLabel(Text(shown ? "" : L10n.text("Recovery phrase hidden", locale: language)))
        .overlay {
            if !shown {
                Button { Task { await authenticate() } } label: {
                    Label(L10n.text("Show", locale: language), systemImage: lock.biometry.symbol == "lock" ? "lock.open" : lock.biometry.symbol)
                        .padding(.horizontal, 6)
                }
                .buttonStyle(HomePillStyle(prominent: false, height: 48, font: NoahTokens.Typography.button))
                .fixedSize()
                .disabled(gate?.state == .authenticating)
                .accessibilityIdentifier("phrase-show")
            }
        }
        .secretShield()
    }

    private func authenticate() async {
        error = nil
        await gate?.show(reason: reason, active: { scenePhase != .background })
    }

    private func hide() {
        revealed = false
        gate?.clear()
    }

    private func copy() {
        guard shown else { return }
        SecureCopy.copy(words.joined(separator: " "))
        haptics.play(.success)
        settings.toast("Copied · clears in 2 minutes")
    }

    private func confirm() {
        do {
            try model.confirmRecoveryPhraseBackedUp()
            haptics.play(.success)
            settings.toast("Backed up")
            settings.back()
        } catch { self.error = "Couldn't save your preference." }
    }
}

private struct NoScreenLock: Identifiable, Equatable { var id: Int { 0 } }

/// One word, softening in from a blur after the one before it.
private struct PhraseWord: View {
    let word: String
    let index: Int
    let revealed: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Text(verbatim: word).noahText(NoahTokens.Typography.label).lineLimit(1).minimumScaleFactor(0.7)
            .opacity(revealed ? 1 : 0)
            .blur(radius: revealed || reduceMotion ? 0 : 8)
            .offset(y: revealed || reduceMotion ? 0 : 4)
            .animation(reduceMotion ? NoahTokens.Motion.reduceMotionFade
                : NoahTokens.Motion.soft.delay(Double(index) * 0.035), value: revealed)
    }
}
