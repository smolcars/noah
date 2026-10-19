# Noah iOS (native)

SwiftUI app that replaces the React Native client in `client/` as an in-place update. Plan and checklist: [docs/native-rewrite](../../docs/native-rewrite/PLAN.md).

## Requirements

- Xcode 27+, iOS 18 deployment target
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). `Noah.xcodeproj` is generated from `project.yml` and not committed.

## Build

```sh
just ios-native-generate          # writes Noah.xcodeproj
just ios-native-build Regtest     # Regtest | Signet | Mainnet
just ios-native-test Regtest      # unit tests on a simulator
```

Schemes `Noah`, `Noah-Signet` and `Noah-Regtest` select the `*-Mainnet`, `*-Signet` and `*-Regtest` configurations. Variant values live in `Config/<Variant>.xcconfig`.

## Identity that must not change

These match the React Native app so the update keeps Keychain items (seed), app-group data and widgets users already placed:

| | Mainnet | Signet | Regtest |
|---|---|---|---|
| Bundle ID | `com.noahwallet.mainnet` | `com.noahwallet.signet` | `com.noahwallet.regtest` |
| App group | `group.com.noahwallet.mainnet` | `group.com.noahwallet.signet` | `group.com.noahwallet.regtest` |
| Widget bundle ID | `com.noahwallet.mainnet.NoahWidgetMainnet` | `com.noahwallet.signet.NoahWidgetSignet` | `com.noahwallet.regtest.NoahWidgetRegtest` |
| Widget kind | `NoahWidgetMainnet` | `NoahWidgetSignet` | `NoahWidgetRegtest` |

Team `AT26Z24V2Q`. The app entitlements list all three app groups, as in the RN app. `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` (0.2.0 / 100) must stay above the last RN release.

## Wallet SDK

[bark-bolt-ffi](https://github.com/smolcars/bark-bolt-ffi) via Swift Package Manager, pinned with `exactVersion` in `project.yml`.

## Simulators

Don't install native builds onto a simulator that holds an RN Noah install you care about: the bundle IDs are the same, so the native build replaces it (that is the upgrade path, use it deliberately).
