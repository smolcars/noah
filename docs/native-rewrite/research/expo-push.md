# Expo push continuity through Noah’s native rewrite

Research date: 2026-10-19. Repository read-only. Installed `client/node_modules/expo-notifications` is absent; I inspected the **58.1.1** package pinned by `bun.lock:1165`, downloaded into scratch space and verified against its lockfile checksum. Expo source references below use that version. UnifiedPush source is connector **3.1.2**, pinned at `client/nitromodules/noah-tools/android/build.gradle:137`.

## TL;DR

**Keep Expo and existing server tokens. A native compatibility layer is technically feasible, without mass re-registration.** Preserve installation identity, platform credentials, persistent storage, payload parsing, and device-token refresh. Expo documents token stability across app upgrades, but its backend token-derivation algorithm is unavailable. Neither unchanged tokens nor delivery before opening the update can be guaranteed for every device. Direct use of Expo’s registration HTTP endpoints is visible in public source; official support for SDK-free Swift/Kotlin clients remains **unverified**. [Expo FAQ](https://docs.expo.dev/push-notifications/faq/)

## Current-state facts

- **Registration:** Noah requests permission, excludes simulators, fetches the native APNs/FCM token, then obtains an Expo token. Only `{push_token: string}` reaches authenticated `/v0/register_push_token`; the native token is discarded, and `pushType` stays client-side. The server upserts one token per wallet pubkey. See [pushNotifications.ts:439](/client/src/lib/pushNotifications.ts:439), [api.ts:393](/client/src/lib/api.ts:393), and [push_token_repo.rs:16](/server/src/db/push_token_repo.rs:16).
- **When/metadata:** `useServerRegistration.ts:38` registers an unregistered, ready wallet after version checking. `usePushNotifications.ts:13` registers push once ready and server-registered; reset/re-registration also invokes it (`lib/server.ts:182`). Manufacturer/model, OS/version, app version/build are sent separately through account registration and conditional last-login updates—not attached to the push token (`lib/server.ts:50,82`; `lib/deviceInfo.ts:7`). UnifiedPush settings also register an endpoint after distributor registration (`screens/UnifiedPushScreen.tsx:64`).
- **Identity/config:** Preserve EAS project `6e79dffb-dcd4-4f3d-b596-638b16377eb0` (`app.config.ts:14`), variant bundle/application IDs (`ios/Config/*.xcconfig:7`; `android/app/build.gradle:146`), and corresponding Firebase app registrations. All three Google configs use project `noah-wallet`, sender `798188805132`, with distinct variant Firebase app IDs (`android/app/src/mainnet/google-services.json:3,29,48,67`).
- **Native wiring:** iOS inherits `ExpoAppDelegate` (`ios/Noah/AppDelegate.swift:7`); `Info.plist:108` enables remote-notification/fetch/processing. `noah.entitlements:5` specifies development APNs; verify the **signed distribution entitlement**, not this source value alone. Android’s app/flavor manifests do not declare FCM handling themselves: Expo’s [library manifest:6](https://unpkg.com/expo-notifications@58.1.1/android/src/main/AndroidManifest.xml) supplies `ExpoFirebaseMessagingService` for `com.google.firebase.MESSAGING_EVENT`.

Unless prefixed otherwise, abbreviated client paths above are under `client/`.

## Token derivation and stability

**Registration protocol.** [getExpoPushTokenAsync.ts:49–117](https://unpkg.com/expo-notifications@58.1.1/src/getExpoPushTokenAsync.ts) POSTs JSON to `https://exp.host/--/api/v2/push/getExpoPushToken`:

`{type, deviceId, development, appId, deviceToken, projectId}`

`type` is `apns`/`fcm`; `deviceId` is a lowercased installation UUID; `appId` is the actual bundle/application ID; `deviceToken` is APNs hexadecimal or FCM token; `development` selects APNs sandbox versus production, otherwise false. Response: `{data:{expoPushToken:string}}`. No authorization header is sent. This is server-issued identity, **not a locally calculated ExponentPushToken hash**.

**Installation persistence:**

- **iOS:** [ServerRegistrationModule.swift:26,120,175](https://unpkg.com/expo-notifications@58.1.1/ios/ExpoNotifications/ServerRegistration/ServerRegistrationModule.swift) reads Keychain generic-password data: service = bundle ID; account and generic = UTF-8 `EXDeviceInstallationUUIDKey`; accessibility = `AfterFirstUnlockThisDeviceOnly`. Legacy fallback is `UserDefaults.standard["EXDeviceInstallationUUIDKey"]`, migrated into Keychain. Preserve the same Keychain access group/app identity; do not create another UUID when Keychain is temporarily inaccessible.
- **Android:** [InstallationId.java:27–80](https://unpkg.com/expo-notifications@58.1.1/android/src/main/java/expo/modules/notifications/serverregistration/InstallationId.java) primarily reads `context.noBackupFilesDir/expo_notifications_installation_uuid.txt`. Fallback order: SharedPreferences `host.exp.exponent.SharedPreferences`, key `uuid`; then no-backup file `expo_installation_uuid.txt`. **SharedPreferences is legacy, not primary storage.**

These stores should survive an in-place update that removes Expo code, provided application data/access rights remain intact (**inference; upgrade test required**).

**Same UUID, same/new device token:** Expect the same Expo token when project/app/platform/environment identity remains unchanged, based on the upgrade guarantee and Expo’s separate mapping-update mechanism. Exact backend uniqueness rules and this SDK-free replay are **unverified**. Port [updateDevicePushTokenAsync.ts:8,86](https://unpkg.com/expo-notifications@58.1.1/src/utils/updateDevicePushTokenAsync.ts): POST `/--/api/v2/push/updateDeviceToken` with `{deviceId,development,deviceToken,appId,type}`; retry failures. Expo currently also refreshes this mapping after seven days. Reuse `getExpoPushToken` on registration and submit any returned replacement through Noah’s existing endpoint.

**APNs/FCM:** An ordinary update is not equivalent to reinstalling. Preserve signing, APNs environment, Firebase app/sender, Firebase installation storage, and default Firebase app identity. Nevertheless, tokens can rotate. Apple requires retrieving the current token at launch and says delivery after a token change requires an app launch. Firebase requires handling `onNewToken`; restore/reinstall/data-clear can change tokens. Firebase’s current [Store.java:188](https://github.com/firebase/firebase-android-sdk/blob/main/firebase-messaging/src/main/java/com/google/firebase/messaging/Store.java#L188) requests refresh when app version changes—refresh does not necessarily mean a different token. [Apple registration](https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/HandlingRemoteNotifications.html), [Firebase registration](https://firebase.google.com/docs/cloud-messaging/android/get-started)

**Never opened after updating:** The old Expo mapping can still address the installation if its underlying token remains valid and the replacement native handler is registered. This is conditional, not a promise: token rotation, force-stop/force-quit, background restrictions, inaccessible wallet storage, or first-launch-only migration can prevent processing.

**Wire contract:** Noah serializes `NotificationData` into a **string**, passed as Expo `data`, with high priority and `_contentAvailable:true`; coordinated pushes omit title/body (`server/src/push.rs:281–304`). The pinned Rust crate preserves that string (`expo_push_message.rs:95`). Expected envelopes are:

- APNs: `{"aps":{"content-available":1},"body":"<Noah JSON>"}`.
- FCM: `{"message":{"data":{"body":"<Noah JSON>"},"android":{"priority":"high"}}}`; no notification block for headless delivery.

These are **inferred minimal shapes, not captured production packets**. iOS must decode `userInfo["body"]`; Android must decode `remoteMessage.data["body"]`. Accept JSON strings or objects at the normalization boundary. `dataString` is an Expo-generated JS convenience. Sources: [iOS transformer:18](https://unpkg.com/expo-notifications@58.1.1/ios/ExpoNotifications/Notifications/Background/BackgroundEventTransformer.swift), [Android serializer:39](https://unpkg.com/expo-notifications@58.1.1/android/src/main/java/expo/modules/notifications/notifications/RemoteMessageSerializer.java), [Expo notification types](https://docs.expo.dev/push-notifications/what-you-need-to-know/).

`experienceId`/`scopeKey` are legacy Expo Go routing fields; tolerate them. `projectId` is required for registration, but its presence in delivered payloads is **unverified** and must not be required. Expo’s [direct-provider examples](https://docs.expo.dev/push-notifications/sending-notifications-custom/) illustrate the legacy fields and JSON-string `data.body`.

## Recommended native design

**iOS:** Use `UNUserNotificationCenter` for permissions/presentation and a SwiftUI-connected app delegate for APNs registration and `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`. Keep push entitlement and `remote-notification` background mode. Initialize storage migration, authentication, and wallet execution during background launch, independently of views. Serialize wallet work, deduplicate jobs, checkpoint unfinished work, and call completion within approximately 30 seconds. Silent delivery is discretionary; iOS can delay/coalesce/throttle it. [Apple background guidance](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app)

**Android/GMS:** Keep Firebase Messaging, implement your own manifest-registered `FirebaseMessagingService`, and handle `onMessageReceived` plus `onNewToken`. Queue longer work using WorkManager; expedited work has a brief exemption immediately following high-priority FCM. The callback itself has only a short processing window. A foreground service needs a valid exemption, notification, and appropriate service type; downgraded FCM does not grant the high-priority exemption. Retain channel **`default`** and its user settings (`pushNotifications.ts:39–60`). Repeated silent high-priority pushes risk FCM deprioritization. [Firebase processing](https://firebase.google.com/docs/cloud-messaging/android-message-priority), [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

**Expo support status:** Implement the HTTP compatibility layer with URLSession/OkHttp; keep Expo’s existing APNs/FCM credentials configured. Public source demonstrates client access, but I found no official SDK-free Swift/Kotlin registration contract or explicit support guarantee. Official [setup guidance](https://docs.expo.dev/push-notifications/push-notifications-setup/) covers Expo/React Native and local builds. Obtain Expo confirmation before treating undocumented endpoint behavior as a long-term guarantee.

**Android/UnifiedPush:** `shouldUseUnifiedPush` selects real Android devices without Play Services (`constants.ts:27`). The handler already runs Kotlin/JNI wallet operations without JS: maintenance, invoice creation/submission/claim polling, and heartbeat; other types are unsupported. Pure Kotlin removes Nitro initialization/reflection and JS endpoint polling, allowing direct calls into the shared wallet/job layer. Preserve authenticated callbacks. See [NoahPushService.kt:348](/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahPushService.kt:348).

Preserve `noah_unified_push` preferences, `endpoint_<instance>`/legacy `endpoint`, and instances `noah-mainnet`, `noah-signet`, `noah-regtest` (`NoahPushService.kt:395`; `NoahTools.kt:277`). **Correction:** connector 3.1.2 stores registration in SQLite **`unifiedpush-connector`**, not just preferences (`internal/DBStore.kt:888`). Legacy preferences are `unifiedpush.connector`, with `unifiedpush.instances`, `unifiedpush.distributor`, and `<instance>/unifiedpush.connector` (`internal/LegacyStore.kt:90`). Keep connector storage/receiver/service wiring and distributor registration; update Noah on endpoint changes. Persistence is expected across update, but distributor endpoint continuity needs testing. Keep existing UnifiedPush channel `noah-push-default` too (`NoahPushService.kt:25`). [Pinned connector source](https://repo.maven.apache.org/maven2/org/unifiedpush/android/connector/3.1.2/connector-3.1.2-sources.jar)

## Server changes

**No mandatory migration or payload change** for Expo-compatible native clients. Keep existing token rows, credentials, six `notification_type` variants, correlation IDs, authenticated callbacks, and coordinator spacing (`server/src/types.rs:612`; `server/src/notification_coordinator.rs:190`). Keep `default` as a local channel; adding `channelId` to Expo sends can change their headless classification.

Direct APNs/FCM would offer documented provider APIs and finer control, but requires collecting native tokens plus platform/environment metadata, new senders, and coexistence with old Expo tokens. Noah does **not** currently register native tokens server-side; this alternative cannot replace delivery to unopened installations immediately.

## Open risks and verification

No live push or upgrade was performed. Required release tests:

1. Install current regtest builds on real iOS/GMS/non-GMS devices; update in place without uninstalling or opening. Send all supported jobs to the **old saved server token**, verify wallet effects and callbacks.
2. Compare identity/token equality privately; exercise APNs sandbox and TestFlight production, FCM refresh/offline retry, locked/rebooted/Doze/force-stopped states, duplicate pushes, and preserved channel settings.
3. Capture sanitized wire shapes and verify string encoding/optional metadata; test UnifiedPush with the same distributor and instances.
4. Monitor heartbeats: 20 consecutive pending/timeouts qualify for deregistration (`server/src/db/heartbeat_repo.rs:9,160`), which deletes push tokens (`server/src/cron.rs:180`). Validate recovery before broad rollout. Simulator injection tests parsing only; it does not prove Expo delivery continuity.
