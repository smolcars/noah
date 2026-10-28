import Foundation
import Observation

/// Address checks for the optional email step.
enum EmailEntry {
    static let domains = ["gmail.com", "icloud.com", "outlook.com", "proton.me", "yahoo.com", "hotmail.com"]
    static let typos = [
        "gmial.com": "gmail.com", "gmal.com": "gmail.com", "gamil.com": "gmail.com", "gmail.co": "gmail.com",
        "gnail.com": "gmail.com", "iclod.com": "icloud.com", "icloud.co": "icloud.com", "hotmial.com": "hotmail.com",
        "outlok.com": "outlook.com", "yaho.com": "yahoo.com",
    ]

    enum Hint: Equatable {
        case hidden
        /// "Did you mean maya@gmail.com"
        case didYouMean(String)
        /// Domain completions, as full addresses.
        case complete([String])
    }

    static func normalize(_ raw: String) -> String {
        String(raw.filter { !$0.isWhitespace }).lowercased()
    }

    /// A known typo domain is never valid; its fix is offered instead.
    static func isValid(_ email: String) -> Bool {
        email.range(of: #"^[^\s@]+@[^\s@]+\.[a-z]{2,}$"#, options: [.regularExpression, .caseInsensitive]) != nil
            && typos[domain(of: email) ?? ""] == nil
    }

    static func hint(for email: String) -> Hint {
        guard let at = email.firstIndex(of: "@"), at > email.startIndex else { return .hidden }
        let user = email[..<at], domain = String(email[email.index(after: at)...])
        if let fixed = typos[domain] { return .didYouMean("\(user)@\(fixed)") }
        if isValid(email) { return .hidden }
        let completions = domains.filter { $0.hasPrefix(domain) && $0 != domain }.prefix(3).map { "\(user)@\($0)" }
        return completions.isEmpty ? .hidden : .complete(Array(completions))
    }

    private static func domain(of email: String) -> String? {
        email.firstIndex(of: "@").map { String(email[email.index(after: $0)...]) }
    }
}

/// Entry → code → verified. Uses today's `/email/send_verification` and
/// `/email/verify` through `ServerRegistration`.
@MainActor
@Observable
final class EmailVerification {
    enum Stage: Equatable {
        case entry
        case sending
        case code
        case verifying
        /// The boxes turn red and shake; the code clears after a moment.
        case wrongCode
        case verified
    }

    static let codeLength = 6
    static let resendDelay: TimeInterval = 30
    static let wrongCodeMessage = "That code didn’t work. Try again."

    /// Bound to the address field.
    var text = ""
    /// Bound to the one-time-code field.
    var code = ""
    private(set) var stage = Stage.entry
    /// Where the code went.
    private(set) var email = ""
    private(set) var error: String?
    private(set) var resendAvailable: Date?
    /// Wrong attempts, so the view can shake on each.
    private(set) var failures = 0
    /// A send or resend is in flight. The server keeps one challenge per
    /// account, so sends never overlap, and Edit and verification wait.
    private(set) var isSending = false
    @ObservationIgnored private var verifying = ""

    /// Answers true when the address is already verified and no code was sent.
    @ObservationIgnored private let send: (String) async throws -> Bool
    @ObservationIgnored private let verify: (_ code: String, _ email: String) async throws -> Void
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let clearDelay: Duration

    init(send: @escaping (String) async throws -> Bool,
         verify: @escaping (_ code: String, _ email: String) async throws -> Void,
         now: @escaping () -> Date = Date.init, clearDelay: Duration = .milliseconds(650)) {
        self.send = send
        self.verify = verify
        self.now = now
        self.clearDelay = clearDelay
    }

    var isValid: Bool { EmailEntry.isValid(text) }
    var hint: EmailEntry.Hint { EmailEntry.hint(for: text) }

    func textChanged() {
        let normalized = EmailEntry.normalize(text)
        if normalized != text { text = normalized }
        if stage == .entry { error = nil }
    }

    func sendCode() async {
        guard stage == .entry, isValid, !isSending else { return }
        isSending = true
        defer { isSending = false }
        email = text
        error = nil
        stage = .sending
        do {
            if try await send(email) {
                stage = .verified
            } else {
                code = ""
                resendAvailable = now().addingTimeInterval(Self.resendDelay)
                stage = .code
            }
        } catch {
            self.error = ServerFailure.message(for: error, fallback: "Failed to send verification code. Please try again.")
            stage = .entry
        }
    }

    func resendRemaining(at date: Date) -> Int {
        guard let resendAvailable else { return 0 }
        return max(0, Int(resendAvailable.timeIntervalSince(date).rounded(.up)))
    }

    func resend() async {
        guard stage == .code || stage == .wrongCode, resendRemaining(at: now()) == 0, !isSending else { return }
        isSending = true
        resendAvailable = now().addingTimeInterval(Self.resendDelay)
        error = nil
        do {
            if try await send(email) { stage = .verified }
        } catch {
            resendAvailable = nil
            self.error = ServerFailure.message(for: error, fallback: "Failed to send verification code. Please try again.")
        }
        isSending = false
        // Digits typed while the resend was in flight are checked now.
        if stage == .code || stage == .wrongCode { await codeChanged() }
    }

    /// Digits only, up to six; the sixth verifies. Edits while verifying are ignored.
    func codeChanged() async {
        if stage == .verifying || stage == .verified {
            if code != verifying { code = verifying }
            return
        }
        let digits = String(code.filter { $0.isASCII && $0.isNumber }.prefix(Self.codeLength))
        if digits != code { code = digits }
        guard stage == .code || stage == .wrongCode else { return }
        if stage == .wrongCode && !digits.isEmpty { stage = .code; error = nil }
        guard digits.count == Self.codeLength, !isSending else { return }
        verifying = digits
        stage = .verifying
        error = nil
        do {
            try await verify(digits, email)
            stage = .verified
        } catch {
            failures += 1
            self.error = Self.wrongCodeMessage
            stage = .wrongCode
            try? await Task.sleep(for: clearDelay)
            if stage == .wrongCode { code = "" }
        }
    }

    /// "Edit": back to the address, which keeps what was typed.
    func edit() {
        guard stage == .code || stage == .wrongCode, !isSending else { return }
        code = ""
        error = nil
        resendAvailable = nil
        stage = .entry
    }
}
