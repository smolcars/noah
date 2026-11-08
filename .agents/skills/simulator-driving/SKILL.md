---
name: simulator-driving
description: Drive the Noah iOS simulator and Android emulator fast with agent-device. Use whenever a task needs to navigate the native app, reach a screen, check a UI change, take screenshots, or onboard a fresh wallet. Covers the open/snapshot/press loop, batching, the one-call onboarding replay, and reaching states without tapping.
---

# Driving the simulators

The device tooling is sub-second. What makes UI checks slow is the loop around it: one model turn per tap, a screenshot after every step, fixed sleeps, and onboarding by hand. Measured on the iOS simulator: `snapshot -i` 0.4 s, `press` 0.1 to 0.9 s, `press --settle` about 2 s, a four-step `batch` 2 s.

## Getting the CLI

In T3 Code, call `device_open` and run the `agent-device` path it returns. It is bound to one device and session, so pass no `--platform`, `--udid` or `--session`. Elsewhere, `npx agent-device` with `--platform ios --udid <udid>` or `--platform android --serial <serial>`.

## The loop

1. `open com.noahwallet.regtest --foreground` once. It returns the first snapshot.
2. Act on refs or selectors from that snapshot. Chain confident steps with `&&`, or put them in one `batch`:

   ```sh
   agent-device press 'label="Receive"' && agent-device press 'id="receive-amount-button"' --settle
   agent-device batch --steps '[{"command":"press","input":{"target":{"kind":"selector","selector":"label=\"Receive\""}}},{"command":"snapshot","input":{"interactiveOnly":true}}]'
   ```

3. Verify with `wait text "..."`, `wait 'id="..."'`, or `snapshot --diff`. A screenshot is not verification.
4. Screenshot only when the question is visual. `agent-device screenshot <path>` saves 1x, which is cheaper to read than `device_screenshot`.

Rules that save turns:

- Never `sleep`. Use `wait text` or `wait <selector>` with a timeout.
- Cap settle: `--settle --timeout 2000`. Home never settles while the wallet syncs, so `--settle` there waits the full 10 s.
- Refs go stale after any mutation. Prefer `id=`/`label=` selectors for chains; re-snapshot only when the diff lacks the next target.
- Both apps carry the same accessibility IDs for the same control. The Android Maestro README lists them. The tab bar has none: use `label="Home"`, `"Receive"`, `"Send"`, `"Activity"`.
- For a visual pass over several screens, `record start --scope device <path>` then `record stop` and `record contact-sheet <video>` gives one image for the flow.

## Onboarding in one call

A fresh install needs a wallet. Do not tap through onboarding. Replay the Maestro subflow through agent-device; it works on both platforms and needs no Maestro install:

```sh
agent-device replay apps/maestro/subflows/create-wallet.yml --maestro
```

It clears the app's state first, so run it only on a disposable simulator or emulator. It ends on Home with `home-balance` visible. Measured 2026-11-08: 34 s on an iPhone 17 Pro simulator, 13 s on a Pixel 7 emulator with animations off. The regtest stack must be up (`just up`). An Android APK built with `-PnoahRegtestHost=127.0.0.1` (for a physical device) fails creation on an emulator with `Connection refused` to bitcoind; either rebuild with the default host or run `adb reverse tcp:<port> tcp:<port>` for 3000, 3535 and 18443. `apps/maestro/subflows/fund-wallet.yml` goes on to copy a 10,000 sat payment request to the clipboard.

## Reaching a state without tapping

- Deep links: `agent-device open com.noahwallet.regtest 'bitcoin:...?amount=...'` lands on Send review. `apps/maestro/` shows the forms in use.
- Rendered states without a device walk: the opt-in `*ShotTests` in `apps/ios/NoahTests` (Home, Send/Receive, Settings, Exit, Activity, Onboarding, Widget) show the real screens with scripted data and no wallet or server. Each runs as a test host you keep alive: write a state name to the file it watches in the app's Documents (for example `sr-shot`), wait for its `-ready` file, take the screenshot, then write `stop`. The states are listed in each test's header comment; `apps/ios/SendTests/README.md` and `apps/ios/OnboardingTests/README.md` describe the protocol. agent-device taps do not reach a shot host, so put the interaction state in the fixture.
- Funding and payments, only when the task needs them: `apps/maestro/subflows/fund-wallet.yml` onboards and copies a 10,000 sat request, `scripts/test-maestro-native.sh` shows how the invoice is paid from LND, and `apps/maestro/funded/ark-send.yml` sends 5,000 sats to an `ARK` address through a deep link. From the stack side, `just bark send <ark-address> "5000 sats"` and `just send-to <bitcoin-address> 0.01` need no UI; the `ark-regtest-controller` skill covers those recipes. A single stitched fund-and-pay command is a follow-up once a regtest launch hook makes the wallet's addresses known in advance.

## Keep devices warm

The first agent-device call on a simulator builds an XCTest runner and takes minutes. Leave simulators booted between tasks. Run iOS and Android checks in parallel sub-agents, each with its own `device_open`.
