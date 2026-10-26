import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The words being entered. Memory only; they stay while the app is in the
/// background, and the flow clears them when Restore leaves the screen and before restoring.
struct RestoreDraft {
    var input = RecoveryPhraseInput()
    var focusedWord: Int?
    /// The words passed the list but not the BIP-39 checksum.
    var invalidPhrase = false
    /// The Paste button found something other than 12 or 24 words.
    var pasteRejected = false
    var shakes = 0
    /// Bumped when every field is replaced at once (paste, 12/24); fields restart and stagger in.
    var generation = 0
    /// Bumped by every edit to a word, which a paste still loading must not overwrite.
    var edits = 0

    var allInList: Bool { input.words.allSatisfy { RecoveryPhraseInput.wordSet.contains($0) } }
    var canRestore: Bool { input.isComplete && allInList }

    /// Typed words outside the list, except the one being typed.
    func isInvalid(_ index: Int) -> Bool {
        let word = input.words.indices.contains(index) ? input.words[index] : ""
        return !word.isEmpty && index != focusedWord && !RecoveryPhraseInput.wordSet.contains(word)
    }

    var firstInvalid: Int? { input.words.indices.first(where: isInvalid) }
}

/// "Welcome back": 12 or 24 words with BIP-39 suggestions above the keyboard.
struct RestoreWalletScreen: View {
    let layout: OnboardingLayout
    @Binding var draft: RestoreDraft
    let issue: RestoreIssue?
    let haptics: OnboardingHaptics
    /// A whole phrase was pasted from the pasteboard at that `changeCount`; the flow's `RestorePasteboard`
    /// clears it from the clipboard.
    let onPaste: (String, Int) -> Void
    let onRestore: () -> Void
    let onBack: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var locked: Bool { issue?.requiresRestart == true }
    private var keyboardUp: Bool { draft.focusedWord != nil }

    var body: some View {
        // Wide panes always top-align: the headline sits level with the 12/24 control,
        // keyboard or not, since a 24-word grid fills the pane anyway.
        let column = OnboardingColumn(wide: layout.wide, keyboard: layout.wide || keyboardUp)
        OnboardingPanes(wide: layout.wide, column: .leadingFits, sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            OnboardingHeadline(title: Text("Welcome back"), subtitle: Text("Enter your recovery phrase."), large: layout.wide)
                .padding(.top, layout.wide ? column.headlineTop : 56) // 56 keeps 24 words unscrolled on a 393-pt phone.
                .frame(maxHeight: layout.wide ? .infinity : nil, alignment: column.headlineAlignment)
                .onboardingPane()
            VStack(spacing: 0) {
                FitOrScroll {
                    VStack(alignment: .leading, spacing: 0) {
                        Spacer(minLength: 0).frame(maxHeight: column.lead)
                        WordCountPicker(count: draft.input.words.count, select: selectCount)
                            .disabled(locked)
                            .padding(.top, column.firstTop(compact: 18))
                            .onboardingAppear(2)
                        grid
                            .id(draft.generation)
                            .secretShield()
                            .disabled(locked)
                            .padding(.top, 14)
                            .keyframeAnimator(initialValue: CGFloat.zero, trigger: draft.shakes) { content, offset in
                                content.offset(x: reduceMotion ? 0 : offset)
                            } keyframes: { _ in
                                LinearKeyframe(-11, duration: 0.084)
                                LinearKeyframe(9, duration: 0.084)
                                LinearKeyframe(-6, duration: 0.084)
                                LinearKeyframe(3, duration: 0.084)
                                LinearKeyframe(0, duration: 0.084)
                            }
                            .onboardingAppear(3)
                        message.padding(.top, 14)
                        // Wide windows centre the actions with the words; compact pins them to the bottom.
                        if layout.wide && !keyboardUp && !locked {
                            actions.padding(.top, NoahTokens.Space.screen).onboardingAppear(4)
                        }
                        Spacer(minLength: NoahTokens.Space.l).frame(maxHeight: layout.wide ? .infinity : nil)
                    }
                    .onboardingPane()
                }
                if keyboardUp {
                    suggestions.onboardingPane(NoahTokens.Space.s).padding(.vertical, NoahTokens.Space.s)
                } else if !locked && !layout.wide {
                    actions.onboardingPane().padding(.bottom, NoahTokens.Space.l).onboardingAppear(4)
                }
            }
        }
        .overlay(alignment: .topLeading) {
            Button(action: onBack) { Image(systemName: "chevron.left").font(.body.weight(.semibold)) }
                .buttonStyle(OnboardingBarButtonStyle(muted: false))
                .accessibilityLabel(Text("Back"))
                .disabled(locked)
                .padding(.leading, 12)
                .padding(.top, 4)
        }
    }

