import Testing
import UIKit
import UserNotifications

@testable import Noah

/// The silent-push entry point, called directly (simulators do not run the
/// background handler for `simctl push`; real delivery is checked on devices).
@MainActor
struct AppDelegatePushTests {
    @Test func foregroundNotificationPresentationIsInstalled() {
        #expect(AppDelegate().responds(to: #selector(UNUserNotificationCenterDelegate.userNotificationCenter(_:willPresent:withCompletionHandler:))))
    }
    /// Each lifecycle callback hands its drain to a background task. The writer is stalled and the drain has no limit
    /// of its own, so a callback that waited for the drain couldn't return until the writer did: each returns while it's
    /// held, and the record accepted before it lands once the writer resumes.
    @Test(arguments: ["resign", "background", "scene"])
    func lifecycleDrainMustNotBlockMainThread(callback: String) async {
        let queue = DispatchQueue(label: "regression.blocked-writer")
        queue.suspend()
        let stall = LogStall { queue.resume() }
        let directory = FileManager.default.temporaryDirectory.appending(path: "r3-drain-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory, queue: queue, drainLimit: 3_600)
        file.append(level: .info, tag: "r3", message: "accepted")
        let delegate = AppDelegate(logFile: file)
        switch callback {
        case "resign": delegate.applicationWillResignActive(UIApplication.shared)
        case "background": delegate.applicationDidEnterBackground(UIApplication.shared)
        default: delegate.drainLifecycleLogs()
        }
        #expect(stall.release(), "the \(callback) callback waited for the stalled writer")
        await file.flushAsync()
        #expect(file.lines().last?.hasSuffix("[r3] accepted") == true)
    }

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
    @Test(arguments: [true, false])
    func backgroundCompletionDrainsAcceptedRecords(push: Bool) async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "lifecycle-log-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let queue = DispatchQueue(label: "test.delayed-lifecycle-writer")
        queue.suspend()
        let file = LogFile(directory: directory, queue: queue)
        // Let the writer run after completion would have returned without a drain.
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(100)) { queue.resume() }
        if push {
            let result = await AppDelegate.completeWithin(.seconds(2), logFile: file) {
                file.append(level: .warn, tag: "test", message: "Wallet operation failed")
            }
            #expect(result == .newData)
        } else {
            let result = await withCheckedContinuation { continuation in
                _ = BackgroundExecution(timeout: .seconds(2), logFile: file, completion: {
                    continuation.resume(returning: $0)
                }) { file.append(level: .warn, tag: "test", message: "Wallet operation failed") }
            }
            #expect(result)
        }
        #expect(FileManager.default.fileExists(atPath: directory.appending(path: "noah.log").path))
        await file.flushAsync()
    }

    @Test func expiryDuringLogDrainStillCompletesExactlyOnce() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "expiry-log-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let queue = DispatchQueue(label: "test.expiry-drain")
        queue.suspend()
        let file = LogFile(directory: directory, queue: queue)
        var completed: [Bool] = []
        let recorder = PushJobsTests.Recorder()
        let execution = BackgroundExecution(logFile: file, completion: { completed.append($0) }) {
            file.append(level: .info, tag: "test", message: "Accepted")
            recorder.add("operation finished")
        }
        let limit = ContinuousClock.now + .seconds(2)
        while recorder.all.isEmpty, ContinuousClock.now < limit { await Task.yield() }
        // Allow the worker to enter its drain before the OS revokes execution.
        try await Task.sleep(for: .milliseconds(10))
        execution.systemExpired()
        while completed.isEmpty, ContinuousClock.now < limit { await Task.yield() }
        #expect(completed == [false])
        queue.resume()
        await file.flushAsync()
        execution.expire()
        #expect(completed == [false])
    }

    @Test func deadlineCompletesWhileCancellationShieldedWorkerIsStillRunning() async throws {
        let gate = RecurringReservationTests.Gate()
        var settled = false
        let started = ContinuousClock.now
        let result = await AppDelegate.completeWithin(.milliseconds(30)) {
            await Task { await gate.wait() }.value
            await MainActor.run { settled = true }
        }
        #expect(result == .failed)
        #expect(ContinuousClock.now - started < .seconds(1))
        #expect(!settled)
        try #require(await gate.entered)
        await gate.release()
        let limit = ContinuousClock.now + .seconds(2)
        while !settled, ContinuousClock.now < limit { await Task.yield() }
        #expect(settled)
    }

}
