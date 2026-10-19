import Testing
import UIKit

@testable import Noah

/// The silent-push entry point, called directly (simulators do not run the
/// background handler for `simctl push`; real delivery is checked on devices).
@MainActor
struct AppDelegatePushTests {
    @Test func ignoresPushesThatAreNotForNoah() async {
        let result = await AppDelegate().application(UIApplication.shared, didReceiveRemoteNotification: ["aps": ["alert": "hi"]])
        #expect(result == .noData)
    }

    @Test func heartbeatCompletesEvenWhenTheServerRefuses() async {
        // The test host is not registered with a server; heartbeat failures are swallowed like the RN app.
        let payload: [AnyHashable: Any] = [
            "aps": ["content-available": 1],
            "body": #"{"notification_type":"heartbeat","notification_id":"test"}"#,
        ]
        let started = Date()
        let result = await AppDelegate().application(UIApplication.shared, didReceiveRemoteNotification: payload)
        #expect(result == .newData)
        #expect(Date().timeIntervalSince(started) < 26, "stays inside the background budget")
    }
}