    private func selectCount(_ count: Int) {
        guard count != draft.input.words.count else { return }
        draft.focusedWord = nil
        draft.input.selectWordCount(count)
        draft.invalidPhrase = false
        draft.pasteRejected = false
        draft.generation += 1
        haptics.play(.selection)
    }

    // MARK: Grid

    private var grid: some View {
        let count = draft.input.words.count
        // Accessibility text sizes keep whole words: two columns, scrolling if needed.
        let columns = count == 12 || dynamicTypeSize.isAccessibilitySize ? 2 : 3
        return Grid(horizontalSpacing: NoahTokens.Space.l, verticalSpacing: 2) {
            ForEach(0..<(count / columns), id: \.self) { row in
                GridRow {
                    ForEach(0..<columns, id: \.self) { column in
                        cell(row * columns + column, count: count)
                    }
                }
            }
        }
    }

    private func cell(_ index: Int, count: Int) -> some View {
        let invalid = draft.isInvalid(index)
        return HStack(spacing: NoahTokens.Space.s) {
            Text(verbatim: "\(index + 1)")
                .noahText(NoahTokens.Typography.caption)
                .monospacedDigit()
                .foregroundStyle(NoahTokens.Colors.muted)
                .frame(minWidth: 18, alignment: .trailing)
                .accessibilityHidden(true)
            RecoveryWordField(
                text: Binding(get: { draft.input.words.indices.contains(index) ? draft.input.words[index] : "" }, set: { update($0, at: index) }),
                index: index, wordCount: count, focusedWord: $draft.focusedWord,
                invalid: invalid, compact: count == 24,
                acceptFirst: { acceptFirst(at: index) })
                .frame(height: NoahTokens.Size.hitTarget)
        }
        .overlay(alignment: .bottom) {
            OnboardingUnderline(focused: draft.focusedWord == index, failed: invalid, thickness: 1)
                .padding(.leading, 26)
                .padding(.bottom, 5)
        }
        .onboardingAppear(draft.generation == 0 ? 0 : index, base: 0, step: 0.028, distance: 8)
    }

    private func update(_ text: String, at index: Int) {
        let pasted = RecoveryPhraseInput.normalizedWords(text).count > 1
        draft.edits += 1
        draft.input.update(text, at: index)
        draft.invalidPhrase = false
        draft.pasteRejected = false
        // A field pastes synchronously, so the pasteboard still holds what it pasted.
        if pasted && !draft.input.wrongWordCount { onPasted(text, change: UIPasteboard.general.changeCount) }
    }

    /// A whole phrase pasted into any field fills every word.
    func onPasted(_ text: String, change: Int) {
        draft.focusedWord = nil
        draft.generation += 1
        haptics.play(.light)
        if draft.input.words.indices.contains(where: { draft.isInvalid($0) }) { haptics.play(.warning) }
        onPaste(text, change)
    }

    private func acceptFirst(at index: Int) -> Bool {
        guard let first = draft.input.suggestions(at: index).first else { return false }
        accept(first, at: index)
        return true
    }

    private func accept(_ word: String, at index: Int) {
        draft.edits += 1
        draft.input.update(word, at: index)
        draft.invalidPhrase = false
        draft.focusedWord = index < draft.input.words.count - 1 ? index + 1 : nil
        haptics.play(.selection)
    }

    @ViewBuilder
    private var message: some View {
        let text: String? = if let issue { issue.message }
            else if draft.input.wrongWordCount || draft.pasteRejected { "Paste exactly 12 or 24 words. Your entries haven't been replaced." }
            else if let invalid = draft.firstInvalid { L10n.text("Check word {number}", invalid + 1, locale: locale.identifier) }
            else if draft.invalidPhrase { "These words don’t form a valid phrase." }
            else { nil }
        Text(LocalizedStringKey(text ?? " "))
            .noahText(NoahTokens.Typography.sub)
            .foregroundStyle(NoahTokens.Colors.failure)
            .frame(maxWidth: .infinity, minHeight: 20, alignment: .leading)
            .opacity(text == nil ? 0 : 1)
            .accessibilityHidden(text == nil)
            .accessibilityIdentifier(issue == nil ? "restore-phrase-error" : "restore-error")
            .animation(NoahTokens.Motion.snappy, value: text)
    }

