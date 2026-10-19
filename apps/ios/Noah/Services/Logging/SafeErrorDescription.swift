import BarkBoltFFI
import Foundation

/// Error text that is safe to log or send to the server: bark-bolt-ffi
/// messages, otherwise only the error's type. Never the description of a
/// generated-client error (it includes request inputs and headers).
enum SafeErrorDescription {
    static func of(_ error: Error) -> String {
        if let bark = error as? BarkError { return LogFile.redact(bark.message) }
        return String(describing: type(of: error))
    }
}
