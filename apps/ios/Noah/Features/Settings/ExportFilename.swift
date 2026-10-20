import Foundation

enum ExportFilename {
    static func database(at date: Date = Date()) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let timestamp = formatter.string(from: date).replacingOccurrences(of: ":", with: "-").replacingOccurrences(of: ".", with: "-")
        return "noah_backup_\(timestamp).noahbackup"
    }
}