    // MARK: Keyboard accessory and actions

    /// Up to three BIP-39 completions (the first is bold; space or return
    /// accepts it) and a button to hide the keyboard.
    private var suggestions: some View {
        let index = draft.focusedWord ?? 0
        let words = Array(draft.input.suggestions(at: index).prefix(3))
        return GlassGroup(spacing: NoahTokens.Space.s) {
            HStack(spacing: NoahTokens.Space.s) {
                ForEach(Array(words.enumerated()), id: \.element) { position, word in
                    Button(word) { accept(word, at: index) }
                        .buttonStyle(OnboardingChipStyle(bold: position == 0))
                        .accessibilityLabel(Text(verbatim: word))
                }
                Spacer(minLength: 0)
                Button { draft.focusedWord = nil } label: { Image(systemName: "keyboard.chevron.compact.down") }
                    .buttonStyle(OnboardingBarButtonStyle(muted: false))
                    .accessibilityLabel(Text("Hide keyboard"))
            }
        }
        .secretShield()
    }

    private var actions: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: NoahTokens.Space.s)) : AnyLayout(HStackLayout(spacing: NoahTokens.Space.m))
        return layout {
            // The system paste control: tapping it is consent, so it reads without asking. A plain button's
            // read returns nil unless pasting from other apps is allowed in Settings.
            RestorePasteControl {
                // The items load after the tap. Back, the 12/24 switch, Restore (the generation) or any edit to a word
                // meanwhile drop the late text.
                let generation = draft.generation, edits = draft.edits, change = UIPasteboard.general.changeCount
                return { text in if draft.generation == generation && draft.edits == edits { paste(text, change: change) } }
            }
                .frame(maxWidth: .infinity, minHeight: NoahTokens.Size.button, maxHeight: NoahTokens.Size.button)
                .accessibilityIdentifier("restore-paste")
            Button("Restore", action: onRestore)
                .buttonStyle(OnboardingPrimaryButtonStyle())
                .disabled(!draft.canRestore)
                .accessibilityIdentifier("restore-wallet-submit")
        }
    }

    /// What the paste control read; `RestorePasteboard` (in the flow) clears an accepted phrase from the clipboard.
    private func paste(_ text: String, change: Int) {
        guard [12, 24].contains(RecoveryPhraseInput.normalizedWords(text).count) else {
            draft.pasteRejected = true
            haptics.play(.warning)
            return
        }
        draft.input.update(text, at: 0)
        draft.invalidPhrase = false
        draft.pasteRejected = false
        onPasted(text, change: change)
    }
}

/// `UIPasteControl` as the secondary capsule (the system draws it, so it can't be glass). Passes on
/// every pasted string, one per line.
struct RestorePasteControl: UIViewRepresentable {
    /// Called as the control is tapped; what it returns gets the text once the items load, unless the control was
    /// tapped again first.
    let onPaste: () -> (String) -> Void

    func makeCoordinator() -> Target { Target() }

    func makeUIView(context: Context) -> UIPasteControl {
        var configuration = UIPasteControl.Configuration()
        configuration.displayMode = .iconAndLabel
        configuration.cornerStyle = .capsule
        configuration.baseBackgroundColor = UIColor(NoahTokens.Colors.soft)
        configuration.baseForegroundColor = UIColor(NoahTokens.Colors.foreground)
        let control = UIPasteControl(configuration: configuration)
        control.target = context.coordinator
        return control
    }

    func updateUIView(_ control: UIPasteControl, context: Context) {
        context.coordinator.onPaste = onPaste
    }

    final class Target: UIResponder {
        var onPaste: () -> (String) -> Void = { { _ in } }
        private var latest = 0

        override init() {
            super.init()
            // Plain text, or RTF alone (a text field accepts that too).
            let configuration = UIPasteConfiguration(forAccepting: String.self)
            configuration.addAcceptableTypeIdentifiers([UTType.rtf.identifier])
            pasteConfiguration = configuration
        }

        override func paste(itemProviders: [NSItemProvider]) {
            latest += 1
            let request = latest, finish = onPaste()
            Task {
                var strings: [String] = []
                for provider in itemProviders {
                    if let text = await Self.text(provider) { strings.append(text) }
                }
                if request == latest { finish(strings.joined(separator: "\n")) }
            }
        }

