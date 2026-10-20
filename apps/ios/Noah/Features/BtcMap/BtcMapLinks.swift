import Foundation

enum BtcMapLinks {
    static func web(_ text: String) -> URL? {
        guard !text.contains(where: { $0.isWhitespace || $0.isNewline }),
              let url = URL(string: text, encodingInvalidCharacters: false),
              ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil else { return nil }
        return url
    }

    static func phone(_ text: String) -> URL? {
        let allowed = CharacterSet(charactersIn: "+0123456789 ()-.")
        guard text.unicodeScalars.allSatisfy(allowed.contains) else { return nil }
        let number = text.filter { "+0123456789".contains($0) }
        let digits = number.hasPrefix("+") ? String(number.dropFirst()) : number
        guard !digits.isEmpty, digits.allSatisfy({ "0123456789".contains($0) }) else { return nil }
        return URL(string: "tel:\(number)")
    }
}
