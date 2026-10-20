import SwiftUI

struct UpdateWarningView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL

    var body: some View {
        if app.versionCheck.updateRequired {
            Button { openURL(AppVersionCheck.updateURL(variant: app.variant)) } label: {
                NoahCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("Update Available", systemImage: "chevron.right").font(NoahFont.headline)
                        Text("Please update to the latest version of the app for bug fixes and improvements.")
                        LabeledContent("Current Version", value: app.versionCheck.currentVersion)
                        LabeledContent("Latest Version", value: app.versionCheck.minimumVersion ?? "0.0.1")
                    }
                    .font(NoahFont.footnote)
                }
            }
            .buttonStyle(.plain)
        }
    }
}
