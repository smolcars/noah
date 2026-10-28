import Foundation

/// Build variant, set per scheme through `Config/<Variant>.xcconfig`.
enum AppVariant: String, Sendable, CaseIterable {
    case mainnet
    case signet
    case regtest

    static let current: AppVariant = {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "NoahAppVariant") as? String,
              let variant = Self(rawValue: raw)
        else {
            preconditionFailure(#"NoahAppVariant missing or invalid in Info.plist"#)
        }
        return variant
    }()

    /// App group shared with the widget extension. Same identifiers as the
    /// React Native app.
    var appGroup: String { "group.com.noahwallet.\(rawValue)" }
}
