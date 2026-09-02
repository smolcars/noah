---
name: maestro-expo-debug
description: Write and run Noah Maestro flows against Expo debug or dev-client iOS builds. Use when a Maestro test launches the regtest app, opens the Expo development-client URL, or cannot find Noah UI because Expo's developer-menu tutorial is covering the app.
---

# Maestro with Expo debug builds

Expo debug builds can show a developer-menu tutorial over Noah. Its X button has the accessibility label `Close`, and the tutorial can reappear after another `launchApp` in the same test run.

Wait for the launch animation, then dismiss the tutorial after opening the development-client URL and after every later `launchApp` before asserting Noah UI:

```yaml
- waitForAnimationToEnd:
    timeout: 5000
- tapOn:
    text: "Close"
    optional: true
```

Keep the action optional so the same flow works when the tutorial was already dismissed or is not present. Use `Close`, not `Continue`; the intended action is the X button.

A typical debug launch starts like this:

```yaml
- launchApp:
    clearState: true
    clearKeychain: true
- openLink: "exp+noahs-ark-wallet://expo-development-client/?url=http%3A%2F%2F127.0.0.1%3A8081"
- waitForAnimationToEnd:
    timeout: 5000
- tapOn:
    text: "Close"
    optional: true
- extendedWaitUntil:
    visible: "Create Wallet"
    timeout: 30000
```

If Noah content is visible behind a large Expo card and an assertion fails, fix the launch flow first. Do not change Noah selectors or production UI until the overlay is ruled out.