        private static func text(_ provider: NSItemProvider) async -> String? {
            if provider.canLoadObject(ofClass: String.self) {
                return await withCheckedContinuation { continuation in
                    _ = provider.loadObject(ofClass: String.self) { text, _ in continuation.resume(returning: text) }
                }
            }
            guard provider.hasItemConformingToTypeIdentifier(UTType.rtf.identifier) else { return nil }
            return await withCheckedContinuation { continuation in
                _ = provider.loadDataRepresentation(for: .rtf) { data, _ in
                    let text = data.flatMap { try? NSAttributedString(data: $0, options: [.documentType: NSAttributedString.DocumentType.rtf], documentAttributes: nil) }
                    continuation.resume(returning: text?.string)
                }
            }
        }
    }
}

/// 12 / 24 with a sliding pill, on glass.
private struct WordCountPicker: View {
    let count: Int
    let select: (Int) -> Void
    @Namespace private var pill
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 0) {
            ForEach([12, 24], id: \.self) { option in
                Button { select(option) } label: {
                    (option == 12 ? Text("12 words") : Text("24 words"))
                        .noahText(NoahTokens.Typography.control)
                        .bold()
                        .foregroundStyle(option == count ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
                        .padding(.horizontal, NoahTokens.Space.l)
                        .frame(minHeight: 34)
                        .background {
                            if option == count {
                                Capsule()
                                    .fill(colorScheme == .dark ? Color(white: 0.23) : NoahTokens.Colors.background)
                                    .shadow(color: .black.opacity(0.14), radius: 2, y: 1)
                                    .matchedGeometryEffect(id: "pill", in: pill)
                            }
                        }
                        .frame(minHeight: NoahTokens.Size.hitTarget - 6)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(option == count ? .isSelected : [])
            }
        }
        .padding(3)
        .glassCompat(in: Capsule())
        .animation(NoahTokens.Motion.travel(reduceMotion), value: count)
        .accessibilityIdentifier("restore-word-count")
    }
}

/// Reinstall recovery: the phrase is still in the Keychain, so there is
/// nothing to type.
struct StoredSeedRestoreScreen: View {
    let layout: OnboardingLayout
    let issue: RestoreIssue?
    let onRestore: () -> Void

    var body: some View {
        OnboardingPanes(wide: layout.wide, column: .leadingFits, sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            OnboardingHeadline(title: Text("Recovery phrase found"),
                               subtitle: Text("Your recovery phrase is still securely stored on this device. Use it to restore your wallet from its backup."),
                               large: layout.wide)
                .padding(.top, layout.wide ? 0 : 60)
                .frame(maxHeight: layout.wide ? .infinity : nil)
                .onboardingPane()
            VStack(alignment: .leading, spacing: NoahTokens.Space.l) {
                Spacer(minLength: 0)
                if let issue {
                    Text(LocalizedStringKey(issue.message))
                        .noahText(NoahTokens.Typography.sub)
                        .foregroundStyle(NoahTokens.Colors.failure)
                        .accessibilityIdentifier("restore-error")
                }
                if issue?.requiresRestart != true {
                    Button("Restore Wallet", action: onRestore)
                        .buttonStyle(OnboardingPrimaryButtonStyle())
                        .accessibilityIdentifier("restore-wallet-submit")
                }
                Spacer(minLength: 0).frame(maxHeight: layout.wide ? .infinity : 0)
            }
            .padding(.bottom, NoahTokens.Space.l)
            .onboardingPane()
        }
    }
}

/// UIKit exposes spellCheckingType, which SwiftUI's TextField does not.
/// Layout and keyboard avoidance remain owned by the SwiftUI scroll view.
struct RecoveryWordField: UIViewRepresentable {
    @Binding var text: String
    let index: Int
    var wordCount = 12
    @Binding var focusedWord: Int?
    var invalid = false
    /// 24 words use 16 pt so three columns fit.
    var compact = false
    /// Space or return accept the first suggestion; false when there is none.
    var acceptFirst: () -> Bool = { false }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> RecoveryWordTextField {
        let field = RecoveryWordTextField()
        field.delegate = context.coordinator
        field.returnKeyType = index == wordCount - 1 ? .done : .next
        field.adjustsFontForContentSizeCategory = true
        field.tintColor = UIColor(NoahTokens.Colors.orange)
        field.accessibilityLabel = L10n.text("Word {number}", index + 1)
        field.accessibilityIdentifier = "restore-word-\(index + 1)"
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return field
    }

