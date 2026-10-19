import Foundation

/// The `development` flag expo-notifications sends when asking Expo for a
/// push token: `aps-environment` from `embedded.mobileprovision`
/// (`EXProvisioningProfile.notificationServiceEnvironment`), "development"
/// → true, anything else or no profile (App Store, simulator) → false.
/// Sending the same value keeps the same Expo token across the update.
enum ApnsEnvironment {
    static func isDevelopment(profile: Data? = embeddedProfile()) -> Bool {
        guard let profile, let plist = plist(from: profile),
            let entitlements = plist["Entitlements"] as? [String: Any]
        else { return false }
        return entitlements["aps-environment"] as? String == "development"
    }

    static func embeddedProfile() -> Data? {
        Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision").flatMap { try? Data(contentsOf: $0) }
    }

    /// The profile is a CMS envelope around an XML plist; like Expo, take
    /// the text from the XML prolog to `</plist>`.
    static func plist(from profile: Data) -> [String: Any]? {
        let text = String(decoding: profile, as: UTF8.self)
        guard let start = text.range(of: "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
            let end = text.range(of: "</plist>", range: start.upperBound..<text.endIndex)
        else { return nil }
        let xml = Data(text[start.lowerBound..<end.upperBound].utf8)
        return try? PropertyListSerialization.propertyList(from: xml, format: nil) as? [String: Any]
    }
}
