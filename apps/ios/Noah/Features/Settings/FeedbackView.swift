import PhotosUI
import SwiftUI

struct FeedbackView: View {
    @State private var draft = FeedbackDraft()
    @State private var photo: PhotosPickerItem?
    @State private var loadingPhoto = false
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
                    Text("Your feedback has been sent to the Noah team.")
                }
            } else {
                Section {
                    Text("Report a bug or share what would make Noah better.")
                    TextField("Name", text: $draft.name).focused($focus, equals: .name)
                    TextField("Email (optional)", text: $draft.email).keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focus, equals: .email)
                    TextField("Subject", text: $draft.subject).focused($focus, equals: .subject)
                    TextField("Message", text: $draft.body, axis: .vertical).lineLimit(5...12).focused($focus, equals: .body)
                }.disabled(busy)
                Section("Screenshot (optional)") {
                    PhotosPicker(selection: $photo, matching: .images) { Label("Add Screenshot", systemImage: "photo") }
                        .disabled(busy || loadingPhoto)
                    if loadingPhoto { ProgressView() }
                    if let data = draft.screenshot, let uiImage = UIImage(data: data) {
                        Image(uiImage: uiImage).resizable().scaledToFit().frame(maxHeight: 180).accessibilityLabel("Selected screenshot")
                        Button("Remove Screenshot", role: .destructive) { photo = nil; draft.screenshot = nil }.disabled(busy)
                    }
                    Text("JPEG or PNG, 5 MB or smaller").font(NoahFont.footnote)
                }
                if let error { Section { Text(error).foregroundStyle(NoahColor.destructive) } }
                Section {
                    Button(busy ? "Sending..." : "Send Feedback", action: submit)
                        .disabled(busy || loadingPhoto || draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || draft.subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            Section { Link("Telegram Support", destination: URL(string: "https://t.me/blixtwallet/605026")!) }
        }
        .settingsStyle("Send Feedback")
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focus = nil } } }
        .task(id: photo) {
            guard let photo else { return }
            loadingPhoto = true; error = nil; draft.screenshot = nil
            defer { loadingPhoto = false }
            do {
                guard let data = try await photo.loadTransferable(type: Data.self) else { throw IdentityValidationError("Could not read the selected screenshot.") }
                try Task.checkCancellation()
                _ = try FeedbackDraft.attachment(data)
                draft.screenshot = data
            } catch is CancellationError { } catch let validation as IdentityValidationError {
                error = validation.errorDescription
            } catch { self.error = "Could not read the selected screenshot." }
        }
    }
    private func submit() {
        guard !busy, !loadingPhoto else { return }; focus = nil; busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let payload = try draft.payload(device: DeviceInfo.current())
                _ = try await AppServices.shared.client.submitSupportTicket(body: .json(payload)).ok
                success = true; draft = .init(); photo = nil
            } catch let validation as IdentityValidationError { error = validation.errorDescription }
            catch { self.error = "Failed to submit feedback. Please try again." }
        }
    }
}
