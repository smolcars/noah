import Foundation

/// Minimal JWT expiry check (`shouldRefreshServerAuthToken` in crypto.ts).
/// The server validates signatures; the client only reads `exp`.
enum JWT {
    enum Failure: Error, Equatable {
        case malformed
        case missingExpiry
    }

    /// Refresh window and clock skew used by the RN app.
    static let refreshWindow: TimeInterval = 3 * 60 * 60
    static let clockSkew: TimeInterval = 60

    static func expiry(of token: String) throws(Failure) -> Date {
        guard let exp = try payload(of: token)["exp"] as? Double, exp.isFinite else { throw .missingExpiry }
        return Date(timeIntervalSince1970: exp)
    }

    /// `sub`: the login public key the server issued the token to.
    static func subject(of token: String) throws(Failure) -> String {
        guard let subject = try payload(of: token)["sub"] as? String else { throw .malformed }
        return subject
    }

    private static func payload(of token: String) throws(Failure) -> [String: Any] {
        let segments = token.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 3 else { throw .malformed }
        var base64 = segments[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: base64),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw .malformed }
        return payload
    }

    static func needsRefresh(_ token: String, now: Date = Date()) throws(Failure) -> Bool {
        try expiry(of: token).timeIntervalSince(now) <= refreshWindow + clockSkew
    }
}
