import Foundation
import OSLog

/// Tagged app logger, the native counterpart of `client/src/lib/log.ts`.
/// Writes to OSLog and to the rotating file the Logs screen exports.
/// Never pass secrets; `LogFile` still redacts mnemonic-shaped text.
struct AppLog: Sendable {
    enum Level: String, Sendable {
        case verbose, debug, info, warn, error

        var symbol: String {
            switch self {
            case .verbose: "V"
            case .debug: "D"
            case .info: "I"
            case .warn: "W"
            case .error: "E"
            }
        }
    }

    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.noahwallet"

    let tag: String
    private let logger: Logger
    private let file: LogFile

    init(_ tag: String, file: LogFile = .shared) {
        self.tag = tag
        self.logger = Logger(subsystem: Self.subsystem, category: tag)
        self.file = file
    }

    func v(_ message: String) { log(.verbose, message) }
    func d(_ message: String) { log(.debug, message) }
    func i(_ message: String) { log(.info, message) }
    func w(_ message: String) { log(.warn, message) }
    func e(_ message: String) { log(.error, message) }

    private func log(_ level: Level, _ message: String) {
        let message = LogFile.redact(message)
        switch level {
        case .verbose, .debug: logger.debug("\(message, privacy: .public)")
        case .info: logger.info("\(message, privacy: .public)")
        case .warn: logger.warning("\(message, privacy: .public)")
        case .error: logger.error("\(message, privacy: .public)")
        }
        file.append(level: level, tag: tag, message: message)
    }
}

/// Rotating log files in `Caches/noah_logs`, same layout and limits as the
/// React Native app (`noah.log`, `noah.log.1` … `.4`, 512 KB each).
final class LogFile: @unchecked Sendable {
    // All file access happens on `queue`.
    static let shared = LogFile(
        directory: URL.cachesDirectory.appending(path: "noah_logs", directoryHint: .isDirectory))

    static let fileName = "noah.log"
    static let maxFileSizeBytes = 512 * 1024
    static let maxFiles = 4
    static let maxLines = 4000

    private let directory: URL
    private let queue = DispatchQueue(label: "com.noahwallet.log-file")
    private let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter
    }()

    init(directory: URL) {
        self.directory = directory
    }

    func append(level: AppLog.Level, tag: String, message: String, date: Date = Date()) {
        queue.async { [self] in
            let cleaned = message.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\n", with: "\n  ")
            let line = "\(formatter.string(from: date)) \(level.symbol) [\(tag)] \(cleaned)\n"
            try? write(Data(line.utf8))
        }
    }

    /// The newest `maxLines` lines across all files, oldest first.
    func lines() -> [String] {
        queue.sync {
            let files = (try? FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: nil, options: .skipsHiddenFiles)) ?? []
            // noah.log.4 is oldest, noah.log newest.
            let ordered = files
                .filter { $0.lastPathComponent.hasPrefix(Self.fileName) }
                .sorted { Self.age(of: $0) > Self.age(of: $1) }
            var lines: [String] = []
            for url in ordered {
                guard let contents = try? String(contentsOf: url, encoding: .utf8) else { continue }
                lines.append(contentsOf: contents.split(whereSeparator: \.isNewline).map(String.init))
            }
            return Array(lines.suffix(Self.maxLines))
        }
    }

    /// Waits for pending writes. For tests and before exporting.
    func flush() {
        queue.sync {}
    }

    private static func age(of url: URL) -> Int {
        Int(url.pathExtension) ?? 0
    }

    private func write(_ data: Data) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let active = directory.appending(path: Self.fileName)
        if let size = try? FileManager.default.attributesOfItem(atPath: active.path)[.size] as? Int,
            size >= Self.maxFileSizeBytes
        {
            try rotate(active: active)
        }
        if !FileManager.default.fileExists(atPath: active.path) {
            FileManager.default.createFile(atPath: active.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: active)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
    }

    private func rotate(active: URL) throws {
        let fileManager = FileManager.default
        for index in stride(from: Self.maxFiles, through: 1, by: -1) {
            let source = index == 1 ? active : directory.appending(path: "\(Self.fileName).\(index - 1)")
            guard fileManager.fileExists(atPath: source.path) else { continue }
            let destination = directory.appending(path: "\(Self.fileName).\(index)")
            try? fileManager.removeItem(at: destination)
            try fileManager.moveItem(at: source, to: destination)
        }
    }

    /// Replaces runs of 12 or more short lowercase words (the shape of a
    /// BIP39 recovery phrase) so a mistaken log call cannot persist one.
    static func redact(_ message: String) -> String {
        message.replacing(mnemonicShape, with: "[redacted]")
    }

    nonisolated(unsafe) private static let mnemonicShape = /\b[a-z]{3,8}(?:\s+[a-z]{3,8}){11,}\b/
}
