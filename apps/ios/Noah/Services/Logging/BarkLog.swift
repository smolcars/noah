import BarkBoltFFI
import Foundation

/// Rust invokes this synchronously on arbitrary worker threads (Send + Sync).
/// Never call a Bark API from the callback: it may recursively emit logs.
final class BarkLog: BarkLogger, @unchecked Sendable {
    private let lock = NSLock()
    private let file: LogFile

    init(file: LogFile = .shared) { self.file = file }

    // nitro-ark 0.0.144's init_logger uses Debug for every build/variant.
    static let maxLevel: LogLevel = .debug

    static func install() {
        do { try setLogger(logger: BarkLog(), maxLevel: maxLevel) }
        catch { AppLog("bark").e("Could not install Bark logger") }
    }

    func log(level: LogLevel, target: String, message: String) {
        lock.lock()
        defer { lock.unlock() }
        AppLog("bark:\(target)", file: file).log(Self.appLevel(level), message)
    }

    static func appLevel(_ level: LogLevel) -> AppLog.Level {
        switch level {
        case .trace: .verbose
        case .debug: .debug
        case .info: .info
        case .warn: .warn
        case .error: .error
        }
    }
}
