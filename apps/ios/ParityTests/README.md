# iOS launch, Home, version and QR parity

## Coverage

These NoahTests suites hold the native launch gate, Home, version check and QR hub to
RN's behaviour through shared fixtures in `apps/fixtures/`:

- `PushPermissionTests`: the notification gate (`push-gate.json`, `platform-gates.json`).
  Only an initialized wallet on a physical device with denied permission is blocked;
  simulators skip the gate, as in RN.
- `HomeParityTests`, `HomeRulesTests`, `HomeStatusHookTests`: backup/boarding/round
  banners and expiry, round polling, facts and hidden fiat (`home-status.json`,
  `home-rounds.json`).
- `AppVersionTests`: the nonblocking update warning (`app-version.json`).
- `QRHubTests`: Lightning-address QR handoffs into Send (`qr-hub.json`).

## Running

```sh
cd apps/ios && xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build/DerivedData \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=YES \
  test -only-testing:NoahTests/PushPermissionTests -only-testing:NoahTests/HomeParityTests \
  -only-testing:NoahTests/HomeRulesTests -only-testing:NoahTests/HomeStatusHookTests \
  -only-testing:NoahTests/AppVersionTests -only-testing:NoahTests/QRHubTests
```

Use your own disposable simulator. Keep ad hoc signing: Keychain tests need the app's
entitlements. A single Swift Testing function selector must end in `()`; check the
reported test count, because a malformed selector runs zero tests.

Regenerate the fixtures from RN source, from the repository root:

```sh
node apps/fixtures/tools/generate-push-gate.mjs
node apps/fixtures/tools/generate-home-status.mjs
node apps/fixtures/tools/generate-home-parity.mjs
node apps/fixtures/tools/generate-qr-hub.mjs
node apps/fixtures/tools/generate-app-version.mjs
```

## Manual checks and limits

Live UI checks run against the regtest server at `127.0.0.1:3000` on an owned
simulator: create a wallet, check fiat and hidden balances, copy the Lightning QR, deny
and then grant camera permission, and paste a Receive request through the QR hub into
Send without submitting a payment. Recovery-phrase screens must not be captured.

The simulator bypasses the push gate and has no camera, so physical push denial,
APNs delivery and camera capture need a real device. Funded boarding and real pending
rounds are covered by fixtures and view-model tests rather than live runs.
