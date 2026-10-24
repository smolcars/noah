import SwiftUI

/// Help → Feedback: today's fields, screenshot and limits, then Send; Telegram for a chat.
struct FeedbackView: View {
    @Environment(\.settings) private var settings
    @Environment(\.openURL) private var openURL
    @Environment(\.locale) private var locale
    @State private var draft = FeedbackDraft()
    @State private var pickingPhoto = false
    @State private var busy = false
    @State private var success = false
    @State private var error: String?
    @State private var haptics = OnboardingHaptics()

    private var language: String { locale.identifier }
    private var ready: Bool {
        ![draft.name, draft.subject, draft.body].contains { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    var body: some View {
        SettingsScroll(line: success ? nil : L10n.text("Report a bug or share an idea.", locale: language)) {
            if success {
                VStack(alignment: .leading, spacing: 0) {
                    VerifiedCheck().frame(width: 72, height: 72).padding(.top, 10)
                    Text(L10n.text("Thank you", locale: language)).noahText(SettingsText.heroName).padding(.top, 16)
                    Text(L10n.text("It’s with the Noah team.", locale: language)).noahText(NoahTokens.Typography.sub)
                        .foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
                }
                .accessibilityElement(children: .combine)
            } else {
                Group {
                    SettingsField(label: L10n.text("Name", locale: language), placeholder: L10n.text("Your name", locale: language), text: $draft.name)
                    SettingsField(label: L10n.text("Email (optional)", locale: language), placeholder: "you@example.com", text: $draft.email,
                                  keyboard: .emailAddress)
                    SettingsField(label: L10n.text("Subject", locale: language), placeholder: L10n.text("Short summary", locale: language), text: $draft.subject)
                    SettingsField(label: L10n.text("Message", locale: language), placeholder: L10n.text("What happened, or what would help?", locale: language),
                                  text: $draft.body, multiline: true)
                }
                .disabled(busy)
                Text(L10n.text("Screenshot (optional)", locale: language)).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
                    .padding(.top, 20)
                screenshot.padding(.top, 8)
                SettingsFine(text: L10n.text("JPEG or PNG, 5 MB or smaller", locale: language) + ". "
                             + L10n.text("Your app version and phone model are attached.", locale: language))
                if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
            }
            SettingsGroup {
                SettingsRow(icon: "paperplane", title: L10n.text("Chat on Telegram", locale: language), trailing: .external, hairline: false) {
                    openURL(NoahStoryView.telegram)
                }
            }
        }
        .settingsChrome("Feedback")
        .settingsFooter {
            if success {
                Button(L10n.text("Done", locale: language)) { settings.back() }.buttonStyle(SettingsActionStyle())
            } else {
                Button(action: submit) {
                    HStack(spacing: 8) {
                        if busy { ProgressView().tint(NoahTokens.Colors.background) }
                        Text(L10n.text("Send", locale: language))
                    }
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(busy || !ready)
                .accessibilityIdentifier("feedback-send")
            }
        }
        .onboardingHaptics(haptics)
        .sheet(isPresented: $pickingPhoto) {
            FeedbackImagePicker { image in
                error = nil
                do {
                    guard let data = image?.jpegData(compressionQuality: 0.8) else { throw IdentityValidationError("Could not read the selected screenshot.") }
                    _ = try FeedbackDraft.attachment(data)
                    draft.screenshot = data
                } catch let validation as IdentityValidationError { error = validation.errorDescription }
                catch { self.error = "Could not read the selected screenshot." }
            }
        }
    }

    @ViewBuilder private var screenshot: some View {
        if let data = draft.screenshot, let image = UIImage(data: data) {
            HStack(spacing: 14) {
                Image(uiImage: image).resizable().scaledToFill().frame(width: 64, height: 84)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityLabel(Text("Selected screenshot"))
                Button(L10n.text("Remove screenshot", locale: language), role: .destructive) { draft.screenshot = nil }
                    .buttonStyle(HomePillStyle(prominent: false, height: 40))
                    .disabled(busy)
                Spacer()
            }
        } else {
            Button { pickingPhoto = true } label: {
                Label(L10n.text("Add screenshot", locale: language), systemImage: "photo")
                    .noahText(NoahTokens.Typography.control)
                    .foregroundStyle(NoahTokens.Colors.muted)
                    .frame(maxWidth: .infinity, minHeight: 84)
                    .overlay { RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(NoahTokens.Colors.hairline, lineWidth: 1.5) }
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(busy)
        }
    }

    private func submit() {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let payload = try draft.payload(device: DeviceInfo.current())
                _ = try await AppServices.shared.client.submitSupportTicket(body: .json(payload)).ok
                haptics.play(.success)
                success = true
                draft = .init()
            } catch let validation as IdentityValidationError { error = validation.errorDescription }
            catch { self.error = "Failed to submit feedback. Please try again." }
        }
    }
}

private struct FeedbackImagePicker: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss
    let selected: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .photoLibrary
        picker.mediaTypes = ["public.image"]
        picker.allowsEditing = true
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: FeedbackImagePicker
        init(_ parent: FeedbackImagePicker) { self.parent = parent }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            parent.selected(info[.editedImage] as? UIImage)
            parent.dismiss()
        }
    }
}
