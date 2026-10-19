import SwiftUI

struct EmergencyEmailView: View {
    let fromOnboarding: Bool
    let onContinue: () -> Void

    private enum Field: Hashable { case email, code }
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focus: Field?
    @State private var email = ""
    @State private var code = ""
    @State private var codeSent = false
    @State private var busy = false
    @State private var registrationFailed = false
    @State private var errorMessage: String?
    @State private var errorTitle = "Error"
    @State private var completeAfterAlert = false

    var body: some View {
        ScrollViewReader { scroll in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text("Emergency Email").font(NoahFont.title)
                    if model.server.isRegistering {
                        ProgressView("Setting up your account...")
                    }
                    if codeSent {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("We sent a 6-digit verification code to:")
                                .foregroundStyle(NoahColor.mutedForeground)
                            Text(email).font(NoahFont.headline)
                        }
                        NoahCard {
                            VStack(alignment: .leading, spacing: 16) {
                                Text("Enter Verification Code").font(NoahFont.footnote)
                                TextField("6-digit code", text: $code,
                                          prompt: Text("6-digit code").foregroundStyle(NoahColor.mutedForeground))
                                    .font(NoahFont.inter(28, .medium))
                                    .monospacedDigit()
                                    .textContentType(.oneTimeCode)
                                    .keyboardType(.numberPad)
                                    .focused($focus, equals: .code)
                                    .id(Field.code)
                                    .accessibilityIdentifier("verification-code-input")
                                    .onChange(of: code) { _, value in
                                        code = String(value.filter { $0.isASCII && $0.isNumber }.prefix(6))
                                        if code.count == 6 { focus = nil }
                                    }
                            }
                        }
                        VStack(spacing: 8) {
                            Text("Didn't receive the code?").foregroundStyle(NoahColor.mutedForeground)
                            Button("Resend Code") { code = ""; send() }
                                .frame(minHeight: 44)
                            Button("Change email address") {
                                codeSent = false; code = ""; focus = .email
                            }
                            .frame(minHeight: 44)
                            Text("The verification code will expire in 10 minutes.")
                                .font(NoahFont.footnote)
                                .foregroundStyle(NoahColor.mutedForeground)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .disabled(busy)
                    } else {
                        Text("Email is optional. Noah uses it only for urgent wallet safety messages, such as when your VTXOs are close to expiring and you need to come online to refresh them.")
                            .foregroundStyle(NoahColor.mutedForeground)
                        NoahCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Email Address").font(NoahFont.footnote)
                                TextField("your@email.com", text: $email,
                                          prompt: Text("your@email.com").foregroundStyle(NoahColor.mutedForeground))
                                    .keyboardType(.emailAddress)
                                    .textContentType(.emailAddress)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .focused($focus, equals: .email)
                                    .id(Field.email)
                                    .submitLabel(.done)
                                    .onSubmit { focus = nil }
                                    .frame(minHeight: 44)
                                    .accessibilityIdentifier("email-address-input")
                            }
                        }
                    }
                }
                .font(NoahFont.body)
                .padding(20)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .onChange(of: focus) { _, field in
                if let field { withAnimation { scroll.scrollTo(field, anchor: .center) } }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                if let focus { withAnimation { scroll.scrollTo(focus, anchor: .center) } }
            }
            .scrollDismissesKeyboard(.interactively)
            .foregroundStyle(NoahColor.foreground)
            .background(NoahColor.background.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    NoahButton(title: codeSent ? "Verify Email" : "Send Verification Code", isLoading: busy) {
                        codeSent ? verify() : send()
                    }
                    .disabled((codeSent ? code.count != 6 : email.isEmpty) || model.server.isRegistering || registrationFailed)
                    if fromOnboarding && !codeSent {
                        Button("Continue without email") { focus = nil; onContinue() }
                            .font(NoahFont.callout)
                            .frame(minHeight: 44)
                            .disabled(busy || model.server.isRegistering || registrationFailed)
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 12)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
                .background(NoahColor.background)
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focus = nil }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(busy)
            .task { await register() }
            .alert(errorTitle, isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {
                    if registrationFailed { dismiss() }
                    else if completeAfterAlert { onContinue() }
                }
            } message: { Text(errorMessage ?? "") }
        }
    }

    private func completed(alreadyVerified: Bool) {
        if fromOnboarding { onContinue(); return }
        completeAfterAlert = true
        errorTitle = alreadyVerified ? "Already Verified" : "Email Verified"
        errorMessage = alreadyVerified ? "This email is already verified." : "Your email has been verified successfully."
    }

    private func register() async {
        registrationFailed = false
        do { try await model.server.ensureRegistered() }
        catch {
            registrationFailed = true
            errorTitle = "Registration Error"
            errorMessage = "Failed to register with server. Please try again."
        }
    }

    private func send() {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.range(of: "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", options: .regularExpression) != nil else {
            errorTitle = "Invalid Email"; errorMessage = "Please enter a valid email address."
            return
        }
        guard !busy else { return }
        focus = nil; busy = true; email = trimmed
        Task {
            defer { busy = false }
            do {
                if try await model.server.sendEmail(trimmed) { completed(alreadyVerified: true) }
                else { codeSent = true; focus = .code }
            } catch {
                errorTitle = "Error"
                errorMessage = ServerFailure.message(for: error, fallback: "Failed to send verification code. Please try again.")
            }
        }
    }

    private func verify() {
        guard !busy, code.count == 6 else { return }
        focus = nil; busy = true
        Task {
            defer { busy = false }
            do {
                try await model.server.verifyEmail(code: code, email: email)
                code = ""
                completed(alreadyVerified: false)
            } catch {
                code = ""
                errorTitle = "Verification Failed"
                errorMessage = ServerFailure.message(for: error, fallback: "Invalid or expired verification code.")
            }
        }
    }
}
