import SwiftUI
import UIKit

struct RestoreWalletScreen: View {
    var useStoredSeed = false
    @Environment(AppModel.self) private var model
    @State private var input = RecoveryPhraseInput()
    @State private var focusedWord: Int?
    @State private var invalidPhrase = false
    @State private var clearGeneration = 0
    @State private var clipboard = RestorePasteboard()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ScrollViewReader { scroll in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text(useStoredSeed ? "Recovery phrase found" : "Your recovery phrase")
                        .font(NoahFont.title)
                    Text(useStoredSeed
                         ? "Your recovery phrase is still securely stored on this device. Use it to restore your wallet from its backup."
                         : "Enter your 12 words in order, or paste the whole phrase into any word field.")
                        .foregroundStyle(NoahColor.mutedForeground)
                    if !useStoredSeed {
                        phraseFields
                            .id(clearGeneration)
                            .secretShield()
                            .disabled(model.isRestoring || model.restoreIssue?.requiresRestart == true)
                    }
                    if input.wrongWordCount || invalidPhrase {
                        Text(input.wrongWordCount ? "Paste exactly 12 words. Your entries haven't been replaced." : RestoreIssue.invalidPhrase.message)
                            .foregroundStyle(NoahColor.destructive)
                            .accessibilityIdentifier("restore-phrase-error")
                    }
                    if model.isRestoring {
                        ProgressView(progressMessage)
                            .accessibilityIdentifier("restore-progress")
                    }
                    if let issue = model.restoreIssue {
                        Text(issue.message)
                            .foregroundStyle(NoahColor.destructive)
                            .accessibilityIdentifier("restore-error")
                    }
                }
                .font(NoahFont.body)
                .padding(24)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: focusedWord) { _, index in
                if let index { withAnimation { scroll.scrollTo(index, anchor: .center) } }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                if let focusedWord { withAnimation { scroll.scrollTo(focusedWord, anchor: .center) } }
            }
        }
        .foregroundStyle(NoahColor.foreground)
        .background(NoahColor.background.ignoresSafeArea())
        .safeAreaInset(edge: .bottom) {
            if model.restoreIssue?.requiresRestart != true {
                VStack(spacing: 8) {
                    if let focusedWord, !model.isRestoring {
                        suggestions(at: focusedWord).secretShield()
                    }
                    NoahButton(title: "Restore Wallet", isLoading: model.isRestoring, action: restore)
                        .disabled(!useStoredSeed && !input.isComplete)
                        .accessibilityIdentifier("restore-wallet-submit")
                }
                .padding(20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .background(NoahColor.background)
            }
        }
        .navigationTitle("Restore Wallet")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.isRestoring || model.restoreIssue?.requiresRestart == true)
        .onDisappear(perform: clear)
        .onChange(of: scenePhase) { _, phase in if phase != .active { clear() } }
    }

    private var phraseFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            NoahCard {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    ForEach(0..<12) { index in
                        HStack(spacing: 8) {
                            Text("\(index + 1)")
                                .font(NoahFont.footnote)
                                .foregroundStyle(NoahColor.mutedForeground)
                                .frame(minWidth: 20, alignment: .trailing)
                            RecoveryWordField(
                                text: Binding(get: { input.words[index] }, set: {
                                    input.update($0, at: index)
                                    if !input.wrongWordCount, RecoveryPhraseInput.normalizedWords($0).count == 12 {
                                        clipboard.accepted($0)
                                    }
                                    invalidPhrase = false
                                }), index: index, focusedWord: $focusedWord)
                                .frame(height: 44)
                        }
                        .id(index)
                    }
                }
            }
            Text("\(input.wordCount) of 12 words")
                .font(NoahFont.footnote)
                .foregroundStyle(NoahColor.mutedForeground)
        }
    }

    private func suggestions(at index: Int) -> some View {
        let words = input.suggestions(at: index)
        // Only the fitting row exists visually or for VoiceOver: no offscreen suggestions,
        // full-phrase labels or hidden input. Each button announces exactly its visible word.
        return ViewThatFits(in: .horizontal) {
            suggestionButtons(words, at: index)
            suggestionButtons(Array(words.prefix(3)), at: index)
            suggestionButtons(Array(words.prefix(1)), at: index)
        }
    }

    private func suggestionButtons(_ words: [String], at index: Int) -> some View {
        HStack(spacing: 20) {
            ForEach(words, id: \.self) { word in
                Button(word) {
                    input.update(word, at: index)
                    focusedWord = index < 11 ? index + 1 : nil
                }
                .accessibilityLabel(Text(word))
                .frame(minHeight: 44)
            }
        }
        .fixedSize(horizontal: true, vertical: true)
    }

    private var progressMessage: String {
        switch model.restoreStep {
        case nil: "Starting restore…"
        case .fetching: "Fetching backup…"
        case .validating: "Validating backup…"
        case .loading: "Loading wallet…"
        case .finalizing: "Finalizing…"
        }
    }

    private func clear() {
        clipboard.clearIfUnchanged()
        focusedWord = nil
        input = RecoveryPhraseInput()
        clearGeneration += 1
    }

    private func restore() {
        focusedWord = nil
        guard useStoredSeed || (input.isComplete && RecoveryPhraseInput.isValid(input.phrase)) else {
            clear()
            invalidPhrase = true
            return
        }
        let phrase = useStoredSeed ? nil : input.phrase
        clear()
        Task { await model.restoreWallet(phrase: phrase) }
    }
}

/// UIKit exposes spellCheckingType, which SwiftUI's TextField does not.
/// Layout and keyboard avoidance remain owned by the SwiftUI scroll view.
struct RecoveryWordField: UIViewRepresentable {
    @Binding var text: String
    let index: Int
    @Binding var focusedWord: Int?

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> RecoveryWordTextField {
        let field = RecoveryWordTextField()
        field.delegate = context.coordinator
        field.returnKeyType = index == 11 ? .done : .next
        field.font = UIFont.preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.textColor = UIColor(NoahColor.foreground)
        field.accessibilityLabel = "Word \(index + 1)"
        field.accessibilityIdentifier = "restore-word-\(index + 1)"
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        let toolbar = UIToolbar()
        toolbar.items = [UIBarButtonItem(systemItem: .flexibleSpace),
                         UIBarButtonItem(title: "Done", style: .done, target: context.coordinator, action: #selector(Coordinator.done))]
        toolbar.sizeToFit()
        field.inputAccessoryView = toolbar
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return field
    }

    func updateUIView(_ field: RecoveryWordTextField, context: Context) {
        context.coordinator.parent = self
        if field.text != text { field.text = text }
        field.isEnabled = context.environment.isEnabled
        if focusedWord == index && !field.isFirstResponder { field.becomeFirstResponder() }
        if focusedWord != index && field.isFirstResponder { field.resignFirstResponder() }
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
        @objc func done() { parent.focusedWord = nil }
        func textFieldDidBeginEditing(_ textField: UITextField) { parent.focusedWord = parent.index }
        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            parent.focusedWord = parent.index < 11 ? parent.index + 1 : nil
            return true
        }
    }
}

/// Recovery words must never be retained in UIKit's shared undo manager.
final class RecoveryWordTextField: UITextField {
    override var undoManager: UndoManager? { nil }

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
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}
