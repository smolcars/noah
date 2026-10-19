import SwiftUI
import UIKit

struct RestoreWalletScreen: View {
    var useStoredSeed = false
    @Environment(AppModel.self) private var model
    @State private var input = RecoveryPhraseInput()
    @State private var focusedWord: Int?
    @State private var invalidPhrase = false

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
                NoahButton(title: "Restore Wallet", isLoading: model.isRestoring, action: restore)
                    .disabled(!useStoredSeed && !input.isComplete)
                    .padding(20)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                    .background(NoahColor.background)
                    .accessibilityIdentifier("restore-wallet-submit")
            }
        }
        .navigationTitle("Restore Wallet")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.isRestoring || model.restoreIssue?.requiresRestart == true)
        .onDisappear { input = RecoveryPhraseInput(); focusedWord = nil }
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
                                    invalidPhrase = false
                                }), index: index, focusedWord: $focusedWord)
                                .frame(height: 44)
                        }
                        .id(index)
                    }
                }
            }
            if let focusedWord {
                ScrollView(.horizontal) {
                    HStack(spacing: 12) {
                        ForEach(input.suggestions(at: focusedWord), id: \.self) { word in
                            Button(word) {
                                input.update(word, at: focusedWord)
                                self.focusedWord = focusedWord < 11 ? focusedWord + 1 : nil
                            }
                            .frame(minHeight: 44)
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            Text("\(input.wordCount) of 12 words")
                .font(NoahFont.footnote)
                .foregroundStyle(NoahColor.mutedForeground)
        }
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

    private func restore() {
        focusedWord = nil
        guard useStoredSeed || (input.isComplete && RecoveryPhraseInput.isValid(input.phrase)) else {
            invalidPhrase = true
            return
        }
        let phrase = useStoredSeed ? nil : input.phrase
        Task { await model.restoreWallet(phrase: phrase) }
    }
}

/// UIKit exposes spellCheckingType, which SwiftUI's TextField does not.
/// Layout and keyboard avoidance remain owned by the SwiftUI scroll view.
private struct RecoveryWordField: UIViewRepresentable {
    @Binding var text: String
    let index: Int
    @Binding var focusedWord: Int?

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextField {
        let field = UITextField()
        field.delegate = context.coordinator
        field.autocorrectionType = .no
        field.autocapitalizationType = .none
        field.spellCheckingType = .no
        field.smartQuotesType = .no
        field.smartDashesType = .no
        field.keyboardType = .asciiCapable
        field.textContentType = .password
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

    func updateUIView(_ field: UITextField, context: Context) {
        context.coordinator.parent = self
        if field.text != text { field.text = text }
        field.isEnabled = context.environment.isEnabled
        if focusedWord == index && !field.isFirstResponder { field.becomeFirstResponder() }
        if focusedWord != index && field.isFirstResponder { field.resignFirstResponder() }
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
