# Physical-device UI driver (regtest only)

Drives the installed `com.noahwallet.regtest` app on a real iPhone, so RN → native
upgrade acceptance runs need no human taps. It's a separate host app, so
`xcodebuild test` never reinstalls the regtest app. It answers SpringBoard
permission alerts with "Allow" and never touches any other bundle ID.

    xcodegen generate
    xcodebuild -project NoahDeviceDriver.xcodeproj -scheme NoahDeviceDriver \
      -destination id=<udid> -derivedDataPath /private/tmp/noah-device-driver-dd \
      -allowProvisioningUpdates build-for-testing
    xcodebuild test-without-building -xctestrun /private/tmp/noah-device-driver-dd/Build/Products/*.xctestrun \
      -destination id=<udid> -only-testing:NoahDeviceDriverUITests/RegtestDriverTests/<test>

Tests:
- `testCompleteRNOnboarding`
- `testRNDeleteWalletAndReonboard`
- `testToggleBalanceWhileRunning` (MMKV writes; `TEST_RUNNER_NOAH_DRIVER_SECONDS`)
- `testStayForeground`
- `testNativeLandsOnHome`
- `testRestoreKeepsWordsAndPastes` (fresh install on Welcome; a simulator works too)
- `testRunScript`: steps from `TEST_RUNNER_NOAH_SCRIPT`, one per line (`tap:Label`, `tapid:identifier`, `wait:Label@60`, `field:0:text`, `home`, `dump`, …)

Requirements:
- The phone stays unlocked.
- The first run asks for the device passcode once ("Enable UI Automation"). Only the
  device owner may enter it.
