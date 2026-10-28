import SwiftUI

struct SettingsLabel: View {
    let title: String
    let detail: String
    var value: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(LocalizedStringKey(title)).font(NoahFont.callout)
                if let value {
                    Spacer()
                    Text(value).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                }
            }
            Text(LocalizedStringKey(detail)).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
        }.padding(.vertical, 6)
    }
}

extension View {
    func settingsStyle(_ title: LocalizedStringKey, maximumWidth: CGFloat = AdaptiveLayout.readableWidth) -> some View {
        navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .font(NoahFont.body).foregroundStyle(NoahColor.foreground)
            .readableWidth(maximumWidth)
            .scrollContentBackground(.hidden).background(NoahColor.background).tint(NoahColor.brand)
    }
}
