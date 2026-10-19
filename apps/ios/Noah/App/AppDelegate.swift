import UIKit

/// Hosts the UIKit callbacks SwiftUI does not expose (APNs registration,
/// background remote notifications). Push handling lands in Phase 0.6.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        true
    }
}
