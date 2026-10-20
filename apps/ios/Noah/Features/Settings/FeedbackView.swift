import SwiftUI

struct FeedbackView: View {
    @State private var draft = FeedbackDraft()
    @State private var pickingPhoto = false
    @State private var busy = false
    @State private var success = false
    @State private var error: String?
    private enum Field { case name, email, subject, body }
    @FocusState private var focus: Field?

    var body: some View {
        Form {
            if success {
                Section {
                    Label("Thank You", systemImage: "checkmark.circle").font(NoahFont.title)
                    Text("Your feedback was sent to the Noah team.")
                }
            } else {
                Section {
                    Text("Report a bug or share what would make Noah better.")
                    TextField("Name", text: $draft.name).focused($focus, equals: .name)
                    TextField("Email (Optional)", text: $draft.email).keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focus, equals: .email)
                    TextField("Subject", text: $draft.subject).focused($focus, equals: .subject)
                    TextField("Message", text: $draft.body, axis: .vertical).lineLimit(5...12).focused($focus, equals: .body)
                }.disabled(busy)
                Section("Screenshot (optional)") {
                    Button { pickingPhoto = true } label: { Label("Add Screenshot", systemImage: "photo") }
                        .disabled(busy)
                    if let data = draft.screenshot, let uiImage = UIImage(data: data) {
                        Image(uiImage: uiImage).resizable().scaledToFit().frame(maxHeight: 180).accessibilityLabel("Selected screenshot")
                        Button("Remove Screenshot", role: .destructive) { draft.screenshot = nil }.disabled(busy)
                    }
                    Text("JPEG or PNG, 5 MB or smaller").font(NoahFont.footnote)
                }
                if let error { Section { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) } }
                Section {
                    Button(busy ? "Sending..." : "Send Feedback", action: submit)
                        .disabled(busy || draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || draft.subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            Section { Link("Telegram Support", destination: URL(string: "https://t.me/blixtwallet/605026")!) }
        }
        .settingsStyle("Send Feedback")
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focus = nil } } }
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
    private func submit() {
        guard !busy else { return }; focus = nil; busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let payload = try draft.payload(device: DeviceInfo.current())
                _ = try await AppServices.shared.client.submitSupportTicket(body: .json(payload)).ok
                success = true; draft = .init()
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
