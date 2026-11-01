# Direct APNs and FCM push

New native apps register their raw device token. The server sends to it directly, so
Expo isn't involved. Apps that haven't updated keep their Expo token and keep working.

## Routing

`push_tokens` holds one token per wallet. A registration replaces the whole row.

| `provider` | Token | Sent through |
| --- | --- | --- |
| `apns` | Hex device token, with `apns_sandbox` | APNs |
| `fcm` | FCM registration token | FCM |
| empty | `ExponentPushToken[…]` and other Expo formats | Expo |
| empty | Public HTTPS URL | UnifiedPush |

Deploy the server first: older servers reject raw tokens.

## Server settings

All optional. A raw token without matching credentials stays registered, and each send
logs an error. Credentials that are set but unusable stop the server from starting.

| Setting | Value |
| --- | --- |
| `FCM_SERVICE_ACCOUNT_JSON` | The full service account JSON key for the `noah-wallet` Firebase project |
| `APNS_KEY_P8` | The contents of the `.p8` key |
| `APNS_KEY_ID`, `APNS_TEAM_ID` | From Apple Developer |
| `APNS_TOPIC` | The app's bundle ID, such as `com.noahwallet.mainnet` |

Each server uses one APNs key, and a token's `apns_sandbox` flag picks the APNs host. A
key limited to one environment can only reach that environment's tokens: production for
TestFlight, ad-hoc and App Store builds, sandbox for Xcode builds.

## Before the iOS release

Direct silent pushes use Apple's documented settings: type `background`, priority 5.
Expo sends these wake-ups at priority 10. What matters is that silent wakes keep running
refreshes and `maintenance_delegated`. Lightning-address speed isn't a release blocker
(owner's decision, 2026-11-01).

Check a `maintenance` push on a TestFlight or ad-hoc build, which uses production APNs.
So far, on an iPhone 17 with the sandbox:

- With budget available, Expo and direct at priority 5 and 10 woke the app in under a
  second.
- At one push a minute, iOS throttled every route after eight pushes, Expo included.
  Space test pushes well apart: Apple suggests no more than two or three an hour.
- One Lightning-address invoice at priority 5 timed out at 45 s just after install. The
  next one, through Expo, took 1 s. The cause is unknown.

## Retiring Expo

`noah-cli stats` counts tokens per route. Remove the Expo sender once almost no Expo
tokens remain. Apps can't be forced to update: those left behind stop getting pushes and
are deregistered after 20 missed heartbeats.
