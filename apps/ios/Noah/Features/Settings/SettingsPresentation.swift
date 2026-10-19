import SwiftUI

struct SettingsLabel: View {
    let title: String
    let detail: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LocalizedStringKey(title)).font(NoahFont.callout)
            Text(LocalizedStringKey(detail)).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
        }.padding(.vertical, 6)
    }
}

extension View {
    func settingsStyle(_ title: LocalizedStringKey) -> some View {
        self.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .font(NoahFont.body).foregroundStyle(NoahColor.foreground)
            .scrollContentBackground(.hidden).background(NoahColor.background).tint(NoahColor.brand)
    }
}
