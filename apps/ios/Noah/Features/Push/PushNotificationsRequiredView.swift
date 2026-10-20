import SwiftUI

struct PushNotificationsRequiredView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Image(systemName: "bell.badge").font(.system(size: 48)).foregroundStyle(NoahColor.brand)
                Text("Turn on push notifications").font(NoahFont.largeTitle).multilineTextAlignment(.center)
                Text("Push notifications are critical for the app to function and enabling them helps prevent VTXOs from expiring.")
                    .foregroundStyle(NoahColor.mutedForeground).multilineTextAlignment(.center)
                NoahCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Refresh expiring VTXOs", systemImage: "arrow.clockwise").font(NoahFont.headline)
                        Text("We attempt to refresh VTXOs in the background to prevent them from expiring.")
                    }
                }
                NoahCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Receive while app is closed", systemImage: "bolt").font(NoahFont.headline)
                        Text("Lightning and Ark payments keep flowing, even in the background.")
                    }
                }
                NoahButton(title: "Enable notifications", isLoading: app.pushPermission.isRequesting) {
                    Task { await app.resolvePushPermission(request: true) }
                }
                Button("I turned them on - check again") { Task { await app.resolvePushPermission(request: false) } }
                Button("Open settings to allow notifications") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
            }
            .disabled(app.pushPermission.isRequesting)
            .padding(24).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
        .background(NoahColor.background.ignoresSafeArea())
    }
}
