import SwiftUI

/// Empty host for the device UI driver; the tests act on com.noahwallet.regtest.
@main
struct DriverHostApp: App {
    var body: some Scene {
        WindowGroup { Text("Noah device driver") }
    }
}
