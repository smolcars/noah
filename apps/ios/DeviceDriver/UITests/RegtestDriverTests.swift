import XCTest

/// Taps through the installed regtest app on a physical device so upgrade
/// acceptance runs need no human. Only ever drives `com.noahwallet.regtest`
/// and answers system permission alerts with "Allow".
final class RegtestDriverTests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.noahwallet.regtest")
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUp() {
        continueAfterFailure = false
    }

    /// RN onboarding without email, ending on Home (the History tab).
    func testCompleteRNOnboarding() {
        app.activate()
        step("Create Wallet")
        wait(for: "Noah is in beta")
        step("I accept")
        wait(for: "Your Recovery Phrase", timeout: 60)
        step("I Have Saved It, Continue")
        wait(for: "Emergency Email", timeout: 60)
        step("Continue without email")
        wait(for: "Choose your Lightning Address", timeout: 60)
        step("Skip")
        wait(for: "History", timeout: 90)
        // Let the post-onboarding permission prompts appear and be allowed.
        settle(seconds: 15)
    }

    /// RN Settings → Delete Wallet → onboarding again, all in one app session
    /// (the sequence that preceded the lost RN settings on 2026-10-20).
    func testRNDeleteWalletAndReonboard() {
        app.activate()
        step("Open settings")
        var row = element("Delete Wallet")
        for _ in 0..<8 where !(row.exists && row.isHittable) {
            app.swipeUp()
            row = element("Delete Wallet")
        }
        row.tap()
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        field.tap()
        field.typeText("delete")
        let confirm = app.descendants(matching: .any)["confirm-delete-wallet"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        testCompleteRNOnboarding()
    }

    /// Keeps the app in the foreground (and alerts answered) while it writes state.
    func testStayForeground() {
        app.activate()
        settle(seconds: TimeInterval(ProcessInfo.processInfo.environment["NOAH_DRIVER_SECONDS"] ?? "30") ?? 30)
    }

    /// Writes MMKV continuously (RN's privacy-storage) so an install that kills
    /// the app is likely to land mid-write. Stops quietly once the app is gone.
    func testToggleBalanceWhileRunning() {
        app.activate()
        let seconds = TimeInterval(ProcessInfo.processInfo.environment["NOAH_DRIVER_SECONDS"] ?? "60") ?? 60
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            allowAlerts()
            guard app.state == .runningForeground else { return }
            for label in ["Hide wallet balance", "Show wallet balance"] {
                let toggle = element(label)
                if toggle.exists && toggle.isHittable { toggle.tap(); break }
            }
        }
    }

    /// Native after the upgrade must land on Home, never back in onboarding.
    func testNativeLandsOnHome() {
        app.activate()
        let deadline = Date().addingTimeInterval(90)
        while Date() < deadline {
            allowAlerts()
            if element("Your Recovery Phrase").exists || element("Welcome to Noah").exists {
                XCTFail("Native resumed onboarding after the upgrade")
                return
            }
            if element("Activity").exists { return }
            sleep(1)
        }
        XCTFail("Native did not reach Home")
    }

    /// Restore from Welcome on a fresh install (a simulator works too): typed words survive leaving the app and the
    /// shield clears on return; Back clears them; Paste fills every word from a one-line copy and takes it off the
    /// clipboard. Public BIP39 test words only.
    func testRestoreKeepsWordsAndPastes() {
        let words = Array(repeating: "abandon", count: 11) + ["about"]
        func field(_ number: Int) -> XCUIElement { app.textFields["restore-word-\(number)"] }
        func value(_ number: Int) -> String {
            let value = field(number).value as? String ?? ""
            return value == field(number).placeholderValue ? "" : value
        }
        app.terminate()
        app.activate()
        step("I already have a wallet")
        XCTAssertTrue(field(1).waitForExistence(timeout: 15))
        field(1).tap()
        for word in words.prefix(3) { app.typeText(word + " ") }
        XCUIDevice.shared.press(.home)
        sleep(2)
        app.activate()
        // The shield takes the words out of accessibility until the app is active again.
        XCTAssertTrue(field(3).waitForExistence(timeout: 5), "The shield didn't clear")
        XCTAssertEqual((1...3).map(value), Array(words.prefix(3)))
        step("Back")
        step("I already have a wallet")
        XCTAssertTrue(field(1).waitForExistence(timeout: 15))
        XCTAssertEqual(value(1), "")
        UIPasteboard.general.string = words.joined(separator: " ") + "\n"
        app.descendants(matching: .any)["restore-paste"].tap()
        XCTAssertTrue(field(12).waitForExistence(timeout: 5))
        let deadline = Date().addingTimeInterval(5)
        while value(12).isEmpty && Date() < deadline { usleep(200_000) }
        XCTAssertEqual((1...12).map(value), words)
        XCTAssertFalse(UIPasteboard.general.hasStrings, "The accepted phrase is still on the clipboard")
    }

    /// Runs `TEST_RUNNER_NOAH_SCRIPT`: one step per line, `command:argument`.
    /// tap, tapid, wait, gone, scrollto (label); type (text into focus);
    /// field (`index:text`); swipeup, swipedown, home, activate, dump; sleep (seconds).
    /// `wait`/`gone` accept `label@seconds`.
    func testRunScript() {
        let script = ProcessInfo.processInfo.environment["NOAH_SCRIPT"] ?? ""
        for raw in script.split(separator: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { continue }
            let (command, argument) = line.firstIndex(of: ":").map {
                (String(line[..<$0]), String(line[line.index(after: $0)...]))
            } ?? (line, "")
            let (label, seconds) = argument.lastIndex(of: "@").flatMap { at in
                TimeInterval(argument[argument.index(after: at)...]).map { (String(argument[..<at]), $0) }
            } ?? (argument, 30)
            print("NOAH-STEP \(command):\(argument)")
            switch command {
            case "activate": app.activate()
            case "tap": step(label, timeout: seconds)
            case "tapid":
                let target = app.descendants(matching: .any)[label]
                XCTAssertTrue(target.waitForExistence(timeout: seconds), "No element \(label)")
                target.tap()
            case "wait": wait(for: label, timeout: seconds)
            case "gone":
                let deadline = Date().addingTimeInterval(seconds)
                while element(label).exists && Date() < deadline { allowAlerts(); sleep(1) }
                XCTAssertFalse(element(label).exists, "Still showing \"\(label)\"")
            case "scrollto":
                for _ in 0..<10 where !(element(label).exists && element(label).isHittable) { app.swipeUp() }
                step(label)
            case "type": app.typeText(argument)
            case "field":
                let parts = argument.split(separator: ":", maxSplits: 1).map(String.init)
                let field = app.textFields.element(boundBy: Int(parts[0]) ?? 0)
                XCTAssertTrue(field.waitForExistence(timeout: 15))
                field.tap()
                field.typeText(parts.count > 1 ? parts[1] : "")
            case "tapxy":
                // Screen points from the top-left, for system popovers outside the app's tree.
                let xy = argument.split(separator: ":").compactMap { Double($0) }
                app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: xy[0], dy: xy[1])).tap()
            case "swipeup": app.swipeUp()
            case "swipedown": app.swipeDown()
            case "home": XCUIDevice.shared.press(.home)
            case "sleep": settle(seconds: TimeInterval(argument) ?? 1)
            case "dump": print("NOAH-DUMP-BEGIN\n\(app.debugDescription)\nNOAH-DUMP-END")
            default: XCTFail("Unknown step \(line)")
            }
        }
    }

    // MARK: - Helpers

    private func element(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func wait(for label: String, timeout: TimeInterval = 30) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            allowAlerts()
            if element(label).exists { return }
            sleep(1)
        }
        XCTFail("Timed out waiting for \"\(label)\"")
    }

    private func step(_ label: String, timeout: TimeInterval = 30) {
        wait(for: label, timeout: timeout)
        let target = element(label)
        if target.isHittable { target.tap() } else { target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    }

    private func settle(seconds: TimeInterval) {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            allowAlerts()
            sleep(1)
        }
    }

    /// Local network and notification prompts come from SpringBoard.
    private func allowAlerts() {
        let alert = springboard.alerts.firstMatch
        guard alert.exists else { return }
        for title in ["Allow", "Allow While Using App", "OK"] where alert.buttons[title].exists {
            alert.buttons[title].tap()
            return
        }
    }
}
