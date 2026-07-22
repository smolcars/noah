# UnifiedPush (non‑GMS) push notifications in Noah

This document explains how the Android app handles push notifications without Google Play Services. It focuses on the UnifiedPush flow implemented in Kotlin and the JNI bridge to the NitroArk native library.

## Components
- `NoahPushService` (`client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahPushService.kt`): Android `PushService` from `org.unifiedpush.android.connector`. Runs in the background process that receives UnifiedPush messages.
- NitroArk JNI bridge (`node_modules/react-native-nitro-ark/android/src/main/cpp/NitroArkJni.cpp`): Exposes native wallet functions to Java/Kotlin, including `tryClaimLightningReceive` used to wait for Lightning payments.
- Server push sender (`server/src/push.rs`): Sends to either Expo tokens or UnifiedPush HTTP endpoints. UnifiedPush endpoints are posted to directly (no FCM required).

## Registration flow (device → server)
1) UnifiedPush distributor (e.g., ntfy) supplies an HTTP endpoint to the app.
2) `NoahPushService.onNewEndpoint` stores the endpoint in shared preferences (per app instance).
3) React Native layer calls the gated API `POST /v0/register_push_token` with the endpoint; the server saves it in `push_tokens`.
4) From then on, the server can POST notification payloads straight to the saved endpoint.

## Message delivery (server → device)
- Server builds a JSON payload (`NotificationData` in `server/src/types.rs`) and uses `send_push_notification` / `send_push_notification_with_unique_k1` in `server/src/push.rs`.
- UnifiedPush tokens are sent via `send_unified_notification`, which HTTP POSTs the JSON to the distributor endpoint (optionally with a bearer token `ntfy_auth_token`).
- The UnifiedPush distributor delivers the message to the app, waking `NoahPushService` even without Play Services.

## Kotlin handling in `NoahPushService`
High‑level steps for every message:
1) Load native libs (`JNIOnLoad.initializeNativeNitro`, `noahtoolsOnLoad.initializeNative`).
2) Parse JSON, dispatch on `notification_type`.
3) Lazily load the wallet if needed (`ensureWalletLoaded` via JNI).
4) Execute the action and report status when applicable.

### Supported notification types
- `maintenance`: runs delegated wallet maintenance and reports success/failure via `/report_job_status` using the notification's `notification_k1` as a correlation identifier.
- `lightning_invoice_request`:
  - Build a Bolt11 invoice from the requested `amount` (msat) using `bolt11Invoice`.
  - Submit the invoice back to the server with a JWT bearer token so the payer can see it.
  - Wait for payment by calling `tryClaimLightningReceive(paymentHash, wait=true, token=null)` on a background thread. This blocks until the receive is claimable or the OS kills the process.
  - After a successful claim, show a local high‑priority notification (“Lightning Payment Received! ⚡”).
  - If claim fails, it logs the error; no notification is shown.
- `heartbeat`: responds to `/heartbeat_response` with the notification's `notification_id` and a JWT bearer token to confirm liveness.

### Server authentication
The background service uses the same JWT login protocol as the React Native client:
1) Reuse the encrypted, per-wallet JWT while it is valid.
2) If the token is missing or close to expiry, request a one-time challenge from `/v0/getk1`.
3) Sign the challenge with wallet key index 0 and submit it to `/v0/auth/login`.
4) Send callbacks with `Authorization: Bearer <token>`.
5) If a callback returns HTTP 401, clear the cached token, authenticate once, and retry.

The `notification_k1` in maintenance payloads is only a job-correlation identifier. It is not an authentication credential.

### Local notification channel
`ensureNotificationChannel` creates `noah-push-default` (importance HIGH) on Android O+ so payment receipts surface immediately.

## JNI details relevant to waiting for payments
- `NitroArkJni.cpp` exposes `tryClaimLightningReceive(paymentHash, wait, token)` and forwards to Rust `bark_cxx::try_claim_lightning_receive`.
- `NoahPushService` invokes this with `wait=true`, ensuring the native side blocks until the payment is settled/claimable. The call runs on a worker thread to avoid blocking the UnifiedPush service thread itself.

## Failure/edge cases
- If the UnifiedPush endpoint POST returns non‑2xx, the server logs but continues; the client won’t receive that push.
- If wallet isn’t loaded, `ensureWalletLoaded` attempts to load it; failures are logged and abort handling.
- If `tryClaimLightningReceive` throws (timeout, network, or wallet state), the error is logged and no “payment received” notification is shown.
- OS may kill the background thread if the process is evicted; in that case the claim will be retried on the next app foreground or via periodic flows (e.g., `tryClaimAllLightningReceives` elsewhere).

## Notes for contributors
- Keep `notification_type` strings in sync with `NotificationData::notification_type()` (Rust) and Kotlin handler cases.
- Never call `tryClaimLightningReceive` on the main/service thread; it is intentionally blocking with `wait=true`.
- When adding new push types, update both the server enum (`NotificationData`) and the Kotlin handler in `NoahPushService`.
