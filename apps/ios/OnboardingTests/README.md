# Native onboarding checks

Use a dedicated simulator: the reset below deletes its regtest wallet and clears its Keychain. Never use a simulator holding a wallet you need.

Build and test from the repository root (inside `nix develop` when available):

```sh
xcodegen generate --spec apps/ios/project.yml
TEST_RUNNER_NOAH_SERVER_IT=1 xcodebuild -project apps/ios/Noah.xcodeproj \
  -scheme Noah-Regtest -destination "platform=iOS Simulator,id=$NOAH_TEST_UDID" \
  -derivedDataPath apps/ios/build/DerivedData -parallel-testing-enabled NO test
```

The regtest stack must already be running at `http://localhost:3000` with `EMAIL_DEV_MODE=true`. Tests use the fixed dev-only verification bypass in `server/src/cache/email_verification_store.rs`; they never retrieve or print a user's OTP. Live tests create throwaway identities. They do not recreate containers.

For a fresh flow:

```sh
xcrun simctl uninstall "$NOAH_TEST_UDID" com.noahwallet.regtest
xcrun simctl keychain "$NOAH_TEST_UDID" reset
xcrun simctl install "$NOAH_TEST_UDID" apps/ios/build/DerivedData/Build/Products/Debug-Regtest-iphonesimulator/Noah.app
maestro --device "$NOAH_TEST_UDID" test --debug-output apps/ios/build/maestro \
  -e EMAIL="native-$(date +%s)@example.com" -e USERNAME="native-$(date +%s)" \
  -e SHOTS="$PWD/apps/ios/OnboardingTests/screenshots/verify" \
  apps/ios/OnboardingTests/create-and-verify.yaml
```

`create-and-verify.yaml` covers the redesigned flow: Welcome → beta sheet → Creating, a restart that resumes at Name (the recovery phrase is no longer an onboarding step), an available name, the email code with a wrong code and the regtest code, required notifications, Done with the saved address, Home with the backup prompt and no email banner, then nsec rejection and npub linking in Settings → Profile → Edit Lightning Address (where the Nostr field now lives). `create-and-skip.yaml` covers skipping the name, the typo chip, sending a code and editing the address, skipping email, "Your wallet is ready.", the Home email entry point, persisted banner dismissal and the persisted backup prompt. Supply `SHOTS` for either flow.

The email address is submitted with the keyboard's Send key: tapping the button straight after `inputText` can hit the button's pre-keyboard position, which Maestro caches within a run (it lands on the keyboard's `@` key).

All onboarding states, including ones the simulator can't reach (it never denies notifications), render from fakes in `NoahTests/OnboardingShotTests.swift`: run it with `TEST_RUNNER_NOAH_ONBOARDING_SHOTS=1` and write a state name (`welcome`, `beta`, `name-taken`, `email-code`, `notifications-required`, `restoring-ready`, …) to the app's `Documents/onboarding-shot`; `stop` ends the session. The restore states use only the public BIP-39 test vector.

Use `xcrun simctl ui "$NOAH_TEST_UDID" appearance light` or `dark` before each pass. An iPhone SE (3rd generation) provides the 375 × 667 layout. Screenshots go to the `SHOTS` directory and are not committed (`apps/ios/OnboardingTests/screenshots/` is git-ignored); Maestro debug output goes to `apps/ios/build/maestro`. Delete both and `apps/ios/build/DerivedData` when done.
