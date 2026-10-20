import SwiftUI

@main
struct NoahApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            #if DEBUG && targetEnvironment(simulator)
            // Exercise the public map on an isolated simulator without creating a wallet.
            if ProcessInfo.processInfo.arguments.contains("-btc-map-preview") {
                NavigationStack { BtcMapView() }
            } else {
                RootView(replaceModel: { model = $0 }).id(ObjectIdentifier(model)).environment(model)
            }
            #else
            RootView(replaceModel: { model = $0 }).id(ObjectIdentifier(model)).environment(model)
            #endif
        }
    }
}
