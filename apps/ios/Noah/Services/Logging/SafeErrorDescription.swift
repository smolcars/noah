import BarkBoltFFI
import Foundation

/// Error text that is safe to log or send to the server: bark-bolt-ffi
/// messages, otherwise only the error's type. Never the description of a
/// generated-client error (it includes request inputs and headers).
enum SafeErrorDescription {
    static func of(_ error: Error) -> String {
        if let bark = error as? BarkError { return LogFile.redact(bark.message) }
        return LogFile.redact(String(describing: type(of: error)))
    }

    /// Raw SDK text is only for AppLog's bounded memory queue, never a server report.
    static func forLogging(_ error: Error) -> String {
        if let bark = error as? BarkError { return bark.message }
        return LogFile.redact(String(describing: type(of: error)))
    }
}
