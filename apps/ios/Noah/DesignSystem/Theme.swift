import SwiftUI
import UIKit

/// Colors from the RN theme (`client/global.css`, `useTheme.ts`), as
/// dynamic light/dark colors.
enum NoahColor {
    static let background = dynamic(light: 0xF5F8FC, dark: 0x0A0A0B)
    static let foreground = dynamic(light: 0x1A2332, dark: 0xFAFAFA)
    static let mutedForeground = dynamic(light: 0x5A6578, dark: 0xA1A1AA)
    static let border = dynamic(light: 0xD8E2ED, dark: 0x27272A)
    static let card = dynamic(light: 0xEDF2F9, dark: 0x161618)
    static let secondary = dynamic(light: 0xE4EBF4, dark: 0x27272A)
    /// Brand amber (`COLORS.BITCOIN_ORANGE`).
    static let brand = Color(hex: 0xC98A3C)
    static let success = Color(hex: 0x22C55E)
    static let destructive = Color(hex: 0xEF4444)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

/// Inter, as in the RN app, scaled with Dynamic Type.
enum NoahFont {
    enum Weight: String {
        case regular = "Inter-Regular"
        case medium = "Inter-Medium"
        case semibold = "Inter-SemiBold"
        case bold = "Inter-Bold"
    }

    static func inter(_ size: CGFloat, _ weight: Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom(weight.rawValue, size: size, relativeTo: style)
    }

    static let largeTitle = inter(32, .bold, relativeTo: .largeTitle)
    static let title = inter(26, .bold, relativeTo: .title)
    static let headline = inter(18, .semibold, relativeTo: .headline)
    static let body = inter(17, .regular, relativeTo: .body)
    static let callout = inter(16, .medium, relativeTo: .callout)
    static let footnote = inter(14, .regular, relativeTo: .footnote)
}

extension Color {
    init(hex: UInt32) {
        self.init(uiColor: UIColor(hex: hex))
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1)
    }
}
