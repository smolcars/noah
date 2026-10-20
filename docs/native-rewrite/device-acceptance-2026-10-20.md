# Physical-device acceptance, 2026-10-20

Devices:
- Pixel 6 (Android 17, GMS, no screen lock), `com.noahwallet.regtest` debug build of `604c80a5`.
- iPhone 17 (iOS 27.2), `com.noahwallet.regtest` Release-Regtest build of `604c80a5`. The user's mainnet and signet apps were not touched.

Backend: local regtest stack plus a Noah server on port 3100 with real Expo credentials. It shares the stack's Postgres with the Docker server on port 3000, so both servers run the five-minute recurring sweep.

## Results

| Check | Platform | Result |
| --- | --- | --- |
| `recurring_payment_due` push wakes a backgrounded app and pays | Pixel | Pass. 6:00 PM monthly 1,000-sat Ark schedule. At 18:00:00 the server sent the wake-up. Both servers' pushes arrived at 18:00:02 and the push job sent one arkoor payment at 18:00:08. The receiver (bark CLI) recorded exactly one 1,000-sat receive (movement 88). The "Recurring payment sent" notification was posted. |
| `recurring_payment_due` background delivery | iPhone | Pass, delivery only. With Settings in the foreground, a due server row for the iPhone's wallet produced a push at 18:05:00.366. The app log shows `Handling recurring_payment_due push` at 18:05:00.674 and `Finished` at 18:05:01.158, and the app synced at 18:05:00.995. The row was inserted directly into the test DB: UI automation needed the device passcode, so no local schedule existed and nothing was paid. A `backup_trigger` push was also handled in the background (report 200 at 22:00:06Z). |
| Timed local reminder (24 h before) | Pixel | Pass. Schedule for Oct 21 6:05 PM. "Upcoming recurring payment: ₿ 500 to Reminder test will be sent tomorrow" posted at 18:05:06. |
| Foreground 60 s sync/claim cadence | Pixel | Pass. `[SYNC]` passes every 60 s in the foreground (17:53:28, 17:54:29, 18:05:27, 18:06:27) and none while backgrounded (17:56–18:05). A 2,345-sat Lightning payment made while Settings was open was claimed at 18:06:39, 3 s after LND paid. The claim came via the SDK mailbox notification. |
| Recovery phrase on a device with no screen lock | Pixel | Pass. "Authenticate to view…" shows the "No screen lock" warning. Cancel keeps 0 words visible; "Show recovery phrase" reveals 12. Screenshots are black (FLAG_SECURE). The Recents card is black. Returning from Home clears the phrase. |
| BOLT12 offer (regtest CLN) | Pixel | Pass. 2,000 sats, 83-sat fee; CLN invoice `paid`. |
| BOLT12 offer (regtest CLN) | iOS simulator | Pass. 1,500 sats, 81-sat fee; CLN invoice `paid`. |
| Lightning address (local LNURL-pay mock backed by LND) | Pixel | Pass. `alice@localtest.me`, 3,000 sats, 87-sat fee. The LND invoice settled with the metadata description hash. |
| Lightning address (local LNURL-pay mock backed by LND) | iOS simulator | Pass. 4,000 sats, 91-sat fee; LND invoice settled. |

The LNURL mock was a throwaway HTTPS server on the Mac with a three-day test CA. `localtest.me` resolves to 127.0.0.1, and the Pixel used `adb reverse tcp:443`. The Pixel ran a local-only build whose `regtestDebug` network config trusted that CA. The build was removed and the normal APK reinstalled afterwards. The simulator trusted the CA via `simctl keychain add-root-cert`.

## iPhone, second session (user present for the passcode, Face ID and camera)

| Check | Result |
| --- | --- |
| Recurring payment from a real local schedule | Pass, with one issue. A 6:50 PM monthly 500-sat schedule was saved and the app sent to the background at 18:43. The 18:50 sweep sent the push at 18:50:00.7; the receiver recorded exactly one 500-sat payment. Issue: the app log shows sync plus the arkoor send at 18:50:01–02, *before* `Handling recurring_payment_due push` (18:50:03.4). Some path other than the push job executed the payment after the push resumed the suspended process. Tracked as an open item. |
| Timed local reminder | Pass. "Upcoming recurring payment: ₿ 300 to Reminder test iOS will be sent tomorrow" banner at 18:55. |
| Biometric lock, app switcher, unlock | Pass. Enabling the lock asked for Face ID permission and Face ID. The app-switcher card showed "Noah is Locked" with no wallet content. Face ID prompted automatically on return, the Unlock button remains as a fallback, and the same Settings screen and scroll position came back. |
| Recovery phrase Face ID gate | Pass. Opening the screen prompts Face ID automatically; success showed 12 words (never screenshotted or logged). Cancel left 0 words and the "Authenticate to view your seed phrase" button. |
| Camera QR | Pass both ways. The iPhone scanned the Pixel's receive QR and the Pixel scanned the iPhone's; both offered Ark/On-chain for the unified request. |

Also observed: Home shows "Backup failed: CancellationError" after background/foreground transitions. A cancelled backup is presented as a failure with a raw error type. Tracked as an open item.

## Not verified on hardware

Physical iOS LNURL: needs a trusted certificate, which can't be installed without the passcode. Covered on the simulator instead.