    func updateUIView(_ field: RecoveryWordTextField, context: Context) {
        context.coordinator.parent = self
        if field.text != text { field.text = text }
        field.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .systemFont(ofSize: compact ? 16 : 17))
        field.textColor = UIColor(invalid ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
        field.returnKeyType = index == wordCount - 1 ? .done : .next
        field.isEnabled = context.environment.isEnabled
        context.coordinator.updateFocus(field)
    }

    static func dismantleUIView(_ field: RecoveryWordTextField, coordinator: Coordinator) {
        field.resignFirstResponder()
        field.text = ""
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: RecoveryWordField
        init(_ parent: RecoveryWordField) { self.parent = parent }
        @objc func changed(_ field: UITextField) {
            parent.text = field.text ?? ""
            field.text = parent.text
        }
        func textFieldDidBeginEditing(_ textField: UITextField) {
            if parent.focusedWord != parent.index { parent.focusedWord = parent.index }
        }
        func updateFocus(_ field: UITextField) {
            // Resigning inside a SwiftUI graph update loops on iOS 27.0 after rotation.
            DispatchQueue.main.async { [weak self, weak field] in
                guard let self, let field else { return }
                if parent.focusedWord == parent.index {
                    if !field.isFirstResponder { field.becomeFirstResponder() }
                } else if field.isFirstResponder { field.resignFirstResponder() }
            }
        }
        private func advance() {
            parent.focusedWord = parent.index < parent.wordCount - 1 ? parent.index + 1 : nil
        }
        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            if !parent.acceptFirst() { advance() }
            return false
        }
        /// A typed space accepts the first suggestion or moves on; pasted phrases still pass through.
        func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
            guard string == " " else { return true }
            if !parent.acceptFirst(), !(textField.text ?? "").isEmpty { advance() }
            return false
        }
        /// The edit menu offers only Paste and selection.
        func textField(_ textField: UITextField, editMenuForCharactersIn range: NSRange, suggestedActions: [UIMenuElement]) -> UIMenu? {
            UIMenu(children: RecoveryWordTextField.editMenu(suggestedActions))
        }
    }
}

/// Recovery words must never be retained in UIKit's shared undo manager, and
/// never leave the field: they stay visible, but Copy, Cut, Share, Look Up,
/// Translate and Define are refused from the edit menu, keyboard shortcuts and
/// accessibility, and dragging text out is off. Paste and selection still work.
final class RecoveryWordTextField: UITextField {
    override var undoManager: UndoManager? { nil }

    static let blockedActions: Set<Selector> = [
        #selector(UIResponderStandardEditActions.copy(_:)), #selector(UIResponderStandardEditActions.cut(_:)),
        Selector(("_share:")), Selector(("_lookup:")), Selector(("_translate:")), Selector(("_define:")),
    ]
    static let menuActions: Set<Selector> = [
        #selector(UIResponderStandardEditActions.paste(_:)), #selector(UIResponderStandardEditActions.select(_:)),
        #selector(UIResponderStandardEditActions.selectAll(_:)),
    ]

    /// Keeps only Paste and selection commands; actions without a selector (AutoFill, Writing Tools, Share) go.
    static func editMenu(_ elements: [UIMenuElement]) -> [UIMenuElement] {
        elements.compactMap { element in
            if let command = element as? UICommand { return menuActions.contains(command.action) ? command : nil }
            if let menu = element as? UIMenu {
                let children = editMenu(menu.children)
                return children.isEmpty ? nil : menu.replacingChildren(children)
            }
            return nil
        }
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        Self.blockedActions.contains(action) ? false : super.canPerformAction(action, withSender: sender)
    }

    /// ⌘C, ⌘X and any other direct call export nothing.
    override func copy(_ sender: Any?) {}
    override func cut(_ sender: Any?) {}

    override func validate(_ command: UICommand) {
        super.validate(command)
        if Self.blockedActions.contains(command.action) { command.attributes.insert(.disabled) }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        // Visible entry is intentional. AppDelegate blocks keyboard extensions; disable
        // system predictions and text transformations without hiding typed words.
        autocorrectionType = .no
        spellCheckingType = .no
        smartQuotesType = .no
        smartDashesType = .no
        smartInsertDeleteType = .no
        inlinePredictionType = .no
        textContentType = nil
        keyboardType = .asciiCapable
        autocapitalizationType = .none
        writingToolsBehavior = .none
        textDragInteraction?.isEnabled = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}
